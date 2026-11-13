package app.beam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One send or receive: a direct, encrypted WebRTC data channel to the other device. */
final class Transfer {
  static final String PREPARING = "preparing", WAITING = "waiting", ASKING = "asking", CONNECTING = "connecting",
      SENDING = "sending", RECEIVING = "receiving", FINISHING = "finishing", DONE = "done", FAILED = "failed";
  static final int CHUNK = 64 * 1024;
  static final long HIGH_WATER = 4L * 1024 * 1024;

  static final class Item {
    final String name, type;
    final long size;
    final boolean text;
    Uri uri;          // outgoing file
    File temp;        // outgoing copy we made (deleted after)
    String textValue; // outgoing text, or received text
    Uri saved;        // received file
    Item(String name, long size, String type, boolean text) { this.name = name; this.size = size; this.type = type; this.text = text; }
  }

  final Beam beam;
  final String tid, peerId, peerName, peerKind;
  final boolean incoming, paired;
  final List<Item> items;
  final long total;
  volatile long done;
  volatile String state;
  String reason, offerSdp;
  final long created = System.currentTimeMillis();

  private PeerConnection pc;
  private DataChannel dc;
  private final Object lock = new Object();
  private boolean gatherDone, gatherFired, senderStarted;
  private Runnable afterGather;
  private long lastUi;

  // receive state (used on the WebRTC thread)
  private int cur = -1;
  private long got;
  private OutputStream out;
  private Uri pendingUri;
  private ByteArrayOutputStream textBuf;

  Transfer(Beam beam, String tid, boolean incoming, String peerId, String peerName, String peerKind, boolean paired, List<Item> items) {
    this.beam = beam;
    this.tid = tid;
    this.incoming = incoming;
    this.peerId = peerId;
    this.peerName = peerName;
    this.peerKind = peerKind;
    this.paired = paired;
    this.items = items;
    long t = 0;
    for (Item i : items) t += i.size;
    this.total = t;
    this.state = incoming ? ASKING : PREPARING;
  }

  static String cleanName(String n) {
    if (n == null) n = "";
    n = n.replaceAll("[/\\\\:\\u0000-\\u001f]", "_").replaceAll("^\\.+", "");
    if (n.length() > 180) n = n.substring(0, 180);
    return n.isEmpty() ? "file" : n;
  }

  String describe() {
    if (items.size() == 1) return items.get(0).text ? "some text" : items.get(0).name;
    boolean photos = true;
    for (Item i : items) if (!i.type.startsWith("image/")) photos = false;
    return items.size() + (photos ? " photos" : " files");
  }

  boolean active() { return !DONE.equals(state) && !FAILED.equals(state); }

  // ---------- WebRTC setup ----------
  private static PeerConnectionFactory factory;
  private static synchronized PeerConnectionFactory factory(Context c) {
    if (factory == null) {
      PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(c).createInitializationOptions());
      factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
    }
    return factory;
  }

  private PeerConnection.RTCConfiguration config() {
    List<PeerConnection.IceServer> list = new ArrayList<>();
    JSONArray ice = beam.ice;
    for (int i = 0; ice != null && i < ice.length(); i++) {
      JSONObject s = ice.optJSONObject(i);
      if (s == null) continue;
      List<String> urls = new ArrayList<>();
      JSONArray u = s.optJSONArray("urls");
      if (u != null) for (int k = 0; k < u.length(); k++) urls.add(u.optString(k));
      else if (s.has("urls")) urls.add(s.optString("urls"));
      if (urls.isEmpty()) continue;
      PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(urls);
      if (s.has("username")) b.setUsername(s.optString("username"));
      if (s.has("credential")) b.setPassword(s.optString("credential"));
      list.add(b.createIceServer());
    }
    return new PeerConnection.RTCConfiguration(list);
  }

  private final PeerConnection.Observer observer = new PeerConnection.Observer() {
    public void onSignalingChange(PeerConnection.SignalingState s) {}
    public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
      if (s == PeerConnection.IceConnectionState.FAILED) beam.main.post(() -> fail("Couldn't connect"));
    }
    public void onIceConnectionReceivingChange(boolean b) {}
    public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
      if (s == PeerConnection.IceGatheringState.COMPLETE) beam.main.post(() -> { gatherDone = true; fireGather(); });
    }
    public void onIceCandidate(IceCandidate c) {}
    public void onIceCandidatesRemoved(IceCandidate[] c) {}
    public void onAddStream(MediaStream s) {}
    public void onRemoveStream(MediaStream s) {}
    public void onDataChannel(DataChannel channel) { if (incoming) setupReceive(channel); }
    public void onRenegotiationNeeded() {}
  };

  /** Runs `r` on the main thread once all network routes are known (or after 2.5 s). */
  private void whenGathered(Runnable r) {
    beam.main.post(() -> {
      afterGather = r;
      gatherFired = false;
      if (gatherDone) fireGather();
      else beam.main.postDelayed(this::fireGather, 2500);
    });
  }

  private void fireGather() {
    if (afterGather == null || gatherFired) return;
    gatherFired = true;
    afterGather.run();
  }

  private abstract static class Sdp implements SdpObserver {
    public void onCreateSuccess(SessionDescription d) {}
    public void onSetSuccess() {}
    public void onCreateFailure(String e) { failed(e); }
    public void onSetFailure(String e) { failed(e); }
    abstract void failed(String e);
  }

  private void ui() {
    lastUi = System.currentTimeMillis();
    beam.transferChanged(this);
  }

  private void progress() {
    long now = System.currentTimeMillis();
    if (now - lastUi < 300) return;
    lastUi = now;
    beam.main.post(() -> beam.transferChanged(this));
  }

  // ---------- sending ----------
  void startSend() {
    state = PREPARING;
    ui();
    pc = factory(beam.app).createPeerConnection(config(), observer);
    if (pc == null) { fail("WebRTC unavailable"); return; }
    dc = pc.createDataChannel("beam", new DataChannel.Init());
    dc.registerObserver(new DataChannel.Observer() {
      public void onBufferedAmountChange(long previous) { synchronized (lock) { lock.notifyAll(); } }
      public void onStateChange() {
        DataChannel.State s = dc.state();
        if (s == DataChannel.State.OPEN) beam.main.post(Transfer.this::startPump);
        else if (s == DataChannel.State.CLOSED) beam.main.post(() -> { if (SENDING.equals(state)) fail("Connection lost"); });
        synchronized (lock) { lock.notifyAll(); }
      }
      public void onMessage(DataChannel.Buffer b) {
        if (b.binary) return;
        String s = text(b);
        beam.main.post(() -> {
          if (s.contains("\"ok\"")) finish();
          else if (s.contains("\"cancel\"")) fail("Cancelled");
        });
      }
    });
    final Sdp failer = new Sdp() { void failed(String e) { beam.main.post(() -> fail("Setup failed")); } };
    pc.createOffer(new Sdp() {
      @Override public void onCreateSuccess(SessionDescription desc) {
        pc.setLocalDescription(new Sdp() {
          @Override public void onSetSuccess() { whenGathered(Transfer.this::sendOffer); }
          void failed(String e) { failer.failed(e); }
        }, desc);
      }
      void failed(String e) { failer.failed(e); }
    }, new MediaConstraints());
  }

  private void sendOffer() {
    if (!PREPARING.equals(state)) return;
    try {
      String sdp = pc.getLocalDescription().description;
      JSONArray files = new JSONArray();
      for (Item i : items) {
        JSONObject f = new JSONObject().put("name", i.name).put("size", i.size).put("type", i.type);
        if (i.text) f.put("text", true);
        files.put(f);
      }
      JSONObject d = new JSONObject().put("k", "offer").put("tid", tid).put("sdp", sdp)
          .put("sig", beam.me.sign("beam-offer|" + tid + "|" + peerId + "|" + sdp))
          .put("pub", beam.me.pub).put("name", beam.prefs.name).put("kind", "android").put("files", files);
      state = WAITING;
      ui();
      beam.signal(peerId, d);
      beam.main.postDelayed(() -> {
        if (WAITING.equals(state)) {
          try { beam.signal(peerId, new JSONObject().put("k", "cancel").put("tid", tid)); } catch (Exception ignored) {}
          fail("No response");
        }
      }, Beam.OFFER_TIMEOUT);
    } catch (Exception e) {
      fail("Setup failed");
    }
  }

  void onAnswer(String sdp) {
    state = CONNECTING;
    ui();
    pc.setRemoteDescription(new Sdp() { void failed(String e) { beam.main.post(() -> fail("Couldn't connect")); } },
        new SessionDescription(SessionDescription.Type.ANSWER, sdp));
  }

  private void startPump() {
    if (senderStarted || !active()) return;
    senderStarted = true;
    state = SENDING;
    ui();
    final ContentResolver cr = beam.app.getContentResolver();
    new Thread(() -> {
      byte[] buf = new byte[CHUNK];
      try {
        for (int i = 0; i < items.size(); i++) {
          Item it = items.get(i);
          sendText("{\"t\":\"file\",\"i\":" + i + "}");
          InputStream in = it.text ? new ByteArrayInputStream(it.textValue.getBytes(StandardCharsets.UTF_8))
              : it.temp != null ? new FileInputStream(it.temp) : cr.openInputStream(it.uri);
          if (in == null) throw new IOException("Couldn't open " + it.name);
          try {
            long sent = 0;
            while (sent < it.size) {
              int want = (int) Math.min(CHUNK, it.size - sent);
              int n = readFully(in, buf, want);
              if (n <= 0) throw new IOException(it.name + " is shorter than expected");
              sendBinary(buf, n);
              sent += n;
              done += n;
              progress();
            }
          } finally {
            in.close();
          }
          sendText("{\"t\":\"eof\",\"i\":" + i + "}");
        }
        sendText("{\"t\":\"done\"}");
        beam.main.post(() -> { if (SENDING.equals(state)) { state = FINISHING; ui(); } });
      } catch (Exception e) {
        final String msg = e.getMessage();
        beam.main.post(() -> fail(msg != null ? msg : "Transfer failed"));
      }
    }, "beam-send").start();
  }

  private static int readFully(InputStream in, byte[] buf, int want) throws IOException {
    int n = 0;
    while (n < want) {
      int r = in.read(buf, n, want - n);
      if (r < 0) break;
      n += r;
    }
    return n;
  }

  private void waitRoom() throws IOException {
    synchronized (lock) {
      while (dc.bufferedAmount() > HIGH_WATER) {
        if (!active() || dc.state() != DataChannel.State.OPEN) throw new IOException("Stopped");
        try { lock.wait(200); } catch (InterruptedException e) { throw new IOException("Stopped"); }
      }
    }
    if (!active() || dc.state() != DataChannel.State.OPEN) throw new IOException("Stopped");
  }

  private void sendBinary(byte[] buf, int n) throws IOException {
    while (true) {
      waitRoom();
      if (dc.send(new DataChannel.Buffer(ByteBuffer.wrap(buf, 0, n), true))) return;
      synchronized (lock) { try { lock.wait(100); } catch (InterruptedException e) { throw new IOException("Stopped"); } }
    }
  }

  private void sendText(String s) throws IOException {
    waitRoom();
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    if (!dc.send(new DataChannel.Buffer(ByteBuffer.wrap(b), false))) throw new IOException("Connection lost");
  }

  private static String text(DataChannel.Buffer b) {
    byte[] bytes = new byte[b.data.remaining()];
    b.data.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  // ---------- receiving ----------
  void accept() {
    if (!ASKING.equals(state)) return;
    state = CONNECTING;
    ui();
    pc = factory(beam.app).createPeerConnection(config(), observer);
    if (pc == null) { fail("WebRTC unavailable"); return; }
    final Sdp failer = new Sdp() { void failed(String e) { beam.main.post(() -> fail("Couldn't connect")); } };
    pc.setRemoteDescription(new Sdp() {
      @Override public void onSetSuccess() {
        pc.createAnswer(new Sdp() {
          @Override public void onCreateSuccess(SessionDescription desc) {
            pc.setLocalDescription(new Sdp() {
              @Override public void onSetSuccess() { whenGathered(Transfer.this::sendAnswer); }
              void failed(String e) { failer.failed(e); }
            }, desc);
          }
          void failed(String e) { failer.failed(e); }
        }, new MediaConstraints());
      }
      void failed(String e) { failer.failed(e); }
    }, new SessionDescription(SessionDescription.Type.OFFER, offerSdp));
  }

  private void sendAnswer() {
    if (!CONNECTING.equals(state)) return;
    try {
      String sdp = pc.getLocalDescription().description;
      beam.signal(peerId, new JSONObject().put("k", "answer").put("tid", tid).put("sdp", sdp)
          .put("sig", beam.me.sign("beam-answer|" + tid + "|" + peerId + "|" + sdp)).put("pub", beam.me.pub));
    } catch (Exception e) {
      fail("Setup failed");
    }
  }

  private void setupReceive(DataChannel channel) {
    dc = channel;
    channel.registerObserver(new DataChannel.Observer() {
      public void onBufferedAmountChange(long previous) {}
      public void onStateChange() {
        DataChannel.State s = channel.state();
        if (s == DataChannel.State.OPEN) beam.main.post(() -> { if (CONNECTING.equals(state)) { state = RECEIVING; ui(); } });
        else if (s == DataChannel.State.CLOSED) beam.main.post(() -> { if (RECEIVING.equals(state) || CONNECTING.equals(state)) fail("Connection lost"); });
      }
      public void onMessage(DataChannel.Buffer b) {
        try {
          onData(b);
        } catch (Exception e) {
          final String msg = e.getMessage();
          beam.main.post(() -> fail(msg != null ? msg : "Transfer failed"));
        }
      }
    });
  }

  private synchronized void onData(DataChannel.Buffer b) throws Exception {
    if (!active()) return;
    if (!b.binary) {
      JSONObject m = new JSONObject(text(b));
      switch (m.optString("t")) {
        case "file": {
          int i = m.optInt("i", -1);
          if (i != cur + 1 || i >= items.size()) throw new IOException("Bad data");
          cur = i;
          got = 0;
          openOutput(items.get(cur));
          break;
        }
        case "eof": {
          Item it = items.get(cur);
          if (m.optInt("i", -1) != cur || got != it.size) throw new IOException("File was incomplete");
          closeOutput(it);
          break;
        }
        case "done":
          sendText("{\"t\":\"ok\"}");
          beam.main.post(this::finish);
          break;
        case "cancel":
          beam.main.post(() -> fail("Cancelled"));
          break;
      }
      return;
    }
    if (cur < 0) throw new IOException("Bad data");
    Item it = items.get(cur);
    int n = b.data.remaining();
    got += n;
    done += n;
    if (got > it.size) throw new IOException("Too much data");
    byte[] bytes = new byte[n];
    b.data.get(bytes);
    if (textBuf != null) textBuf.write(bytes);
    else out.write(bytes);
    progress();
  }

  private void openOutput(Item it) throws IOException {
    if (it.text) {
      if (it.size > 1024 * 1024) throw new IOException("Text too long");
      textBuf = new ByteArrayOutputStream();
      return;
    }
    textBuf = null;
    String mime = it.type;
    int dot = it.name.lastIndexOf('.');
    if (dot > 0) {
      String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.name.substring(dot + 1).toLowerCase());
      if (m != null) mime = m;
    }
    ContentValues v = new ContentValues();
    v.put(MediaStore.Downloads.DISPLAY_NAME, it.name);
    v.put(MediaStore.Downloads.MIME_TYPE, mime);
    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Beam");
    v.put(MediaStore.Downloads.IS_PENDING, 1);
    ContentResolver cr = beam.app.getContentResolver();
    pendingUri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
    if (pendingUri == null) throw new IOException("Couldn't save to Downloads");
    out = cr.openOutputStream(pendingUri);
    if (out == null) throw new IOException("Couldn't save to Downloads");
  }

  private void closeOutput(Item it) throws IOException {
    if (textBuf != null) {
      it.textValue = new String(textBuf.toByteArray(), StandardCharsets.UTF_8);
      textBuf = null;
      return;
    }
    out.close();
    out = null;
    ContentValues v = new ContentValues();
    v.put(MediaStore.Downloads.IS_PENDING, 0);
    beam.app.getContentResolver().update(pendingUri, v, null, null);
    it.saved = pendingUri;
    pendingUri = null;
  }

  private synchronized void discardPartial() {
    try { if (out != null) out.close(); } catch (Exception ignored) {}
    out = null;
    if (pendingUri != null) {
      try { beam.app.getContentResolver().delete(pendingUri, null, null); } catch (Exception ignored) {}
      pendingUri = null;
    }
  }

  // ---------- ending ----------
  void finish() {
    if (!active()) return;
    state = DONE;
    ui();
    cleanupTemp();
    beam.main.postDelayed(this::closePc, 1500);
    if (incoming) beam.notify.received(this);
  }

  void fail(String why) {
    if (!active()) return;
    if (dc != null) {
      try {
        if (dc.state() == DataChannel.State.OPEN) dc.send(new DataChannel.Buffer(ByteBuffer.wrap("{\"t\":\"cancel\"}".getBytes(StandardCharsets.UTF_8)), false));
      } catch (Exception ignored) {}
    }
    state = FAILED;
    reason = why;
    synchronized (lock) { lock.notifyAll(); }
    if (incoming) new Thread(this::discardPartial).start();
    cleanupTemp();
    ui();
    beam.main.postDelayed(this::closePc, 500);
  }

  private void cleanupTemp() {
    for (Item i : items) if (i.temp != null) { i.temp.delete(); i.temp = null; }
  }

  private boolean closed;
  private void closePc() {
    if (closed || pc == null) return;
    closed = true;
    try {
      if (dc != null) { dc.unregisterObserver(); dc.close(); dc.dispose(); }
    } catch (Exception ignored) {}
    try { pc.dispose(); } catch (Exception ignored) {}
  }
}

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

/**
 * One send or receive: a direct, encrypted WebRTC data channel to the other device.
 * If the connection drops part-way, the sender reconnects and both sides carry on
 * from the last byte received (for up to 15 minutes).
 */
final class Transfer {
  static final String PREPARING = "preparing", WAITING = "waiting", ASKING = "asking", CONNECTING = "connecting",
      SENDING = "sending", RECEIVING = "receiving", FINISHING = "finishing", PAUSED = "paused", RECONNECTING = "reconnecting",
      DONE = "done", FAILED = "failed";
  static final int CHUNK = 64 * 1024;
  static final long HIGH_WATER = 4L * 1024 * 1024;
  static final long RESUME_WINDOW = 15 * 60 * 1000L;

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

  /** One WebRTC connection attempt. A transfer may go through several if the network drops. */
  private final class Conn {
    final int gen;
    PeerConnection pc;
    DataChannel dc;
    boolean gatherDone, gatherFired, startedPump, sentFrom, closed;
    Runnable afterGather;
    Conn(int gen) { this.gen = gen; }
    boolean live() { return conn == this && active(); }
  }

  final Beam beam;
  final String tid, peerId, peerName, peerKind;
  final boolean incoming, paired;
  final List<Item> items;
  final long total;
  volatile long done;
  volatile String state;
  String reason, offerSdp;
  int gen;
  boolean offline; // going over Bluetooth + direct Wi-Fi (Nearby) instead of WebRTC
  final long created = System.currentTimeMillis();

  private volatile Conn conn;
  private final Object lock = new Object();
  private long lastUi, pausedAt;
  private boolean everOpen;

  // receive state (touched on the WebRTC thread, under `this`)
  private int cur = -1;
  private boolean curDone = true;
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

  private PeerConnection.Observer observer(final Conn c) {
    return new PeerConnection.Observer() {
      public void onSignalingChange(PeerConnection.SignalingState s) {}
      public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
        if (s == PeerConnection.IceConnectionState.FAILED) beam.main.post(() -> dropped(c));
      }
      public void onIceConnectionReceivingChange(boolean b) {}
      public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
        if (s == PeerConnection.IceGatheringState.COMPLETE) beam.main.post(() -> { c.gatherDone = true; fireGather(c); });
      }
      public void onIceCandidate(IceCandidate x) {}
      public void onIceCandidatesRemoved(IceCandidate[] x) {}
      public void onAddStream(MediaStream s) {}
      public void onRemoveStream(MediaStream s) {}
      public void onDataChannel(DataChannel channel) { if (incoming) setupReceive(c, channel); }
      public void onRenegotiationNeeded() {}
    };
  }

  /** Runs `r` on the main thread once all network routes are known (or after 2.5 s). */
  private void whenGathered(final Conn c, Runnable r) {
    beam.main.post(() -> {
      c.afterGather = r;
      if (c.gatherDone) fireGather(c);
      else beam.main.postDelayed(() -> fireGather(c), 2500);
    });
  }

  private void fireGather(Conn c) {
    if (c.afterGather == null || c.gatherFired) return;
    c.gatherFired = true;
    c.afterGather.run();
  }

  private abstract static class Sdp implements SdpObserver {
    public void onCreateSuccess(SessionDescription d) {}
    public void onSetSuccess() {}
    public void onCreateFailure(String e) { failed(e); }
    public void onSetFailure(String e) { failed(e); }
    abstract void failed(String e);
  }

  void ui() {
    lastUi = System.currentTimeMillis();
    beam.transferChanged(this);
  }

  /** Progress changed; refresh the screen at most every 300 ms. Safe from any thread. */
  void progressUi() { progress(); }

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
    if (offline) beam.nearby.startSend(this, beam.nearby.peerById(peerId));
    else connectOut("offer");
  }

  /** Opens a new connection: the first offer, or a resume after a drop. */
  private void connectOut(final String mode) {
    closeConn();
    final Conn c = new Conn(++gen);
    conn = c;
    c.pc = factory(beam.app).createPeerConnection(config(), observer(c));
    if (c.pc == null) { fail("WebRTC unavailable"); return; }
    c.dc = c.pc.createDataChannel("beam", new DataChannel.Init());
    c.dc.registerObserver(new DataChannel.Observer() {
      public void onBufferedAmountChange(long previous) { synchronized (lock) { lock.notifyAll(); } }
      public void onStateChange() {
        if (c.dc.state() == DataChannel.State.CLOSED) beam.main.post(() -> dropped(c));
        synchronized (lock) { lock.notifyAll(); }
      }
      public void onMessage(DataChannel.Buffer b) {
        if (b.binary) return;
        final String s = text(b);
        beam.main.post(() -> {
          if (!c.live()) return;
          try {
            JSONObject m = new JSONObject(s);
            switch (m.optString("t")) {
              case "from": startPump(c, m.optInt("i", 0), Math.max(0, m.optLong("off", 0))); break;
              case "ok": finish(); break;
              case "cancel": fail("Cancelled"); break;
            }
          } catch (Exception ignored) {}
        });
      }
    });
    final Sdp failer = new Sdp() { void failed(String e) { beam.main.post(() -> dropped(c)); } };
    c.pc.createOffer(new Sdp() {
      @Override public void onCreateSuccess(SessionDescription desc) {
        c.pc.setLocalDescription(new Sdp() {
          @Override public void onSetSuccess() { whenGathered(c, () -> sendOffer(c, mode)); }
          void failed(String e) { failer.failed(e); }
        }, desc);
      }
      void failed(String e) { failer.failed(e); }
    }, new MediaConstraints());
    if ("resume".equals(mode)) {
      beam.main.postDelayed(() -> { if (conn == c && RECONNECTING.equals(state)) dropped(c); }, 20000);
    }
  }

  private void sendOffer(Conn c, String mode) {
    if (!c.live()) return;
    try {
      String sdp = c.pc.getLocalDescription().description;
      JSONObject d = new JSONObject().put("k", mode).put("tid", tid).put("gen", c.gen).put("sdp", sdp)
          .put("sig", beam.me.sign("beam-" + mode + "|" + tid + "|" + peerId + "|" + sdp)).put("pub", beam.me.pub);
      if ("offer".equals(mode)) {
        JSONArray files = new JSONArray();
        for (Item i : items) {
          JSONObject f = new JSONObject().put("name", i.name).put("size", i.size).put("type", i.type);
          if (i.text) f.put("text", true);
          files.put(f);
        }
        d.put("name", beam.prefs.name).put("kind", "android").put("files", files);
        state = WAITING;
        ui();
        beam.main.postDelayed(() -> {
          if (WAITING.equals(state)) {
            try { beam.signal(peerId, new JSONObject().put("k", "cancel").put("tid", tid)); } catch (Exception ignored) {}
            fail("No response");
          }
        }, Beam.OFFER_TIMEOUT);
      }
      beam.signal(peerId, d);
    } catch (Exception e) {
      fail("Setup failed");
    }
  }

  void onAnswer(String sdp, int answerGen) {
    final Conn c = conn;
    if (c == null || c.gen != answerGen) return;
    if (WAITING.equals(state)) { state = CONNECTING; ui(); }
    c.pc.setRemoteDescription(new Sdp() { void failed(String e) { beam.main.post(() -> dropped(c)); } },
        new SessionDescription(SessionDescription.Type.ANSWER, sdp));
  }

  private void startPump(final Conn c, final int i0, final long off0) {
    if (c.startedPump || !c.live()) return;
    if (i0 < 0 || i0 > items.size() || (i0 < items.size() && off0 > items.get(i0).size)) { fail("Bad resume point"); return; }
    c.startedPump = true;
    everOpen = true;
    pausedAt = 0;
    state = SENDING;
    long d = off0;
    for (int k = 0; k < i0; k++) d += items.get(k).size;
    done = d;
    ui();
    final ContentResolver cr = beam.app.getContentResolver();
    new Thread(() -> {
      byte[] buf = new byte[CHUNK];
      try {
        for (int i = i0; i < items.size(); i++) {
          Item it = items.get(i);
          long sent = i == i0 ? off0 : 0;
          sendText(c, "{\"t\":\"file\",\"i\":" + i + ",\"off\":" + sent + "}");
          InputStream in = openAt(cr, it, sent);
          try {
            while (sent < it.size) {
              int want = (int) Math.min(CHUNK, it.size - sent);
              int n = readFully(in, buf, want);
              if (n <= 0) throw new IOException(it.name + " is shorter than expected");
              sendBinary(c, buf, n);
              sent += n;
              done += n;
              progress();
            }
          } finally {
            in.close();
          }
          sendText(c, "{\"t\":\"eof\",\"i\":" + i + "}");
        }
        sendText(c, "{\"t\":\"done\"}");
        beam.main.post(() -> { if (c.live() && SENDING.equals(state)) { state = FINISHING; ui(); } });
      } catch (Stopped s) {
        // the connection went away; dropped() takes it from here
      } catch (Exception e) {
        final String msg = e.getMessage();
        beam.main.post(() -> { if (c.live()) fail(msg != null ? msg : "Transfer failed"); });
      }
    }, "beam-send").start();
  }

  private static InputStream openAt(ContentResolver cr, Item it, long off) throws IOException {
    if (it.text) {
      ByteArrayInputStream in = new ByteArrayInputStream(it.textValue.getBytes(StandardCharsets.UTF_8));
      in.skip(off);
      return in;
    }
    if (it.temp != null) {
      FileInputStream in = new FileInputStream(it.temp);
      in.getChannel().position(off);
      return in;
    }
    InputStream in = cr.openInputStream(it.uri);
    if (in == null) throw new IOException("Couldn't open " + it.name);
    if (in instanceof FileInputStream) {
      ((FileInputStream) in).getChannel().position(off);
      return in;
    }
    long left = off;
    byte[] scratch = new byte[CHUNK];
    while (left > 0) {
      long s = in.skip(left);
      if (s <= 0) {
        int r = in.read(scratch, 0, (int) Math.min(scratch.length, left));
        if (r < 0) throw new IOException(it.name + " is shorter than expected");
        s = r;
      }
      left -= s;
    }
    return in;
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

  private static final class Stopped extends IOException { Stopped() { super("Stopped"); } }

  private boolean usable(Conn c) {
    try {
      return c.live() && c.dc != null && c.dc.state() == DataChannel.State.OPEN;
    } catch (Exception e) {
      return false; // channel already released
    }
  }

  private void waitRoom(Conn c) throws IOException {
    synchronized (lock) {
      while (true) {
        if (!usable(c)) throw new Stopped();
        long buffered;
        try { buffered = c.dc.bufferedAmount(); } catch (Exception e) { throw new Stopped(); }
        if (buffered <= HIGH_WATER) break;
        try { lock.wait(200); } catch (InterruptedException e) { throw new Stopped(); }
      }
    }
  }

  private void sendBinary(Conn c, byte[] buf, int n) throws IOException {
    while (true) {
      waitRoom(c);
      try {
        if (c.dc.send(new DataChannel.Buffer(ByteBuffer.wrap(buf, 0, n), true))) return;
      } catch (IllegalStateException e) {
        throw new Stopped();
      }
      synchronized (lock) { try { lock.wait(100); } catch (InterruptedException e) { throw new Stopped(); } }
    }
  }

  private void sendText(Conn c, String s) throws IOException {
    waitRoom(c);
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    try {
      if (!c.dc.send(new DataChannel.Buffer(ByteBuffer.wrap(b), false))) throw new Stopped();
    } catch (IllegalStateException e) {
      throw new Stopped();
    }
  }

  private static String text(DataChannel.Buffer b) {
    byte[] bytes = new byte[b.data.remaining()];
    b.data.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  // ---------- dropped connections ----------
  /** The connection went away (WiFi blip, out of range, network switch). Pause and try to pick back up. */
  private void dropped(Conn c) {
    if (conn != c || !active() || ASKING.equals(state)) return;
    if (!incoming && !everOpen) { fail("Couldn't connect"); return; }
    closeConn();
    state = PAUSED;
    if (pausedAt == 0) pausedAt = System.currentTimeMillis();
    ui();
    if (!incoming) {
      beam.main.postDelayed(this::retry, 2000 + (long) (Math.random() * 1000));
    } else {
      beam.main.postDelayed(() -> {
        if (PAUSED.equals(state) && System.currentTimeMillis() - pausedAt >= RESUME_WINDOW) fail("Connection lost");
      }, RESUME_WINDOW + 1000);
    }
  }

  /** The server says the other device isn't connected right now. */
  void peerUnreachable() {
    if (RECONNECTING.equals(state) && conn != null) dropped(conn);
    else if (PAUSED.equals(state)) { /* keep waiting */ }
    else fail("Device went offline");
  }

  private void retry() {
    if (!PAUSED.equals(state)) return;
    if (System.currentTimeMillis() - pausedAt > RESUME_WINDOW) { fail("Connection lost"); return; }
    if (!beam.online) { beam.main.postDelayed(this::retry, 3000); return; }
    state = RECONNECTING;
    ui();
    connectOut("resume");
  }

  // ---------- receiving ----------
  void accept() {
    if (!ASKING.equals(state)) return;
    if (offline) { beam.nearby.accept(this); return; }
    state = CONNECTING;
    ui();
    answerConn(offerSdp, gen);
  }

  /** The sender reconnected after a drop. */
  void onResume(String sdp, int resumeGen) {
    if (!(PAUSED.equals(state) || RECEIVING.equals(state) || CONNECTING.equals(state) || RECONNECTING.equals(state))) return;
    state = RECONNECTING;
    if (pausedAt == 0) pausedAt = System.currentTimeMillis();
    ui();
    answerConn(sdp, resumeGen);
  }

  private void answerConn(String sdp, int g) {
    closeConn();
    gen = g;
    final Conn c = new Conn(g);
    conn = c;
    c.pc = factory(beam.app).createPeerConnection(config(), observer(c));
    if (c.pc == null) { fail("WebRTC unavailable"); return; }
    final Sdp failer = new Sdp() { void failed(String e) { beam.main.post(() -> dropped(c)); } };
    c.pc.setRemoteDescription(new Sdp() {
      @Override public void onSetSuccess() {
        c.pc.createAnswer(new Sdp() {
          @Override public void onCreateSuccess(SessionDescription desc) {
            c.pc.setLocalDescription(new Sdp() {
              @Override public void onSetSuccess() { whenGathered(c, () -> sendAnswer(c)); }
              void failed(String e) { failer.failed(e); }
            }, desc);
          }
          void failed(String e) { failer.failed(e); }
        }, new MediaConstraints());
      }
      void failed(String e) { failer.failed(e); }
    }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
  }

  private void sendAnswer(Conn c) {
    if (!c.live()) return;
    try {
      String sdp = c.pc.getLocalDescription().description;
      beam.signal(peerId, new JSONObject().put("k", "answer").put("tid", tid).put("gen", c.gen).put("sdp", sdp)
          .put("sig", beam.me.sign("beam-answer|" + tid + "|" + peerId + "|" + sdp)).put("pub", beam.me.pub));
    } catch (Exception e) {
      fail("Setup failed");
    }
  }

  private void setupReceive(final Conn c, final DataChannel channel) {
    c.dc = channel;
    channel.registerObserver(new DataChannel.Observer() {
      public void onBufferedAmountChange(long previous) {}
      public void onStateChange() {
        DataChannel.State s = channel.state();
        if (s == DataChannel.State.OPEN) beam.main.post(() -> opened(c));
        else if (s == DataChannel.State.CLOSED) beam.main.post(() -> dropped(c));
      }
      public void onMessage(DataChannel.Buffer b) {
        if (!c.live()) return;
        try {
          onData(c, b);
        } catch (Exception e) {
          final String msg = e.getMessage();
          beam.main.post(() -> fail(msg != null ? msg : "Transfer failed"));
        }
      }
    });
    if (channel.state() == DataChannel.State.OPEN) beam.main.post(() -> opened(c));
  }

  /** Tell the sender where to start: the beginning, or wherever we got to before a drop. */
  private void opened(Conn c) {
    if (!c.live() || c.sentFrom) return;
    c.sentFrom = true;
    state = RECEIVING;
    pausedAt = 0;
    ui();
    String from;
    synchronized (this) {
      from = curDone ? "{\"t\":\"from\",\"i\":" + (cur + 1) + ",\"off\":0}" : "{\"t\":\"from\",\"i\":" + cur + ",\"off\":" + got + "}";
    }
    try { sendText(c, from); } catch (IOException ignored) {}
  }

  private synchronized void onData(Conn c, DataChannel.Buffer b) throws Exception {
    if (!c.live()) return;
    if (!b.binary) {
      JSONObject m = new JSONObject(text(b));
      switch (m.optString("t")) {
        case "file": {
          int i = m.optInt("i", -1);
          long off = m.optLong("off", 0);
          if (i == cur && !curDone && off == got) break; // resuming the same file
          if (!(curDone && i == cur + 1 && off == 0 && i < items.size())) throw new IOException("Bad data");
          cur = i;
          got = 0;
          curDone = false;
          openOutput(items.get(cur));
          break;
        }
        case "eof": {
          Item it = items.get(cur);
          if (m.optInt("i", -1) != cur || curDone || got != it.size) throw new IOException("File was incomplete");
          closeOutput(it);
          curDone = true;
          break;
        }
        case "done":
          if (!curDone || cur != items.size() - 1) throw new IOException("File was incomplete");
          sendText(c, "{\"t\":\"ok\"}");
          beam.main.post(this::finish);
          break;
        case "cancel":
          beam.main.post(() -> fail("Cancelled"));
          break;
      }
      return;
    }
    if (curDone || cur < 0) throw new IOException("Bad data");
    Item it = items.get(cur);
    int n = b.data.remaining();
    if (got + n > it.size) throw new IOException("Too much data");
    byte[] bytes = new byte[n];
    b.data.get(bytes);
    if (textBuf != null) textBuf.write(bytes);
    else out.write(bytes);
    got += n;
    done += n;
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
    final Conn c = conn;
    beam.main.postDelayed(() -> { if (conn == c) closeConn(); }, 1500);
    if (offline) beam.nearby.onFinish(this);
    if (incoming) beam.notify.received(this);
  }

  void fail(String why) {
    if (!active()) return;
    final Conn c = conn;
    if (c != null && c.dc != null) {
      try {
        if (c.dc.state() == DataChannel.State.OPEN) c.dc.send(new DataChannel.Buffer(ByteBuffer.wrap("{\"t\":\"cancel\"}".getBytes(StandardCharsets.UTF_8)), false));
      } catch (Exception ignored) {}
    }
    state = FAILED;
    reason = why;
    synchronized (lock) { lock.notifyAll(); }
    if (offline) beam.nearby.onFail(this);
    if (incoming) new Thread(this::discardPartial).start();
    cleanupTemp();
    ui();
    beam.main.postDelayed(() -> { if (conn == c) closeConn(); }, 500);
  }

  private void cleanupTemp() {
    for (Item i : items) if (i.temp != null) { i.temp.delete(); i.temp = null; }
  }

  /** Releases the current connection. A sender thread still using it stops at its next check. */
  private void closeConn() {
    final Conn c = conn;
    conn = null;
    if (c == null || c.closed) return;
    c.closed = true;
    synchronized (lock) { lock.notifyAll(); }
    // Give a sender thread a moment to notice before the native objects are freed.
    beam.main.postDelayed(() -> {
      try { if (c.dc != null) { c.dc.unregisterObserver(); c.dc.close(); c.dc.dispose(); } } catch (Exception ignored) {}
      try { if (c.pc != null) c.pc.dispose(); } catch (Exception ignored) {}
    }, 400);
  }
}

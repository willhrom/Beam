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
 * One send or receive. The bytes travel over a "route": the internet path (WebRTC) or the
 * direct phone-to-phone path (Bluetooth discovery + Wi-Fi Direct, see Nearby). Both routes
 * speak the same protocol, so if one drops the transfer carries on over whichever route
 * is available, from the last byte received.
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

  /** A path the bytes can travel over. */
  interface Route {
    boolean usable();
    /** Sends a control message (JSON). */
    void sendText(String s) throws IOException;
    /** Sends file `i` from byte `off`, with the file/eof framing. Blocks until handed off. */
    void sendFile(Transfer t, int i, long off, InputStream in, long len) throws IOException;
    void close();
    boolean direct(); // phone-to-phone (no internet)
  }

  /** Thrown when the route went away mid-send; the drop handling takes it from there. */
  static final class Stopped extends IOException { Stopped() { super("Stopped"); } }

  final Beam beam;
  final String tid, peerId, peerName, peerKind;
  final boolean incoming, paired;
  final List<Item> items;
  final long total;
  volatile long done;
  volatile String state;
  String reason, offerSdp;
  int gen;
  Route offeredOn; // incoming: the direct route the offer arrived on (accepting uses it)
  final long created = System.currentTimeMillis();

  volatile Route route;
  private Route pumpRoute;
  private volatile Route rxStartedOn; // the sender has begun sending on this route
  private final Object lock = new Object();
  private long lastUi, pausedAt;
  private boolean everOpen, discovering;

  // receive state (guarded by `this`)
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

  boolean direct() { Route r = route; return r != null && r.direct(); }

  void ui() {
    lastUi = System.currentTimeMillis();
    beam.transferChanged(this);
  }

  /** Progress changed; refresh the screen at most every 300 ms. Safe from any thread. */
  void progressUi() {
    long now = System.currentTimeMillis();
    if (now - lastUi < 300) return;
    lastUi = now;
    beam.main.post(() -> beam.transferChanged(this));
  }

  // =====================================================================================
  // Starting
  // =====================================================================================

  void startSend() {
    state = PREPARING;
    ui();
    Beam.Peer near = beam.nearby.peerById(peerId);
    Beam.Peer online = beam.onlinePeer(peerId);
    // Same network → internet path (it's direct on the LAN anyway). Right next to us but
    // not on our network → phone-to-phone, no data used. Otherwise → internet.
    if (near != null && (online == null || !online.local)) beam.nearby.startSend(this, near, false);
    else connectOut("offer");
  }

  void accept() {
    if (!ASKING.equals(state)) return;
    if (offeredOn != null) { sendFromOver(offeredOn); return; }
    state = CONNECTING;
    ui();
    answerConn(offerSdp, gen);
  }

  /** Receiver: a route is ready; tell the sender where to start (0, or wherever we got to). */
  void sendFromOver(Route r) {
    if (!active()) return;
    if (route != r) attach(r);
    state = RECEIVING;
    pausedAt = 0;
    ui();
    try { r.sendText(fromMessage()); } catch (IOException e) { routeDropped(r); }
  }

  private synchronized String fromMessage() {
    return curDone ? "{\"t\":\"from\",\"i\":" + (cur + 1) + ",\"off\":0}" : "{\"t\":\"from\",\"i\":" + cur + ",\"off\":" + got + "}";
  }

  /** Repeat "start here" if the sender hasn't begun: the first message on a new connection can get lost. */
  private void resendFrom(Route r) {
    if (route != r || !active() || rxStartedOn == r) return;
    try { r.sendText(fromMessage()); } catch (IOException ignored) {}
  }

  private void retryFrom(final Route r, final int n) {
    beam.main.postDelayed(() -> {
      if (route != r || !active() || rxStartedOn == r || n >= 6) return;
      resendFrom(r);
      retryFrom(r, n + 1);
    }, 1500);
  }

  /** Makes `r` the current route, releasing the old one. */
  void attach(Route r) {
    Route old = route;
    route = r;
    if (old != null && old != r) old.close();
  }

  // =====================================================================================
  // Sending (route-independent)
  // =====================================================================================

  /** The receiver told us where to start. Runs the send loop for this route. */
  void startPump(final Route r, final int i0, final long off0) {
    if (pumpRoute == r || route != r || !active()) return;
    if (i0 < 0 || i0 > items.size() || (i0 < items.size() && off0 > items.get(i0).size)) { fail("Bad resume point"); return; }
    pumpRoute = r;
    everOpen = true;
    pausedAt = 0;
    stopDiscovering();
    state = SENDING;
    long d = off0;
    for (int k = 0; k < i0; k++) d += items.get(k).size;
    done = d;
    ui();
    final ContentResolver cr = beam.app.getContentResolver();
    new Thread(() -> {
      try {
        for (int i = i0; i < items.size(); i++) {
          if (route != r || !SENDING.equals(state)) throw new Stopped();
          Item it = items.get(i);
          long off = i == i0 ? off0 : 0;
          try (InputStream in = openAt(cr, it, off)) {
            r.sendFile(this, i, off, in, it.size - off);
          }
        }
        r.sendText("{\"t\":\"done\"}");
        beam.main.post(() -> { if (route == r && SENDING.equals(state)) { state = FINISHING; ui(); } });
      } catch (Stopped s) {
        // the route went away; routeDropped() takes it from here
      } catch (Exception e) {
        final String msg = e.getMessage();
        beam.main.post(() -> { if (route == r) fail(msg != null ? msg : "Transfer failed"); });
      }
    }, "beam-send").start();
  }

  void addDone(long n) {
    done += n;
    progressUi();
  }

  static InputStream openAt(ContentResolver cr, Item it, long off) throws IOException {
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
        int rd = in.read(scratch, 0, (int) Math.min(scratch.length, left));
        if (rd < 0) throw new IOException(it.name + " is shorter than expected");
        s = rd;
      }
      left -= s;
    }
    return in;
  }

  static int readFully(InputStream in, byte[] buf, int want) throws IOException {
    int n = 0;
    while (n < want) {
      int r = in.read(buf, n, want - n);
      if (r < 0) break;
      n += r;
    }
    return n;
  }

  // =====================================================================================
  // Receiving (route-independent): every route feeds control messages and bytes in here
  // =====================================================================================

  synchronized void rxControl(JSONObject m, Route r) throws IOException {
    if (!active() || route != r) return;
    switch (m.optString("t")) {
      case "file": {
        int i = m.optInt("i", -1);
        long off = m.optLong("off", 0);
        rxStartedOn = r;
        if (i == cur && !curDone && off == got) return; // resuming the same file
        if (!(curDone && i == cur + 1 && off == 0 && i < items.size())) throw new IOException("Bad data");
        cur = i;
        got = 0;
        curDone = false;
        openOutput(items.get(cur));
        return;
      }
      case "eof": {
        Item it = items.get(Math.max(cur, 0));
        if (m.optInt("i", -1) != cur || curDone || got != it.size) throw new IOException("File was incomplete");
        closeOutput(it);
        curDone = true;
        return;
      }
      case "done":
        if (!curDone || cur != items.size() - 1) throw new IOException("File was incomplete");
        r.sendText("{\"t\":\"ok\"}");
        beam.main.post(this::finish);
        return;
      case "cancel":
        beam.main.post(() -> fail("Cancelled"));
    }
  }

  synchronized void rxBytes(Route r, byte[] b, int off, int n) throws IOException {
    if (!active() || route != r) throw new Stopped();
    if (curDone || cur < 0) throw new IOException("Bad data");
    Item it = items.get(cur);
    if (got + n > it.size) throw new IOException("Too much data");
    if (textBuf != null) textBuf.write(b, off, n);
    else out.write(b, off, n);
    got += n;
    done += n;
    progressUi();
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

  // =====================================================================================
  // Drops and switching routes
  // =====================================================================================

  /** A route went away (WiFi blip, out of range, network switch). Pause and pick back up. */
  void routeDropped(Route r) {
    if (route != r || !active() || ASKING.equals(state)) return;
    if (!incoming && !everOpen) {
      // Never got going on this route. If the other route can reach them, try that once.
      if (r.direct() && beam.onlinePeer(peerId) != null && !triedOther) { triedOther = true; route = null; r.close(); connectOut("offer"); return; }
      if (!r.direct() && beam.nearby.peerById(peerId) != null && !triedOther) { triedOther = true; route = null; r.close(); beam.nearby.startSend(this, beam.nearby.peerById(peerId), false); return; }
      fail("Couldn't connect");
      return;
    }
    route = null;
    pumpRoute = null;
    r.close();
    state = PAUSED;
    if (pausedAt == 0) pausedAt = System.currentTimeMillis();
    synchronized (lock) { lock.notifyAll(); }
    ui();
    if (!incoming) {
      startDiscovering(); // look for them nearby too, in case the internet is what dropped
      beam.main.postDelayed(this::retry, 2000 + (long) (Math.random() * 1000));
    } else {
      beam.main.postDelayed(() -> {
        if (PAUSED.equals(state) && System.currentTimeMillis() - pausedAt >= RESUME_WINDOW) fail("Connection lost");
      }, RESUME_WINDOW + 1000);
    }
  }
  private boolean triedOther;

  /** The server says the other device isn't connected right now. */
  void peerUnreachable() {
    Route r = route;
    if (RECONNECTING.equals(state) && r != null) routeDropped(r);
    else if (PAUSED.equals(state)) { /* keep waiting */ }
    else if (!direct()) fail("Device went offline");
  }

  /** Sender: reconnect over whichever route can reach them now, preferring phone-to-phone. */
  private void retry() {
    if (!PAUSED.equals(state)) return;
    if (System.currentTimeMillis() - pausedAt > RESUME_WINDOW) { fail("Connection lost"); return; }
    Beam.Peer near = beam.nearby.peerById(peerId);
    if (near != null) {
      state = RECONNECTING;
      ui();
      beam.nearby.startSend(this, near, true);
    } else if (beam.online && beam.onlinePeer(peerId) != null) {
      state = RECONNECTING;
      ui();
      connectOut("resume");
    } else {
      beam.main.postDelayed(this::retry, 3000);
    }
  }

  /** Called when a resume attempt on some route didn't come through. */
  void attemptFailed(Route r) {
    if (route == r) routeDropped(r);
  }

  private void startDiscovering() {
    if (discovering) return;
    discovering = true;
    beam.nearby.discover(true);
  }

  private void stopDiscovering() {
    if (!discovering) return;
    discovering = false;
    beam.nearby.discover(false);
  }

  // =====================================================================================
  // The internet route: a WebRTC data channel
  // =====================================================================================

  private final class Conn implements Route {
    final int gen;
    PeerConnection pc;
    DataChannel dc;
    boolean gatherDone, gatherFired, sentFrom, closed;
    Runnable afterGather;
    Conn(int gen) { this.gen = gen; }

    public boolean direct() { return false; }

    public boolean usable() {
      try {
        return route == this && active() && !closed && dc != null && dc.state() == DataChannel.State.OPEN;
      } catch (Exception e) {
        return false; // channel already released
      }
    }

    private void waitRoom() throws IOException {
      synchronized (lock) {
        while (true) {
          if (!usable()) throw new Stopped();
          long buffered;
          try { buffered = dc.bufferedAmount(); } catch (Exception e) { throw new Stopped(); }
          if (buffered <= HIGH_WATER) break;
          try { lock.wait(200); } catch (InterruptedException e) { throw new Stopped(); }
        }
      }
    }

    public void sendText(String s) throws IOException {
      waitRoom();
      byte[] b = s.getBytes(StandardCharsets.UTF_8);
      try {
        if (!dc.send(new DataChannel.Buffer(ByteBuffer.wrap(b), false))) throw new Stopped();
      } catch (IllegalStateException e) {
        throw new Stopped();
      }
    }

    private void sendBinary(byte[] buf, int n) throws IOException {
      while (true) {
        waitRoom();
        try {
          if (dc.send(new DataChannel.Buffer(ByteBuffer.wrap(buf, 0, n), true))) return;
        } catch (IllegalStateException e) {
          throw new Stopped();
        }
        synchronized (lock) { try { lock.wait(100); } catch (InterruptedException e) { throw new Stopped(); } }
      }
    }

    public void sendFile(Transfer t, int i, long off, InputStream in, long len) throws IOException {
      sendText("{\"t\":\"file\",\"i\":" + i + ",\"off\":" + off + "}");
      byte[] buf = new byte[CHUNK];
      long sent = 0;
      while (sent < len) {
        int n = readFully(in, buf, (int) Math.min(CHUNK, len - sent));
        if (n <= 0) throw new IOException(items.get(i).name + " is shorter than expected");
        sendBinary(buf, n);
        sent += n;
        t.addDone(n);
      }
      sendText("{\"t\":\"eof\",\"i\":" + i + "}");
    }

    public void close() {
      if (closed) return;
      closed = true;
      synchronized (lock) { lock.notifyAll(); }
      // Give a sender thread a moment to notice before the native objects are freed.
      beam.main.postDelayed(() -> {
        try { if (dc != null) { dc.unregisterObserver(); dc.close(); dc.dispose(); } } catch (Exception ignored) {}
        try { if (pc != null) pc.dispose(); } catch (Exception ignored) {}
      }, 400);
    }
  }

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
        if (s == PeerConnection.IceConnectionState.FAILED) beam.main.post(() -> routeDropped(c));
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

  private static String text(DataChannel.Buffer b) {
    byte[] bytes = new byte[b.data.remaining()];
    b.data.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  /** Sender: open a WebRTC connection — the first offer, or a resume after a drop. */
  private void connectOut(final String mode) {
    final Conn c = new Conn(++gen);
    attach(c);
    c.pc = factory(beam.app).createPeerConnection(config(), observer(c));
    if (c.pc == null) { fail("WebRTC unavailable"); return; }
    c.dc = c.pc.createDataChannel("beam", new DataChannel.Init());
    c.dc.registerObserver(new DataChannel.Observer() {
      public void onBufferedAmountChange(long previous) { synchronized (lock) { lock.notifyAll(); } }
      public void onStateChange() {
        DataChannel.State st = c.dc.state();
        if (st == DataChannel.State.CLOSED) beam.main.post(() -> routeDropped(c));
        // Tell the receiver we're listening, in case its first message was sent before we could hear it.
        else if (st == DataChannel.State.OPEN) beam.main.post(() -> { try { if (route == c) c.sendText("{\"t\":\"ready\"}"); } catch (IOException ignored) {} });
        synchronized (lock) { lock.notifyAll(); }
      }
      public void onMessage(DataChannel.Buffer b) {
        if (b.binary) return;
        final String s = text(b);
        beam.main.post(() -> onSenderMessage(c, s));
      }
    });
    final Sdp failer = new Sdp() { void failed(String e) { beam.main.post(() -> routeDropped(c)); } };
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
      beam.main.postDelayed(() -> { if (route == c && RECONNECTING.equals(state)) routeDropped(c); }, 20000);
    }
  }

  /** Sender: a control message from the receiver, on any route. */
  void onSenderMessage(Route r, String s) {
    if (route != r || !active()) return;
    try {
      JSONObject m = new JSONObject(s);
      switch (m.optString("t")) {
        case "from": startPump(r, m.optInt("i", 0), Math.max(0, m.optLong("off", 0))); break;
        case "ok": finish(); break;
        case "cancel": fail("Cancelled"); break;
      }
    } catch (Exception ignored) {}
  }

  private void sendOffer(Conn c, String mode) {
    if (route != c || !active()) return;
    try {
      String sdp = c.pc.getLocalDescription().description;
      JSONObject d = new JSONObject().put("k", mode).put("tid", tid).put("gen", c.gen).put("sdp", sdp)
          .put("sig", beam.me.sign("beam-" + mode + "|" + tid + "|" + peerId + "|" + sdp)).put("pub", beam.me.pub);
      if ("offer".equals(mode)) {
        d.put("name", beam.prefs.name).put("kind", "android").put("files", filesJson());
        markWaiting();
      }
      beam.signal(peerId, d);
    } catch (Exception e) {
      fail("Setup failed");
    }
  }

  JSONArray filesJson() throws Exception {
    JSONArray files = new JSONArray();
    for (Item i : items) {
      JSONObject f = new JSONObject().put("name", i.name).put("size", i.size).put("type", i.type);
      if (i.text) f.put("text", true);
      files.put(f);
    }
    return files;
  }

  /** Sender: the offer is out; give the other person two minutes to accept. */
  void markWaiting() {
    if (!PREPARING.equals(state) && !CONNECTING.equals(state)) return;
    state = WAITING;
    ui();
    beam.main.postDelayed(() -> {
      if (WAITING.equals(state)) {
        if (!direct()) try { beam.signal(peerId, new JSONObject().put("k", "cancel").put("tid", tid)); } catch (Exception ignored) {}
        fail("No response");
      }
    }, Beam.OFFER_TIMEOUT);
  }

  void onAnswer(String sdp, int answerGen) {
    Route r = route;
    if (!(r instanceof Conn) || ((Conn) r).gen != answerGen) return;
    final Conn c = (Conn) r;
    if (WAITING.equals(state)) { state = CONNECTING; ui(); }
    c.pc.setRemoteDescription(new Sdp() { void failed(String e) { beam.main.post(() -> routeDropped(c)); } },
        new SessionDescription(SessionDescription.Type.ANSWER, sdp));
  }

  /** Receiver: the sender reconnected over the internet after a drop. */
  void onResume(String sdp, int resumeGen) {
    if (!(PAUSED.equals(state) || RECEIVING.equals(state) || CONNECTING.equals(state) || RECONNECTING.equals(state))) return;
    state = RECONNECTING;
    if (pausedAt == 0) pausedAt = System.currentTimeMillis();
    ui();
    answerConn(sdp, resumeGen);
  }

  private void answerConn(String sdp, int g) {
    gen = g;
    final Conn c = new Conn(g);
    attach(c);
    c.pc = factory(beam.app).createPeerConnection(config(), observer(c));
    if (c.pc == null) { fail("WebRTC unavailable"); return; }
    final Sdp failer = new Sdp() { void failed(String e) { beam.main.post(() -> routeDropped(c)); } };
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
    if (route != c || !active()) return;
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
        else if (s == DataChannel.State.CLOSED) beam.main.post(() -> routeDropped(c));
      }
      public void onMessage(DataChannel.Buffer b) {
        try {
          if (b.binary) {
            byte[] bytes = new byte[b.data.remaining()];
            b.data.get(bytes);
            rxBytes(c, bytes, 0, bytes.length);
          } else {
            JSONObject m = new JSONObject(text(b));
            if ("ready".equals(m.optString("t"))) beam.main.post(() -> resendFrom(c));
            else rxControl(m, c);
          }
        } catch (Stopped s) {
          // old route
        } catch (Exception e) {
          final String msg = e.getMessage();
          beam.main.post(() -> { if (route == c) fail(msg != null ? msg : "Transfer failed"); });
        }
      }
    });
    if (channel.state() == DataChannel.State.OPEN) beam.main.post(() -> opened(c));
  }

  private void opened(Conn c) {
    if (route != c || !active() || c.sentFrom) return;
    c.sentFrom = true;
    sendFromOver(c);
    retryFrom(c, 0);
  }

  // =====================================================================================
  // Ending
  // =====================================================================================

  void finish() {
    if (!active()) return;
    state = DONE;
    ui();
    cleanupTemp();
    stopDiscovering();
    final Route r = route;
    beam.main.postDelayed(() -> { if (r != null) r.close(); }, 1500);
    if (incoming) beam.notify.received(this);
  }

  void fail(String why) {
    if (!active()) return;
    final Route r = route;
    if (r != null) try { if (r.usable()) r.sendText("{\"t\":\"cancel\"}"); } catch (Exception ignored) {}
    state = FAILED;
    reason = why;
    synchronized (lock) { lock.notifyAll(); }
    if (incoming) new Thread(this::discardPartial).start();
    cleanupTemp();
    stopDiscovering();
    ui();
    beam.main.postDelayed(() -> { if (r != null) r.close(); }, 500);
  }

  private void cleanupTemp() {
    for (Item i : items) if (i.temp != null) { i.temp.delete(); i.temp = null; }
  }
}

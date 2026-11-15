package app.beam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The direct phone-to-phone route: devices find each other over Bluetooth Low Energy, then
 * Google's Nearby Connections carries the data over a direct Wi-Fi link (no router or
 * internet). It speaks the same transfer protocol as the internet route, so a transfer can
 * start on one and finish on the other.
 *
 * Security: Nearby encrypts the link. On top of that, each side signs the link's secret
 * token with its Beam key, so nobody can impersonate one of your devices or sit in the middle.
 */
final class Nearby {
  private static final String SERVICE = "app.beam.nearby.v1";

  /** One Nearby connection, carrying (at most) one transfer. */
  final class Link implements Transfer.Route {
    final String endpointId;
    Transfer t;
    String expectId;      // outgoing: the device we meant to reach
    boolean resume;       // outgoing: picking up a transfer that dropped
    byte[] token;
    boolean helloSent, verified, connected, closed;
    String peerId, peerName, peerKind;
    final ExecutorService rx = Executors.newSingleThreadExecutor();
    final Map<Long, Payload> streams = new HashMap<>();   // incoming data, by payload id
    final Map<Long, Integer> outStatus = new HashMap<>(); // outgoing data status, by payload id
    final Map<Long, Long> outBase = new HashMap<>();
    Link(String endpointId) { this.endpointId = endpointId; }

    public boolean direct() { return true; }

    public boolean usable() { return !closed && connected && verified && t != null && t.route == this && t.active(); }

    public void sendText(String s) throws IOException {
      if (closed || !connected) throw new Transfer.Stopped();
      client.sendPayload(endpointId, Payload.fromBytes(s.getBytes(StandardCharsets.UTF_8)));
    }

    public void sendFile(Transfer tr, int i, long off, InputStream in, long len) throws IOException {
      Payload p = Payload.fromStream(in);
      long pid = p.getId();
      synchronized (this) {
        outBase.put(pid, tr.done);
        outStatus.put(pid, PayloadTransferUpdate.Status.IN_PROGRESS);
      }
      sendText("{\"t\":\"file\",\"i\":" + i + ",\"off\":" + off + ",\"pid\":" + pid + ",\"len\":" + len + "}");
      client.sendPayload(endpointId, p);
      synchronized (this) {
        while (true) {
          Integer st = outStatus.get(pid);
          if (st != null && st == PayloadTransferUpdate.Status.SUCCESS) break;
          if (!usable() || (st != null && st != PayloadTransferUpdate.Status.IN_PROGRESS)) throw new Transfer.Stopped();
          try { wait(500); } catch (InterruptedException e) { throw new Transfer.Stopped(); }
        }
        outStatus.remove(pid);
        tr.done = outBase.remove(pid) + len;
      }
      tr.progressUi();
      sendText("{\"t\":\"eof\",\"i\":" + i + "}");
    }

    public void close() {
      if (closed) return;
      closed = true;
      synchronized (this) { notifyAll(); }
      rx.shutdownNow();
      beam.main.postDelayed(() -> {
        if (links.get(endpointId) == this) links.remove(endpointId);
        try { client.disconnectFromEndpoint(endpointId); } catch (Exception ignored) {}
      }, 600);
    }
  }

  private final Beam beam;
  private final ConnectionsClient client;
  final Map<String, Beam.Peer> found = new LinkedHashMap<>(); // endpointId -> peer
  private final Map<String, Link> links = new HashMap<>();
  private boolean advertising, discovering;
  private int discoverWanted;

  Nearby(Beam beam) {
    this.beam = beam;
    this.client = com.google.android.gms.nearby.Nearby.getConnectionsClient(beam.app);
  }

  /** The permissions Android needs before Bluetooth discovery and direct Wi-Fi can be used. */
  static String[] permissions() {
    List<String> p = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 31) {
      p.add(Manifest.permission.BLUETOOTH_SCAN);
      p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
      p.add(Manifest.permission.BLUETOOTH_CONNECT);
    } else {
      p.add(Manifest.permission.ACCESS_FINE_LOCATION);
    }
    if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
    return p.toArray(new String[0]);
  }

  static boolean allowed(Context c) {
    for (String p : permissions()) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
    return true;
  }

  boolean usable() { return beam.running && beam.account != null && allowed(beam.app); }

  /**
   * What other phones see over Bluetooth before connecting. No device name: the direct route
   * is only used between your own and paired devices, which already know each other's names.
   */
  private String endpointName() {
    return "b1|" + beam.me.id + "|p|android|";
  }

  // ---------- advertising: always on while Beam runs, so others can find this phone ----------
  void refresh() {
    if (!usable()) { stopAll(); return; }
    if (advertising) client.stopAdvertising();
    advertising = true;
    client.startAdvertising(endpointName(), SERVICE, lifecycle,
        new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
        .addOnFailureListener(e -> advertising = false);
    if (discoverWanted > 0 && !discovering) startDiscovery();
  }

  void stopAll() {
    if (advertising) client.stopAdvertising();
    if (discovering) client.stopDiscovery();
    advertising = discovering = false;
    if (!found.isEmpty()) { found.clear(); beam.changed(); }
  }

  // ---------- discovery: while a Beam screen is open or a transfer is reconnecting ----------
  void discover(boolean on) {
    discoverWanted = Math.max(0, discoverWanted + (on ? 1 : -1));
    if (discoverWanted > 0 && !discovering && usable()) startDiscovery();
    else if (discoverWanted == 0 && discovering) {
      beam.main.postDelayed(() -> {
        if (discoverWanted == 0 && discovering) {
          client.stopDiscovery();
          discovering = false;
          found.clear();
          beam.changed();
        }
      }, 30000);
    }
  }

  private void startDiscovery() {
    discovering = true;
    client.startDiscovery(SERVICE, discovery, new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
        .addOnFailureListener(e -> discovering = false);
  }

  private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
    @Override public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
      String[] p = info.getEndpointName().split("\\|", 5);
      if (p.length < 5 || !"b1".equals(p[0]) || !p[1].matches("[0-9a-f]{32}") || p[1].equals(beam.me.id)) return;
      // Offline, only devices whose keys we already know: your own (same account) and paired ones.
      // Strangers can't reach this phone without the server checking they've been invited.
      Prefs.Paired pp = beam.prefs.paired.get(p[1]);
      if (pp == null) return;
      Beam.Peer peer = new Beam.Peer(p[1], pp.name, p[3], pp.pub);
      peer.endpointId = endpointId;
      found.put(endpointId, peer);
      beam.changed();
    }

    @Override public void onEndpointLost(String endpointId) {
      if (found.remove(endpointId) != null) beam.changed();
    }
  };

  Beam.Peer peerById(String id) {
    for (Beam.Peer p : found.values()) if (p.id.equals(id)) return p;
    return null;
  }

  // ---------- connections ----------
  private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
    @Override public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
      Link l = links.get(endpointId);
      if (l == null || l.closed) {
        // Someone wants to send to us. Accept the link; they must still prove who they are.
        if (!info.isIncomingConnection()) { client.rejectConnection(endpointId); return; }
        l = new Link(endpointId);
        links.put(endpointId, l);
      }
      l.token = info.getRawAuthenticationToken();
      client.acceptConnection(endpointId, payloads);
      final Link link = l;
      beam.main.postDelayed(() -> { if (!link.verified && !link.closed) { link.close(); lost(link); } }, 20000);
    }

    @Override public void onConnectionResult(String endpointId, ConnectionResolution result) {
      Link l = links.get(endpointId);
      if (l == null) return;
      if (result.getStatus().getStatusCode() != ConnectionsStatusCodes.STATUS_OK) { lost(l); return; }
      l.connected = true;
      sendHello(l);
    }

    @Override public void onDisconnected(String endpointId) {
      Link l = links.get(endpointId);
      if (l != null) lost(l);
    }
  };

  /** A link went away or never came up. Any transfer on it pauses (or tries the other route). */
  private void lost(Link l) {
    l.connected = false;
    if (links.get(l.endpointId) == l) links.remove(l.endpointId);
    l.closed = true;
    synchronized (l) { l.notifyAll(); }
    l.rx.shutdownNow();
    Transfer t = l.t;
    if (t == null) return;
    if (t.incoming && Transfer.ASKING.equals(t.state) && t.offeredOn == l) {
      beam.notify.cancelRequest(t);
      t.fail("Connection lost");
    } else if (t.route == l) {
      t.routeDropped(l);
    }
  }

  private void sendHello(Link l) {
    if (l.helloSent || l.token == null) return;
    l.helloSent = true;
    try {
      String sig = beam.me.sign("beam-nearby|" + Crypto.hex(l.token) + "|" + beam.me.id);
      l.sendText(new JSONObject().put("t", "hello").put("pub", beam.me.pub).put("sig", sig).put("name", beam.prefs.name).put("kind", "android").toString());
    } catch (Exception e) {
      lost(l);
    }
  }

  // ---------- sending ----------
  /** Start (or, with `resume`, pick back up) a transfer over a direct link to `p`. */
  void startSend(Transfer t, Beam.Peer p, boolean resume) {
    if (p == null || p.endpointId == null) { t.fail("That device went away"); return; }
    Link old = links.get(p.endpointId);
    if (old != null && !old.closed) old.close();
    final Link l = new Link(p.endpointId);
    l.t = t;
    l.expectId = p.id;
    l.resume = resume;
    links.put(p.endpointId, l);
    t.attach(l);
    if (!resume) { t.state = Transfer.CONNECTING; t.ui(); }
    client.requestConnection(endpointName(), p.endpointId, lifecycle)
        .addOnFailureListener(e -> lost(l));
    // Give up on this attempt if the link doesn't come up.
    beam.main.postDelayed(() -> { if (!l.verified && !l.closed) { l.close(); lost(l); } }, 30000);
  }

  // ---------- receiving side helpers ----------
  /** Receiver declined an offer that came over a direct link. */
  void decline(Transfer t) {
    Transfer.Route r = t.offeredOn;
    if (!(r instanceof Link)) return;
    Link l = (Link) r;
    try { l.sendText("{\"t\":\"decline\"}"); } catch (Exception ignored) {}
    beam.main.postDelayed(l::close, 1000);
  }

  // ---------- payloads ----------
  private final PayloadCallback payloads = new PayloadCallback() {
    @Override public void onPayloadReceived(String endpointId, Payload p) {
      Link l = links.get(endpointId);
      if (l == null) return;
      if (p.getType() == Payload.Type.STREAM) {
        if (!l.verified || l.t == null || !l.t.incoming) { client.cancelPayload(p.getId()); return; }
        synchronized (l) { l.streams.put(p.getId(), p); l.notifyAll(); }
        return;
      }
      if (p.getType() != Payload.Type.BYTES) { client.cancelPayload(p.getId()); return; }
      try {
        onMessage(l, new JSONObject(new String(p.asBytes(), StandardCharsets.UTF_8)));
      } catch (Exception e) {
        lost(l);
        l.close();
      }
    }

    @Override public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate u) {
      Link l = links.get(endpointId);
      if (l == null) return;
      long pid = u.getPayloadId();
      int st = u.getStatus();
      synchronized (l) {
        if (l.outStatus.containsKey(pid)) {
          // our outgoing data
          Transfer t = l.t;
          Long base = l.outBase.get(pid);
          if (t != null && base != null && st == PayloadTransferUpdate.Status.IN_PROGRESS) {
            t.done = base + u.getBytesTransferred();
            t.progressUi();
          }
          l.outStatus.put(pid, st);
          l.notifyAll();
        }
      }
      if (st == PayloadTransferUpdate.Status.FAILURE) {
        Transfer t = l.t;
        if (t != null && t.route == l) { l.close(); t.routeDropped(l); }
      }
    }
  };

  private void onMessage(final Link l, final JSONObject m) throws Exception {
    String type = m.optString("t");
    if ("hello".equals(type)) {
      String pub = m.optString("pub");
      String id = Crypto.idFromPub(pub);
      boolean ok = l.token != null && beam.trusted(id, pub)
          && Crypto.verify(pub, m.optString("sig"), "beam-nearby|" + Crypto.hex(l.token) + "|" + id)
          && (l.expectId == null || l.expectId.equals(id));
      boolean mine = beam.prefs.paired.containsKey(id);
      if (!ok || !mine) { l.close(); lost(l); return; } // only your own and paired devices
      l.verified = true;
      l.peerId = id;
      l.peerName = mine ? beam.prefs.paired.get(id).name : Beam.trim(m.optString("name", "Device"));
      l.peerKind = m.optString("kind", "android");
      sendHello(l);
      Transfer t = l.t;
      if (t != null && !t.incoming && t.route == l) {
        if (l.resume) {
          l.sendText(new JSONObject().put("t", "resume").put("tid", t.tid).toString());
        } else {
          l.sendText(new JSONObject().put("t", "offer").put("tid", t.tid).put("name", beam.prefs.name).put("kind", "android")
              .put("files", t.filesJson()).toString());
          t.markWaiting();
        }
      }
      return;
    }
    if (!l.verified) { l.close(); lost(l); return; }
    final Transfer t = l.t;
    switch (type) {
      // ----- receiver side -----
      case "offer":
      case "resume": {
        String tid = m.optString("tid");
        Transfer existing = beam.tx.get(tid);
        if (existing != null) {
          // A transfer we already know about, now arriving over this direct link.
          if (!existing.incoming || !existing.peerId.equals(l.peerId)) { l.close(); return; }
          l.t = existing;
          if (Transfer.DONE.equals(existing.state)) { l.sendText("{\"t\":\"complete\"}"); beam.main.postDelayed(l::close, 1500); }
          else if (Transfer.ASKING.equals(existing.state)) existing.offeredOn = l; // accepting will use this link
          else if (existing.active()) existing.sendFromOver(l);
          return;
        }
        if ("resume".equals(type)) { l.close(); return; } // nothing to resume
        org.json.JSONArray files = Transfer.unpackFiles(m);
        if (tid.isEmpty() || files == null || files.length() == 0) { l.close(); return; }
        List<Transfer.Item> items = new ArrayList<>();
        for (int i = 0; i < Math.min(files.length(), Transfer.MAX_ITEMS); i++) {
          JSONObject f = files.getJSONObject(i);
          items.add(new Transfer.Item(Transfer.cleanName(f.optString("name")), Math.max(0, f.optLong("size")),
              f.optString("type", "application/octet-stream"), f.optBoolean("text")));
      items.get(items.size() - 1).path = Transfer.cleanPath(f.optString("path"));
        }
        Transfer in = new Transfer(beam, tid, true, l.peerId, l.peerName, l.peerKind, beam.prefs.paired.containsKey(l.peerId), items);
        in.offeredOn = l;
        l.t = in;
        beam.incoming(in);
        return;
      }
      case "file":
      case "eof":
      case "done":
        if (t == null || !t.incoming) return;
        l.rx.execute(() -> receive(l, t, m));
        return;
      // ----- sender side -----
      case "from":
      case "ok":
        if (t != null && !t.incoming) t.onSenderMessage(l, m.toString());
        return;
      case "complete":
        if (t != null && !t.incoming) t.finish();
        return;
      case "decline":
        if (t != null && !t.incoming) t.fail("Declined");
        return;
      // ----- either side -----
      case "cancel":
        if (t != null && t.route == l) t.fail("Cancelled");
    }
  }

  /** Receiver, on the link's own thread: apply data messages in the order they were sent. */
  private void receive(Link l, Transfer t, JSONObject m) {
    try {
      t.rxControl(m, l);
      if (!"file".equals(m.optString("t"))) return;
      long pid = m.optLong("pid"), len = m.optLong("len");
      Payload p;
      synchronized (l) {
        long until = System.currentTimeMillis() + 60000;
        while ((p = l.streams.remove(pid)) == null) {
          if (l.closed || System.currentTimeMillis() > until) throw new Transfer.Stopped();
          l.wait(500);
        }
      }
      byte[] buf = new byte[Transfer.CHUNK];
      long left = len;
      try (InputStream in = p.asStream().asInputStream()) {
        while (left > 0) {
          int n = in.read(buf, 0, (int) Math.min(buf.length, left));
          if (n < 0) throw new Transfer.Stopped(); // link broke mid-file; resume picks it up
          t.rxBytes(l, buf, 0, n);
          left -= n;
        }
      }
    } catch (Transfer.Stopped | InterruptedException s) {
      beam.main.post(() -> { if (t.route == l) { l.close(); t.routeDropped(l); } });
    } catch (Exception e) {
      final String msg = e.getMessage();
      beam.main.post(() -> { if (t.route == l) t.fail(msg != null ? msg : "Transfer failed"); });
    }
  }
}

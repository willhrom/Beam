package app.beam;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline sharing between Android phones, like AirDrop: devices find each other over
 * Bluetooth Low Energy, then Google's Nearby Connections moves the files over a direct
 * Wi-Fi link (no router or internet needed).
 *
 * Security: Nearby encrypts the link. On top of that, each side signs the link's secret
 * token with its Beam key, so a device can't pretend to be one of yours, and nobody in
 * the middle can swap themselves in without the signatures failing.
 */
final class Nearby {
  private static final String SERVICE = "app.beam.nearby.v1";
  private static final int MAX_TEXT = 30000;

  /** One Nearby connection, used for a single transfer. */
  private final class Link {
    final String endpointId;
    Transfer t;              // set once the offer is made or received
    String expectId;         // outgoing: the device we meant to reach
    byte[] token;
    boolean helloSent, verified;
    String peerId, peerPub, peerName, peerKind;
    final Map<Long, Integer> pidToItem = new HashMap<>();
    final Map<Long, Payload> files = new HashMap<>();
    final Set<Long> complete = new HashSet<>();
    final Map<Long, Long> progress = new HashMap<>();
    final Set<Integer> itemsDone = new HashSet<>();
    final List<ParcelFileDescriptor> open = new ArrayList<>();
    Link(String endpointId) { this.endpointId = endpointId; }
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

  boolean usable() { return beam.prefs.offline && allowed(beam.app); }

  /** What other phones see before connecting: Beam ID, who may see us, device kind and name. */
  private String endpointName() {
    String name = beam.prefs.name.replace("|", " ");
    if (name.length() > 24) name = name.substring(0, 24);
    return "b1|" + beam.me.id + "|" + (beam.prefs.nearby ? "e" : "p") + "|android|" + name;
  }

  // ---------- advertising (always on while Beam runs, so others can find this phone) ----------
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

  // ---------- discovery (only while a Beam screen is open, to save battery) ----------
  void discover(boolean on) {
    discoverWanted = Math.max(0, discoverWanted + (on ? 1 : -1));
    if (discoverWanted > 0 && !discovering && usable()) startDiscovery();
    else if (discoverWanted == 0 && discovering) {
      // Keep looking for a little while after the screen closes, in case a send is about to start.
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
      boolean mine = beam.prefs.paired.containsKey(p[1]);
      if (!"e".equals(p[2]) && !mine) return; // only visible to its own devices
      if (!beam.prefs.nearby && !mine) return; // we chose to only deal with our own devices
      Prefs.Paired pp = beam.prefs.paired.get(p[1]);
      Beam.Peer peer = new Beam.Peer(p[1], pp != null ? pp.name : Beam.trim(p[4]), p[3], pp != null ? pp.pub : "");
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
      if (l == null) {
        // Someone wants to send to us. Accept the link; they must still prove who they are.
        if (!info.isIncomingConnection()) { client.rejectConnection(endpointId); return; }
        l = new Link(endpointId);
        links.put(endpointId, l);
      }
      l.token = info.getRawAuthenticationToken();
      client.acceptConnection(endpointId, payloads);
    }

    @Override public void onConnectionResult(String endpointId, ConnectionResolution result) {
      Link l = links.get(endpointId);
      if (l == null) return;
      if (result.getStatus().getStatusCode() != ConnectionsStatusCodes.STATUS_OK) {
        drop(l, "Couldn't connect");
        return;
      }
      sendHello(l);
    }

    @Override public void onDisconnected(String endpointId) {
      Link l = links.get(endpointId);
      if (l != null) drop(l, "Connection lost");
    }
  };

  private void sendHello(Link l) {
    if (l.helloSent || l.token == null) return;
    l.helloSent = true;
    try {
      String sig = beam.me.sign("beam-nearby|" + Crypto.hex(l.token) + "|" + beam.me.id);
      send(l, new JSONObject().put("t", "hello").put("pub", beam.me.pub).put("sig", sig).put("name", beam.prefs.name).put("kind", "android"));
    } catch (Exception e) {
      drop(l, "Setup failed");
    }
  }

  private void send(Link l, JSONObject o) {
    client.sendPayload(l.endpointId, Payload.fromBytes(o.toString().getBytes(StandardCharsets.UTF_8)));
  }

  /** Drop a link; if a transfer was riding on it and still running, it fails. */
  private void drop(Link l, String why) {
    if (links.remove(l.endpointId) == null) return;
    try { client.disconnectFromEndpoint(l.endpointId); } catch (Exception ignored) {}
    for (ParcelFileDescriptor p : l.open) try { p.close(); } catch (Exception ignored) {}
    // Clean up any received files Nearby left behind that we didn't move yet.
    for (Payload p : l.files.values()) {
      try {
        Uri u = p.asFile() != null ? p.asFile().asUri() : null;
        if (u != null) beam.app.getContentResolver().delete(u, null, null);
      } catch (Exception ignored) {}
    }
    if (l.t != null && l.t.active()) l.t.fail(why);
  }

  // ---------- sending ----------
  void startSend(Transfer t, Beam.Peer p) {
    for (Transfer.Item i : t.items) {
      if (i.text && i.textValue.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT) { t.fail("Text is too long to send offline"); return; }
    }
    if (p == null || p.endpointId == null) { t.fail("That device went away"); return; }
    final Link l = new Link(p.endpointId);
    l.t = t;
    l.expectId = p.id;
    links.put(p.endpointId, l);
    t.state = Transfer.CONNECTING;
    t.ui();
    client.requestConnection(endpointName(), p.endpointId, lifecycle)
        .addOnFailureListener(e -> drop(l, "Couldn't connect"));
  }

  private void sendOffer(Link l) {
    Transfer t = l.t;
    try {
      JSONArray files = new JSONArray();
      for (Transfer.Item i : t.items) {
        JSONObject f = new JSONObject().put("name", i.name).put("size", i.size).put("type", i.type);
        if (i.text) f.put("text", true);
        files.put(f);
      }
      send(l, new JSONObject().put("t", "offer").put("tid", t.tid).put("name", beam.prefs.name).put("kind", "android").put("files", files));
      t.state = Transfer.WAITING;
      t.ui();
      beam.main.postDelayed(() -> { if (Transfer.WAITING.equals(t.state)) t.fail("No response"); }, Beam.OFFER_TIMEOUT);
    } catch (Exception e) {
      t.fail("Setup failed");
    }
  }

  private void sendFiles(Link l) {
    Transfer t = l.t;
    t.state = Transfer.SENDING;
    t.done = 0;
    t.ui();
    ContentResolver cr = beam.app.getContentResolver();
    try {
      for (int i = 0; i < t.items.size(); i++) {
        Transfer.Item it = t.items.get(i);
        if (it.text) {
          send(l, new JSONObject().put("t", "text").put("i", i).put("v", it.textValue));
          l.itemsDone.add(i);
          t.done += it.size;
          continue;
        }
        ParcelFileDescriptor pfd = it.temp != null
            ? ParcelFileDescriptor.open(it.temp, ParcelFileDescriptor.MODE_READ_ONLY)
            : cr.openFileDescriptor(it.uri, "r");
        if (pfd == null) throw new Exception("Couldn't open " + it.name);
        l.open.add(pfd);
        Payload p = Payload.fromFile(pfd);
        l.pidToItem.put(p.getId(), i);
        send(l, new JSONObject().put("t", "file").put("i", i).put("pid", p.getId()));
        client.sendPayload(l.endpointId, p);
      }
      if (l.itemsDone.size() == t.items.size()) { t.state = Transfer.FINISHING; t.ui(); }
    } catch (Exception e) {
      t.fail(e.getMessage() != null ? e.getMessage() : "Couldn't read the files");
    }
  }

  // ---------- receiving ----------
  void accept(Transfer t) {
    Link l = linkFor(t);
    if (l == null) { t.fail("Connection lost"); return; }
    t.state = Transfer.RECEIVING;
    t.ui();
    try { send(l, new JSONObject().put("t", "accept").put("tid", t.tid)); } catch (Exception ignored) {}
  }

  void decline(Transfer t) {
    Link l = linkFor(t);
    if (l == null) return;
    try { send(l, new JSONObject().put("t", "decline").put("tid", t.tid)); } catch (Exception ignored) {}
    beam.main.postDelayed(() -> drop(l, "Declined"), 1000);
  }

  /** The transfer failed or was cancelled on this side: tell the other phone and hang up. */
  void onFail(Transfer t) {
    Link l = linkFor(t);
    if (l == null) return;
    try { send(l, new JSONObject().put("t", "cancel").put("tid", t.tid)); } catch (Exception ignored) {}
    for (Long pid : l.files.keySet()) try { client.cancelPayload(pid); } catch (Exception ignored) {}
    beam.main.postDelayed(() -> drop(l, t.reason), 800);
  }

  void onFinish(Transfer t) {
    Link l = linkFor(t);
    if (l != null) beam.main.postDelayed(() -> drop(l, null), 1500);
  }

  private Link linkFor(Transfer t) {
    for (Link l : links.values()) if (l.t == t) return l;
    return null;
  }

  // ---------- payloads ----------
  private final PayloadCallback payloads = new PayloadCallback() {
    @Override public void onPayloadReceived(String endpointId, Payload p) {
      Link l = links.get(endpointId);
      if (l == null) return;
      if (p.getType() == Payload.Type.FILE) {
        // Only take files we were told about, after the person accepted.
        if (l.t == null || !l.t.incoming || !Transfer.RECEIVING.equals(l.t.state) || !l.verified) { client.cancelPayload(p.getId()); return; }
        l.files.put(p.getId(), p);
        tryStore(l, p.getId());
        return;
      }
      if (p.getType() != Payload.Type.BYTES) { client.cancelPayload(p.getId()); return; }
      try {
        onMessage(l, new JSONObject(new String(p.asBytes(), StandardCharsets.UTF_8)));
      } catch (Exception e) {
        drop(l, "Bad data");
      }
    }

    @Override public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate u) {
      Link l = links.get(endpointId);
      if (l == null || l.t == null) return;
      Transfer t = l.t;
      long pid = u.getPayloadId();
      if (!l.pidToItem.containsKey(pid) && !l.files.containsKey(pid)) return; // control messages
      l.progress.put(pid, u.getBytesTransferred());
      long sum = 0;
      for (int i : l.itemsDone) sum += t.items.get(i).size;
      for (Map.Entry<Long, Long> e : l.progress.entrySet()) {
        Integer idx = l.pidToItem.get(e.getKey());
        if (idx == null || !l.itemsDone.contains(idx)) sum += e.getValue();
      }
      t.done = Math.min(sum, t.total);
      int st = u.getStatus();
      if (st == PayloadTransferUpdate.Status.SUCCESS) {
        if (t.incoming) {
          l.complete.add(pid);
          tryStore(l, pid);
        } else {
          Integer idx = l.pidToItem.get(pid);
          if (idx != null) l.itemsDone.add(idx);
          if (l.itemsDone.size() == t.items.size()) { t.state = Transfer.FINISHING; t.ui(); }
          else t.ui();
        }
      } else if (st == PayloadTransferUpdate.Status.FAILURE || st == PayloadTransferUpdate.Status.CANCELED) {
        t.fail("Transfer failed");
      } else {
        t.progressUi();
      }
    }
  };

  private void onMessage(Link l, JSONObject m) throws Exception {
    String type = m.optString("t");
    if ("hello".equals(type)) {
      String pub = m.optString("pub");
      String id = Crypto.idFromPub(pub);
      boolean ok = l.token != null && beam.trusted(id, pub)
          && Crypto.verify(pub, m.optString("sig"), "beam-nearby|" + Crypto.hex(l.token) + "|" + id)
          && (l.expectId == null || l.expectId.equals(id));
      boolean mine = beam.prefs.paired.containsKey(id);
      if (!ok || (!beam.prefs.nearby && !mine)) { drop(l, "Couldn't verify that device"); return; }
      l.verified = true;
      l.peerId = id;
      l.peerPub = pub;
      l.peerName = mine ? beam.prefs.paired.get(id).name : Beam.trim(m.optString("name", "Device"));
      l.peerKind = m.optString("kind", "android");
      sendHello(l);
      if (l.t != null && !l.t.incoming) sendOffer(l);
      return;
    }
    if (!l.verified) { drop(l, "Not verified"); return; }
    Transfer t = l.t;
    switch (type) {
      case "offer": {
        if (t != null) return;
        JSONArray files = m.optJSONArray("files");
        if (files == null || files.length() == 0) { drop(l, null); return; }
        List<Transfer.Item> items = new ArrayList<>();
        for (int i = 0; i < Math.min(files.length(), 500); i++) {
          JSONObject f = files.getJSONObject(i);
          items.add(new Transfer.Item(Transfer.cleanName(f.optString("name")), Math.max(0, f.optLong("size")),
              f.optString("type", "application/octet-stream"), f.optBoolean("text")));
        }
        String tid = m.optString("tid");
        if (tid.isEmpty() || beam.tx.containsKey(tid)) { drop(l, null); return; }
        Transfer in = new Transfer(beam, tid, true, l.peerId, l.peerName, l.peerKind, beam.prefs.paired.containsKey(l.peerId), items);
        in.offline = true;
        l.t = in;
        beam.incoming(in);
        return;
      }
      case "accept":
        if (t != null && !t.incoming && Transfer.WAITING.equals(t.state)) sendFiles(l);
        return;
      case "decline":
        if (t != null) t.fail("Declined");
        return;
      case "cancel":
        if (t != null) t.fail("Cancelled");
        return;
      case "ok":
        if (t != null && !t.incoming) t.finish();
        return;
      case "file": {
        if (t == null || !t.incoming || !Transfer.RECEIVING.equals(t.state)) return;
        int i = m.optInt("i", -1);
        if (i < 0 || i >= t.items.size() || t.items.get(i).text || l.pidToItem.containsValue(i)) { t.fail("Bad data"); return; }
        long pid = m.optLong("pid");
        l.pidToItem.put(pid, i);
        tryStore(l, pid);
        return;
      }
      case "text": {
        if (t == null || !t.incoming || !Transfer.RECEIVING.equals(t.state)) return;
        int i = m.optInt("i", -1);
        if (i < 0 || i >= t.items.size() || !t.items.get(i).text) { t.fail("Bad data"); return; }
        t.items.get(i).textValue = m.optString("v");
        l.itemsDone.add(i);
        t.done += t.items.get(i).size;
        checkAllReceived(l);
        return;
      }
    }
  }

  /** A received file is ready once we've seen both its announcement and its completed data. */
  private void tryStore(final Link l, final long pid) {
    final Integer idx = l.pidToItem.get(pid);
    final Payload p = l.files.get(pid);
    if (idx == null || p == null || !l.complete.contains(pid)) return;
    l.files.remove(pid);
    final Transfer t = l.t;
    final Transfer.Item it = t.items.get(idx);
    new Thread(() -> {
      String err = null;
      Uri dest = null;
      ContentResolver cr = beam.app.getContentResolver();
      Uri src = p.asFile() != null ? p.asFile().asUri() : null;
      try {
        if (src == null) throw new Exception("File was missing");
        String mime = it.type;
        int dot = it.name.lastIndexOf('.');
        if (dot > 0) {
          String mm = MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.name.substring(dot + 1).toLowerCase());
          if (mm != null) mime = mm;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, it.name);
        v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Beam");
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        dest = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (dest == null) throw new Exception("Couldn't save to Downloads");
        long copied = 0;
        try (InputStream in = cr.openInputStream(src); OutputStream out = cr.openOutputStream(dest)) {
          if (in == null || out == null) throw new Exception("Couldn't save to Downloads");
          byte[] buf = new byte[256 * 1024];
          int n;
          while ((n = in.read(buf)) > 0) {
            copied += n;
            if (copied > it.size) throw new Exception("Too much data");
            out.write(buf, 0, n);
          }
        }
        if (copied != it.size) throw new Exception("File was incomplete");
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(dest, done, null, null);
      } catch (Exception e) {
        err = e.getMessage() != null ? e.getMessage() : "Couldn't save";
        if (dest != null) try { cr.delete(dest, null, null); } catch (Exception ignored) {}
        dest = null;
      }
      try { if (src != null) cr.delete(src, null, null); } catch (Exception ignored) {}
      final String error = err;
      final Uri saved = dest;
      beam.main.post(() -> {
        if (error != null) { t.fail(error); return; }
        it.saved = saved;
        l.itemsDone.add(idx);
        checkAllReceived(l);
      });
    }, "beam-nearby-save").start();
  }

  private void checkAllReceived(Link l) {
    Transfer t = l.t;
    if (t == null || !t.active() || l.itemsDone.size() != t.items.size()) { if (t != null) t.ui(); return; }
    try { send(l, new JSONObject().put("t", "ok").put("tid", t.tid)); } catch (Exception ignored) {}
    t.done = t.total;
    t.finish();
  }
}

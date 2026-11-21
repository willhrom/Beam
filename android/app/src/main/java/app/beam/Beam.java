package app.beam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * The heart of the app: keeps the connection to the handshake server, knows which devices
 * are around, and runs transfers. Everything here runs on the main thread.
 */
final class Beam {
  static final String SERVER = BuildConfig.SERVER_URL;
  static final long OFFER_TIMEOUT = 120_000;

  interface Listener {
    void onChange();
    void onEvent(String type, Object arg);
  }

  static final class Peer {
    final String id, name, kind, pub;
    String endpointId; // set when found nearby over Bluetooth
    boolean mine;      // signed in to the same account
    boolean local;     // the server says it's on our network
    long seen;         // offline devices: when it was last online
    Peer(String id, String name, String kind, String pub) { this.id = id; this.name = name; this.kind = kind; this.pub = pub; }
  }

  static final class Pairing {
    final boolean show; // true = this device shows the code
    String code, error, safety;
    boolean submitted; // a code was sent and we're waiting for the server
    Prefs.Paired peer;
    Pairing(boolean show) { this.show = show; }
  }

  private static Beam instance;
  static synchronized Beam get(Context c) {
    if (instance == null) instance = new Beam(c.getApplicationContext());
    return instance;
  }

  final Context app;
  final Prefs prefs;
  final Identity me;
  final Handler main = new Handler(Looper.getMainLooper());
  final Notify notify;
  final Nearby nearby;
  final List<Listener> listeners = new CopyOnWriteArrayList<>();
  final Map<String, Transfer> tx = new LinkedHashMap<>();
  List<Peer> peers = new ArrayList<>();
  /** My account's devices that aren't connected right now (shown dimmed, so Beam remembers them). */
  List<Peer> offline = new ArrayList<>();
  List<Peer> offlineAll = new ArrayList<>();   // every offline device on my account, copies included

  static java.util.Set<String> onlineIds(List<Peer> list) {
    java.util.Set<String> s = new java.util.HashSet<>();
    for (Peer p : list) s.add(p.id);
    return s;
  }

  /** Leftovers from devices that came back with a new key: older pairings of the same device, and my
   *  account's offline copies of a device I still have. [0] = pairings to remove, [1] = account copies to forget. */
  List<List<String>> duplicates() {
    java.util.Set<String> on = onlineIds(peers);
    List<String> pairs = new ArrayList<>(), devs = new ArrayList<>();
    List<Prefs.Paired> all = new ArrayList<>(prefs.paired.values());
    for (int i = 0; i < all.size(); i++) {
      Prefs.Paired p = all.get(i);
      if (on.contains(p.id)) continue;
      for (int j = 0; j < all.size(); j++) {
        Prefs.Paired o = all.get(j);
        if (j != i && Prefs.sameDevice(o.name, o.kind, p.name, p.kind) && (on.contains(o.id) || j > i)) { pairs.add(p.id); break; }
      }
    }
    for (Peer d : offlineAll) {
      if (prefs.favs.contains(d.id)) continue;
      boolean dup = false;
      for (Peer p : peers) if (p.mine && Prefs.sameDevice(p.name, p.kind, d.name, d.kind)) dup = true;
      for (Peer o : offlineAll) if (o != d && Prefs.sameDevice(o.name, o.kind, d.name, d.kind) && o.seen > d.seen) dup = true;
      if (dup) devs.add(d.id);
    }
    List<List<String>> r = new ArrayList<>();
    r.add(pairs); r.add(devs);
    return r;
  }

  void removeDuplicates() {
    List<List<String>> d = duplicates();
    for (String id : d.get(0)) prefs.paired.remove(id);
    prefs.save();
    updateServer();
    for (String id : d.get(1)) forgetDevice(id);
  }
  JSONArray ice;
  long iceAt;
  boolean online, running;
  /** Waiting for the server's answer to a connection test. */
  interface PongWait { void got(JSONObject pong); }
  PongWait pongWait;
  Pairing pairing;
  JSONObject account;          // {username, name, admin} once signed in
  boolean needLogin, needSetup;
  String loginError;

  final OkHttpClient http = new OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build();
  private WebSocket ws;
  private long backoff = 1000;
  private int generation;

  private Beam(Context app) {
    this.app = app;
    this.prefs = new Prefs(app);
    this.me = new Identity();
    this.notify = new Notify(app);
    this.nearby = new Nearby(this);
    try {
      ice = new JSONArray("[{\"urls\":[\"stun:stun.cloudflare.com:3478\",\"stun:stun.l.google.com:19302\"]}]");
    } catch (Exception ignored) {}
  }

  // ---------- lifecycle ----------
  private boolean updaterScheduled;

  void start() {
    if (running) return;
    running = true;
    if (!updaterScheduled) {
      updaterScheduled = true;
      Updater.schedule(this);
      // Relay credentials last a day; refresh them while Beam stays connected for days.
      main.postDelayed(new Runnable() {
        @Override public void run() {
          if (online && System.currentTimeMillis() - iceAt > 4 * 3600 * 1000L) {
            try { wsSend(new JSONObject().put("t", "ice")); } catch (Exception ignored) {}
          }
          main.postDelayed(this, 10 * 60 * 1000L);
        }
      }, 10 * 60 * 1000L);
    }
    UpdateJob.schedule(app);
    connect();
    nearby.refresh();
    Shortcuts.update(this);
  }

  void stop() {
    running = false;
    generation++;
    if (ws != null) ws.close(1000, "bye");
    ws = null;
    online = false;
    peers = new ArrayList<>();
    nearby.stopAll();
    changed();
  }

  /** Called when the network comes back or the app opens, to skip any wait. */
  void kick() {
    if (running && !online) {
      backoff = 1000;
      connect();
    }
  }

  /** The network changed: the old connection is probably dead, so open a fresh one now. */
  void reconnect() {
    if (!running) return;
    backoff = 1000;
    connect();
  }

  private void connect() {
    if (!running) return;
    final int gen = ++generation;
    if (ws != null) ws.cancel();
    String url = SERVER.replaceFirst("^http", "ws") + "/ws";
    ws = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
      @Override public void onMessage(WebSocket w, String text) {
        if ("pong".equals(text)) return;
        main.post(() -> {
          if (gen != generation) return;
          try { onServer(new JSONObject(text)); } catch (Exception e) { e.printStackTrace(); }
        });
      }
      @Override public void onClosed(WebSocket w, int code, String reason) { main.post(() -> dropped(gen)); }
      @Override public void onFailure(WebSocket w, Throwable t, Response r) { main.post(() -> dropped(gen)); }
    });
  }

  private void dropped(int gen) {
    if (gen != generation) return;
    online = false;
    peers = new ArrayList<>();
    changed();
    if (!running) return;
    final int g = generation;
    main.postDelayed(() -> { if (g == generation && running) connect(); }, backoff);
    backoff = Math.min(backoff * 2, 30000);
  }

  void wsSend(JSONObject o) {
    if (ws != null) ws.send(o.toString());
  }

  void signal(String to, JSONObject data) {
    try { wsSend(new JSONObject().put("t", "signal").put("to", to).put("data", data)); } catch (Exception ignored) {}
  }

  // ---------- server messages ----------
  private void onServer(JSONObject m) throws Exception {
    switch (m.optString("t")) {
      case "challenge": {
        JSONObject hello = new JSONObject()
            .put("t", "hello").put("pub", me.pub).put("sig", me.sign("beam-hello:" + m.getString("nonce")))
            .put("name", prefs.name).put("kind", "android").put("nearby", prefs.nearby).put("watch", new JSONArray(prefs.watch()))
            .put("caps", new JSONArray().put("offline").put("inbox"));
        wsSend(hello);
        break;
      }
      case "welcome":
        if (m.optBoolean("login")) {
          // This phone isn't signed in to a Beam account yet.
          account = null;
          online = false;
          needLogin = true;
          needSetup = m.optBoolean("setup");
          nearby.stopAll();
          notify.refreshStatus(this);
          event("login", null);
          changed();
          break;
        }
        boolean wasLoggedOut = needLogin;
        account = m.optJSONObject("user");
        needLogin = false;
        if (wasLoggedOut) { nearby.refresh(); event("login", null); event("toast", "Signed in as " + (account != null ? account.optString("name") : "")); }
        online = true;
        backoff = 1000;
        JSONArray i = m.optJSONArray("ice");
        if (i != null && i.length() > 0) ice = i;
        iceAt = System.currentTimeMillis();
        changed();
        break;
      case "ice": {
        JSONArray fresh = m.optJSONArray("ice");
        if (fresh != null && fresh.length() > 0) { ice = fresh; iceAt = System.currentTimeMillis(); }
        break;
      }
      case "inbox":
        onInbox(m);
        break;
      case "pong":
        if (pongWait != null) { PongWait w = pongWait; pongWait = null; w.got(m); }
        break;
      case "peers": {
        List<Peer> list = new ArrayList<>(), away = new ArrayList<>();
        JSONArray a = m.optJSONArray("peers");
        boolean save = false;
        for (int k = 0; a != null && k < a.length(); k++) {
          JSONObject p = a.getJSONObject(k);
          Peer peer = new Peer(p.getString("id"), p.optString("name", "Device"), p.optString("kind", "web"), p.optString("pub"));
          peer.local = p.optBoolean("local");
          peer.mine = p.optBoolean("mine");
          if (p.optBoolean("offline")) { peer.seen = p.optLong("seen"); away.add(peer); continue; }
          list.add(peer);
          Prefs.Paired pp = prefs.paired.get(peer.id);
          if (pp != null && pp.pub.equals(peer.pub) && !pp.name.equals(peer.name)) { pp.name = peer.name; save = true; }
        }
        // Devices on my own account link up automatically (their keys get pinned like a pairing).
        boolean linked = false;
        for (Peer p : list) {
          if (p.mine && !p.pub.isEmpty() && !prefs.paired.containsKey(p.id)) {
            prefs.addPaired(new Prefs.Paired(p.id, p.pub, p.name, p.kind), onlineIds(list));
            linked = save = true;
          }
        }
        if (save) prefs.save();
        peers = list;
        offlineAll = away;
        offline = remembered(list, away);
        if (linked) updateServer();
        changed();
        break;
      }
      case "signal":
        onSignal(m.getString("from"), m.optJSONObject("data"));
        break;
      case "error":
        if ("offline".equals(m.optString("code"))) {
          Transfer t = tx.get(m.optString("ref"));
          if (t != null) t.peerUnreachable();
        }
        break;
      case "login-error":
        loginError = m.optString("reason");
        event("login", null);
        break;
      case "signed-out":
        account = null;
        event("toast", m.optString("reason", "Signed out"));
        break;
      case "account-alert":
        // Someone signed in to this account on another device.
        notify.accountAlert(trim(m.optString("device", "A device")));
        break;
      case "account-error":
      case "account-ok":
        event("toast", m.optString("reason", m.optString("message")));
        break;
      case "pair-code":
        if (pairing != null && pairing.show) { pairing.code = m.getString("code"); event("pairing", null); }
        break;
      case "pair-found":
        if (pairing != null && !pairing.show) {
          String id = m.getString("id"), pub = m.getString("pub");
          if (!Crypto.idFromPub(pub).equals(id)) { pairDone(false, "Key check failed"); return; }
          pairing.peer = new Prefs.Paired(id, pub, m.optString("name", "Device"), m.optString("kind", "web"));
          pairing.submitted = false;
          pairing.safety = Crypto.safetyCode(me.pub, pub);
          event("pairing", null);
        }
        break;
      case "pair-error":
        if (pairing != null) { pairing.error = m.optString("reason"); pairing.submitted = false; event("pairing", null); }
        break;
    }
  }

  private void onSignal(String from, JSONObject d) throws Exception {
    if (d == null) return;
    String k = d.optString("k");
    if ("offer".equals(k)) { onOffer(from, d); return; }
    Transfer t = tx.get(d.optString("tid"));
    switch (k) {
      case "answer": {
        if (t == null || t.incoming || !t.peerId.equals(from)) return;
        if (!Transfer.WAITING.equals(t.state) && !Transfer.RECONNECTING.equals(t.state)) return;
        String pub = d.optString("pub");
        if (!trusted(from, pub)) { t.fail("Device key didn't match"); return; }
        String sdp = d.optString("sdp");
        if (!Crypto.verify(pub, d.optString("sig"), "beam-answer|" + t.tid + "|" + me.id + "|" + sdp)) { t.fail("Signature check failed"); return; }
        t.onAnswer(sdp, d.optInt("gen", -1));
        break;
      }
      case "resume": {
        // The sender lost the connection part-way and is reconnecting to carry on.
        if (t == null || !t.incoming || !t.peerId.equals(from)) return;
        String pub = d.optString("pub"), sdp = d.optString("sdp");
        if (!trusted(from, pub)) return;
        if (!Crypto.verify(pub, d.optString("sig"), "beam-resume|" + t.tid + "|" + me.id + "|" + sdp)) return;
        if (Transfer.DONE.equals(t.state)) { signal(from, new JSONObject().put("k", "complete").put("tid", t.tid)); return; }
        t.onResume(sdp, d.optInt("gen", 0));
        break;
      }
      case "complete":
        if (t != null && !t.incoming && t.peerId.equals(from)) t.finish();
        break;
      case "decline":
      case "cancel":
        if (t != null && t.peerId.equals(from)) {
          notify.cancelRequest(t);
          t.fail("decline".equals(k) ? "Declined" : "Cancelled");
        }
        break;
      case "pair": {
        String ppub = d.optString("pub");
        if (!Crypto.idFromPub(ppub).equals(from)) return;
        if (pairing == null || !pairing.show) {
          signal(from, new JSONObject().put("k", "pair-no"));
          return;
        }
        pairing.peer = new Prefs.Paired(from, ppub, trim(d.optString("name", "Device")), d.optString("kind", "web"));
        pairing.safety = Crypto.safetyCode(me.pub, ppub);
        event("pairing", null);
        break;
      }
      case "pair-ok":
        if (pairing == null || pairing.show || pairing.peer == null || !pairing.peer.id.equals(from) || !pairing.peer.pub.equals(d.optString("pub"))) return;
        prefs.addPaired(new Prefs.Paired(from, pairing.peer.pub, trim(d.optString("name", pairing.peer.name)), d.optString("kind", pairing.peer.kind)), onlineIds(peers));
        prefs.save();
        updateServer();
        pairDone(true, null);
        break;
      case "pair-no":
        if (pairing != null && pairing.peer != null && pairing.peer.id.equals(from)) pairDone(false, "Pairing was declined");
        break;
    }
  }

  static String trim(String s) {
    s = s == null ? "" : s.replaceAll("[\\u0000-\\u001f]", "").trim();
    return s.length() > 40 ? s.substring(0, 40) : s;
  }

  /** Paired devices must use the key saved at pairing; others must at least match their ID. */
  boolean trusted(String id, String pub) {
    if (pub == null || pub.isEmpty() || !Crypto.idFromPub(pub).equals(id)) return false;
    Prefs.Paired p = prefs.paired.get(id);
    return p == null || p.pub.equals(pub);
  }

  private void onOffer(String from, JSONObject d) throws Exception {
    String tid = d.optString("tid");
    if (tid.isEmpty()) return;
    String pub = d.optString("pub"), sdp = d.optString("sdp");
    if (!trusted(from, pub)) return;
    if (!Crypto.verify(pub, d.optString("sig"), "beam-offer|" + tid + "|" + me.id + "|" + sdp)) return;
    Transfer known = tx.get(tid);
    if (known != null) {
      // The sender switched this transfer to the internet route (e.g. the direct link failed).
      if (!known.incoming || !known.peerId.equals(from)) return;
      if (Transfer.ASKING.equals(known.state)) { known.offeredOn = null; known.offerSdp = sdp; known.gen = d.optInt("gen", 0); }
      else if (known.active()) known.onResume(sdp, d.optInt("gen", 0));
      return;
    }
    JSONArray files = Transfer.unpackFiles(d);
    if (files == null || files.length() == 0) return;
    List<Transfer.Item> items = new ArrayList<>();
    for (int i = 0; i < Math.min(files.length(), Transfer.MAX_ITEMS); i++) {
      JSONObject f = files.getJSONObject(i);
      items.add(new Transfer.Item(Transfer.cleanName(f.optString("name")), Math.max(0, f.optLong("size")), f.optString("type", "application/octet-stream"), f.optBoolean("text")));
      items.get(items.size() - 1).path = Transfer.cleanPath(f.optString("path"));
    }
    Prefs.Paired pp = prefs.paired.get(from);
    String name = pp != null ? pp.name : trim(d.optString("name", "Device"));
    Transfer t = new Transfer(this, tid, true, from, name, d.optString("kind", "web"), pp != null, items);
    t.offerSdp = sdp;
    t.gen = d.optInt("gen", 0);
    incoming(t);
  }

  /** A new incoming transfer (over the internet or offline): auto-accept from my devices, otherwise ask. */
  void incoming(final Transfer t) {
    tx.put(t.tid, t);
    if (t.paired && prefs.autoAccept) {
      t.accept();
    } else {
      notify.request(t);
      event("incoming", t);
      main.postDelayed(() -> {
        if (Transfer.ASKING.equals(t.state)) {
          notify.cancelRequest(t);
          if (t.offeredOn != null) nearby.decline(t);
          t.fail("Expired");
        }
      }, OFFER_TIMEOUT);
    }
    changed();
  }

  // ---------- actions from the UI ----------
  /** One send to several devices: a separate transfer to each, from the same files. */
  List<Transfer> sendToMany(List<Peer> peers, List<Transfer.Item> items) {
    List<Transfer> out = new ArrayList<>();
    for (int k = 0; k < peers.size(); k++) {
      // Each transfer reads the files itself, so temp copies must be shared, not deleted twice.
      List<Transfer.Item> copy = new ArrayList<>();
      for (Transfer.Item i : items) {
        Transfer.Item c = new Transfer.Item(i.name, i.size, i.type, i.text);
        c.path = i.path; c.uri = i.uri; c.textValue = i.textValue;
        c.temp = i.temp; c.sharedTemp = i.temp != null;
        copy.add(c);
      }
      out.add(send(peers.get(k), copy));
    }
    if (items.size() > 0) TempFiles.track(this, items, out);
    return out;
  }

  Transfer send(Peer p, List<Transfer.Item> items) {
    Transfer t = new Transfer(this, Crypto.randomId(), false, p.id, p.name, p.kind, prefs.paired.containsKey(p.id), items);
    tx.put(t.tid, t);
    prefs.sentTo(p.id, p.name, p.kind);
    Shortcuts.used(app, p.id);
    Shortcuts.update(this);
    t.startSend();
    changed();
    return t;
  }

  void accept(String tid) {
    Transfer t = tx.get(tid);
    if (t != null) { notify.cancelRequest(t); t.accept(); }
  }

  void decline(String tid) {
    Transfer t = tx.get(tid);
    if (t == null || !Transfer.ASKING.equals(t.state)) return;
    notify.cancelRequest(t);
    if (t.inboxId != null) inboxDone(t.inboxId); // sent from iPhone: delete it from the server
    else if (t.offeredOn != null) nearby.decline(t);
    else try { signal(t.peerId, new JSONObject().put("k", "decline").put("tid", tid)); } catch (Exception ignored) {}
    t.state = Transfer.FAILED;
    t.reason = "Declined";
    tx.remove(tid);
    changed();
  }

  void cancel(String tid) {
    Transfer t = tx.get(tid);
    if (t == null) return;
    if (!t.direct() && (Transfer.WAITING.equals(t.state) || Transfer.PREPARING.equals(t.state) || Transfer.PAUSED.equals(t.state) || Transfer.RECONNECTING.equals(t.state))) {
      try { signal(t.peerId, new JSONObject().put("k", "cancel").put("tid", tid)); } catch (Exception ignored) {}
    }
    t.fail("Cancelled");
  }

  void clear(String tid) {
    Transfer t = tx.remove(tid);
    if (t != null && !t.incoming && !t.active()) t.cleanupTemp();
    changed();
  }

  /** Which offline devices of mine to show: one per name and type (the same device signed in again shows
   *  up as a new one), not this phone's own old sign-ins, nothing unseen for 30 days unless pinned, at most 8. */
  private List<Peer> remembered(List<Peer> online, List<Peer> away) {
    java.util.Set<String> taken = new java.util.HashSet<>();
    for (Peer p : online) if (p.mine) taken.add(p.name.trim().toLowerCase() + "|" + p.kind);
    taken.add(prefs.name.trim().toLowerCase() + "|android");
    java.util.Collections.sort(away, (x, y) -> Long.compare(y.seen, x.seen));
    long month = System.currentTimeMillis() - 30L * 86400000L;
    List<Peer> pinned = new ArrayList<>(), rest = new ArrayList<>();
    for (Peer p : away) {
      boolean fav = prefs.favs.contains(p.id);
      String key = p.name.trim().toLowerCase() + "|" + p.kind;
      if (!fav && (p.seen < month || taken.contains(key))) continue;
      taken.add(key);
      if (fav) pinned.add(p); else if (rest.size() < 8) rest.add(p);
    }
    pinned.addAll(rest);
    return pinned;
  }

  void forgetDevice(String id) {
    try { wsSend(new JSONObject().put("t", "forget-device").put("id", id)); } catch (Exception ignored) {}
    if (prefs.favs.remove(id)) prefs.save();
  }

  // ---- sent from iPhone (the Shortcut): the file waits on the server; we fetch it and save it as usual ----
  void onInbox(JSONObject m) {
    String id = m.optString("id");
    if (id.isEmpty() || tx.containsKey(id)) return;
    String from = m.optString("from", "iPhone");
    List<Transfer.Item> items = new ArrayList<>();
    if (m.has("text")) {
      String text = m.optString("text");
      if (text.length() > 65536) text = text.substring(0, 65536);
      Transfer.Item it = new Transfer.Item("Text", text.length(), "text/plain", true);
      it.textValue = text;
      items.add(it);
      Transfer t = new Transfer(this, id, true, "iphone", from, "phone", true, items);
      tx.put(id, t);
      t.done = t.total;
      t.finish();
      changed();
      return;
    }
    items.add(new Transfer.Item(Transfer.cleanName(m.optString("name", "File")), Math.max(0, m.optLong("size")), m.optString("type", "application/octet-stream"), false));
    Transfer t = new Transfer(this, id, true, "iphone", from, "phone", true, items);
    t.inboxId = id;
    t.inboxDl = m.optString("dl");
    incoming(t);
  }

  void inboxDone(String id) {
    try { wsSend(new JSONObject().put("t", "inbox-done").put("id", id)); } catch (Exception ignored) {}
  }

  /** Signed in to my own account. */
  boolean isMine(String id) {
    if ("iphone".equals(id)) return true; // sent from my own iPhone Shortcut
    for (Peer p : allPeers()) if (p.id.equals(id)) return p.mine;
    return false;
  }

  /** A send that failed for good: send the same things again, from the start. */
  void sendAgain(String tid) {
    Transfer old = tx.get(tid);
    if (old == null || !old.canSendAgain()) return;
    Peer p = null;
    for (Peer q : allPeers()) if (q.id.equals(old.peerId)) p = q;
    if (p == null) {
      android.widget.Toast.makeText(app, old.peerName + " isn't reachable right now. Try again when it's back", android.widget.Toast.LENGTH_LONG).show();
      return;
    }
    List<Transfer.Item> items = new ArrayList<>();
    for (Transfer.Item i : old.items) {
      Transfer.Item n = new Transfer.Item(i.name, i.size, i.type, i.text);
      n.path = i.path;
      n.uri = i.uri;
      n.temp = i.temp;            // the copy moves to the new transfer
      n.sharedTemp = i.sharedTemp;
      n.textValue = i.textValue;
      i.temp = null;
      items.add(n);
    }
    tx.remove(tid);
    send(p, items);
  }

  void updateServer() {
    try {
      wsSend(new JSONObject().put("t", "update").put("name", prefs.name).put("nearby", prefs.nearby).put("watch", new JSONArray(prefs.watch())));
    } catch (Exception ignored) {}
    notify.refreshStatus(this);
    if (running) nearby.refresh();
    Shortcuts.update(this);
    changed();
  }

  void unpair(String id) {
    prefs.paired.remove(id);
    prefs.save();
    updateServer();
  }

  // ---------- account ----------
  void login(String user, String pin) {
    try { wsSend(new JSONObject().put("t", "login").put("user", user).put("pin", pin)); } catch (Exception ignored) {}
  }

  void join(String user, String invite, String pin) {
    try { wsSend(new JSONObject().put("t", "login").put("user", user).put("invite", invite).put("pin", pin)); } catch (Exception ignored) {}
  }

  void setup(String code, String name, String user, String pin) {
    try { wsSend(new JSONObject().put("t", "setup").put("code", code).put("name", name).put("user", user).put("pin", pin)); } catch (Exception ignored) {}
  }

  void changePin(String old, String pin) {
    try { wsSend(new JSONObject().put("t", "pin-change").put("old", old).put("pin", pin)); } catch (Exception ignored) {}
  }

  void logout() {
    try { wsSend(new JSONObject().put("t", "logout")); } catch (Exception ignored) {}
  }

  // pairing
  void pairShow() {
    pairing = new Pairing(true);
    try { wsSend(new JSONObject().put("t", "pair-code")); } catch (Exception ignored) {}
    event("pairing", null);
  }

  void pairJoin() {
    pairing = new Pairing(false);
    event("pairing", null);
  }

  void pairSubmit(String code) {
    if (pairing == null) return;
    pairing.error = null;
    pairing.submitted = true;
    try { wsSend(new JSONObject().put("t", "pair-join").put("code", code)); } catch (Exception ignored) {}
  }

  void pairConfirm(boolean yes) {
    Pairing p = pairing;
    if (p == null || p.peer == null || !p.show) return;
    try {
      if (yes) {
        prefs.addPaired(p.peer, onlineIds(peers));
        prefs.save();
        signal(p.peer.id, new JSONObject().put("k", "pair-ok").put("pub", me.pub).put("name", prefs.name).put("kind", "android"));
        updateServer();
        pairDone(true, null);
      } else {
        signal(p.peer.id, new JSONObject().put("k", "pair-no"));
        pairing = null;
        event("pairing", null);
      }
    } catch (Exception ignored) {}
  }

  void pairCancel() {
    pairing = null;
  }

  private void pairDone(boolean ok, String why) {
    String name = pairing != null && pairing.peer != null ? pairing.peer.name : "device";
    pairing = null;
    event("pairing", null);
    event("toast", ok ? "Paired with " + name : (why != null ? why : "Pairing failed"));
  }

  Peer onlinePeer(String id) {
    for (Peer p : peers) if (p.id.equals(id)) return p;
    return null;
  }

  Peer peer(String id) {
    Peer p = onlinePeer(id);
    return p != null ? p : nearby.peerById(id);
  }

  /** Everyone reachable: through the server, plus phones found offline over Bluetooth. */
  /** Every device I can reach, pinned favorites first (in the order they were pinned). */
  List<Peer> allPeers() {
    List<Peer> all = new ArrayList<>(peers);
    for (Peer p : nearby.found.values()) if (onlinePeer(p.id) == null && !contains(all, p.id)) all.add(p);
    final List<String> fav = prefs.favs;
    java.util.Collections.sort(all, (a, b) -> {
      int fa = fav.indexOf(a.id), fb = fav.indexOf(b.id);
      return Integer.compare(fa < 0 ? Integer.MAX_VALUE : fa, fb < 0 ? Integer.MAX_VALUE : fb);
    });
    return all;
  }

  boolean isFav(String id) { return prefs.favs.contains(id); }

  void toggleFav(String id) {
    if (!prefs.favs.remove(id)) prefs.favs.add(id);
    prefs.save();
    Shortcuts.update(this);
    changed();
  }

  private static boolean contains(List<Peer> list, String id) {
    for (Peer p : list) if (p.id.equals(id)) return true;
    return false;
  }

  boolean isOfflineOnly(String id) { return onlinePeer(id) == null && nearby.peerById(id) != null; }

  // ---------- listeners ----------
  void changed() {
    for (Listener l : listeners) l.onChange();
  }

  void event(String type, Object arg) {
    for (Listener l : listeners) l.onEvent(type, arg);
  }

  /** Called by transfers (on the main thread) when they change state. */
  void transferChanged(Transfer t) {
    notify.transfer(t);
    changed();
  }
}

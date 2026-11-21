// Beam handshake server.
//
// It never sees file contents. It only:
//   1. proves each device owns the key its ID comes from (signed challenge),
//   2. tells devices who is nearby (same network) or paired with them,
//   3. passes signed WebRTC offers/answers between devices,
//   4. hands out short-lived pairing codes.
// Files then go device-to-device over an encrypted WebRTC data channel whose
// keys are signed by each device, so this server cannot read or swap them.

import { DurableObject } from "cloudflare:workers";

const MAX_NAME = 40;
const MAX_WATCH = 40;
const MAX_SIGNAL = 512 * 1024; // big batches send a compressed file list with the offer
const PAIR_TTL = 10 * 60 * 1000;
const MAX_WAITING_PER_NET = 12, MAX_WAITING = 400; // connections not yet signed in
const GUEST_TTL = 24 * 3600 * 1000;               // how long a guest link (QR code) works
const GUEST_OK = new Set(["signal", "ping", "ice", "update"]); // all a guest may do: send to the device that invited them
const TALK_TTL = 30 * 60 * 1000;                   // how long a started transfer may resume across networks

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === "/ws") {
      if (req.headers.get("Upgrade") !== "websocket") return new Response("Expected websocket", { status: 426 });
      const hub = env.HUB.get(env.HUB.idFromName("global"));
      return hub.fetch(req);
    }
    if (url.pathname === "/api/health") return Response.json({ ok: true });
    // iPhone Shortcut: list my devices, send to one; devices fetch what was sent to them.
    if (url.pathname.startsWith("/api/sc/") || url.pathname.startsWith("/api/inbox/")) {
      return env.HUB.get(env.HUB.idFromName("global")).fetch(req);
    }
    return env.ASSETS ? env.ASSETS.fetch(req) : new Response("Not found", { status: 404 });
  },
};

// ---------- helpers ----------
const b64d = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const b64u = (u) => btoa(String.fromCharCode(...u)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
async function idFromPub(spki) {
  return hex(await crypto.subtle.digest("SHA-256", spki)).slice(0, 32);
}
async function verify(spki, sigRaw, text) {
  try {
    const key = await crypto.subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, sigRaw, new TextEncoder().encode(text));
  } catch {
    return false;
  }
}
function netKey(ip) {
  if (!ip) return "?";
  if (ip.includes(":")) {
    // IPv6: devices on one home network share the first 64 bits
    const parts = ip.split(":");
    const full = [];
    for (let i = 0; i < parts.length; i++) {
      if (parts[i] === "" && i > 0 && i < parts.length - 1) {
        const missing = 8 - (parts.length - 1);
        for (let j = 0; j < missing; j++) full.push("0");
      } else full.push(parts[i] || "0");
    }
    return "6:" + full.slice(0, 4).join(":");
  }
  return "4:" + ip;
}
// PINs are stored only as salted PBKDF2 hashes.
async function hashPin(pin, saltHex) {
  const salt = saltHex ? Uint8Array.from(saltHex.match(/../g).map((h) => parseInt(h, 16))) : crypto.getRandomValues(new Uint8Array(16));
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations: 100000 }, key, 256);
  return { s: hex(salt), h: hex(bits) };
}
async function checkPin(rec, pin) {
  if (!rec || !rec.s || !/^\d{4,12}$/.test(String(pin || ""))) return false;
  const { h } = await hashPin(String(pin), rec.s);
  let diff = 0;
  for (let i = 0; i < h.length; i++) diff |= h.charCodeAt(i) ^ rec.h.charCodeAt(i);
  return diff === 0 && h.length === rec.h.length;
}
const validPin = (p) => /^\d{6,12}$/.test(String(p || "")); // for choosing a PIN
const validUser = (u) => /^[a-z0-9._-]{2,32}$/.test(u);
const normUser = (u) => String(u || "").trim().toLowerCase();
const INVITE_TTL = 7 * 24 * 3600 * 1000;
function inviteCode() {
  const abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I
  const r = crypto.getRandomValues(new Uint8Array(8));
  const c = [...r].map((x) => abc[x % abc.length]).join("");
  return c.slice(0, 4) + "-" + c.slice(4);
}
const normInvite = (c) => String(c || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
const LOCK_AFTER = 5, LOCK_FOR = 15 * 60 * 1000;

const clean = (s, n) => String(s ?? "").replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, n);
const send = (ws, obj) => {
  try {
    ws.send(JSON.stringify(obj));
  } catch {}
};

export class Hub extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.env = env;
    this.ice = null; // cached TURN credentials
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path.startsWith("/api/")) return this.api(req).catch((e) => Response.json({ error: "Something went wrong: " + String(e && e.message || e).slice(0, 100) }, { status: 500 }));
    // Limit connections that haven't signed in yet, so nobody can tie the server up.
    const net = netKey(req.headers.get("CF-Connecting-IP"));
    const waiting = this.ctx.getWebSockets().map((w) => w.deserializeAttachment() || {}).filter((x) => !x.user);
    if (waiting.filter((x) => x.net === net).length >= MAX_WAITING_PER_NET || waiting.length >= MAX_WAITING) {
      return new Response("Too many connections", { status: 429 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    const nonce = hex(crypto.getRandomValues(new Uint8Array(16)));
    server.serializeAttachment({ nonce, net, tries: 0 });
    send(server, { t: "challenge", nonce, v: (this.env.VERSION && this.env.VERSION.id) || "" });
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Signed-in devices. */
  /** Signed-in devices, plus guests from a QR link (who only ever see the device that invited them). */
  sockets() {
    return this.allSockets().filter((s) => s.a.user || s.a.guest);
  }

  /** Every connected device that has proven its key, signed in or not. */
  allSockets() {
    const out = [];
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a && a.id) out.push({ ws, a });
    }
    return out;
  }

  async hasUsers() { return !!(await this.ctx.storage.get("meta:hasUsers")); }

  /** Finish connecting a device that belongs to an account. */
  // ---- iPhone Shortcut inbox ----
  // A Shortcut can't make Beam's direct device-to-device connection, so it uploads here: the file waits in
  // R2 (deleted once the device has it, or after a day), and the device is told to come and get it.
  async api(req) {
    const url = new URL(req.url);
    const p = url.pathname;
    await this.loadDevs();
    if (p.startsWith("/api/inbox/")) {
      const id = p.slice("/api/inbox/".length);
      const rec = /^[A-Za-z0-9_-]{10,40}$/.test(id) ? await this.ctx.storage.get("inbox:" + id) : null;
      if (!rec || rec.dl !== url.searchParams.get("t") || rec.exp < Date.now() || !this.env.INBOX) return new Response("Not found", { status: 404 });
      const obj = await this.env.INBOX.get("inbox/" + id);
      if (!obj) return new Response("Not found", { status: 404 });
      // Served from Beam's own address, so never as something a browser would run (a page or script).
      return new Response(obj.body, { headers: {
        "Content-Type": "application/octet-stream", "Content-Length": String(obj.size), "Cache-Control": "no-store",
        "Content-Disposition": "attachment", "X-Content-Type-Options": "nosniff", "Content-Security-Policy": "sandbox; default-src 'none'",
      } });
    }
    const key = (req.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "") || url.searchParams.get("key") || "";
    const k = /^[A-Za-z0-9_-]{20,64}$/.test(key) ? await this.ctx.storage.get("skey:" + hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(key)))) : null;
    const user = k && (await this.ctx.storage.get("user:" + k.user)) ? k.user : null;
    if (!user) return Response.json({ error: "This Beam Shortcut isn't set up any more. Set it up again from Beam's Settings." }, { status: 401 });
    const mine = this.sockets().filter((s) => s.a.user === user && s.a.inbox);

    if (p === "/api/sc/devices") {
      // Name → device id, for the Shortcut's "Choose from List".
      const out = {};
      for (const s of mine.sort((x, y) => x.a.name.localeCompare(y.a.name))) {
        let n = s.a.name, i = 2;
        while (out[n]) n = s.a.name + " (" + i++ + ")";
        out[n] = s.a.id;
      }
      if (!Object.keys(out).length) return Response.json({ error: "None of your devices are online. Open Beam on one and try again." }, { status: 409 });
      return Response.json(out);
    }

    if (p === "/api/sc/send" && req.method === "POST") {
      const to = url.searchParams.get("to") || "";
      const target = mine.find((s) => s.a.id === to);
      if (!target) return Response.json({ error: "That device isn't online. Open Beam on it and try again." }, { status: 409 });
      const type = (req.headers.get("Content-Type") || "application/octet-stream").split(";")[0].trim().slice(0, 100);
      const size = Number(req.headers.get("Content-Length") || 0);
      const name = clean(url.searchParams.get("name"), 200) || "File";
      // Text and links (small plain text) go straight to the device.
      if (type === "text/plain" && size <= 64 * 1024 && !/\.(txt|md|csv|log)$/i.test(name)) {
        const text = (await req.text()).slice(0, 64 * 1024);
        send(target.ws, { t: "inbox", id: b64u(crypto.getRandomValues(new Uint8Array(12))), text, from: "iPhone" });
        return Response.json({ ok: true, sent: "text" });
      }
      if (!this.env.INBOX) return Response.json({ error: "Sending files from iPhone isn't switched on yet (R2 storage is missing)." }, { status: 503 });
      if (!size) return Response.json({ error: "Empty file" }, { status: 400 });
      if (size > 100 * 1048576) return Response.json({ error: "That file is over 100 MB. Use the Beam web page in Safari for big files." }, { status: 413 });
      // Waiting files per account: at most 50 and 2 GB, so a lost Shortcut key can't fill the storage.
      const waiting = [...(await this.ctx.storage.list({ prefix: "inbox:" })).values()].filter((r) => r.user === user && r.exp > Date.now());
      if (waiting.length >= 50 || waiting.reduce((n, r) => n + (r.size || 0), 0) + size > 2 * 1024 * 1048576)
        return Response.json({ error: "Too many files are waiting to be picked up. Open Beam on your devices and try again." }, { status: 429 });
      const id = b64u(crypto.getRandomValues(new Uint8Array(15)));
      await this.env.INBOX.put("inbox/" + id, req.body, { httpMetadata: { contentType: type } });
      const rec = { to, user, name, size, type, dl: b64u(crypto.getRandomValues(new Uint8Array(18))), exp: Date.now() + 24 * 3600 * 1000 };
      await this.ctx.storage.put("inbox:" + id, rec);
      if (!(await this.ctx.storage.getAlarm())) await this.ctx.storage.setAlarm(Date.now() + 3600 * 1000);
      send(target.ws, { t: "inbox", id, name, size, type, dl: rec.dl, from: "iPhone" });
      return Response.json({ ok: true, sent: name });
    }
    return Response.json({ error: "Not found" }, { status: 404 });
  }

  /** Hand a device anything sent to it from iPhone while it was briefly away. */
  async deliverInbox(ws, id) {
    const list = await this.ctx.storage.list({ prefix: "inbox:" });
    for (const [k, r] of list) if (r.to === id && r.exp > Date.now()) send(ws, { t: "inbox", id: k.slice(6), name: r.name, size: r.size, type: r.type, dl: r.dl, from: "iPhone" });
  }

  async dropInbox(id) {
    await this.ctx.storage.delete("inbox:" + id);
    try { if (this.env.INBOX) await this.env.INBOX.delete("inbox/" + id); } catch {}
  }

  // Hourly while anything is waiting: delete what nobody picked up within a day.
  async alarm() {
    const list = await this.ctx.storage.list({ prefix: "inbox:" });
    let left = 0;
    for (const [k, r] of list) { if (r.exp < Date.now()) await this.dropInbox(k.slice(6)); else left++; }
    if (left) await this.ctx.storage.setAlarm(Date.now() + 3600 * 1000);
  }

  // ---- device records, cached in memory (every device signed in to an account) ----
  async loadDevs() {
    if (this.devs) return;
    const list = await this.ctx.storage.list({ prefix: "dev:" });
    const devs = new Map();
    for (const [k, d] of list) devs.set(k.slice(4), d);
    this.devs = devs;
  }
  async putDev(id, d) { await this.loadDevs(); this.devs.set(id, d); await this.ctx.storage.put("dev:" + id, d); }
  async delDev(id) { await this.loadDevs(); this.devs.delete(id); await this.ctx.storage.delete("dev:" + id); }

  async signedIn(ws, a, rec) {
    await this.loadDevs();
    a.admin = !!rec.admin;
    ws.serializeAttachment(a);
    send(ws, { t: "welcome", id: a.id, ice: await this.turn(), user: { username: a.user, name: rec.name, admin: a.admin } });
    this.broadcast(a, null);
    await this.deliverInbox(ws, a.id);
  }

  /** Disconnect every device matching `pred` (removed person or device). */
  kick(pred, why) {
    for (const s of this.allSockets()) {
      if (!pred(s.a)) continue;
      const before = { ...s.a };
      s.ws.serializeAttachment({});
      send(s.ws, { t: "signed-out", reason: why });
      try { s.ws.close(4002, "signed out"); } catch {}
      if (before.user || before.guest) this.broadcast(before, before);
    }
  }

  async adminList() {
    const users = await this.ctx.storage.list({ prefix: "user:" });
    const devs = await this.ctx.storage.list({ prefix: "dev:" });
    const online = new Set(this.sockets().map((s) => s.a.id));
    const out = [];
    for (const [k, u] of users) {
      const username = k.slice(5);
      const devices = [];
      for (const [dk, d] of devs) if (d.user === username) devices.push({ id: dk.slice(4), name: d.name, kind: d.kind, online: online.has(dk.slice(4)) });
      out.push({
        username, name: u.name, admin: !!u.admin,
        status: u.s ? "active" : "invited",
        invite: u.invite && u.invite.exp > Date.now() ? u.invite.code : null,
        locked: Object.values(u.locks || {}).some((l) => l.until > Date.now()),
        devices,
      });
    }
    out.sort((x, y) => (y.admin - x.admin) || x.name.localeCompare(y.name));
    return { t: "admin-list", users: out };
  }

  // Can `viewer` see `peer`? Same network (both visible to everyone) or paired both ways.
  visible(viewer, peer) {
    if (viewer.id === peer.id) return false;
    // A guest and the device that invited it see each other, and nothing else.
    if (viewer.guest || peer.guest) return !!((viewer.guest && peer.user && viewer.guest.to === peer.id) || (peer.guest && viewer.user && peer.guest.to === viewer.id));
    if (!viewer.user || !peer.user) return false;
    if (viewer.user === peer.user) return true; // my own devices, anywhere
    if (viewer.nearby && peer.nearby && viewer.net === peer.net) return true;
    return viewer.watch.includes(peer.id) && peer.watch.includes(viewer.id);
  }

  peersFor(a, all) {
    const list = all
      .filter((p) => this.visible(a, p.a))
      .map((p) => ({ id: p.a.id, name: p.a.name, kind: p.a.kind, pub: p.a.pub, paired: a.watch.includes(p.a.id), local: p.a.net === a.net, mine: !!a.user && p.a.user === a.user, guest: !!p.a.guest }));
    // My own devices that aren't connected right now, so Beam remembers them.
    if (a.offlineList && a.user && this.devs) {
      const online = new Set(all.map((p) => p.a.id));
      for (const [id, d] of this.devs) {
        if (d.user !== a.user || id === a.id || online.has(id)) continue;
        list.push({ id, name: d.name, kind: d.kind, mine: true, offline: true, seen: d.seen || d.added || 0 });
      }
    }
    return list;
  }

  /** Re-send device lists to everyone on an account (a device was added, removed or renamed). */
  refreshAccount(user) {
    if (!user) return;
    const all = this.sockets();
    for (const s of all) if (s.a.user === user) send(s.ws, { t: "peers", peers: this.peersFor(s.a, all) });
  }

  // Send fresh peer lists to everyone whose view of `changed` may have changed.
  broadcast(changed, before) {
    const all = this.sockets();
    for (const s of all) {
      if (s.a.id === changed.id) {
        send(s.ws, { t: "peers", peers: this.peersFor(s.a, all) });
        continue;
      }
      const now = all.some((p) => p.a.id === changed.id) && this.visible(s.a, changed);
      const was = before ? this.visible(s.a, before) : false;
      if (now || was) send(s.ws, { t: "peers", peers: this.peersFor(s.a, all) });
    }
  }

  async turn() {
    const { TURN_KEY_ID, TURN_KEY_API_TOKEN } = this.env;
    const stun = [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] }];
    if (!TURN_KEY_ID || !TURN_KEY_API_TOKEN) return stun;
    if (this.ice && this.ice.exp > Date.now()) return this.ice.list;
    // After a failure, wait a minute before asking Cloudflare again.
    if (this.iceFailAt && Date.now() - this.iceFailAt < 60000) return stun;
    try {
      const r = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(TURN_KEY_ID.trim())}/credentials/generate-ice-servers`, {
        method: "POST",
        headers: { Authorization: `Bearer ${TURN_KEY_API_TOKEN.trim()}`, "Content-Type": "application/json" },
        body: JSON.stringify({ ttl: 86400 }),
      });
      const body = await r.text();
      if (!r.ok) throw new Error(`Cloudflare said ${r.status}${r.status === 401 || r.status === 403 ? " (check the TURN key ID and API token)" : r.status === 404 ? " (TURN key ID not found)" : ""}`);
      const j = JSON.parse(body);
      let list = Array.isArray(j.iceServers) ? j.iceServers : j.iceServers ? [j.iceServers] : [];
      list = list.map((s) => ({ ...s, urls: (Array.isArray(s.urls) ? s.urls : [s.urls]).filter((u) => !/:53\b/.test(u)) }))
        .filter((s) => s.urls.some((u) => /^turns?:/.test(u)));
      if (!list.length) throw new Error("Cloudflare sent no relay addresses");
      this.iceErr = null;
      this.iceFailAt = 0;
      this.ice = { list: stun.concat(list), exp: Date.now() + 6 * 3600 * 1000 };
      return this.ice.list;
    } catch (e) {
      this.iceErr = String((e && e.message) || e).slice(0, 160);
      this.iceFailAt = Date.now();
      console.error("TURN credentials failed:", this.iceErr);
      return stun;
    }
  }

  async webSocketMessage(ws, raw) {
    try {
      await this.loadDevs();
      await this.handle(ws, raw);
    } catch (e) {
      console.error("message failed", e && e.message);
    }
  }

  async handle(ws, raw) {
    if (typeof raw !== "string" || raw.length > MAX_SIGNAL) return;
    const a = ws.deserializeAttachment() || {};
    if (!a.user && raw.length > 16 * 1024) return; // only signed-in devices send big messages
    let m;
    try {
      m = JSON.parse(raw);
    } catch {
      return;
    }

    if (m.t === "hello") {
      if (a.id) return;
      if (typeof m.pub !== "string" || m.pub.length > 300 || typeof m.sig !== "string" || m.sig.length > 200) return ws.close(4001, "auth");
      const pub = b64d(m.pub);
      const ok = await verify(pub, b64d(String(m.sig || "")), "beam-hello:" + a.nonce);
      if (!ok) {
        send(ws, { t: "error", code: "auth" });
        ws.close(4001, "auth");
        return;
      }
      const id = await idFromPub(pub);
      // A device reconnecting replaces its old connection.
      for (const s of this.allSockets())
        if (s.a.id === id) {
          const before = { ...s.a };
          s.ws.serializeAttachment({});
          try { s.ws.close(4000, "replaced"); } catch {}
          if (before.user) this.broadcast(before, before);
        }
      const me = {
        id,
        pub: m.pub,
        name: clean(m.name, MAX_NAME) || "Device",
        kind: clean(m.kind, 12) || "web",
        nearby: m.nearby !== false,
        watch: (Array.isArray(m.watch) ? m.watch : []).map(String).filter((x) => /^[0-9a-f]{32}$/.test(x)).slice(0, MAX_WATCH),
        net: a.net,
        tries: 0,
        user: null,
        // Newer apps also list this account's devices that are offline right now.
        offlineList: Array.isArray(m.caps) && m.caps.includes("offline"),
        inbox: Array.isArray(m.caps) && m.caps.includes("inbox"), // can receive what's sent from iPhone
      };
      // A guest from a QR code: no account, and it can only see the device that made the code.
      if (typeof m.guest === "string" && m.guest) {
        const g = /^[A-Za-z0-9_-]{16,40}$/.test(m.guest) ? await this.ctx.storage.get("guest:" + m.guest) : null;
        if (!g || g.exp < Date.now()) {
          send(ws, { t: "guest-expired" });
          ws.close(4003, "guest link expired");
          return;
        }
        me.guest = { token: m.guest, to: g.to };
        me.kind = "guest";
        me.nearby = false;
        me.watch = [];
        ws.serializeAttachment(me);
        send(ws, { t: "welcome", id, ice: await this.turn(), guest: { toName: g.toName, to: g.to, exp: g.exp } });
        this.broadcast(me, null);
        return;
      }
      // Is this device signed in to an account?
      const dev = await this.ctx.storage.get("dev:" + id);
      const rec = dev && (await this.ctx.storage.get("user:" + dev.user));
      if (dev && !rec) await this.delDev(id);
      if (!rec) {
        ws.serializeAttachment(me);
        send(ws, { t: "welcome", id, login: true, setup: !(await this.hasUsers()) });
        return;
      }
      me.user = dev.user;
      if (dev.name !== me.name || dev.kind !== me.kind || !dev.seen || Date.now() - dev.seen > 3600000) await this.putDev(id, { ...dev, name: me.name, kind: me.kind, seen: Date.now() });
      await this.signedIn(ws, me, rec);
      return;
    }
    if (!a.id) return;

    // ---------- signing in (the only thing a device can do before it has an account) ----------
    if (m.t === "setup" && !a.user) {
      // First run: whoever has the setup code creates the admin account.
      a.tries = (a.tries || 0) + 1;
      ws.serializeAttachment(a);
      if (a.tries > 5) return send(ws, { t: "login-error", reason: "Too many tries. Reconnect and try again." });
      if (await this.hasUsers()) return send(ws, { t: "login-error", reason: "Beam is already set up. Sign in instead." });
      const code = String(this.env.BEAM_SETUP_CODE || "");
      if (!code || normInvite(m.code) !== normInvite(code)) return send(ws, { t: "login-error", reason: "That setup code isn't right." });
      const username = normUser(m.user), name = clean(m.name, 40);
      if (!validUser(username)) return send(ws, { t: "login-error", reason: "Usernames are 2–32 letters, numbers, dots, dashes or underscores." });
      if (!name) return send(ws, { t: "login-error", reason: "Enter your name." });
      if (!validPin(m.pin)) return send(ws, { t: "login-error", reason: "PINs are 6–12 digits." });
      const rec = { name, admin: true, ...(await hashPin(String(m.pin))), created: Date.now() };
      await this.ctx.storage.put("user:" + username, rec);
      await this.ctx.storage.put("meta:hasUsers", true);
      await this.putDev(a.id, { user: username, name: a.name, kind: a.kind, added: Date.now(), seen: Date.now() });
      a.user = username;
      return this.signedIn(ws, a, rec);
    }

    if (m.t === "login" && !a.user) {
      const username = normUser(m.user);
      const key = "user:" + username;
      const rec = validUser(username) ? await this.ctx.storage.get(key) : null;
      const wrong = () => send(ws, { t: "login-error", reason: m.invite ? "That username or invite code isn't right." : "Wrong username or PIN." });
      if (!rec) return wrong();
      // Wrong-PIN lockouts are per network, so someone guessing elsewhere can't lock you out at home.
      // A global cap still stops guessing spread across many networks.
      const now = Date.now();
      rec.locks = rec.locks || {};
      const nl = rec.locks[a.net] || { n: 0, until: 0 };
      const g = rec.gfail && now - rec.gfail.since < 3600000 ? rec.gfail : { n: 0, since: now };
      const until = Math.max(nl.until || 0, g.n >= 25 ? g.since + 3600000 : 0);
      if (until > now) {
        const mins = Math.ceil((until - now) / 60000);
        return send(ws, { t: "login-error", reason: `Too many wrong tries. Try again in ${mins} minute${mins === 1 ? "" : "s"}.` });
      }
      let ok = false;
      if (m.invite) {
        ok = !!rec.invite && rec.invite.exp > Date.now() && normInvite(m.invite) === normInvite(rec.invite.code);
        if (ok && !validPin(m.pin)) return send(ws, { t: "login-error", reason: "Choose a PIN of 6–12 digits." });
        if (ok) { Object.assign(rec, await hashPin(String(m.pin))); delete rec.invite; }
      } else {
        ok = await checkPin(rec, m.pin);
      }
      if (!ok) {
        nl.n = (nl.n || 0) + 1;
        if (nl.n >= LOCK_AFTER) { nl.until = now + LOCK_FOR; nl.n = 0; }
        rec.locks[a.net] = nl;
        const nets = Object.keys(rec.locks);
        if (nets.length > 50) delete rec.locks[nets[0]];
        g.n += 1;
        rec.gfail = g;
        await this.ctx.storage.put(key, rec);
        return wrong();
      }
      delete rec.locks[a.net];
      await this.ctx.storage.put(key, rec);
      await this.putDev(a.id, { user: username, name: a.name, kind: a.kind, added: Date.now(), seen: Date.now() });
      a.user = username;
      for (const o of this.sockets())
        if (o.a.user === username && o.a.id !== a.id) send(o.ws, { t: "account-alert", device: a.name, kind: a.kind, at: Date.now() });
      return this.signedIn(ws, a, rec);
    }

    if (!a.user && !(a.guest && GUEST_OK.has(m.t))) return; // everything below needs a signed-in device

    // The device got (or turned down) something sent from iPhone: delete it from storage.
    if (m.t === "inbox-done") {
      const id = String(m.id || "");
      const r = await this.ctx.storage.get("inbox:" + id);
      if (r && r.to === a.id) await this.dropInbox(id);
      return;
    }
    // iPhone Shortcut key: a personal key the Shortcut uses to send to my devices.
    if (m.t === "shortcut-key" || m.t === "shortcut-revoke") {
      const listKey = "skeys:" + a.user;
      for (const h of (await this.ctx.storage.get(listKey)) || []) await this.ctx.storage.delete("skey:" + h);
      await this.ctx.storage.delete(listKey);
      if (m.t === "shortcut-revoke") return send(ws, { t: "shortcut-key", key: null });
      const token = b64u(crypto.getRandomValues(new Uint8Array(24)));
      const h = hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)));
      await this.ctx.storage.put("skey:" + h, { user: a.user, created: Date.now() });
      await this.ctx.storage.put(listKey, [h]);
      return send(ws, { t: "shortcut-key", key: token });
    }

    // QR guest link: anyone who scans it can send files to this device (and only this one) for a day.
    if (m.t === "guest-link" || m.t === "guest-link-get" || m.t === "guest-revoke") {
      const old = await this.ctx.storage.get("guestlink:" + a.id);
      if (m.t === "guest-link-get") {
        const g = old && (await this.ctx.storage.get("guest:" + old));
        return send(ws, { t: "guest-link", token: g && g.exp > Date.now() ? old : null, exp: g ? g.exp : 0 });
      }
      if (old) {
        await this.ctx.storage.delete("guest:" + old);
        await this.ctx.storage.delete("guestlink:" + a.id);
        this.kick((x) => x.guest && x.guest.token === old, "This guest link was turned off.");
      }
      if (m.t === "guest-revoke") return send(ws, { t: "guest-link", token: null, exp: 0 });
      const token = b64u(crypto.getRandomValues(new Uint8Array(18)));
      const exp = Date.now() + GUEST_TTL;
      await this.ctx.storage.put("guest:" + token, { to: a.id, toName: a.name, user: a.user, exp });
      await this.ctx.storage.put("guestlink:" + a.id, token);
      return send(ws, { t: "guest-link", token, exp });
    }

    // Remove one of my own devices that's gone for good (old laptop, reinstalled app…).
    if (m.t === "forget-device") {
      const id = String(m.id || "");
      await this.loadDevs();
      const d = this.devs.get(id);
      if (!d || d.user !== a.user || id === a.id) return;
      await this.delDev(id);
      this.kick((x) => x.id === id, "This device was removed from your account.");
      return this.refreshAccount(a.user);
    }

    if (m.t === "logout") {
      await this.delDev(a.id);
      this.kick((x) => x.id === a.id, "You signed out on this device.");
      return this.refreshAccount(a.user);
    }

    // The Mac and Windows apps move a device to a login key the app keeps on disk: the old key (this
    // signed-in connection) vouches for the new one, which proves it's held by signing this request.
    if (m.t === "rekey") {
      if (typeof m.pub !== "string" || m.pub.length > 300 || typeof m.sig !== "string" || m.sig.length > 200) return;
      const pub = b64d(m.pub);
      if (!(await verify(pub, b64d(m.sig), "beam-rekey:" + a.id + ":" + m.pub))) return;
      const nid = await idFromPub(pub);
      if (nid === a.id) return send(ws, { t: "rekey-ok" });
      const taken = await this.ctx.storage.get("dev:" + nid);
      if (taken && taken.user !== a.user) return;
      const dev = await this.ctx.storage.get("dev:" + a.id);
      if (!dev) return;
      await this.putDev(nid, { ...dev, seen: Date.now() });
      await this.delDev(a.id);
      send(ws, { t: "rekey-ok" });
      return this.refreshAccount(a.user);
    }

    if (m.t === "pin-change") {
      const key = "user:" + a.user;
      const rec = await this.ctx.storage.get(key);
      if (!rec || !(await checkPin(rec, m.old))) return send(ws, { t: "account-error", reason: "Your current PIN isn't right." });
      if (!validPin(m.pin)) return send(ws, { t: "account-error", reason: "PINs are 6–12 digits." });
      Object.assign(rec, await hashPin(String(m.pin)));
      await this.ctx.storage.put(key, rec);
      return send(ws, { t: "account-ok", message: "PIN changed." });
    }

    // ---------- admin: managing people ----------
    if (m.t && m.t.startsWith("admin-")) {
      if (!a.admin) return;
      const username = normUser(m.user);
      const key = "user:" + username;
      if (m.t === "admin-add") {
        const name = clean(m.name, 40);
        if (!validUser(username)) return send(ws, { t: "account-error", reason: "Usernames are 2–32 letters, numbers, dots, dashes or underscores." });
        if (!name) return send(ws, { t: "account-error", reason: "Enter their name." });
        if (await this.ctx.storage.get(key)) return send(ws, { t: "account-error", reason: "That username is taken." });
        const code = inviteCode();
        await this.ctx.storage.put(key, { name, admin: false, invite: { code, exp: Date.now() + INVITE_TTL }, created: Date.now() });
        send(ws, { t: "admin-invite", user: username, name, code });
      } else if (m.t === "admin-reset") {
        const rec = await this.ctx.storage.get(key);
        if (!rec) return;
        const code = inviteCode();
        rec.invite = { code, exp: Date.now() + INVITE_TTL };
        rec.locks = {};
        delete rec.gfail;
        await this.ctx.storage.put(key, rec);
        send(ws, { t: "admin-invite", user: username, name: rec.name, code, reset: true });
      } else if (m.t === "admin-remove") {
        if (username === a.user) return send(ws, { t: "account-error", reason: "You can't remove yourself." });
        await this.ctx.storage.delete(key);
        const devs = await this.ctx.storage.list({ prefix: "dev:" });
        for (const [dk, d] of devs) if (d.user === username) await this.delDev(dk.slice(4));
        this.kick((x) => x.user === username, "Your Beam account was removed.");
      } else if (m.t === "admin-remove-device") {
        const id = String(m.id || "");
        const d = await this.ctx.storage.get("dev:" + id);
        if (!d) return;
        await this.delDev(id);
        this.kick((x) => x.id === id, "This device was signed out by the admin.");
        this.refreshAccount(d.user);
      } else if (m.t === "admin-set-admin") {
        const rec = await this.ctx.storage.get(key);
        if (!rec || username === a.user) return;
        rec.admin = !!m.admin;
        await this.ctx.storage.put(key, rec);
        for (const s of this.sockets()) if (s.a.user === username) { s.a.admin = rec.admin; s.ws.serializeAttachment(s.a); }
      } else if (m.t !== "admin-list") {
        return;
      }
      return send(ws, await this.adminList());
    }

    // Connection test: round-trip time and whether the relay is set up.
    if (m.t === "ping") {
      const { TURN_KEY_ID, TURN_KEY_API_TOKEN } = this.env;
      const relay = !!(TURN_KEY_ID && TURN_KEY_API_TOKEN);
      const ice = relay ? await this.turn() : null;
      return send(ws, { t: "pong", n: clean(m.n, 40), relay, relayError: relay ? this.iceErr || null : null, ice });
    }
    // Fresh relay credentials (they expire, and some devices stay connected for days).
    if (m.t === "ice") return send(ws, { t: "ice", ice: await this.turn() });

    if (m.t === "update") {
      const before = { ...a };
      if (m.name !== undefined) a.name = clean(m.name, MAX_NAME) || a.name;
      if (m.nearby !== undefined) a.nearby = !!m.nearby;
      if (Array.isArray(m.watch)) a.watch = m.watch.map(String).filter((x) => /^[0-9a-f]{32}$/.test(x)).slice(0, MAX_WATCH);
      ws.serializeAttachment(a);
      this.broadcast(a, before);
      if (a.name !== before.name) { const d = this.devs.get(a.id); if (d) await this.putDev(a.id, { ...d, name: a.name }); }
      return;
    }

    if (m.t === "signal") {
      const to = String(m.to || "");
      const target = this.sockets().find((s) => s.a.id === to);
      // Only relay between devices that can see each other, plus pairing replies.
      const k = m.data && m.data.k;
      const pairing = (k === "pair-ok" || k === "pair-no") && a.pairWith === to;
      // A transfer already under way may continue after one side changes network (and so
      // stops being "nearby"); new offers still need the devices to see each other.
      const talkKey = "talk:" + [a.id, to].sort().join("|");
      let ongoing = false;
      if (target && (k === "resume" || k === "answer" || k === "complete" || k === "cancel" || k === "decline")) {
        const until = await this.ctx.storage.get(talkKey);
        ongoing = !!until && until > Date.now();
      }
      if (!target || (!this.visible(a, target.a) && !pairing && !ongoing)) {
        send(ws, { t: "error", code: "offline", to, ref: m.data && m.data.tid });
        return;
      }
      if (k === "offer" || k === "resume" || k === "answer") await this.ctx.storage.put(talkKey, Date.now() + TALK_TTL);
      send(target.ws, { t: "signal", from: a.id, data: m.data });
      return;
    }

    if (m.t === "pair-code") {
      const code = String(100000 + (crypto.getRandomValues(new Uint32Array(1))[0] % 900000));
      await this.ctx.storage.put("code:" + code, { id: a.id, exp: Date.now() + PAIR_TTL });
      send(ws, { t: "pair-code", code, exp: PAIR_TTL });
      return;
    }

    if (m.t === "pair-join") {
      a.tries = (a.tries || 0) + 1;
      ws.serializeAttachment(a);
      if (a.tries > 8) return send(ws, { t: "pair-error", reason: "Too many tries. Reconnect and try again." });
      const code = String(m.code || "").replace(/\D/g, "");
      const rec = await this.ctx.storage.get("code:" + code);
      if (!rec || rec.exp < Date.now()) return send(ws, { t: "pair-error", reason: "That code didn't work." });
      const owner = this.sockets().find((s) => s.a.id === rec.id);
      if (!owner || owner.a.id === a.id) return send(ws, { t: "pair-error", reason: "That device isn't connected." });
      await this.ctx.storage.delete("code:" + code);
      owner.a.pairWith = a.id;
      owner.ws.serializeAttachment(owner.a);
      a.pairWith = owner.a.id;
      ws.serializeAttachment(a);
      send(ws, { t: "pair-found", id: owner.a.id, pub: owner.a.pub, name: owner.a.name, kind: owner.a.kind });
      send(owner.ws, { t: "signal", from: a.id, data: { k: "pair", pub: a.pub, name: a.name, kind: a.kind } });
      return;
    }
  }

  async webSocketClose(ws) {
    const a = ws.deserializeAttachment();
    if (a && a.id) {
      ws.serializeAttachment({});
      if (a.user) {
        // Remember when it was last online ("Last seen 2 hours ago").
        try { await this.loadDevs(); const d = this.devs.get(a.id); if (d) await this.putDev(a.id, { ...d, seen: Date.now() }); } catch {}
      }
      this.broadcast({ ...a, id: a.id }, a);
    }
  }

  async webSocketError(ws) {
    await this.webSocketClose(ws);
  }
}

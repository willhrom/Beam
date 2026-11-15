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
    return env.ASSETS ? env.ASSETS.fetch(req) : new Response("Not found", { status: 404 });
  },
};

// ---------- helpers ----------
const b64d = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
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
  sockets() {
    return this.allSockets().filter((s) => s.a.user);
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
  async signedIn(ws, a, rec) {
    a.admin = !!rec.admin;
    ws.serializeAttachment(a);
    send(ws, { t: "welcome", id: a.id, ice: await this.turn(), user: { username: a.user, name: rec.name, admin: a.admin } });
    this.broadcast(a, null);
  }

  /** Disconnect every device matching `pred` (removed person or device). */
  kick(pred, why) {
    for (const s of this.allSockets()) {
      if (!pred(s.a)) continue;
      const before = { ...s.a };
      s.ws.serializeAttachment({});
      send(s.ws, { t: "signed-out", reason: why });
      try { s.ws.close(4002, "signed out"); } catch {}
      if (before.user) this.broadcast(before, before);
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
    if (viewer.id === peer.id || !viewer.user || !peer.user) return false;
    if (viewer.user === peer.user) return true; // my own devices, anywhere
    if (viewer.nearby && peer.nearby && viewer.net === peer.net) return true;
    return viewer.watch.includes(peer.id) && peer.watch.includes(viewer.id);
  }

  peersFor(a, all) {
    return all
      .filter((p) => this.visible(a, p.a))
      .map((p) => ({ id: p.a.id, name: p.a.name, kind: p.a.kind, pub: p.a.pub, paired: a.watch.includes(p.a.id), local: p.a.net === a.net, mine: p.a.user === a.user }));
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
    try {
      const r = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${TURN_KEY_ID}/credentials/generate-ice-servers`, {
        method: "POST",
        headers: { Authorization: `Bearer ${TURN_KEY_API_TOKEN}`, "Content-Type": "application/json" },
        body: JSON.stringify({ ttl: 86400 }),
      });
      const j = await r.json();
      let list = Array.isArray(j.iceServers) ? j.iceServers : j.iceServers ? [j.iceServers] : [];
      list = list.map((s) => ({ ...s, urls: (Array.isArray(s.urls) ? s.urls : [s.urls]).filter((u) => !/:53\b/.test(u)) }));
      this.ice = { list: stun.concat(list), exp: Date.now() + 6 * 3600 * 1000 };
      return this.ice.list;
    } catch {
      return stun;
    }
  }

  async webSocketMessage(ws, raw) {
    try {
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
      };
      // Is this device signed in to an account?
      const dev = await this.ctx.storage.get("dev:" + id);
      const rec = dev && (await this.ctx.storage.get("user:" + dev.user));
      if (dev && !rec) await this.ctx.storage.delete("dev:" + id);
      if (!rec) {
        ws.serializeAttachment(me);
        send(ws, { t: "welcome", id, login: true, setup: !(await this.hasUsers()) });
        return;
      }
      me.user = dev.user;
      if (dev.name !== me.name || dev.kind !== me.kind) await this.ctx.storage.put("dev:" + id, { ...dev, name: me.name, kind: me.kind });
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
      await this.ctx.storage.put("dev:" + a.id, { user: username, name: a.name, kind: a.kind, added: Date.now() });
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
      await this.ctx.storage.put("dev:" + a.id, { user: username, name: a.name, kind: a.kind, added: Date.now() });
      a.user = username;
      for (const o of this.sockets())
        if (o.a.user === username && o.a.id !== a.id) send(o.ws, { t: "account-alert", device: a.name, kind: a.kind, at: Date.now() });
      return this.signedIn(ws, a, rec);
    }

    if (!a.user) return; // everything below needs a signed-in device

    if (m.t === "logout") {
      await this.ctx.storage.delete("dev:" + a.id);
      return this.kick((x) => x.id === a.id, "You signed out on this device.");
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
        for (const [dk, d] of devs) if (d.user === username) await this.ctx.storage.delete(dk);
        this.kick((x) => x.user === username, "Your Beam account was removed.");
      } else if (m.t === "admin-remove-device") {
        const id = String(m.id || "");
        const d = await this.ctx.storage.get("dev:" + id);
        if (!d) return;
        await this.ctx.storage.delete("dev:" + id);
        this.kick((x) => x.id === id, "This device was signed out by the admin.");
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
      return send(ws, { t: "pong", n: clean(m.n, 40), relay: !!(TURN_KEY_ID && TURN_KEY_API_TOKEN) });
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
      this.broadcast({ ...a, id: a.id }, a);
    }
  }

  async webSocketError(ws) {
    await this.webSocketClose(ws);
  }
}

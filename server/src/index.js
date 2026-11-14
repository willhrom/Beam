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
const MAX_SIGNAL = 64 * 1024;
const PAIR_TTL = 10 * 60 * 1000;

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
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    const nonce = hex(crypto.getRandomValues(new Uint8Array(16)));
    server.serializeAttachment({ nonce, net: netKey(req.headers.get("CF-Connecting-IP")), tries: 0 });
    send(server, { t: "challenge", nonce });
    return new Response(null, { status: 101, webSocket: client });
  }

  sockets() {
    const out = [];
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a && a.id) out.push({ ws, a });
    }
    return out;
  }

  // Can `viewer` see `peer`? Same network (both visible to everyone) or paired both ways.
  visible(viewer, peer) {
    if (viewer.id === peer.id) return false;
    if (viewer.nearby && peer.nearby && viewer.net === peer.net) return true;
    return viewer.watch.includes(peer.id) && peer.watch.includes(viewer.id);
  }

  peersFor(a, all) {
    return all
      .filter((p) => this.visible(a, p.a))
      .map((p) => ({ id: p.a.id, name: p.a.name, kind: p.a.kind, pub: p.a.pub, paired: a.watch.includes(p.a.id), local: p.a.net === a.net }));
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
    if (typeof raw !== "string" || raw.length > MAX_SIGNAL) return;
    let m;
    try {
      m = JSON.parse(raw);
    } catch {
      return;
    }
    const a = ws.deserializeAttachment() || {};

    if (m.t === "hello") {
      if (a.id) return;
      const pub = b64d(String(m.pub || ""));
      const ok = await verify(pub, b64d(String(m.sig || "")), "beam-hello:" + a.nonce);
      if (!ok) {
        send(ws, { t: "error", code: "auth" });
        ws.close(4001, "auth");
        return;
      }
      const id = await idFromPub(pub);
      // A device reconnecting replaces its old connection.
      for (const s of this.sockets())
        if (s.a.id === id) {
          s.ws.serializeAttachment({});
          try { s.ws.close(4000, "replaced"); } catch {}
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
      };
      ws.serializeAttachment(me);
      send(ws, { t: "welcome", id, ice: await this.turn() });
      this.broadcast(me, null);
      return;
    }
    if (!a.id) return;

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
      const ongoing = k === "resume" || k === "answer" || k === "complete" || k === "cancel" || k === "decline";
      if (!target || (!this.visible(a, target.a) && !pairing && !ongoing)) {
        send(ws, { t: "error", code: "offline", to, ref: m.data && m.data.tid });
        return;
      }
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

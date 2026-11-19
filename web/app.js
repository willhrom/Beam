// Beam web client. Also runs inside the Mac app, which adds a native bridge
// (window.webkit.messageHandlers.beam) for notifications and saving to Downloads.
"use strict";

const CHUNK = 256 * 1024;            // the largest message every browser accepts
const HIGH_WATER = 8 * 1024 * 1024;  // how far ahead of the network we read
const OFFER_TIMEOUT = 120000;
// Native hosts: the Mac app (WKWebView) or the Windows app (WebView2).
const native = (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.beam) ||
  (window.chrome && window.chrome.webview && new URLSearchParams(location.search).get("app") === "windows" ? { postMessage: (o) => window.chrome.webview.postMessage(o) } : null);
const nativeKind = native ? (window.webkit ? "mac" : "windows") : null;
const revealLabel = nativeKind === "windows" ? "Show in folder" : "Show in Finder";
const $ = (s) => document.querySelector(s);
if (nativeKind) document.body.classList.add("native-" + nativeKind);

// ---------- small utils ----------
const enc = new TextEncoder();
const dec = new TextDecoder();
const b64 = (buf) => {
  const u = new Uint8Array(buf);
  let s = "";
  for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000));
  return btoa(s);
};
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
const sha256 = async (bytes) => new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
const randId = () => hex(crypto.getRandomValues(new Uint8Array(8)));
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const fmtSize = (n) => (n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(0) + " KB" : n < 1073741824 ? (n / 1048576).toFixed(1) + " MB" : (n / 1073741824).toFixed(2) + " GB");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const cleanName = (n) => String(n || "file").replace(/[\/\\:\u0000-\u001f]/g, "_").replace(/^\.+/, "").slice(0, 180) || "file";
// A folder path inside a transfer, like "Trip/Day 1": each part cleaned, nothing that climbs out.
const cleanPath = (p) => String(p || "").split(/[\/\\]+/).filter((x) => x && x !== "." && x !== "..").map((x) => cleanName(x)).slice(0, 20).join("/");
// The relative path a browser gives a file picked or dropped as part of a folder.
const relPathOf = (f) => (f.beamPath !== undefined ? f.beamPath : f.webkitRelativePath || "").split("/").slice(0, -1).join("/");
const store = {
  get: (k, d) => { try { const v = localStorage.getItem("beam." + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set: (k, v) => { try { localStorage.setItem("beam." + k, JSON.stringify(v)); } catch {} },
};

function guessKind() {
  if (nativeKind) return nativeKind;
  const ua = navigator.userAgent;
  if (/iPhone|iPod/.test(ua)) return "iphone";
  if (/iPad/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return "ipad";
  if (/Android/.test(ua)) return "android";
  if (/Macintosh/.test(ua)) return "mac";
  if (/Windows/.test(ua)) return "windows";
  if (/CrOS/.test(ua)) return "chromebook";
  return "web";
}
function defaultName(kind) {
  return { iphone: "iPhone", ipad: "iPad", android: "Android", mac: "Mac", windows: "Windows PC", chromebook: "Chromebook" }[kind] || "Browser";
}

// ---------- identity (ECDSA P-256 key kept in IndexedDB, private part not extractable) ----------
function idb() {
  return new Promise((res, rej) => {
    const r = indexedDB.open("beam", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("kv");
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}
async function idbGet(k) {
  const db = await idb();
  return new Promise((res) => { const r = db.transaction("kv").objectStore("kv").get(k); r.onsuccess = () => res(r.result); r.onerror = () => res(undefined); });
}
async function idbSet(k, v) {
  const db = await idb();
  return new Promise((res, rej) => { const t = db.transaction("kv", "readwrite"); t.objectStore("kv").put(v, k); t.oncomplete = res; t.onerror = () => rej(t.error); });
}

const Id = {
  keys: null, pub: "", id: "",
  async load() {
    let k = await idbGet("identity");
    if (!k) {
      const kp = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]);
      k = { priv: kp.privateKey, pub: b64(await crypto.subtle.exportKey("spki", kp.publicKey)) };
      await idbSet("identity", k);
    }
    this.keys = k;
    this.pub = k.pub;
    this.id = await idFromPub(k.pub);
  },
  async sign(text) {
    return b64(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, this.keys.priv, enc.encode(text)));
  },
};
async function idFromPub(pubB64) { return hex(await sha256(unb64(pubB64))).slice(0, 32); }
async function verifySig(pubB64, sigB64, text) {
  try {
    const key = await crypto.subtle.importKey("spki", unb64(pubB64), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, unb64(sigB64), enc.encode(text));
  } catch { return false; }
}
// Six digits both devices show while pairing. If the server tampered with keys, they won't match.
async function safetyCode(pubA, pubB) {
  const [x, y] = [pubA, pubB].sort();
  const h = await sha256(enc.encode(x + "|" + y));
  const n = (((h[0] << 24) >>> 0) + (h[1] << 16) + (h[2] << 8) + h[3]) % 1000000;
  const s = String(n).padStart(6, "0");
  return s.slice(0, 3) + " " + s.slice(3);
}
const shortId = (id) => (id.slice(0, 4) + "-" + id.slice(4, 8)).toUpperCase();

// ---------- state ----------
const kind = guessKind();
// Opened from a guest QR code / link: no account, just send to the device that made it.
const GUEST = new URLSearchParams(location.search).get("g") || null;
if (GUEST) document.documentElement.classList.add("guest-mode");
const S = {
  name: store.get("name", defaultName(kind)),
  nearby: store.get("nearby", true),
  autoAccept: store.get("autoAccept", true),
  convert: store.get("convert", true),
  openLinks: store.get("openLinks", true),
  paired: store.get("paired", {}), // id -> {pub, name, kind}
  peers: [],
  offline: [], // my account's devices that aren't connected right now
  ice: [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] }],
  online: false,
  tx: new Map(), // tid -> transfer
  pairing: null,
};
const savePaired = () => store.set("paired", S.paired);

// ---------- server connection ----------
let ws, wsTimer, backoff = 1000;
function connect() {
  clearTimeout(wsTimer);
  const url = location.origin.replace(/^http/, "ws") + "/ws";
  ws = new WebSocket(url);
  ws.onmessage = (e) => e.data !== "pong" && onServer(JSON.parse(e.data)).catch((err) => console.error(err));
  ws.onclose = () => {
    S.online = false;
    S.peers = [];
    render();
    if (S.guestGone) return; // the guest link expired or was turned off: don't keep knocking
    wsTimer = setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 30000);
  };
}
const wsSend = (o) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); };
setInterval(() => { if (ws && ws.readyState === 1) ws.send("ping"); }, 30000);
// Relay credentials last a day; refresh them on devices that stay connected for days.
setInterval(() => { if (S.online && Date.now() - (S.iceAt || 0) > 4 * 3600 * 1000) wsSend({ t: "ice" }); }, 10 * 60 * 1000);
document.addEventListener("visibilitychange", () => { if (!document.hidden && (!ws || ws.readyState > 1)) { backoff = 1000; connect(); } });
window.addEventListener("online", () => { backoff = 1000; connect(); });

async function onServer(m) {
  switch (m.t) {
    case "challenge":
      // Beam was updated on the server: reload to pick up the new version (once nothing is moving).
      if (m.v) {
        if (S.serverVersion && m.v !== S.serverVersion) return reloadWhenIdle();
        S.serverVersion = m.v;
      }
      if (GUEST) { wsSend({ t: "hello", pub: Id.pub, sig: await Id.sign("beam-hello:" + m.nonce), name: store.get("guestName", "") || "Guest", kind, guest: GUEST }); break; }
      wsSend({ t: "hello", pub: Id.pub, sig: await Id.sign("beam-hello:" + m.nonce), name: S.name, kind, nearby: S.nearby, watch: Object.keys(S.paired), caps: ["offline", "inbox"] });
      break;
    case "welcome":
      if (m.id !== Id.id) console.warn("server id mismatch");
      if (m.guest) {
        S.guest = m.guest;
        S.user = null;
        S.online = true;
        backoff = 1000;
        if (Array.isArray(m.ice) && m.ice.length) S.ice = m.ice;
        S.iceAt = Date.now();
        if (store.get("guestName", "")) S.name = store.get("guestName", "") + " (guest)";
        if (!store.get("guestName", "")) guestWelcome(); else render();
        break;
      }
      if (m.login) {
        // This device isn't signed in to a Beam account yet.
        S.user = null;
        S.online = false;
        S.needSetup = !!m.setup;
        render();
        // Keep whatever is already typed if the same screen is showing (e.g. after a reconnect).
        const want = S.needSetup ? "setup" : S.signInMode && S.signInMode !== "setup" ? S.signInMode : "signin";
        if (!S.authOpen || want !== S.signInMode) showSignIn(want);
        else authBusy(false);
        break;
      }
      S.user = m.user || null;
      S.signInMode = "signin";
      if (S.authOpen) { hideSignIn(); toast("Signed in as " + (S.user ? S.user.name : "")); }
      S.online = true;
      backoff = 1000;
      if (Array.isArray(m.ice) && m.ice.length) S.ice = m.ice;
      S.iceAt = Date.now();
      render();
      break;
    case "ice":
      if (Array.isArray(m.ice) && m.ice.length) { S.ice = m.ice; S.iceAt = Date.now(); }
      break;
    case "inbox":
      onInbox(m);
      break;
    case "shortcut-key":
      renderShortcutKey(m);
      break;
    case "guest-link":
      renderGuestLink(m);
      break;
    case "pong":
      if (S.pingWait && S.pingWait.n === m.n) { S.pingWait.res(m); S.pingWait = null; }
      break;
    case "peers":
      // Devices you can send to now, and (separately) your account's devices that are offline.
      S.peers = (m.peers || []).filter((p) => !p.offline);
      S.offline = rememberedDevices(S.peers, (m.peers || []).filter((p) => p.offline));
      autoPairMine();
      if (S.pickOpen && S.pickRefresh) S.pickRefresh();
      // keep paired names fresh
      for (const p of S.peers) if (S.paired[p.id] && S.paired[p.id].pub === p.pub && S.paired[p.id].name !== p.name) { S.paired[p.id].name = p.name; savePaired(); }
      render();
      resumeWaiting();
      break;
    case "signal":
      await onSignal(m.from, m.data || {});
      break;
    case "error":
      if (m.code === "offline" && m.ref && S.tx.has(m.ref)) {
        const t = S.tx.get(m.ref);
        if (t.state === "reconnecting") dropped(t, t.gen); // keep trying until the resume window runs out
        else if (!["paused", "done", "failed"].includes(t.state)) fail(t, "Device went offline");
      }
      break;
    case "pair-code":
      if (S.pairing && S.pairing.mode === "show") { S.pairing.code = m.code; showPairShow(); }
      break;
    case "pair-found":
      if (S.pairing && S.pairing.mode === "join") {
        S.pairing.peer = { id: m.id, pub: m.pub, name: m.name, kind: m.kind };
        if ((await idFromPub(m.pub)) !== m.id) return pairDone(false, "Key check failed");
        S.pairing.safety = await safetyCode(Id.pub, m.pub);
        showPairWaiting();
      }
      break;
    case "login-error":
      if (S.authOpen) authError(m.reason);
      break;
    case "guest-expired":
      S.guestGone = true;
      guestGone("This link has expired");
      break;
    case "signed-out":
      if (GUEST) { S.guestGone = true; guestGone(m.reason || "This link was turned off"); break; }
      S.user = null;
      toast(m.reason || "Signed out");
      break;
    case "account-error":
      toast(m.reason);
      break;
    case "account-alert": {
      // Someone signed in to this account on another device.
      const msg = `"${String(m.device || "A device").slice(0, 40)}" just signed in to your Beam account.`;
      notify("account-alert", "New sign-in to your account", msg + " If this wasn't you, change your PIN in Settings and ask the admin to sign that device out.", false);
      toast(msg);
      break;
    }
    case "account-ok":
      closeSheet();
      toast(m.message);
      break;
    case "admin-list":
      S.adminList = m.users;
      if (S.adminOpen && !S.inviteOpen) showPeople();
      break;
    case "admin-invite":
      showInvite(m);
      break;
    case "pair-error":
      if (S.pairing) { S.pairing.error = m.reason; showPairJoin(); }
      break;
  }
}
function reloadWhenIdle() {
  const busy = [...S.tx.values()].some((t) => !["done", "failed"].includes(t.state));
  if (busy) return setTimeout(reloadWhenIdle, 5000);
  location.reload();
}
function updateServer() { wsSend({ t: "update", name: S.name, nearby: S.nearby, watch: Object.keys(S.paired) }); }

// ---------- signaling between devices ----------
const RESUME_WINDOW = 2 * 3600 * 1000; // how long a dropped transfer waits to pick back up (laptop asleep, out of range…)
const BIG = 100 * 1024 * 1024;        // above this, browsers that can save straight to disk do so

async function onSignal(from, d) {
  if (d.k === "offer") return onOffer(from, d);
  if (d.k === "resume") return onResume(from, d);
  const t = S.tx.get(d.tid);
  if (d.k === "answer") {
    if (!t || t.dir !== "out" || t.peer.id !== from || !["waiting", "reconnecting"].includes(t.state) || d.gen !== t.gen) return;
    if (!(await trustedKey(from, d.pub))) return fail(t, "Device key didn't match");
    if (!(await verifySig(d.pub, d.sig, `beam-answer|${t.tid}|${Id.id}|${d.sdp}`))) return fail(t, "Signature check failed");
    if (t.state === "waiting") t.state = "connecting";
    renderTx(t);
    await t.pc.setRemoteDescription({ type: "answer", sdp: d.sdp });
    return;
  }
  if (d.k === "complete") {
    if (t && t.dir === "out" && t.peer.id === from) finish(t);
    return;
  }
  if (d.k === "decline" || d.k === "cancel") {
    if (t && t.peer.id === from && !["done", "failed"].includes(t.state)) fail(t, d.k === "decline" ? "Declined" : "Cancelled");
    if (t && t.dir === "in") closePrompt(t.tid);
    return;
  }
  if (d.k === "pair") {
    if ((await idFromPub(d.pub)) !== from) return;
    if (!S.pairing || S.pairing.mode !== "show") return wsSend({ t: "signal", to: from, data: { k: "pair-no" } });
    S.pairing.peer = { id: from, pub: d.pub, name: String(d.name || "Device").slice(0, 40), kind: d.kind };
    S.pairing.safety = await safetyCode(Id.pub, d.pub);
    showPairConfirm();
    return;
  }
  if (d.k === "pair-ok") {
    const p = S.pairing && S.pairing.peer;
    if (!p || S.pairing.mode !== "join" || p.id !== from || d.pub !== p.pub) return;
    S.paired[from] = { pub: p.pub, name: String(d.name || p.name).slice(0, 40), kind: d.kind || p.kind };
    savePaired();
    updateServer();
    return pairDone(true);
  }
  if (d.k === "pair-no") {
    if (S.pairing && S.pairing.peer && S.pairing.peer.id === from) pairDone(false, "Pairing was declined");
  }
}

// For paired devices the key must match the one saved at pairing; others must at least hash to their ID.
async function trustedKey(id, pub) {
  if (!pub || (await idFromPub(pub)) !== id) return false;
  if (S.paired[id]) return S.paired[id].pub === pub;
  return true;
}

function gathered(pc, ms = 2500) {
  return new Promise((res) => {
    if (pc.iceGatheringState === "complete") return res();
    const done = () => { if (pc.iceGatheringState === "complete") { pc.removeEventListener("icegatheringstatechange", done); res(); } };
    pc.addEventListener("icegatheringstatechange", done);
    setTimeout(res, ms);
  });
}

function closePc(t) {
  try { t.pc && t.pc.close(); } catch {}
  t.pc = null;
  t.dc = null;
}

// ---------- sending ----------
async function sendItems(peer, files, text) {
  if (files.length > MAX_ITEMS - 1) { toast(`Sending the first ${MAX_ITEMS - 1} files. Send the rest separately`); files = files.slice(0, MAX_ITEMS - 1); }
  const items = files.map((f) => { const it = { name: cleanName(f.name), size: f.size, type: f.type || "application/octet-stream" }; const p = cleanPath(relPathOf(f)); if (p) it.path = p; return it; });
  const blobs = files.slice();
  if (text) {
    const b = new Blob([text], { type: "text/plain" });
    items.push({ name: "Text", size: b.size, type: "text/plain", text: true });
    blobs.push(b);
  }
  if (!items.length) return;
  const t = { tid: randId(), dir: "out", peer, items, blobs, total: items.reduce((a, b) => a + b.size, 0), done: 0, state: "preparing", gen: 0, sentText: text || "" };
  S.tx.set(t.tid, t);
  renderTx(t);
  setTimeout(() => { if (t.state === "waiting") { wsSend({ t: "signal", to: peer.id, data: { k: "cancel", tid: t.tid } }); fail(t, "No response"); } }, OFFER_TIMEOUT);
  connectOut(t, "offer").catch(() => fail(t, "Couldn't connect"));
  return t;
}

// Opens a new connection for a transfer: the first offer, or a resume after a drop.
async function connectOut(t, mode) {
  const gen = ++t.gen;
  closePc(t);
  const live = () => t.gen === gen && !["done", "failed"].includes(t.state);
  const pc = (t.pc = new RTCPeerConnection({ iceServers: S.ice }));
  const dc = (t.dc = pc.createDataChannel("beam", { ordered: true }));
  dc.binaryType = "arraybuffer";
  dc.bufferedAmountLowThreshold = 2 * 1024 * 1024;
  dc.onmessage = (e) => {
    if (!live() || typeof e.data !== "string") return;
    const m = JSON.parse(e.data);
    if (m.t === "from") {
      if (t.pumping === gen) return; // one sender loop per connection
      t.pumping = gen;
      pump(t, gen, m.i | 0, Math.max(0, Number(m.off) || 0)).catch(() => { if (live()) dropped(t, gen); });
    }
    else if (m.t === "ok") finish(t);
    else if (m.t === "cancel") fail(t, "Cancelled");
  };
  // Tell the receiver we're listening, in case its first message was sent before we could hear it.
  dc.onopen = () => { if (live()) try { dc.send(JSON.stringify({ t: "ready" })); } catch {} };
  dc.onclose = () => { if (live()) dropped(t, gen); };
  pc.onconnectionstatechange = () => { if (live() && pc.connectionState === "failed") dropped(t, gen); };
  await pc.setLocalDescription(await pc.createOffer());
  await gathered(pc);
  if (!live()) return;
  const sdp = pc.localDescription.sdp;
  const data = { k: mode, tid: t.tid, gen, sdp, sig: await Id.sign(`beam-${mode}|${t.tid}|${t.peer.id}|${sdp}`), pub: Id.pub };
  if (mode === "offer") Object.assign(data, { name: S.name, kind }, await packFiles(t.items));
  if (t.state === "preparing") t.state = "waiting";
  renderTx(t);
  wsSend({ t: "signal", to: t.peer.id, data });
  if (mode === "resume") setTimeout(() => { if (t.gen === gen && t.state === "reconnecting") dropped(t, gen); }, 20000);
}

async function pump(t, gen, i0, off0) {
  if (i0 > t.items.length || (i0 < t.items.length && off0 > t.items[i0].size)) throw new Error("bad resume point");
  const dc = t.dc;
  const live = () => t.gen === gen && t.state === "sending" && dc.readyState === "open";
  t.state = "sending";
  t.everOpen = true;
  t.pausedAt = null;
  t.done = off0;
  for (let k = 0; k < i0; k++) t.done += t.items[k].size;
  renderTx(t);
  const waitLow = () => new Promise((res) => { dc.onbufferedamountlow = () => { dc.onbufferedamountlow = null; res(); }; });
  for (let i = i0; i < t.items.length; i++) {
    const blob = t.blobs[i];
    let off = i === i0 ? off0 : 0;
    dc.send(JSON.stringify({ t: "file", i, off }));
    while (off < blob.size) {
      const slab = new Uint8Array(await blob.slice(off, off + 16 * CHUNK).arrayBuffer());
      for (let p = 0; p < slab.length; p += CHUNK) {
        if (!live()) return;
        if (dc.bufferedAmount > HIGH_WATER) await waitLow();
        if (!live()) return;
        const part = slab.subarray(p, p + CHUNK);
        dc.send(part);
        t.done += part.length;
      }
      off += slab.length;
      renderProgress(t);
    }
    dc.send(JSON.stringify({ t: "eof", i }));
  }
  dc.send(JSON.stringify({ t: "done" }));
  t.state = "finishing";
  renderTx(t);
}

// The connection dropped (WiFi blip, walked out of range, switched networks…).
function dropped(t, gen) {
  if (t.gen !== gen || ["done", "failed", "asking"].includes(t.state)) return;
  if (t.dir === "out" && !t.everOpen) return fail(t, "Couldn't connect");
  closePc(t);
  t.state = "paused";
  t.pausedAt = t.pausedAt || Date.now();
  renderTx(t);
  if (t.dir === "out") { clearTimeout(t.retryTimer); t.retryTimer = setTimeout(() => retry(t), Math.min(30000, 2000 * 2 ** Math.min(t.tries || 0, 4)) + Math.random() * 1000); }
  else setTimeout(() => { if (t.state === "paused" && Date.now() - t.pausedAt >= RESUME_WINDOW) fail(t, "Connection lost"); }, RESUME_WINDOW + 1000);
}

function retry(t) {
  clearTimeout(t.retryTimer);
  if (t.state !== "paused") return;
  if (Date.now() - t.pausedAt > RESUME_WINDOW) return fail(t, "Connection lost");
  // Wait quietly until we're online and the other device is back, instead of knocking on a closed door.
  const back = S.online && S.peers.some((p) => p.id === t.peer.id);
  if (!back) { t.retryTimer = setTimeout(() => retry(t), 5000); return; }
  t.tries = (t.tries || 0) + 1;
  t.state = "reconnecting";
  renderTx(t);
  connectOut(t, "resume").catch(() => dropped(t, t.gen));
}
// The other device just showed up again: pick paused transfers to it straight back up.
function resumeWaiting() {
  for (const t of S.tx.values()) if (t.dir === "out" && t.state === "paused" && S.peers.some((p) => p.id === t.peer.id)) retry(t);
}
// A transfer that failed for good: send the same things again, from the start.
function sendAgain(t) {
  if (!t.blobs) return;
  const peer = S.peers.find((p) => p.id === t.peer.id) || t.peer;
  const files = [], textIdx = t.items.findIndex((i) => i.text);
  t.items.forEach((it, i) => {
    if (it.text) return;
    const b = t.blobs[i];
    const f = b instanceof File && b.name === it.name ? b : new File([b], it.name, { type: it.type });
    if (it.path && f.beamPath === undefined) try { Object.defineProperty(f, "beamPath", { value: it.path + "/" + it.name }); } catch {}
    files.push(f);
  });
  S.tx.delete(t.tid);
  renderTxList();
  sendItems(peer, files, textIdx >= 0 ? t.sentText : "");
}

// Big batches: the file list goes gzipped so the offer stays small (thousands of names fit).
const MAX_ITEMS = 5000;
async function packFiles(items) {
  const files = items.map((it) => { const o = { name: it.name, size: it.size, type: it.type }; if (it.text) o.text = true; if (it.path) o.path = it.path; return o; });
  const json = JSON.stringify(files);
  if (json.length < 40000 || typeof CompressionStream === "undefined") return { files };
  const z = await new Response(new Blob([json]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer();
  return { filesZ: b64(z), count: files.length };
}
async function unpackFiles(d) {
  if (Array.isArray(d.files)) return d.files;
  if (typeof d.filesZ !== "string" || typeof DecompressionStream === "undefined") return [];
  try {
    const bytes = Uint8Array.from(atob(d.filesZ), (c) => c.charCodeAt(0));
    const text = await new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"))).text();
    if (text.length > 4 * 1048576) return [];
    const list = JSON.parse(text);
    return Array.isArray(list) ? list : [];
  } catch { return []; }
}

// ---------- receiving ----------
async function onOffer(from, d) {
  if (S.tx.has(d.tid)) return;
  if (!(await trustedKey(from, d.pub))) return;
  if (!(await verifySig(d.pub, d.sig, `beam-offer|${d.tid}|${Id.id}|${d.sdp}`))) return;
  const items = (await unpackFiles(d)).slice(0, MAX_ITEMS).map((f) => ({
    name: cleanName(f.name), size: Math.max(0, Number(f.size) || 0), type: String(f.type || "application/octet-stream").slice(0, 100), text: !!f.text,
    path: cleanPath(f.path) || undefined,
  }));
  if (!items.length) return;
  const paired = !!S.paired[from];
  const peerName = paired ? S.paired[from].name : String(d.name || "Device").slice(0, 40);
  const t = {
    tid: d.tid, dir: "in", peer: { id: from, name: peerName, kind: d.kind, paired }, items, total: items.reduce((a, b) => a + b.size, 0),
    done: 0, state: "asking", sdp: d.sdp, gen: d.gen | 0, received: [], cur: -1, curDone: true, got: 0, q: Promise.resolve(),
  };
  S.tx.set(t.tid, t);
  // Auto-accept from my devices, unless it's big and this browser would have to hold it in memory.
  if (paired && S.autoAccept && (native || t.total < BIG || (await savedFolder(false)))) return accept(t, false);
  renderTx(t);
  promptIncoming(t);
}

function describeItems(items) {
  const tops = new Set(items.filter((i) => i.path).map((i) => i.path.split("/")[0]));
  if (tops.size === 1 && items.every((i) => i.path)) return `the folder "${[...tops][0]}"`;
  if (tops.size > 1 && items.every((i) => i.path)) return tops.size + " folders";
  if (items.length === 1) return items[0].text ? "some text" : items[0].name;
  const photos = items.every((i) => /^image\//.test(i.type));
  return items.length + (photos ? " photos" : " files") + (tops.size ? " and folders" : "");
}
function topFolder(items) {
  const tops = new Set(items.filter((i) => i.path).map((i) => i.path.split("/")[0]));
  return tops.size === 1 && items.every((i) => i.path) ? [...tops][0] : null;
}

// Chrome and Edge can write straight into a folder on disk, so size is only limited by disk space.
const canSaveToDisk = !native && "showDirectoryPicker" in window;
async function savedFolder(ask) {
  if (!canSaveToDisk) return null;
  const h = await idbGet("saveDir");
  if (!h) return null;
  let p = await h.queryPermission({ mode: "readwrite" });
  if (p !== "granted" && ask) p = await h.requestPermission({ mode: "readwrite" }).catch(() => "denied");
  return p === "granted" ? h : null;
}
async function pickFolder() {
  const h = await window.showDirectoryPicker({ id: "beam-save", mode: "readwrite", startIn: "downloads" });
  await idbSet("saveDir", h);
  store.set("saveDirName", h.name);
  return h;
}

// `gesture` = the person just clicked Accept, so we may show a folder picker.
async function accept(t, gesture) {
  closePrompt(t.tid);
  if (t.state !== "asking") return;
  t.state = "connecting";
  renderTx(t);
  t.sink = "memory";
  if (native) t.sink = "native";
  else if (canSaveToDisk) {
    let dir = await savedFolder(gesture).catch(() => null);
    if (!dir && gesture && (t.total >= BIG || topFolder(t.items))) dir = await pickFolder().catch(() => null);
    if (dir) { t.sink = "fs"; t.dirHandle = dir; }
  }
  if (t.inbox) return downloadInbox(t);
  await answerConn(t, t.sdp, t.gen);
}

// ----- sent from iPhone (the Shortcut) -----
// The file waits on the server; we download it into the usual place, then tell the server to delete it.
function onInbox(m) {
  if (S.tx.has(m.id)) return;
  const peer = { id: "iphone", name: m.from || "iPhone", kind: "phone", paired: true, mine: true };
  if (typeof m.text === "string") {
    const text = m.text.slice(0, 64 * 1024);
    const t = { tid: m.id, dir: "in", peer, items: [{ name: "Text", size: text.length, type: "text/plain", text: true }], total: text.length, done: text.length, state: "receiving", received: [{ text }], fromMine: true };
    S.tx.set(t.tid, t);
    finish(t);
    renderTx(t);
    notify(t.tid, "From " + peer.name, text.trim().slice(0, 200), false);
    return;
  }
  const item = { name: cleanName(m.name || "File"), size: Math.max(0, Number(m.size) || 0), type: String(m.type || "application/octet-stream").slice(0, 100), text: false };
  const t = {
    tid: m.id, dir: "in", peer, items: [item], total: item.size, done: 0, state: "asking", gen: 0, received: [], cur: -1, curDone: true, got: 0,
    q: Promise.resolve(), inbox: { id: m.id, dl: m.dl }, fromMine: true,
  };
  S.tx.set(t.tid, t);
  (async () => {
    if (S.autoAccept && (native || t.total < BIG || (await savedFolder(false)))) return accept(t, false);
    renderTx(t);
    promptIncoming(t);
  })();
}
async function downloadInbox(t) {
  t.state = "receiving";
  renderTx(t);
  const it = t.items[0];
  let w = null;
  try {
    const r = await fetch(`/api/inbox/${encodeURIComponent(t.inbox.id)}?t=${encodeURIComponent(t.inbox.dl)}`, { cache: "no-store" });
    if (!r.ok || !r.body) throw new Error("gone");
    w = makeWriter(t, it, 0);
    const reader = r.body.getReader();
    for (;;) {
      if (t.state !== "receiving") { await w.abort(); return; } // cancelled
      const { done, value } = await reader.read();
      if (done) break;
      await w.write(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength));
      t.done += value.byteLength;
      renderProgress(t);
    }
    if (t.done !== it.size) throw new Error("short");
    await w.close();
    wsSend({ t: "inbox-done", id: t.inbox.id });
    finish(t);
    renderTx(t);
  } catch {
    try { if (w) await w.abort(); } catch {}
    fail(t, "Couldn't download it. Send it again");
  }
}

async function answerConn(t, sdp, gen) {
  t.gen = gen;
  closePc(t);
  const live = () => t.gen === gen && !["done", "failed"].includes(t.state);
  const pc = (t.pc = new RTCPeerConnection({ iceServers: S.ice }));
  pc.onconnectionstatechange = () => { if (live() && pc.connectionState === "failed") dropped(t, gen); };
  pc.ondatachannel = (e) => setupReceive(t, e.channel, gen);
  await pc.setRemoteDescription({ type: "offer", sdp });
  await pc.setLocalDescription(await pc.createAnswer());
  await gathered(pc);
  if (!live()) return;
  const mine = pc.localDescription.sdp;
  wsSend({ t: "signal", to: t.peer.id, data: { k: "answer", tid: t.tid, gen, sdp: mine, sig: await Id.sign(`beam-answer|${t.tid}|${t.peer.id}|${mine}`), pub: Id.pub } });
}

async function onResume(from, d) {
  const t = S.tx.get(d.tid);
  if (!t || t.dir !== "in" || t.peer.id !== from) return;
  if (!(await trustedKey(from, d.pub))) return;
  if (!(await verifySig(d.pub, d.sig, `beam-resume|${d.tid}|${Id.id}|${d.sdp}`))) return;
  if (t.state === "done") return wsSend({ t: "signal", to: from, data: { k: "complete", tid: t.tid } });
  if (!["paused", "receiving", "connecting", "reconnecting"].includes(t.state)) return;
  t.state = "reconnecting";
  t.pausedAt = t.pausedAt || Date.now();
  renderTx(t);
  await answerConn(t, d.sdp, d.gen | 0);
}

function decline(t) {
  closePrompt(t.tid);
  if (t.inbox) wsSend({ t: "inbox-done", id: t.inbox.id }); // sent from iPhone: delete it from the server
  else wsSend({ t: "signal", to: t.peer.id, data: { k: "decline", tid: t.tid } });
  t.state = "failed";
  t.reason = "Declined";
  S.tx.delete(t.tid);
  renderTxList();
}

// Where a received file goes: memory (then a Save link), a folder on disk, or the Mac/Windows app.
function makeWriter(t, it, index) {
  if (it.text || t.sink === "memory") {
    let parts = [];
    return {
      write: (b) => parts.push(b),
      close: async () => {
        const blob = new Blob(parts, { type: it.type });
        parts = [];
        if (it.text) t.received.push({ text: await blob.text() });
        else t.received.push({ name: it.name, path: it.path, url: URL.createObjectURL(blob), size: it.size });
      },
      abort: () => { parts = []; },
    };
  }
  if (t.sink === "native") {
    const nid = t.tid + ":" + index;
    native.postMessage({ op: "saveBegin", id: nid, name: it.name, path: it.path || "" });
    // Batch chunks into ~1 MB messages: far fewer hops across the bridge.
    let pend = [], pendBytes = 0;
    const flush = () => {
      if (!pendBytes) return;
      const all = new Uint8Array(pendBytes);
      let o = 0;
      for (const p of pend) { all.set(new Uint8Array(p), o); o += p.byteLength; }
      pend = []; pendBytes = 0;
      native.postMessage({ op: "saveChunk", id: nid, b64: b64(all.buffer) });
    };
    return {
      write: (b) => { pend.push(b); pendBytes += b.byteLength; if (pendBytes >= 1048576) flush(); },
      close: () => { flush(); t.received.push({ name: it.name, nid }); native.postMessage({ op: "saveEnd", id: nid }); },
      abort: () => { pend = []; pendBytes = 0; native.postMessage({ op: "saveAbort", id: nid }); },
    };
  }
  // straight to disk (inside the item's folder path, created as needed)
  let ws = null, name = it.name;
  const ready = (async () => {
    let dir = t.dirHandle;
    for (const part of (it.path || "").split("/").filter(Boolean)) dir = await dir.getDirectoryHandle(part, { create: true });
    const dot = it.name.lastIndexOf(".");
    const base = dot > 0 ? it.name.slice(0, dot) : it.name, ext = dot > 0 ? it.name.slice(dot) : "";
    for (let n = 2; ; n++) {
      try { await dir.getFileHandle(name); name = `${base} (${n})${ext}`; } catch { break; }
    }
    const fh = await dir.getFileHandle(name, { create: true });
    ws = await fh.createWritable();
  })();
  let pend = [], pendBytes = 0;
  const flush = async () => {
    if (!pendBytes) return;
    const parts = pend; pend = []; pendBytes = 0;
    await ready;
    await ws.write(parts.length === 1 ? parts[0] : new Blob(parts));
  };
  return {
    write: async (b) => { pend.push(b); pendBytes += b.byteLength; if (pendBytes >= 1048576) await flush(); },
    close: async () => { await flush(); await ready; await ws.close(); t.received.push({ name, folder: t.dirHandle.name }); },
    abort: async () => { try { await ready; await ws.abort(); await t.dirHandle.removeEntry(name); } catch {} },
  };
}

function setupReceive(t, dc, gen) {
  const live = () => t.gen === gen && !["done", "failed"].includes(t.state);
  dc.binaryType = "arraybuffer";
  let sentFrom = false, started = false;
  // Tell the sender where to start: the beginning, or wherever we got to before a drop.
  const sendFrom = () => { try { dc.send(JSON.stringify(t.curDone ? { t: "from", i: t.cur + 1, off: 0 } : { t: "from", i: t.cur, off: t.got })); } catch {} };
  const opened = () => {
    if (!live() || sentFrom) return;
    sentFrom = true;
    t.state = "receiving";
    t.pausedAt = null;
    renderTx(t);
    sendFrom();
    // The first message on a brand-new connection can get lost, so repeat it until the sender starts.
    // (The sender ignores repeats.)
    let tries = 0;
    const again = setInterval(() => {
      if (started || !live() || dc.readyState !== "open" || ++tries > 6) return clearInterval(again);
      sendFrom();
    }, 1500);
  };
  dc.onopen = opened;
  if (dc.readyState === "open") opened();
  dc.onclose = () => { if (live() && ["receiving", "reconnecting", "connecting"].includes(t.state)) dropped(t, gen); };
  // Counters update immediately (so a resume point is always right); disk writes run in order behind them.
  // Work is queued without a promise per chunk: one loop drains the queue as fast as the sink allows.
  const queue = [];
  let draining = false;
  const drain = async () => {
    if (draining) return;
    draining = true;
    try {
      while (queue.length) {
        const fn = queue.shift();
        const r = fn();
        if (r && typeof r.then === "function") await r;
      }
    } catch (e) {
      fail(t, e && e.message ? e.message : "Couldn't save");
    } finally {
      draining = false;
      if (queue.length) drain();
    }
  };
  const later = (fn) => { queue.push(fn); if (!draining) drain(); };
  dc.onmessage = (e) => {
    if (!live()) return;
    if (typeof e.data === "string") {
      const m = JSON.parse(e.data);
      if (m.t === "ready") { if (!started) sendFrom(); return; }
      if (m.t === "file") {
        started = true;
        if (m.i === t.cur && !t.curDone && m.off === t.got) return; // resuming the same file
        if (!(t.curDone && m.i === t.cur + 1 && m.off === 0 && m.i < t.items.length)) return fail(t, "Bad data");
        t.cur = m.i; t.got = 0; t.curDone = false;
        const it = t.items[t.cur], idx = t.cur;
        t.w = makeWriter(t, it, idx);
      } else if (m.t === "eof") {
        const it = t.items[t.cur];
        if (m.i !== t.cur || t.curDone || t.got !== it.size) return fail(t, "File was incomplete");
        t.curDone = true;
        const w = t.w;
        t.w = null;
        later(() => w.close());
        later(() => renderTx(t));
      } else if (m.t === "done") {
        if (!t.curDone || t.cur !== t.items.length - 1) return fail(t, "File was incomplete");
        later(() => { try { dc.send(JSON.stringify({ t: "ok" })); } catch {} finish(t); });
      } else if (m.t === "cancel") fail(t, "Cancelled");
      return;
    }
    if (t.curDone || !t.w) return fail(t, "Bad data");
    const it = t.items[t.cur];
    t.got += e.data.byteLength;
    t.done += e.data.byteLength;
    if (t.got > it.size) return fail(t, "Too much data");
    const w = t.w, buf = e.data;
    later(() => w.write(buf));
    renderProgress(t);
  };
}

// ----- iPhone Shortcut -----
// On any signed-in device: make the personal key and show a QR code that opens the setup steps on the iPhone.
function shortcutSheet() {
  S.scOpen = true;
  openSheet(`<h2>Send from iPhone</h2>
    <p>Add Beam to the iPhone share sheet with a Shortcut. It sends photos, videos (up to 100 MB each), files, text and links to any of your devices that are online.</p>
    <p class="hint">iPhone Shortcuts can't make Beam's direct connection, so files from the Shortcut pass through the Beam server: they're stored briefly and deleted as soon as your device has them (or after a day). Text and links aren't stored.</p>
    <div id="scBody"><div class="actions"><button class="btn ghost" id="scBack">Back</button><button class="btn primary" id="scMake">Set up the Shortcut</button></div></div>`, () => { S.scOpen = false; });
  $("#scBack").onclick = () => { sheetOnClose = null; S.scOpen = false; showSettings(); };
  $("#scMake").onclick = () => wsSend({ t: "shortcut-key" });
}
async function renderShortcutKey(m) {
  if (!S.scOpen || !$("#scBody")) return;
  if (!m.key) { $("#scBody").innerHTML = '<p>The Shortcut is turned off. Any iPhone set up before can\'t send any more.</p><div class="actions"><button class="btn primary" id="scDone">Done</button></div>'; $("#scDone").onclick = closeSheet; return; }
  const url = location.origin + "/?sc=" + m.key;
  if (!window.qrcode) await new Promise((res, rej) => { const sc = document.createElement("script"); sc.src = "/qrcode.js"; sc.onload = res; sc.onerror = rej; document.head.appendChild(sc); }).catch(() => {});
  let svg = "";
  if (window.qrcode) { const q = qrcode(0, "M"); q.addData(url); q.make(); svg = q.createSvgTag({ cellSize: 6, margin: 3, scalable: true }); }
  $("#scBody").innerHTML = `<div class="qr">${svg}</div>
    <p class="hint center">Scan this with the iPhone's camera. It opens the setup steps with everything ready to copy. This code is a key to sending to your devices: don't share it.</p>
    <div class="actions"><button class="btn danger" id="scOff">Turn off</button><button class="btn primary" id="scDone">Done</button></div>`;
  $("#scDone").onclick = closeSheet;
  $("#scOff").onclick = () => wsSend({ t: "shortcut-revoke" });
}
// On the iPhone: the steps to build the Shortcut, with each value ready to copy.
function shortcutSetup(key) {
  const base = location.origin;
  const devices = `${base}/api/sc/devices?key=${key}`;
  const sendUrl = `${base}/api/sc/send?key=${key}`;
  const copy = (id, label, value) => `<div class="sc-copy"><span>${label}</span><div><input type="text" readonly value="${esc(value)}" id="${id}"><button class="btn ghost small" data-copy-from="${id}">Copy</button></div></div>`;
  openSheet(`<h2>Add Beam to your iPhone share sheet</h2>
    <p>In the <b>Shortcuts</b> app, tap <b>+</b> to make a new Shortcut named <b>Beam</b>, then add these actions in order:</p>
    <ol class="sc-steps">
      <li>Tap the <b>ⓘ</b> (Details) button and turn on <b>Show in Share Sheet</b>. Set it to receive <b>Images, Media, Files, Text and URLs</b>.</li>
      <li>Add <b>Get Contents of URL</b> and paste this as the URL:${copy("scDev", "Your devices", devices)}</li>
      <li>Add <b>Choose from List</b>, choosing from <b>Contents of URL</b>. (It lists your devices that are online.)</li>
      <li>Add <b>Get Dictionary Value</b>: get the value for <b>Chosen Item</b> in <b>Contents of URL</b>.</li>
      <li>Add <b>Repeat with Each</b> item in <b>Shortcut Input</b>.</li>
      <li>Inside the repeat, add <b>Get Contents of URL</b>. Paste this URL, then type <b>&amp;to=</b> and insert <b>Dictionary Value</b>, then <b>&amp;name=</b> and insert <b>Repeat Item</b> (tap it and choose <b>Name</b>):${copy("scSend", "Send to Beam", sendUrl)}
        Tap <b>Show More</b>: set <b>Method</b> to <b>POST</b> and <b>Request Body</b> to <b>File</b>, and choose <b>Repeat Item</b>.</li>
      <li>After the repeat, add <b>Show Notification</b> saying <b>Sent with Beam</b>.</li>
    </ol>
    <p>Now in Photos (or anywhere), tap Share, then <b>Beam</b>, and pick a device. Text and links work too.</p>
    <p class="hint">Keep this page private: these links let anyone send to your devices. You can turn them off in Beam's Settings on any of your devices.</p>`);
  document.querySelectorAll("[data-copy-from]").forEach((b) => (b.onclick = () => { const i = $("#" + b.dataset.copyFrom); i.select(); navigator.clipboard.writeText(i.value).then(() => toast("Copied"), () => document.execCommand("copy")); }));
}

// ----- guests (QR code) -----
function guestWelcome() {
  openSheet(`<h2>Send files to ${esc(S.guest.toName)}</h2>
    <p>${esc(S.guest.toName)} invited you to send them files with Beam. Nothing to install, and nothing is stored on a server: files go straight to their device.</p>
    <label class="field"><span>Your name, so they know who's sending</span><input type="text" id="gname" maxlength="40" placeholder="e.g. Sarah" autocomplete="name"></label>
    <div class="actions"><button class="btn primary" id="go">Continue</button></div>`);
  $("#gname").focus();
  const go = () => {
    const n = $("#gname").value.trim().slice(0, 40);
    if (!n) return $("#gname").focus();
    store.set("guestName", n);
    S.name = n + " (guest)"; // what the host sees on the request
    wsSend({ t: "update", name: S.name });
    sheetOnClose = null;
    closeSheet();
    render();
  };
  $("#gname").onkeydown = (e) => { if (e.key === "Enter") go(); };
  $("#go").onclick = go;
}
function guestGone(why) {
  clearTimeout(wsTimer);
  openSheet(`<h2>${esc(why)}</h2><p>Ask for a new QR code or link to send more.</p>`);
}
// The host side: a QR code anyone can scan to send files to this device for a day.
function guestSheet() {
  S.guestLinkOpen = true;
  wsSend({ t: "guest-link-get" });
  openSheet(`<h2>Let a guest send to this device</h2><div id="glink"><p>Getting a code…</p></div>`, () => { S.guestLinkOpen = false; });
}
async function renderGuestLink(m) {
  if (!S.guestLinkOpen || !$("#glink")) return;
  if (!m.token) {
    $("#glink").innerHTML = `<p>Make a QR code that a guest speaker, volunteer or visitor can scan to send files to <b>${esc(S.name)}</b>, without an account or installing anything. It works for 24 hours, only sends to this device, and you still accept each transfer.</p>
      <div class="actions"><button class="btn ghost" id="gback">Back</button><button class="btn primary" id="gmake">Make a QR code</button></div>`;
    $("#gmake").onclick = () => wsSend({ t: "guest-link" });
    $("#gback").onclick = () => { sheetOnClose = null; S.guestLinkOpen = false; showSettings(); };
    return;
  }
  const url = location.origin + "/?g=" + m.token;
  if (!window.qrcode) await new Promise((res, rej) => { const sc = document.createElement("script"); sc.src = "/qrcode.js"; sc.onload = res; sc.onerror = rej; document.head.appendChild(sc); }).catch(() => {});
  let svg = "";
  if (window.qrcode) { const q = qrcode(0, "M"); q.addData(url); q.make(); svg = q.createSvgTag({ cellSize: 6, margin: 3, scalable: true }); }
  const until = new Date(m.exp);
  const when = until.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }) + (until.toDateString() === new Date().toDateString() ? " today" : " tomorrow");
  $("#glink").innerHTML = `<div class="qr">${svg}</div>
    <p class="hint center">Scan with any phone camera. Works until ${esc(when)} and only sends to ${esc(S.name)}. You'll still be asked to accept.</p>
    <div class="glink-url"><input type="text" readonly value="${esc(url)}" aria-label="Guest link"><button class="btn ghost small" id="gcopy">Copy</button></div>
    <div class="actions"><button class="btn danger" id="goff">Turn off</button>${navigator.share ? '<button class="btn ghost" id="gshare">Share link</button>' : ""}<button class="btn primary" id="gdone">Done</button></div>`;
  $("#gcopy").onclick = () => navigator.clipboard.writeText(url).then(() => toast("Link copied"));
  if ($("#gshare")) $("#gshare").onclick = () => navigator.share({ title: "Send files to " + S.name, url }).catch(() => {});
  $("#goff").onclick = () => { wsSend({ t: "guest-revoke" }); toast("Guest link turned off"); };
  $("#gdone").onclick = closeSheet;
}

// ----- drop folders (Mac and Windows apps) -----
// The app watches folders; when new files have settled it hands them here to send to the folder's
// device, and we tell it how that went so it never sends the same file twice.
async function dropSend(files, to, batch, folder) {
  const peer = S.peers.find((p) => p.id === to);
  if (!peer || !files.length) return native && native.postMessage({ op: "dropDone", batch, result: peer ? "done" : "offline" });
  const t = await sendItems(peer, files, null);
  if (t) { t.dropBatch = batch; t.dropFolder = folder; } else native.postMessage({ op: "dropDone", batch, result: "failed" });
}
function dropReport(t, result) {
  if (!t.dropBatch || !native) return;
  native.postMessage({ op: "dropDone", batch: t.dropBatch, result });
  t.dropBatch = null;
}
function dropSummary() {
  const n = (S.dropFolders || []).length;
  return n ? n + (n === 1 ? " folder sends automatically" : " folders send automatically") : "Send new files from a folder automatically";
}
function dropFoldersSheet() {
  const list = S.dropFolders || [];
  const rows = list.map((d, i) => `<div class="opt"><span>${esc(d.name)}<small>Sends new files to ${esc(d.toName)}</small></span><button class="btn danger small" data-drm="${i}">Remove</button></div>`).join("");
  openSheet(`<h2>Drop folders</h2>
    <p>Anything saved into a drop folder on this ${nativeKind === "windows" ? "PC" : "Mac"} is sent to its device automatically, as soon as the file has finished saving. Files already in the folder when you add it aren't sent.</p>
    <div class="group">${rows || '<div class="opt"><span class="sub">No drop folders yet</span></div>'}</div>
    <div class="actions"><button class="btn ghost" id="dback">Back</button><button class="btn primary" id="dadd">Add a drop folder…</button></div>`);
  $("#dback").onclick = () => { sheetOnClose = null; showSettings(); };
  document.querySelectorAll("[data-drm]").forEach((b) => (b.onclick = () => { native.postMessage({ op: "dropRemove", path: list[+b.dataset.drm].path }); }));
  $("#dadd").onclick = () => {
    const devs = byFavorite(S.peers).concat(S.offline || []);
    if (!devs.length) return toast("No devices to send to yet");
    openSheet(`<h2>Send new files to…</h2><p>Pick the device, then the folder to watch.</p>
      <div class="list">${devs.map((p) => `<button class="item pick" data-dto="${p.id}"><span>${iconFor(p.kind)}<span>${esc(p.name)}<small class="sub">${p.offline ? "Offline now" : p.mine ? "My device" : "Nearby"}</small></span></span></button>`).join("")}</div>
      <div class="actions"><button class="btn ghost" id="dcx">Cancel</button></div>`);
    $("#dcx").onclick = dropFoldersSheet;
    document.querySelectorAll("[data-dto]").forEach((b) => (b.onclick = () => {
      const p = devs.find((x) => x.id === b.dataset.dto);
      native.postMessage({ op: "dropAdd", to: p.id, toName: p.name });
      dropFoldersSheet();
    }));
  };
}

// ----- links that open themselves -----
// A single link sent from one of my own devices (same account) opens straight in the browser.
function openLinkIfMine(t) {
  if (t.dir !== "in" || S.openLinks === false || !t.received || t.received.length !== 1 || !t.received[0].text) return;
  const url = t.received[0].text.trim();
  if (!/^https?:\/\/\S+$/.test(url)) return;
  const peer = S.peers.find((p) => p.id === t.peer.id);
  if (!t.fromMine && (!peer || !peer.mine)) return;
  if (native) { native.postMessage({ op: "openUrl", url }); return; }
  // A browser tab may block opening a window it wasn't asked to; then the notification opens it.
  const w = window.open(url, "_blank", "noopener");
  if (!w && "Notification" in window && Notification.permission === "granted") {
    const n = new Notification("Link from " + t.peer.name, { body: url + "\nClick to open", tag: "link-" + t.tid, icon: "/icon-192.png" });
    n.onclick = () => { window.open(url, "_blank", "noopener"); n.close(); };
  }
}

// ----- history -----
// The last 200 transfers on this device, kept locally (never sent anywhere).
const HISTORY_MAX = 200;
function historyList() { const h = store.get("history", []); return Array.isArray(h) ? h : []; }
function recordHistory(t) {
  if (t.historyDone) return;
  t.historyDone = true;
  const files = t.items.filter((i) => !i.text);
  const text = t.dir === "in" ? (t.received || []).filter((r) => r.text).map((r) => r.text).join("\n").slice(0, 4000) : "";
  const entry = {
    id: t.tid, at: Date.now(), dir: t.dir, peer: t.peer.name, ok: t.state === "done", reason: t.state === "done" ? "" : t.reason || "",
    count: files.length, names: files.slice(0, 3).map((i) => i.name), folder: topFolder(t.items) || "", size: files.reduce((a, b) => a + b.size, 0),
    text: text || (t.dir === "out" && t.sentText ? t.sentText.slice(0, 4000) : ""), paths: [],
  };
  const h = historyList().filter((e) => e.id !== entry.id);
  h.unshift(entry);
  store.set("history", h.slice(0, HISTORY_MAX));
}
function historyPath(tid, path) {
  const h = historyList(), e = h.find((x) => x.id === tid);
  if (!e || e.paths.length >= 20) return;
  e.paths.push(path);
  store.set("history", h);
}
function whenText(ms) {
  const d = new Date(ms), now = new Date();
  const time = d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  if (d.toDateString() === now.toDateString()) return "Today " + time;
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return "Yesterday " + time;
  return d.toLocaleDateString([], { month: "short", day: "numeric" }) + ", " + time;
}
function showHistory() {
  const h = historyList();
  const rows = h.map((e, i) => {
    const what = e.folder ? `Folder "${esc(e.folder)}"${e.count > 1 ? ` (${e.count} files)` : ""}`
      : e.count ? esc(e.names[0] || "File") + (e.count > 1 ? ` and ${e.count - 1} more` : "") : e.text ? "Text" : "Nothing";
    const who = (e.dir === "in" ? "From " : "To ") + esc(e.peer);
    const meta = [who, e.size ? fmtSize(e.size) : "", whenText(e.at)].filter(Boolean).join(", ");
    const status = e.ok ? "" : `<small class="bad">${esc(e.reason || "Didn't finish")}</small>`;
    const btns = (native && e.paths.length ? `<button class="btn ghost small" data-hreveal="${i}">Show</button>` : "") +
      (e.text ? `<button class="btn ghost small" data-hcopy="${i}">Copy text</button>` : "");
    return `<div class="item hist ${e.dir}"><span><i>${e.dir === "in" ? "↓" : "↑"}</i><span>${what}<small>${meta}</small>${status}</span></span><span class="row-btns">${btns}</span></div>`;
  }).join("");
  openSheet(`<h2>Transfer history</h2>
    <div class="list">${rows || '<div class="item"><span class="sub">Nothing sent or received on this device yet</span></div>'}</div>
    <p><small>Kept on this device only. The last ${HISTORY_MAX} transfers.</small></p>
    <div class="actions">${h.length ? '<button class="btn ghost" id="hclear">Clear history</button>' : ""}<button class="btn primary" id="done">Done</button></div>`);
  $("#done").onclick = closeSheet;
  if ($("#hclear")) $("#hclear").onclick = () => { if (confirm("Clear the transfer history on this device? Files you received stay where they are.")) { store.set("history", []); showHistory(); } };
  document.querySelectorAll("[data-hreveal]").forEach((b) => (b.onclick = () => native.postMessage({ op: "reveal", path: h[+b.dataset.hreveal].paths[0] })));
  document.querySelectorAll("[data-hcopy]").forEach((b) => (b.onclick = () => navigator.clipboard.writeText(h[+b.dataset.hcopy].text).then(() => toast("Copied"), () => toast("Couldn't copy"))));
}

function finish(t) {
  if (t.state === "done" || t.state === "failed") return;
  t.state = "done";
  recordHistory(t);
  openLinkIfMine(t);
  dropReport(t, "done");
  renderTx(t);
  progressFinal(t);
  setTimeout(() => { if (t.state === "done") closePc(t); }, 1500);
  if (t.dir === "in") {
    const texts = t.received.filter((r) => r.text);
    notify(t.tid, "From " + t.peer.name, texts.length && t.items.length === 1 ? "Text received" : "Received " + describeItems(t.items), false);
  }
}
function fail(t, why) {
  if (t.state === "done" || t.state === "failed") return;
  if (t.dc && t.dc.readyState === "open") try { t.dc.send(JSON.stringify({ t: "cancel" })); } catch {}
  t.state = "failed";
  t.reason = why;
  if (t.dir === "out" || t.everOpen || t.got) recordHistory(t);
  dropReport(t, why === "Declined" ? "declined" : "failed");
  closePc(t);
  if (t.w) { const w = t.w; t.w = null; t.q = t.q.then(() => w.abort()).catch(() => {}); }
  renderTx(t);
  progressFinal(t);
}
function cancelTx(t) {
  if (["waiting", "preparing", "paused", "reconnecting"].includes(t.state)) wsSend({ t: "signal", to: t.peer.id, data: { k: "cancel", tid: t.tid } });
  fail(t, "Cancelled");
}

// ---------- notifications & native bridge ----------
function notify(tag, title, body, actions) {
  if (native) return native.postMessage({ op: "notify", tag, title, body, actions: !!actions });
  if (document.hidden && "Notification" in window && Notification.permission === "granted") {
    const n = new Notification(title, { body, tag, icon: "/icon-192.png" });
    n.onclick = () => { window.focus(); n.close(); };
  }
}
window.beamNative = {
  // Drop folders: the app's list changed, or it has new files to send (Mac passes file refs).
  dropFolders(list) { S.dropFolders = Array.isArray(list) ? list : []; if (!$("#sheet").hidden && $("#dadd")) dropFoldersSheet(); if (!$("#sheet").hidden && $("#drops")) { const sm = $("#dropsSub"); if (sm) sm.textContent = dropSummary(); } },
  dropSend(list, to, batch, folder) { dropSend((list || []).map(macFile), to, batch, folder); },
  // Mac and Windows apps report their version (shown in Settings → About).
  appVersion(v) { S.appVersion = String(v || ""); const el = $("#appVer"); if (el) el.textContent = "Version " + S.appVersion; },
  // Mac and Windows apps: where received files go (the folder picker lives in the app).
  saveDir(path, name, isDefault) {
    const before = S.saveDir;
    S.saveDir = { path, name, isDefault: !!isDefault };
    if (!$("#sheet").hidden && $("#sdPick") && (!before || before.path !== path || before.isDefault !== !!isDefault)) { saveSettings(); sheetOnClose = null; showSettings(); }
  },
  // Mac app: files from Finder (Services menu, Open With, or dropped on the menu bar icon)
  share(list) { shareFromDesktop((list || []).map(macFile)); },
  action(tag, act) {
    const t = S.tx.get(tag);
    if (!t) return;
    if (act === "accept") accept(t, false);
    else if (act === "decline") decline(t);
  },
  saved(id, path) {
    for (const t of S.tx.values()) for (const r of t.received) if (r.nid === id) { r.path = path; renderTx(t); historyPath(t.tid, path); }
  },
  // Windows: an iPhone photo/video is being converted to JPG/MP4 after it arrived.
  converting(id, on) {
    for (const t of S.tx.values()) for (const r of t.received) if (r.nid === id) { r.converting = !!on; renderTx(t); }
  },
  converted(id, path) {
    for (const t of S.tx.values()) for (const r of t.received) if (r.nid === id) {
      const old = r.path;
      r.path = path; r.name = path.split(/[\\/]/).pop(); r.converting = false;
      renderTx(t);
      const h = historyList(), e = h.find((x) => x.id === t.tid);
      if (e) { const i = e.paths.indexOf(old); if (i >= 0) e.paths[i] = path; else e.paths.push(path); store.set("history", h); }
    }
  },
  saveFailed(id) {
    for (const t of S.tx.values()) if (t.dir === "in" && id.startsWith(t.tid + ":")) fail(t, "Couldn't save to " + (S.saveDir ? S.saveDir.name : "Downloads") + ". Check the folder in Settings");
  },
};
if (native) native.postMessage({ op: "getSaveDir" });
if (nativeKind === "windows") native.postMessage({ op: "setConvert", on: S.convert !== false });
// Windows app: files from Explorer arrive as real file handles, so the browser reads them directly.
if (nativeKind === "windows") {
  window.chrome.webview.addEventListener("message", async (e) => {
    if (!e.data || (e.data.op !== "share" && e.data.op !== "dropSend")) return;
    const files = [];
    const walk = async (dir, prefix) => {
      if (files.length >= MAX_ITEMS) return;
      for await (const [name, h] of dir) {
        if (h.kind === "file") { try { const f = await h.getFile(); f.beamPath = prefix + "/" + f.name; files.push(f); } catch {} }
        else await walk(h, prefix + "/" + name);
      }
    };
    for (const h of e.additionalObjects || []) {
      try {
        if (h.kind === "directory") await walk(h, h.name);
        else files.push(await h.getFile());
      } catch {}
    }
    if (e.data.op === "dropSend") return dropSend(files, e.data.to, e.data.batch, e.data.folder);
    shareFromDesktop(files, e.data.text || null, e.data.to || null);
  });
}
function nativeStatus() {
  if (!native) return;
  const busy = [...S.tx.values()].some((t) => !["done", "failed", "asking"].includes(t.state));
  const peers = S.peers.map((p) => ({ id: p.id, name: p.name, kind: p.kind, mine: !!p.mine || (!!S.paired[p.id] && S.paired[p.id].pub === p.pub) }));
  const key = JSON.stringify([S.online, !!S.user, busy, peers]);
  if (key === nativeStatus.last) return;
  nativeStatus.last = key;
  native.postMessage({ op: "status", online: S.online, signedIn: !!S.user, peers: S.peers.length, devices: peers, busy });
}

// A small progress card in the corner of the screen (Mac and Windows apps), and the tab title in browsers.
const LIVE = ["connecting", "receiving", "sending", "finishing", "paused", "reconnecting"];
let progTimer = 0, progShown = false;
function nativeProgressSoon() { if (!progTimer) progTimer = setTimeout(() => { progTimer = 0; nativeProgress(); }, 250); }
function nativeProgress() {
  const live = [...S.tx.values()].filter((t) => LIVE.includes(t.state));
  const t = live.find((x) => x.dir === "in") || live[0];
  const frac = t && t.total ? Math.min(1, t.done / t.total) : 0;
  if (!native) { document.title = t ? `Beam (${Math.floor(frac * 100)}%)` : "Beam"; return; }
  if (!t) {
    if (progShown) { progShown = false; native.postMessage({ op: "progress", show: false }); }
    return;
  }
  t.inPopup = progShown = true;
  const paused = t.state === "paused" || t.state === "reconnecting";
  native.postMessage({
    op: "progress", show: true, state: paused ? "paused" : "active", dir: t.dir, pct: frac,
    title: (t.dir === "in" ? "Receiving " : "Sending ") + describeItems(t.items),
    detail: paused ? "Connection lost. Reconnecting…" : (t.dir === "in" ? "From " : "To ") + t.peer.name + (live.length > 1 ? ` and ${live.length - 1} more` : ""),
  });
}
// A transfer just ended: show how it went for a moment, unless another one is still running.
function progressFinal(t) {
  if (!native) return nativeProgressSoon();
  if ([...S.tx.values()].some((x) => x !== t && LIVE.includes(x.state))) return nativeProgressSoon();
  progShown = false;
  const done = t.state === "done";
  const quiet = !t.inPopup || (!done && ["Cancelled", "Declined", "Expired"].includes(t.reason));
  if (quiet) return native.postMessage({ op: "progress", show: false });
  const what = describeItems(t.items);
  native.postMessage({
    op: "progress", show: true, state: done ? "done" : "failed", dir: t.dir, pct: done ? 1 : t.total ? Math.min(1, t.done / t.total) : 0,
    title: done ? (t.dir === "in" ? "Received " : "Sent ") + what : (t.dir === "in" ? "Didn't receive " : "Didn't send ") + what,
    detail: done ? (t.dir === "in" ? "From " : "To ") + t.peer.name : t.reason || "Something went wrong",
    // On Windows the "Received" notification appears in the same corner, so step aside for it.
    hold: done ? (t.dir === "in" && nativeKind === "windows" ? 0 : 3000) : 5000,
  });
}

// ---------- favorites ----------
// Devices pinned to the top, kept on this device. Pin or unpin from a device's "Send to" sheet.
function favList() { const f = store.get("favs", []); return Array.isArray(f) ? f : []; }
function isFav(id) { return favList().includes(id); }
function toggleFav(id) {
  const f = favList(), i = f.indexOf(id);
  if (i >= 0) f.splice(i, 1); else f.push(id);
  store.set("favs", f);
  render();
}
function byFavorite(peers) {
  const f = favList();
  return peers.slice().sort((a, b) => {
    const fa = f.indexOf(a.id), fb = f.indexOf(b.id);
    if (fa >= 0 || fb >= 0) return (fa < 0 ? 1e9 : fa) - (fb < 0 ? 1e9 : fb);
    return 0;
  });
}

// ---------- UI ----------
const ICONS = {
  phone: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M16 1H8a3 3 0 0 0-3 3v16a3 3 0 0 0 3 3h8a3 3 0 0 0 3-3V4a3 3 0 0 0-3-3zm1 19a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h1.5l.5 1h4l.5-1H16a1 1 0 0 1 1 1z"/></svg>',
  laptop: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M4 5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10h1.5a.5.5 0 0 1 .5.5v1A2.5 2.5 0 0 1 19.5 19h-15A2.5 2.5 0 0 1 2 16.5v-1a.5.5 0 0 1 .5-.5H4zm2 0v10h12V5z"/></svg>',
  desktop: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M3 4a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-5v2h2a1 1 0 1 1 0 2H8a1 1 0 1 1 0-2h2v-2H5a2 2 0 0 1-2-2zm2 0v11h14V4z"/></svg>',
  tablet: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M18 1H6a3 3 0 0 0-3 3v16a3 3 0 0 0 3 3h12a3 3 0 0 0 3-3V4a3 3 0 0 0-3-3zm1 19a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1zm-7-1.5a1 1 0 1 0 0-2 1 1 0 0 0 0 2z"/></svg>',
  globe: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm6.93 6h-2.95a15.7 15.7 0 0 0-1.38-3.56A8.03 8.03 0 0 1 18.93 8zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14a8.2 8.2 0 0 1 0-4h3.38a16.5 16.5 0 0 0 0 4zm.81 2h2.95c.32 1.25.78 2.45 1.38 3.56A8 8 0 0 1 5.07 16zM8.02 8H5.07a8 8 0 0 1 4.33-3.56A15.7 15.7 0 0 0 8.02 8zM12 19.96A14.1 14.1 0 0 1 10.09 16h3.82A14.1 14.1 0 0 1 12 19.96zM14.34 14H9.66a14.7 14.7 0 0 1 0-4h4.68a14.7 14.7 0 0 1 0 4zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95a8.03 8.03 0 0 1-4.33 3.56zM16.36 14a16.5 16.5 0 0 0 0-4h3.38a8.2 8.2 0 0 1 0 4z"/></svg>',
  gear: '<svg viewBox="0 0 24 24"><g fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 7h8m6 0h2M4 17h2m6 0h8"/><circle cx="15" cy="7" r="2.5"/><circle cx="9" cy="17" r="2.5"/></g></svg>',
  down: '<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" d="M12 5v14m-6-6 6 6 6-6"/></svg>',
  up: '<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" d="M12 19V5m-6 6 6-6 6 6"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" d="m5 12.5 4.5 4.5L19 7.5"/></svg>',
  star: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="m12 2.8 2.8 5.9 6.4.8-4.7 4.4 1.2 6.4L12 17.2l-5.7 3.1 1.2-6.4-4.7-4.4 6.4-.8z"/></svg>',
  cross: '<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" d="M7 7l10 10M17 7 7 17"/></svg>',
  pause: '<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" d="M9 7v10m6-10v10"/></svg>',
  file: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zm4 18H6V4h7v5h5z"/></svg>',
  text: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M5 4v3h5.5v12h3V7H19V4z"/></svg>',
  folder: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8z"/></svg>',
};
const iconFor = (k) => ({ android: ICONS.phone, iphone: ICONS.phone, mac: ICONS.laptop, windows: ICONS.desktop, chromebook: ICONS.laptop, ipad: ICONS.tablet })[k] || ICONS.globe;

function render() {
  document.body.classList.toggle("offline", !S.online);
  document.body.classList.toggle("searching", S.online && !S.peers.length);
  const acct = $("#settingsBtn");
  acct.hidden = !S.user;
  const chip = S.user ? `<span class="initial">${esc((S.user.name || "?").trim().charAt(0).toUpperCase())}</span><span class="who">${esc(S.user.name)}</span>${ICONS.gear}` : "";
  if (acct.dataset.html !== chip) { acct.innerHTML = chip; acct.dataset.html = chip; }
  const box = $("#peers");
  const ids = new Set(S.peers.concat(S.offline || []).map((p) => p.id));
  const fresh = [];
  for (const el of [...box.children]) if (!ids.has(el.dataset.id)) el.remove();
  const ordered = byFavorite(S.peers).concat(byFavorite(S.offline || []));
  for (const p of ordered) {
    let el = box.querySelector(`[data-id="${p.id}"]`);
    const paired = (!!S.paired[p.id] && S.paired[p.id].pub === p.pub) || p.mine;
    const fav = isFav(p.id);
    const off = !!p.offline;
    const html = `<div class="avatar">${fav ? `<span class="star" title="Pinned to the top">${ICONS.star}</span>` : ""}${iconFor(p.kind)}<svg class="ring" viewBox="0 0 82 82"><circle class="track" cx="41" cy="41" r="38"/><circle class="arc" cx="41" cy="41" r="38" transform="rotate(-90 41 41)" stroke-dasharray="238.8" stroke-dashoffset="238.8"/></svg></div>
      <div class="name">${esc(p.name)}</div><div class="tag ${off ? "" : paired ? "paired" : ""}">${off ? "Offline" : paired ? "My device" : "Nearby"}</div>`;
    if (!el) {
      el = document.createElement("button");
      el.className = "peer";
      el.dataset.id = p.id;
      el.onclick = () => chooseWhat(p.id);
      el.ondragover = (e) => { e.preventDefault(); el.classList.add("drop"); };
      el.ondragleave = () => el.classList.remove("drop");
      el.ondrop = (e) => {
        e.preventDefault();
        el.classList.remove("drop");
        const text = !(e.dataTransfer.files || []).length && (e.dataTransfer.getData("text/uri-list") || e.dataTransfer.getData("text/plain"));
        const entries = [...(e.dataTransfer.items || [])].map((i) => (i.kind === "file" && i.webkitGetAsEntry ? i.webkitGetAsEntry() : null));
        const plain = [...(e.dataTransfer.files || [])];
        (async () => {
          const files = entries.some(Boolean) ? await filesFromEntries(entries.filter(Boolean)) : plain;
          const peer = S.peers.find((x) => x.id === p.id);
          if (peer && (files.length || text)) sendItems(peer, files, text || null);
        })();
      };
      el.style.setProperty("--i", box.children.length);
      box.appendChild(el);
      el.addEventListener("pointerdown", () => FX.pulse(el.querySelector(".avatar"), "tapped", 500));
      fresh.push(el);
    }
    if (el.dataset.html !== html) { el.innerHTML = html; el.dataset.html = html; el.title = p.name; el.setAttribute("aria-label", "Send to " + p.name); }
    el.classList.toggle("mine", paired);
    el.classList.toggle("fav", fav);
    el.classList.toggle("offline", off);
  }
  // Keep the on-screen order matching: pinned devices first.
  ordered.forEach((p, i) => { const el = box.querySelector(`[data-id="${p.id}"]`); if (el && box.children[i] !== el) box.insertBefore(el, box.children[i] || null); });
  // A new device pops in with a little ping from the beam.
  fresh.forEach((el, i) => setTimeout(() => { if (el.isConnected) { FX.pulse(el.querySelector(".avatar"), "ping", 1000); FX.spark(FX.you(), el.querySelector(".avatar"), { ms: 600 }); } }, 120 + i * 90));
  $("#empty").style.display = S.peers.length || (S.offline || []).length ? "none" : "";
  $("#empty").innerHTML = S.guest ? `${esc(S.guest.toName)} isn't online right now<small>Ask them to open Beam. This page will connect by itself.</small>`
    : !S.user && !S.online && ws && ws.readyState === 1 ? "Sign in to use Beam" : S.online
    ? "Looking for devices<small>Open Beam on another device that's signed in as you, or on this WiFi.</small>"
    : "Connecting to Beam…";
  $("#me").innerHTML = S.guest ? `<b>${esc(store.get("guestName", "") || "Guest")}</b><span>Tap ${esc(S.guest.toName)} to send them files</span>`
    : S.pending ? `<b>Choose a device to send ${esc(pendingLabel())}</b><a href="#" id="pcx">Cancel</a>`
    : `<b>${esc(S.name)}</b><span>${S.nearby ? "Visible to everyone nearby" : "Visible to your devices only"}</span>${S.peers.length > 1 ? `<a href="#" id="many">Send to several devices</a>` : ""}`;
  const many = $("#many");
  if (many) many.onclick = (e) => { e.preventDefault(); chooseWhatMany(); };
  const pcx = $("#pcx");
  if (pcx) pcx.onclick = (e) => { e.preventDefault(); S.pending = null; render(); };
  for (const t of S.tx.values()) if (t.dir === "out") renderRing(t);
  nativeStatus();
}

function pendingLabel() {
  const p = S.pending;
  if (!p) return "";
  const n = (p.files || []).length;
  if (!n) return "what you shared";
  const tops = new Set(p.files.map((f) => cleanPath(relPathOf(f)).split("/")[0]).filter(Boolean));
  if (tops.size === 1 && p.files.every((f) => relPathOf(f))) return `the folder "${[...tops][0]}"`;
  if (n === 1) return p.files[0].name;
  return n + ((p.files || []).every((f) => /^video\//.test(f.type)) ? " videos" : (p.files || []).every((f) => /^image\//.test(f.type)) ? " photos" : " files");
}

// Files handed over by the Mac or Windows app ("Send with Beam"): pick a device and go.
// The device list with checkboxes: pick any number, then one Send. Used whenever something is
// ready to go but no device has been chosen yet (desktop share, drag-drop onto the stage, "Send to several").
function showRecipients(files, text, title) {
  S.pending = { files, text: text || null };
  render();
  const list = () => byFavorite(S.peers).map((p) => `<label class="item pick"><span>${iconFor(p.kind)}<span>${esc(p.name)}<small class="sub">${isFav(p.id) ? "Pinned, " : ""}${S.paired[p.id] || p.mine ? "My device" : "Nearby"}</small></span></span><input type="checkbox" class="check" value="${p.id}" ${S.pickSel && S.pickSel.has(p.id) ? "checked" : ""}></label>`).join("")
    || '<div class="item"><span class="sub">Looking for devices… Open Beam on the other device.</span></div>';
  openSheet(`<h2>${esc(title || "Send " + pendingLabel())}</h2>
    <p>${files.length ? fmtSize(files.reduce((a, f) => a + f.size, 0)) + ". " : ""}Pick one device or several.</p>
    <div class="list" id="pickList">${list()}</div>
    <p class="pick-who" id="pickWho"></p>
    <div class="actions"><button class="btn ghost" id="cx">Cancel</button><button class="btn primary" id="go" disabled>Send</button></div>`, () => { S.pickOpen = false; S.pickSel = null; });
  S.pickOpen = true;
  S.pickSel = S.pickSel || new Set();
  const wire = () => {
    document.querySelectorAll("#pickList .check").forEach((c) => (c.onchange = () => { if (c.checked) S.pickSel.add(c.value); else S.pickSel.delete(c.value); update(); }));
    update();
  };
  const update = () => {
    for (const id of [...S.pickSel]) if (!S.peers.some((p) => p.id === id)) S.pickSel.delete(id);
    const n = S.pickSel.size, go = $("#go");
    if (go) { go.disabled = !n; go.textContent = n > 1 ? `Send to ${n} devices` : "Send"; }
    // Spell out who's ticked, full names, so there's no guessing.
    const who = $("#pickWho");
    if (who) {
      const names = [...S.pickSel].map((id) => (S.peers.find((p) => p.id === id) || {}).name).filter(Boolean);
      who.textContent = names.length ? "Sending to " + names.join(", ") : "";
    }
  };
  S.pickRefresh = () => { const box = $("#pickList"); if (box && S.pickOpen) { box.innerHTML = list(); wire(); } };
  wire();
  $("#cx").onclick = () => { S.pending = null; closeSheet(); render(); };
  $("#go").onclick = () => {
    const ids = [...S.pickSel], v = S.pending;
    S.pending = null;
    closeSheet();
    render();
    sendToMany(ids, v.files, v.text);
  };
}

// One send to several devices: a separate transfer to each, from the same files.
function sendToMany(ids, files, text) {
  const peers = ids.map((id) => S.peers.find((p) => p.id === id)).filter(Boolean);
  for (const p of peers) sendItems(p, files, text || null);
  if (peers.length > 1) toast(`Sending to ${peers.length} devices`);
}

function shareFromDesktop(files, text, to) {
  if (!files.length && !text) return;
  // Picked from the app's quick "Send to…" card: send straight away, no need to open Beam.
  if (to) {
    const ids = Array.isArray(to) ? to : [to];
    if (ids.some((id) => S.peers.some((p) => p.id === id))) { S.pending = null; render(); sendToMany(ids, files, text); return; }
  }
  if (native) native.postMessage({ op: "show" });
  showRecipients(files, text);
}

// A file that lives on the Mac: Beam reads it piece by piece from the Mac app as it sends,
// so even huge videos never have to be loaded all at once.
function macFile(f) {
  const reader = window.webkit.messageHandlers.beamFile;
  const slice = (a, b) => ({
    arrayBuffer: async () => {
      const end = Math.min(b, f.size);
      if (end <= a) return new ArrayBuffer(0);
      const b64s = await reader.postMessage({ op: "read", fid: f.fid, off: a, len: end - a });
      return unb64(b64s).buffer;
    },
  });
  return { name: f.name, size: f.size, type: f.type || "application/octet-stream", beamPath: f.path ? f.path + "/" + f.name : "", slice };
}

// Dropped folders come as directory entries; walk them and tag each file with its path inside the drop.
async function filesFromEntries(entries) {
  const out = [];
  const walk = async (entry, prefix) => {
    if (out.length >= MAX_ITEMS) return;
    if (entry.isFile) {
      const f = await new Promise((res, rej) => entry.file(res, rej)).catch(() => null);
      if (f) { f.beamPath = prefix ? prefix + "/" + f.name : ""; out.push(f); }
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      for (;;) {
        const batch = await new Promise((res, rej) => reader.readEntries(res, rej)).catch(() => []);
        if (!batch.length) break;
        for (const e of batch) await walk(e, prefix ? prefix + "/" + entry.name : entry.name);
      }
    }
  };
  for (const e of entries) await walk(e, "");
  return out;
}

function renderRing(t) {
  const el = document.querySelector(`.peer[data-id="${t.peer.id}"] .ring .arc`);
  if (!el) return;
  const active = ["waiting", "connecting", "sending", "finishing", "paused", "reconnecting"].includes(t.state);
  const frac = active ? (t.total ? t.done / t.total : 0) : 0;
  el.parentNode.classList.toggle("on", active);
  el.style.strokeDashoffset = String(238.8 * (1 - (t.state === "waiting" ? 0.04 : frac)));
}

// What a transfer is, and one plain sentence about where it stands.
function txTitle(t) {
  if (t.items.length === 1) return t.items[0].text ? "Text" : t.items[0].name;
  return describeItems(t.items);
}
function txLine(t) {
  const n = t.peer.name;
  switch (t.state) {
    case "preparing": return "Getting ready to send to " + n;
    case "waiting": return "Waiting for " + n + " to accept";
    case "asking": return n + " wants to send you this";
    case "connecting": return "Connecting to " + n;
    case "sending": return "Sending to " + n;
    case "receiving": return "Receiving from " + n;
    case "finishing": return "Finishing up";
    case "paused": return t.dir === "out" && !S.peers.some((p) => p.id === t.peer.id)
      ? `Waiting for ${n} to come back. It picks up where it stopped`
      : "Connection lost. It will pick up where it stopped";
    case "reconnecting": return "Reconnecting to " + n;
    case "done": return (t.dir === "out" ? "Sent to " : "Received from ") + n;
    default: {
      const why = String(t.reason || "Something went wrong");
      return (t.dir === "out" ? "Not sent to " : "Not received from ") + n + ": " + why.charAt(0).toLowerCase() + why.slice(1);
    }
  }
}
function txIcon(t) {
  if (t.state === "done") return ICONS.check;
  if (t.state === "failed") return ICONS.cross;
  if (t.state === "paused" || t.state === "reconnecting") return ICONS.pause;
  return t.dir === "in" ? ICONS.down : ICONS.up;
}

function renderTxList() {
  const box = $("#transfers");
  const tids = new Set(S.tx.keys());
  for (const el of [...box.children]) if (!tids.has(el.dataset.tid)) el.remove();
  for (const t of [...S.tx.values()].reverse()) renderTx(t);
}

const SHOW_FILES = 25;
function renderTx(t) {
  nativeStatusSoon();
  nativeProgressSoon();
  const box = $("#transfers");
  let el = box.querySelector(`[data-tid="${t.tid}"]`);
  if (!S.tx.has(t.tid)) { FX.stop(t); if (el) el.remove(); return; }
  if (!el) {
    el = document.createElement("div");
    el.dataset.tid = t.tid;
    box.prepend(el);
  }
  el.className = "tx " + t.state;
  const moving = ["sending", "receiving", "paused", "reconnecting"].includes(t.state);
  const pct = t.total ? Math.floor((100 * t.done) / t.total) : 0;
  let body = "";
  if (moving) body += `<div class="bar"><i style="width:${t.total ? (100 * t.done) / t.total : 0}%"></i></div>`;
  const one = t.state === "done" && t.items.length === 1 && t.received && t.received.length === 1 && !t.received[0].text ? t.received[0] : null;
  const folderZip = t.state === "done" && t.sink === "memory" && t.received && t.received.length > 1 && (topFolder(t.items) || t.received.length > SHOW_FILES) && t.received.every((r) => r.url);
  let oneBtn = "";
  if (one) {
    oneBtn = one.url ? `<a class="btn ghost small" href="${one.url}" download="${esc(one.name)}">Save</a>`
      : "folder" in one ? `<span class="size">Saved${one.folder ? " in " + esc(one.folder) : ""}</span>`
      : one.converting ? `<span class="size">Converting…</span>` : one.path ? `<button class="btn ghost small" data-reveal="${esc(one.path)}">${revealLabel}</button>` : `<span class="size">Saving…</span>`;
  } else if (folderZip) {
    body += `<div class="files"><div class="file"><span class="sub">${t.received.length} files</span><button class="btn ghost small" data-zip="${t.tid}">Save as .zip</button></div></div>`;
  } else if (t.received && t.received.length) {
    body += '<div class="files">';
    // Long batches list the first few; drawing thousands of rows on every file would slow receiving down.
    const texts = t.received.filter((r) => r.text), files = t.received.filter((r) => !r.text);
    const more = Math.max(0, files.length - SHOW_FILES);
    for (const r of texts.concat(files.slice(0, SHOW_FILES))) {
      if (r.text) {
        const isUrl = /^https?:\/\/\S+$/.test(r.text.trim());
        body += `<div class="text">${esc(r.text)}</div><div class="row">` +
          (isUrl ? `<a class="btn primary small" href="${esc(r.text.trim())}" target="_blank" rel="noopener noreferrer">Open link</a>` : "<span></span>") +
          `<button class="btn ghost small" data-copy="${esc(r.text)}">Copy</button></div>`;
      } else if (r.url) {
        body += `<div class="file"><span>${r.path ? `<span class="sub">${esc(r.path)}/</span>` : ""}${esc(r.name)}</span><a class="btn ghost small" href="${r.url}" download="${esc(r.name)}">Save</a></div>`;
      } else if ("folder" in r) {
        body += `<div class="file"><span>${esc(r.name)}</span><span class="sub">Saved${r.folder ? " in " + esc(r.folder) : ""}</span></div>`;
      } else if (r.nid) {
        body += `<div class="file"><span>${esc(r.name)}</span>${r.converting ? '<span class="sub">Converting…</span>' : r.path ? `<button class="btn ghost small" data-reveal="${esc(r.path)}">${revealLabel}</button>` : '<span class="sub">Saving…</span>'}</div>`;
      }
    }
    if (more) body += `<div class="file"><span class="sub">and ${more} more file${more === 1 ? "" : "s"}</span></div>`;
    body += "</div>";
  }
  let btns = "";
  if (t.state === "asking") btns = `<button class="btn primary small" data-act="accept">Accept</button><button class="btn ghost small" data-act="decline">Decline</button>`;
  else if (!["done", "failed"].includes(t.state)) btns = `<button class="btn ghost small" data-act="cancel">Cancel</button>`;
  else btns = (t.state === "failed" && t.dir === "out" && t.blobs && t.reason !== "Declined" ? `<button class="btn ghost small" data-act="again">Send again</button>` : "") +
    `<button class="btn ghost small icon" data-act="clear" aria-label="Remove from list">✕</button>`;
  const side = moving ? `<span class="pct">${pct}%</span>` : t.total && !t.items.every((i) => i.text) ? `<span class="size">${fmtSize(t.total)}</span>` : "";
  const html = `<div class="tx-head"><span class="tx-ico">${txIcon(t)}</span><div class="tx-text"><div class="title">${esc(txTitle(t))}</div><div class="sub">${esc(txLine(t))}</div></div><div class="tx-side">${one ? oneBtn : side}${btns}</div></div>${body}`;
  if (el.dataset.html !== html) {
    el.innerHTML = html;
    el.dataset.html = html;
    el.querySelectorAll("[data-act]").forEach((b) => (b.onclick = () => {
      const a = b.dataset.act;
      if (a === "accept") accept(t, true);
      else if (a === "decline") decline(t);
      else if (a === "cancel") cancelTx(t);
      else if (a === "again") sendAgain(t);
      else if (a === "clear") { for (const r of t.received || []) if (r.url) URL.revokeObjectURL(r.url); S.tx.delete(t.tid); renderTxList(); }
    }));
    el.querySelectorAll("[data-copy]").forEach((b) => (b.onclick = () => { navigator.clipboard.writeText(b.dataset.copy).then(() => toast("Copied")); }));
    el.querySelectorAll("[data-zip]").forEach((b) => (b.onclick = () => saveFolderZip(t, b)));
    el.querySelectorAll("[data-reveal]").forEach((b) => (b.onclick = () => native && native.postMessage({ op: "reveal", path: b.dataset.reveal })));
  }
  if (t.dir === "out") renderRing(t);
  FX.onTx(t, el);
}

// ---------- animations ----------
// Small moments of motion that show what's happening: devices pop in, sparks travel along
// the beam while a transfer runs, and a finished transfer lands with a burst.
// All of it is skipped when the system asks for reduced motion.
const FX = {
  calm: () => !window.matchMedia || window.matchMedia("(prefers-reduced-motion: reduce)").matches,
  layer() {
    let l = document.getElementById("fx");
    if (!l) { l = document.createElement("div"); l.id = "fx"; l.setAttribute("aria-hidden", "true"); document.body.appendChild(l); }
    return l;
  },
  center(el) {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    if (!r.width && !r.height) return null;
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  },
  you() { return document.querySelector(".you-dot"); },
  peerAvatar(id) { const el = document.querySelector(`#peers [data-id="${id}"] .avatar`); return el; },
  // A glowing spark that arcs from one element to another.
  spark(fromEl, toEl, opts = {}) {
    if (this.calm() || document.hidden) return;
    const a = this.center(fromEl), b = this.center(toEl);
    if (!a || !b) return;
    const d = document.createElement("i");
    d.className = "spark" + (opts.big ? " big" : "") + (opts.good ? " good" : "");
    this.layer().appendChild(d);
    const bend = (opts.bend ?? (Math.random() * 2 - 1)) * Math.min(120, Math.hypot(b.x - a.x, b.y - a.y) * 0.35);
    const mx = (a.x + b.x) / 2 + bend, my = (a.y + b.y) / 2 - Math.abs(bend) * 0.4;
    const frames = [];
    for (let i = 0; i <= 16; i++) {
      const t = i / 16, u = 1 - t;
      const x = u * u * a.x + 2 * u * t * mx + t * t * b.x, y = u * u * a.y + 2 * u * t * my + t * t * b.y;
      frames.push({ transform: `translate(${x}px, ${y}px) scale(${0.6 + Math.sin(t * Math.PI) * 0.7})`, opacity: t < 0.1 ? t * 10 : t > 0.85 ? (1 - t) / 0.15 : 1 });
    }
    const anim = d.animate(frames, { duration: opts.ms || 720, easing: "cubic-bezier(.45,.05,.35,1)" });
    anim.onfinish = () => { d.remove(); if (opts.onland) opts.onland(); };
  },
  // A ring of particles bursting out of an element.
  burst(el, opts = {}) {
    if (this.calm() || document.hidden) return;
    const c = this.center(el);
    if (!c) return;
    const n = opts.n || 14, colors = opts.colors || ["#5cc8ff", "#4ade9a", "#ffffff", "#2f6bff"];
    for (let i = 0; i < n; i++) {
      const p = document.createElement("i");
      p.className = "bit" + (i % 3 === 0 ? " long" : "");
      p.style.background = colors[i % colors.length];
      this.layer().appendChild(p);
      const ang = (i / n) * Math.PI * 2 + Math.random() * 0.4, dist = (opts.size || 54) * (0.7 + Math.random() * 0.6);
      const rot = (ang * 180) / Math.PI + 90;
      p.animate([
        { transform: `translate(${c.x}px, ${c.y}px) rotate(${rot}deg) scale(1)`, opacity: 1 },
        { transform: `translate(${c.x + Math.cos(ang) * dist}px, ${c.y + Math.sin(ang) * dist + 10}px) rotate(${rot + 120}deg) scale(.4)`, opacity: 0 },
      ], { duration: 650 + Math.random() * 350, easing: "cubic-bezier(.15,.7,.3,1)" }).onfinish = () => p.remove();
    }
  },
  // Re-runs a one-shot CSS animation class on an element.
  pulse(el, cls, ms = 900) {
    if (!el || this.calm()) return;
    el.classList.remove(cls);
    void el.offsetWidth;
    el.classList.add(cls);
    setTimeout(() => el.classList.remove(cls), ms);
  },
  // Sending starts: a little file card arcs from you to the device, which catches it.
  launch(t) {
    if (this.calm() || document.hidden) return;
    const from = this.you(), to = this.peerAvatar(t.peer.id);
    const a = this.center(from), b = this.center(to);
    if (!a || !b) return;
    const card = document.createElement("div");
    card.className = "flycard";
    const files = t.items.filter((i) => !i.text);
    card.innerHTML = files.length > 1 ? `<span>${files.length}</span>` : t.items.some((i) => i.text) && !files.length ? "<span>Aa</span>" : "";
    this.layer().appendChild(card);
    const bend = -Math.min(160, Math.hypot(b.x - a.x, b.y - a.y) * 0.5);
    const frames = [];
    for (let i = 0; i <= 20; i++) {
      const k = i / 20, u = 1 - k;
      const mx = (a.x + b.x) / 2 + bend * 0.6, my = (a.y + b.y) / 2;
      const x = u * u * a.x + 2 * u * k * mx + k * k * b.x, y = u * u * a.y + 2 * u * k * my + k * k * b.y;
      frames.push({ transform: `translate(${x}px, ${y}px) rotate(${-25 + k * 385}deg) scale(${0.5 + Math.sin(k * Math.PI) * 0.8 - k * 0.3})`, opacity: k > 0.92 ? (1 - k) / 0.08 : 1 });
      if (i % 3 === 0 && i > 0 && i < 20) setTimeout(() => this.trail(x, y), 650 * k);
    }
    card.animate(frames, { duration: 650, easing: "cubic-bezier(.5,0,.4,1)" }).onfinish = () => {
      card.remove();
      this.pulse(to, "caught", 600);
      this.burst(to, { n: 8, size: 34, colors: ["#5cc8ff", "#ffffff"] });
    };
  },
  trail(x, y) {
    const d = document.createElement("i");
    d.className = "spark";
    this.layer().appendChild(d);
    d.animate([{ transform: `translate(${x}px, ${y}px) scale(.9)`, opacity: .8 }, { transform: `translate(${x}px, ${y + 14}px) scale(.1)`, opacity: 0 }], { duration: 500, easing: "ease-out" }).onfinish = () => d.remove();
  },
  // A glowing beam between you and the other device, flowing the way the data goes.
  beam(t) {
    if (this.calm()) return;
    let svg = document.getElementById("fxBeam");
    if (!svg) {
      svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      svg.id = "fxBeam";
      svg.setAttribute("aria-hidden", "true");
      document.body.appendChild(svg);
    }
    let path = svg.querySelector(`[data-tid="${t.tid}"]`);
    const a = this.center(this.you()), b = this.center(this.peerAvatar(t.peer.id));
    if (!a || !b) { if (path) path.remove(); return; }
    if (!path) {
      path = document.createElementNS("http://www.w3.org/2000/svg", "path");
      path.dataset.tid = t.tid;
      path.setAttribute("class", "beamline " + t.dir);
      svg.appendChild(path);
    }
    const [p, q] = t.dir === "out" ? [a, b] : [b, a];
    const mx = (p.x + q.x) / 2 + (q.y - p.y) * 0.12, my = (p.y + q.y) / 2 - (q.x - p.x) * 0.12;
    path.setAttribute("d", `M${p.x},${p.y} Q${mx},${my} ${q.x},${q.y}`);
  },
  unbeam(t) { const el = document.querySelector(`#fxBeam [data-tid="${t.tid}"]`); if (el) el.remove(); },
  // A big finish for things you receive: confetti falls from the top of the window.
  confetti(n = 46) {
    if (this.calm() || document.hidden) return;
    const colors = ["#5cc8ff", "#4ade9a", "#ffffff", "#2f6bff", "#ffd166", "#ff8fab"];
    const w = window.innerWidth, h = window.innerHeight;
    for (let i = 0; i < n; i++) {
      const p = document.createElement("i");
      p.className = "bit confetti" + (i % 4 === 0 ? " long" : "");
      p.style.background = colors[i % colors.length];
      this.layer().appendChild(p);
      const x = Math.random() * w, drift = (Math.random() - 0.5) * 160, spin = (Math.random() - 0.5) * 900;
      p.animate([
        { transform: `translate(${x}px, -20px) rotate(0deg)`, opacity: 1 },
        { transform: `translate(${x + drift * 0.5}px, ${h * 0.5}px) rotate(${spin * 0.5}deg)`, opacity: 1, offset: 0.55 },
        { transform: `translate(${x + drift}px, ${h + 20}px) rotate(${spin}deg)`, opacity: 0.2 },
      ], { duration: 1600 + Math.random() * 1200, delay: Math.random() * 300, easing: "cubic-bezier(.25,.6,.5,1)" }).onfinish = () => p.remove();
    }
  },
  // A check mark floats up out of the device you sent to.
  rise(el, text) {
    if (this.calm() || document.hidden) return;
    const c = this.center(el);
    if (!c) return;
    const d = document.createElement("div");
    d.className = "rise";
    d.textContent = text;
    this.layer().appendChild(d);
    d.animate([
      { transform: `translate(${c.x}px, ${c.y - 10}px) translate(-50%, -50%) scale(.4)`, opacity: 0 },
      { transform: `translate(${c.x}px, ${c.y - 44}px) translate(-50%, -50%) scale(1.1)`, opacity: 1, offset: 0.35 },
      { transform: `translate(${c.x}px, ${c.y - 70}px) translate(-50%, -50%) scale(1)`, opacity: 0 },
    ], { duration: 1300, easing: "cubic-bezier(.2,.8,.3,1)" }).onfinish = () => d.remove();
  },
  // While a transfer moves, sparks stream along the beam between you and the other device.
  stream(t) {
    if (t.fxTimer || this.calm()) return;
    const tick = () => {
      if (!S.tx.has(t.tid) || !["sending", "receiving"].includes(t.state)) return this.stop(t);
      const peer = this.peerAvatar(t.peer.id), you = this.you();
      this.beam(t);
      if (!peer || !you) return;
      if (t.dir === "out") this.spark(you, peer, { ms: 760 });
      else this.spark(peer, you, { ms: 760 });
    };
    tick();
    t.fxTimer = setInterval(tick, 420);
  },
  stop(t) { if (t.fxTimer) { clearInterval(t.fxTimer); t.fxTimer = 0; } this.unbeam(t); },
  // Called on every transfer redraw: reacts only when the state changes.
  onTx(t, el) {
    const prev = t.fxState;
    if (prev === t.state) return;
    t.fxState = t.state;
    const moving = t.state === "sending" || t.state === "receiving";
    if (moving) this.stream(t); else this.stop(t);
    if (!prev) this.pulse(el, "enter", 600);
    // You hit send: the file flies to the device.
    if (!prev && t.dir === "out") setTimeout(() => this.launch(t), 30);
    // Someone wants to send you something: their device knocks.
    const peerEl = this.peerAvatar(t.peer.id);
    if (peerEl) peerEl.classList.toggle("knock", t.state === "asking");
    if (t.state === "done" && prev !== "done") {
      const peer = this.peerAvatar(t.peer.id);
      const target = t.dir === "out" ? peer : el.querySelector(".tx-ico");
      if (peer) this.pulse(peer, "landed", 1100);
      this.pulse(el.querySelector(".tx-ico"), "pop", 700);
      setTimeout(() => this.burst(target || el.querySelector(".tx-ico"), { n: t.dir === "in" ? 18 : 12, size: t.dir === "in" ? 64 : 50 }), 60);
      if (t.dir === "in") this.confetti();
      else if (peer) this.rise(peer, "Sent");
    }
    if (t.state === "failed" && prev && prev !== "asking") this.pulse(el, "shake", 600);
  },
};

let statusTimer = 0;
function nativeStatusSoon() { if (native && !statusTimer) statusTimer = setTimeout(() => { statusTimer = 0; nativeStatus(); }, 500); }
let progressRaf = 0, lastProgress = 0;
function renderProgress(t) {
  // First, and not tied to drawing: the corner card has to keep moving while Beam's window is
  // hidden, and a hidden window never runs the drawing callback below.
  nativeProgressSoon();
  if (progressRaf) return;
  const now = performance.now();
  if (now - lastProgress < 120) return;
  lastProgress = now;
  progressRaf = requestAnimationFrame(() => {
    progressRaf = 0;
    const bar = document.querySelector(`[data-tid="${t.tid}"] .bar i`);
    if (bar) bar.style.width = (t.total ? (100 * t.done) / t.total : 0) + "%";
    const pct = document.querySelector(`[data-tid="${t.tid}"] .pct`);
    if (pct) pct.textContent = (t.total ? Math.floor((100 * t.done) / t.total) : 0) + "%";
    if (t.dir === "out") renderRing(t);
  });
}

// A whole received folder as one download (browsers can't save a folder directly). Stored, not compressed.
async function saveFolderZip(t, btn) {
  btn.disabled = true;
  btn.textContent = "Preparing…";
  try {
    const files = [];
    for (const r of t.received) {
      const blob = await (await fetch(r.url)).blob();
      files.push({ path: (r.path ? r.path + "/" : "") + r.name, blob });
    }
    const zip = await makeZip(files);
    const a = document.createElement("a");
    a.href = URL.createObjectURL(zip);
    a.download = (topFolder(t.items) || "Beam") + ".zip";
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 60000);
    btn.textContent = "Saved";
  } catch (e) {
    btn.textContent = "Couldn't zip";
  }
}
// Minimal ZIP writer (store method), enough for any unzipper.
async function makeZip(files) {
  const te = new TextEncoder();
  const crcTable = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
  const crc32 = (u8, crc = 0xffffffff) => { for (let i = 0; i < u8.length; i++) crc = crcTable[(crc ^ u8[i]) & 0xff] ^ (crc >>> 8); return crc; };
  const parts = [], central = [];
  let offset = 0;
  const u16 = (n) => [n & 255, (n >>> 8) & 255], u32 = (n) => [n & 255, (n >>> 8) & 255, (n >>> 16) & 255, (n >>> 24) & 255];
  const d = new Date(), dosTime = ((d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1)) & 0xffff, dosDate = (((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate()) & 0xffff;
  for (const f of files) {
    const name = te.encode(f.path.normalize("NFC"));
    let crc = 0xffffffff, size = 0;
    const chunks = [];
    for (let off = 0; off < f.blob.size; off += 8 * 1048576) {
      const u8 = new Uint8Array(await f.blob.slice(off, off + 8 * 1048576).arrayBuffer());
      crc = crc32(u8, crc); size += u8.length; chunks.push(u8);
    }
    crc = (crc ^ 0xffffffff) >>> 0;
    const head = new Uint8Array([...u32(0x04034b50), ...u16(20), ...u16(0x0800), ...u16(0), ...u16(dosTime), ...u16(dosDate), ...u32(crc), ...u32(size), ...u32(size), ...u16(name.length), ...u16(0), ...name]);
    central.push(new Uint8Array([...u32(0x02014b50), ...u16(20), ...u16(20), ...u16(0x0800), ...u16(0), ...u16(dosTime), ...u16(dosDate), ...u32(crc), ...u32(size), ...u32(size), ...u16(name.length), ...u16(0), ...u16(0), ...u16(0), ...u16(0), ...u32(0), ...u32(offset), ...name]));
    parts.push(head, ...chunks);
    offset += head.length + size;
  }
  const cdSize = central.reduce((a, c) => a + c.length, 0);
  const end = new Uint8Array([...u32(0x06054b50), ...u16(0), ...u16(0), ...u16(files.length), ...u16(files.length), ...u32(cdSize), ...u32(offset), ...u16(0)]);
  return new Blob([...parts, ...central, end], { type: "application/zip" });
}

function toast(msg) {
  const d = document.createElement("div");
  d.className = "toast";
  d.textContent = msg;
  document.body.appendChild(d);
  setTimeout(() => d.remove(), 2200);
}

// ----- sheets -----
let sheetOnClose = null, sheetGen = 0;
function openSheet(html, onClose) {
  sheetGen++;
  $("#sheetBody").innerHTML = html;
  $("#sheet").hidden = false;
  sheetOnClose = onClose || null;
}
function closeSheet() {
  // Run the close handler first: it may still need to read what's on the sheet (e.g. Settings).
  const f = sheetOnClose, gen = sheetGen;
  sheetOnClose = null;
  if (f) f();
  if (sheetGen !== gen) return; // the handler opened another sheet
  $("#sheet").hidden = true;
  $("#sheetBody").innerHTML = "";
}
$("#sheet").addEventListener("click", (e) => { if (e.target.id === "sheet") closeSheet(); });

// "Send to several devices": pick what to send, then tick the devices.
function chooseWhatMany() {
  openSheet(`<h2>Send to several devices</h2>
    <div class="big-choice">
      <button class="btn ghost" id="pickFiles">${ICONS.file}Files or photos</button>
      <button class="btn ghost" id="pickFolder">${ICONS.folder}A folder</button>
      <button class="btn ghost" id="pickText">${ICONS.text}Text or link</button>
    </div>`);
  const pick = (folder) => () => {
    closeSheet();
    const inp = $(folder ? "#folderPick" : "#filePick");
    inp.value = "";
    inp.onchange = () => { if (inp.files.length) showRecipients([...inp.files], null); };
    inp.click();
  };
  $("#pickFiles").onclick = pick(false);
  $("#pickFolder").onclick = pick(true);
  $("#pickText").onclick = () => {
    openSheet(`<h2>Send text to several devices</h2><textarea id="txt" placeholder="Paste a link or type something"></textarea>
      <div class="actions"><button class="btn ghost" id="cx">Cancel</button><button class="btn primary" id="go">Next</button></div>`);
    $("#txt").focus();
    $("#cx").onclick = closeSheet;
    $("#go").onclick = () => { const v = $("#txt").value; if (v.trim()) showRecipients([], v); };
  };
}

function chooseWhat(peerId) {
  const p = S.peers.find((x) => x.id === peerId);
  if (!p) { const o = (S.offline || []).find((x) => x.id === peerId); if (o) offlineDevice(o); return; }
  if (S.pending) { const v = S.pending; S.pending = null; closeSheet(); render(); return sendItems(p, v.files || [], v.text || null); }
  openSheet(`<div class="send-head"><h2>Send to ${esc(p.name)}</h2><button class="btn ghost small pin ${isFav(p.id) ? "on" : ""}" id="pin">${ICONS.star}${isFav(p.id) ? "Pinned" : "Pin to top"}</button></div>
    <div class="big-choice">
      <button class="btn ghost" id="pickFiles">${ICONS.file}Files or photos</button>
      <button class="btn ghost" id="pickFolder">${ICONS.folder}A folder</button>
      <button class="btn ghost" id="pickText">${ICONS.text}Text or link</button>
    </div>`);
  $("#pin").onclick = () => { toggleFav(p.id); const on = isFav(p.id); $("#pin").classList.toggle("on", on); $("#pin").innerHTML = ICONS.star + (on ? "Pinned" : "Pin to top"); toast(on ? p.name + " pinned to the top" : p.name + " unpinned"); };
  const pick = (folder) => () => {
    closeSheet();
    const inp = $(folder ? "#folderPick" : "#filePick");
    inp.value = "";
    inp.onchange = () => { const peer = S.peers.find((x) => x.id === peerId); if (peer && inp.files.length) sendItems(peer, [...inp.files], null); };
    inp.click();
  };
  $("#pickFiles").onclick = pick(false);
  $("#pickFolder").onclick = pick(true);
  $("#pickText").onclick = () => {
    openSheet(`<h2>Send text to ${esc(p.name)}</h2><textarea id="txt" placeholder="Paste a link or type something"></textarea>
      <div class="actions"><button class="btn ghost" id="cx">Cancel</button><button class="btn primary" id="go">Send</button></div>`);
    $("#txt").focus();
    $("#cx").onclick = closeSheet;
    $("#go").onclick = () => { const v = $("#txt").value; const peer = S.peers.find((x) => x.id === peerId); closeSheet(); if (peer && v.trim()) sendItems(peer, [], v); };
  };
}

// Which offline devices of mine to show: one per name and type (the same machine signed in again
// shows up as a new device), nothing unseen for 30 days unless pinned, and at most 8.
function rememberedDevices(online, offline) {
  const key = (p) => (p.name || "").trim().toLowerCase() + "|" + (p.kind || "");
  const taken = new Set(online.filter((p) => p.mine).map(key));
  taken.add(key({ name: S.name, kind })); // an old sign-in of this very device
  const favs = favList(), month = Date.now() - 30 * 86400000;
  const out = [];
  for (const p of offline.slice().sort((a, b) => (b.seen || 0) - (a.seen || 0))) {
    const pinned = favs.includes(p.id);
    if (!pinned && ((p.seen || 0) < month || taken.has(key(p)))) continue;
    taken.add(key(p));
    out.push(p);
  }
  return out.filter((p) => favs.includes(p.id)).concat(out.filter((p) => !favs.includes(p.id)).slice(0, 8));
}

// One of my devices that isn't connected right now.
function ago(ms) {
  if (!ms) return "a while ago";
  const m = Math.round((Date.now() - ms) / 60000);
  if (m < 2) return "just now";
  if (m < 60) return m + " minutes ago";
  const h = Math.round(m / 60);
  if (h < 24) return h + (h === 1 ? " hour ago" : " hours ago");
  const d = Math.round(h / 24);
  return d < 30 ? d + (d === 1 ? " day ago" : " days ago") : new Date(ms).toLocaleDateString([], { month: "short", day: "numeric", year: "numeric" });
}
function offlineDevice(p) {
  openSheet(`<h2>${esc(p.name)} is offline</h2>
    <p>Last seen ${esc(ago(p.seen))}. When Beam is open on it again, it'll be ready to send to here.</p>
    <p class="hint">Replaced this device, or don't use it any more? Forget it to remove it from your account. Signing in on it again brings it back.</p>
    <div class="actions"><button class="btn danger" id="forget">Forget device</button><button class="btn primary" id="done">OK</button></div>`);
  $("#done").onclick = closeSheet;
  $("#forget").onclick = () => {
    wsSend({ t: "forget-device", id: p.id });
    const f = favList().filter((x) => x !== p.id); store.set("favs", f);
    closeSheet();
    toast(p.name + " removed from your account");
  };
}

const prompts = new Set();
function promptIncoming(t) {
  prompts.add(t.tid);
  notify(t.tid, t.peer.name + " wants to send you " + describeItems(t.items), (t.total ? fmtSize(t.total) + " · " : "") + "Device " + shortId(t.peer.id), true);
  if (!$("#sheet").hidden) return; // the transfer card has Accept/Decline too
  const list = t.items.slice(0, 6).map((i) => `<div class="item"><span>${esc(i.text ? "Text" : i.name)}</span><span class="sub">${i.text ? "" : fmtSize(i.size)}</span></div>`).join("") +
    (t.items.length > 6 ? `<div class="item"><span class="sub">and ${t.items.length - 6} more</span></div>` : "");
  openSheet(`<h2>${esc(t.peer.name)} wants to send you ${esc(describeItems(t.items))}</h2>
    <p>${t.total ? fmtSize(t.total) + ", from " : "From "}${t.peer.paired ? "one of your devices" : "device " + shortId(t.peer.id)}</p>
    ${t.items.length > 1 ? `<div class="list">${list}</div>` : ""}${sizeNote(t)}
    <div class="actions"><button class="btn ghost" id="dec">Decline</button><button class="btn primary" id="acc">Accept</button></div>`);
  $("#sheet").dataset.tid = t.tid;
  $("#acc").onclick = () => accept(t, true);
  $("#dec").onclick = () => decline(t);
}
// Browsers without save-to-disk keep the whole file in memory until you tap Save.
function sizeNote(t) {
  if (native) return "";
  if (canSaveToDisk) return t.total >= BIG ? `<p><small>Large transfer: you'll pick a folder and it saves straight to disk.</small></p>`
    : topFolder(t.items) && !store.get("saveDirName", null) ? `<p><small>You'll pick where to save it, and the folder is recreated there.</small></p>` : "";
  const limit = /iPhone|iPad|Android/.test(navigator.userAgent) ? 300 * 1048576 : 1500 * 1048576;
  return t.total > limit ? `<p style="color:var(--bad)"><small>This is a lot for a browser tab and may fail. The Beam app, Chrome or Edge can receive any size.</small></p>` : "";
}

function closePrompt(tid) {
  prompts.delete(tid);
  if (native) native.postMessage({ op: "unnotify", tag: tid });
  if (!$("#sheet").hidden && $("#sheet").dataset.tid === tid) { $("#sheet").dataset.tid = ""; closeSheet(); }
}

// ----- settings & pairing -----
function showSettings() {
  const paired = Object.entries(S.paired);
  const chev = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const nav = (id, label, sub, cls = "") => `<button class="nav ${cls}" id="${id}"><span>${label}${sub ? `<small>${sub}</small>` : ""}</span>${cls.includes("danger") ? "" : chev}</button>`;
  const initial = S.user ? esc((S.user.name || S.user.username || "?").trim().charAt(0).toUpperCase()) : "?";
  const receiving = [
    native ? `<div class="opt"><span>Save files to<small id="sdName">${esc(S.saveDir ? S.saveDir.name : "Downloads")}</small></span><span class="row-btns">${S.saveDir && !S.saveDir.isDefault ? '<button class="btn ghost small" id="sdReset">Use Downloads</button>' : ""}<button class="btn ghost small" id="sdPick">Choose…</button></span></div>` : "",
    canSaveToDisk ? `<div class="opt"><span>Save files to<small>${store.get("saveDirName", null) ? esc(store.get("saveDirName")) + " (any size)" : "Downloads. Big transfers ask where to save"}</small></span><button class="btn ghost small" id="sf">Choose…</button></div>` : "",
    nativeKind === "windows" ? `<label class="opt"><span>Convert iPhone photos and videos<small>HEIC to JPG and HEVC to MP4, so every app opens them</small></span><input type="checkbox" class="toggle" id="cv" ${S.convert !== false ? "checked" : ""}></label>` : "",
    `<label class="opt"><span>Auto-accept from my devices<small>What they send arrives without asking</small></span><input type="checkbox" class="toggle" id="aa" ${S.autoAccept ? "checked" : ""}></label>`,
    `<label class="opt"><span>Open links from my devices<small>A link sent from one of your devices opens in your browser</small></span><input type="checkbox" class="toggle" id="ol" ${S.openLinks !== false ? "checked" : ""}></label>`,
    !native && "Notification" in window && Notification.permission !== "granted" ? '<div class="opt"><span>Notifications<small>Alerts when this tab is in the background</small></span><button class="btn ghost small" id="nt">Enable</button></div>' : "",
  ].join("");
  openSheet(`<div class="set-head"><h2>Settings</h2></div>
    <div class="set-who"><span class="set-avatar">${initial}</span><span>${S.user ? esc(S.user.name) : "Not signed in"}<small>${S.user ? "Username " + esc(S.user.username) + (S.user.admin ? ". You manage this Beam" : "") : ""}</small></span></div>

    <h3>This device</h3>
    <div class="group">
      <label class="opt field"><span>Name</span><input type="text" id="nm" maxlength="40" value="${esc(S.name)}" aria-label="Device name"></label>
      <label class="opt"><span>Everyone nearby can see me<small>When off, only your own and paired devices can</small></span><input type="checkbox" class="toggle" id="nb" ${S.nearby ? "checked" : ""}></label>
    </div>

    <h3>Receiving</h3>
    <div class="group">${receiving}</div>

    <h3>Activity</h3>
    <div class="group">
      ${native ? nav("drops", "Drop folders", `<span id="dropsSub">${dropSummary()}</span>`) : ""}
      ${nav("guestqr", "Guest QR code", "Let someone without Beam send files to this device")}
      ${nav("iphone", "Send from iPhone", "Add Beam to the iPhone share sheet")}
      ${nav("hist", "Transfer history", "What this device sent and received")}
      ${nav("ctest", "Test connection", "Check the server, relay and your devices")}
    </div>

    <h3>Paired devices</h3>
    <div class="group">
      ${paired.map(([id, p]) => `<div class="opt"><span>${esc(p.name)}<small>${shortId(id)}</small></span><button class="btn danger small" data-unpair="${id}">Remove</button></div>`).join("")}
      ${nav("pShow", "Show a pairing code", "On this device, for someone else to enter")}
      ${nav("pJoin", "Enter a pairing code", "From someone else's device")}
    </div>
    <p class="hint">Your own devices find each other automatically. Pair to send with someone else's device.</p>

    <h3>Account</h3>
    <div class="group">
      ${S.user && S.user.admin ? nav("ppl", "Manage people", "Add people, reset PINs, sign out devices") : ""}
      ${nav("cpin", "Change PIN", "")}
      ${nav("sout", "Sign out of this device", "", "danger")}
    </div>
    ${native ? `<h3>About</h3>
    <div class="group">
      ${nav("upd", "Check for updates", `<span id="appVer">${S.appVersion ? "Version " + esc(S.appVersion) : "Beam for " + (nativeKind === "windows" ? "Windows" : "Mac")}</span>`)}
    </div>` : ""}
    <p class="hint">Device code ${shortId(Id.id)}</p>
    <div class="actions sticky"><button class="btn primary" id="done">Done</button></div>`, saveSettings);
  $("#done").onclick = closeSheet;
  $("#ctest").onclick = () => { saveSettings(); sheetOnClose = null; connectionTest(); };
  if ($("#drops")) $("#drops").onclick = () => { saveSettings(); sheetOnClose = null; dropFoldersSheet(); };
  $("#guestqr").onclick = () => { saveSettings(); sheetOnClose = null; guestSheet(); };
  $("#iphone").onclick = () => { saveSettings(); sheetOnClose = null; shortcutSheet(); };
  if (native) native.postMessage({ op: "getDropFolders" });
  if ($("#upd")) $("#upd").onclick = () => { native.postMessage({ op: "checkUpdates" }); toast("Checking for updates…"); };
  if (native) native.postMessage({ op: "getVersion" });
  $("#hist").onclick = () => { saveSettings(); sheetOnClose = null; showHistory(); };
  if ($("#ppl")) $("#ppl").onclick = () => { saveSettings(); sheetOnClose = null; S.adminOpen = true; showPeople(); wsSend({ t: "admin-list" }); };
  $("#cpin").onclick = () => { saveSettings(); sheetOnClose = null; showChangePin(); };
  $("#sout").onclick = () => { if (confirm("Sign out of Beam on this device?")) { sheetOnClose = null; closeSheet(); wsSend({ t: "logout" }); } };
  $("#pShow").onclick = () => { saveSettings(); S.pairing = { mode: "show" }; wsSend({ t: "pair-code" }); showPairShow(); };
  $("#pJoin").onclick = () => { saveSettings(); S.pairing = { mode: "join" }; showPairJoin(); };
  if ($("#sdPick")) $("#sdPick").onclick = () => native.postMessage({ op: "pickSaveDir" });
  if ($("#sdReset")) $("#sdReset").onclick = () => native.postMessage({ op: "resetSaveDir" });
  if (native) native.postMessage({ op: "getSaveDir" });
  const sf = $("#sf");
  if (sf) sf.onclick = () => pickFolder().then(() => { saveSettings(); sheetOnClose = null; showSettings(); }).catch(() => {});
  const nt = $("#nt");
  if (nt) nt.onclick = () => Notification.requestPermission().then(() => showSettings());
  document.querySelectorAll("[data-unpair]").forEach((b) => (b.onclick = () => {
    delete S.paired[b.dataset.unpair];
    savePaired();
    updateServer();
    sheetOnClose = null;
    showSettings();
  }));
}
// ----- connection test -----
// Checks each way a transfer can travel: the server, a direct internet path, and the relay.
async function candidatesWith(iceServers, relayOnly) {
  const pc = new RTCPeerConnection({ iceServers, iceTransportPolicy: relayOnly ? "relay" : "all" });
  const types = new Set();
  pc.onicecandidate = (e) => { const m = e.candidate && / typ (\w+)/.exec(e.candidate.candidate); if (m) types.add(m[1]); };
  try {
    pc.createDataChannel("test");
    await pc.setLocalDescription(await pc.createOffer());
    await gathered(pc, 5000);
  } catch {}
  try { pc.close(); } catch {}
  return types;
}
async function connectionTest() {
  const row = (ok, title, detail) => `<div class="item test ${ok === null ? "wait" : ok ? "good" : "bad"}"><span>${title}<small>${detail}</small></span><b>${ok === null ? "…" : ok ? "OK" : "Problem"}</b></div>`;
  const rows = { server: row(null, "Beam server", "Checking…"), direct: row(null, "Direct connection", "Checking…"), relay: row(null, "Relay", "Checking…"), devices: row(null, "Your devices", "Checking…") };
  let summary = "Testing…";
  const draw = () => { if ($("#testRows")) { $("#testRows").innerHTML = Object.values(rows).join(""); $("#testSum").textContent = summary; } };
  openSheet(`<h2>Connection test</h2><p id="testSum">Testing…</p><div class="list" id="testRows"></div>
    <div class="actions"><button class="btn ghost" id="again">Test again</button><button class="btn primary" id="done">Done</button></div>`);
  $("#done").onclick = closeSheet;
  $("#again").onclick = connectionTest;
  draw();

  // 1. Server
  let pong = null, ms = 0;
  if (S.online) {
    const n = randId(), t0 = performance.now();
    pong = await new Promise((res) => { S.pingWait = { n, res }; wsSend({ t: "ping", n }); setTimeout(() => res(null), 6000); });
    ms = Math.round(performance.now() - t0);
    // The answer carries fresh relay details, so the test doesn't use stale ones.
    if (pong && Array.isArray(pong.ice) && pong.ice.length) { S.ice = pong.ice; S.iceAt = Date.now(); }
  }
  rows.server = pong ? row(true, "Beam server", `Connected, ${ms} ms round trip`) : row(false, "Beam server", S.online ? "Not answering" : S.user === null ? "Not signed in" : "Can't reach it. Check the internet connection");
  draw();

  // 2. Direct path (STUN only)
  const stun = S.ice.filter((x) => [].concat(x.urls).some((u) => /^stuns?:/.test(u))).map((x) => ({ urls: [].concat(x.urls).filter((u) => /^stuns?:/.test(u)) }));
  const direct = await candidatesWith(stun, false);
  const publicOk = direct.has("srflx");
  rows.direct = row(publicOk, "Direct connection", publicOk ? "This network allows direct transfers" : direct.has("host") ? "Only to devices on this same network" : "Blocked on this network");
  draw();

  // 3. Relay (TURN only)
  const turn = S.ice.filter((x) => [].concat(x.urls).some((u) => /^turns?:/.test(u)));
  let relayOk = false;
  if (!turn.length) rows.relay = row(false, "Relay", pong && pong.relay ? "The server couldn't get relay access" + (pong.relayError ? ": " + esc(pong.relayError) : "") : "Not set up yet");
  else {
    relayOk = (await candidatesWith(turn, true)).has("relay");
    rows.relay = row(relayOk, "Relay", relayOk ? "Ready for networks that block direct transfers" : "Can't reach the relay from this network");
  }
  draw();

  // 4. Devices
  const all = S.peers.length;
  rows.devices = row(all > 0, "Your devices", all ? `${all} reachable now` : "None online right now");
  summary = !pong ? "Beam can't send anything until it reaches the server."
    : publicOk || relayOk ? "Transfers should work from this network."
    : "Transfers will only work with devices on this same network.";
  draw();
}

function saveSettings() {
  const nm = $("#nm"), nb = $("#nb"), aa = $("#aa");
  if (!nm) return;
  const name = nm.value.trim().slice(0, 40) || S.name;
  const changed = name !== S.name || nb.checked !== S.nearby;
  S.name = name; S.nearby = nb.checked; S.autoAccept = aa.checked;
  const ol = $("#ol");
  if (ol) { S.openLinks = ol.checked; store.set("openLinks", S.openLinks); }
  const cv = $("#cv");
  if (cv && cv.checked !== (S.convert !== false)) { S.convert = cv.checked; store.set("convert", S.convert); native.postMessage({ op: "setConvert", on: S.convert }); }
  store.set("name", S.name); store.set("nearby", S.nearby); store.set("autoAccept", S.autoAccept);
  if (changed) updateServer();
  render();
}
$("#settingsBtn").onclick = showSettings;

function showPairShow() {
  const code = S.pairing && S.pairing.code;
  openSheet(`<h2>Pair a device</h2><p>On your other device, open Beam → Settings → <b>Enter a code</b>, and type:</p>
    <div class="code">${code ? code.slice(0, 3) + " " + code.slice(3) : "······"}</div><p><small>Expires in 10 minutes.</small></p>
    <div class="actions"><button class="btn ghost" id="cx">Cancel</button></div>`, () => { S.pairing = null; });
  $("#cx").onclick = closeSheet;
}
function showPairJoin() {
  const err = S.pairing && S.pairing.error;
  openSheet(`<h2>Enter pairing code</h2><p>Shown on your other device under Settings → Show a code.</p>
    <input type="text" id="pc" inputmode="numeric" autocomplete="one-time-code" maxlength="7" placeholder="123 456" style="font-size:24px;text-align:center;letter-spacing:4px">
    ${err ? `<p style="color:var(--bad)">${esc(err)}</p>` : ""}
    <div class="actions"><button class="btn ghost" id="cx">Cancel</button><button class="btn primary" id="go">Pair</button></div>`, () => { S.pairing = null; });
  $("#pc").focus();
  $("#cx").onclick = closeSheet;
  $("#go").onclick = () => { const c = $("#pc").value.replace(/\D/g, ""); if (c.length === 6) { S.pairing.error = null; wsSend({ t: "pair-join", code: c }); } };
}
function showPairWaiting() {
  const p = S.pairing;
  openSheet(`<h2>Pairing with ${esc(p.peer.name)}</h2><p>Check that ${esc(p.peer.name)} shows this same number, then accept there:</p>
    <div class="code safety">${p.safety}</div>
    <div class="actions"><button class="btn ghost" id="cx">Cancel</button></div>`, () => { S.pairing = null; });
  $("#cx").onclick = closeSheet;
}
function showPairConfirm() {
  const p = S.pairing;
  openSheet(`<h2>Pair with ${esc(p.peer.name)}?</h2><p>Make sure ${esc(p.peer.name)} shows this same number:</p>
    <div class="code safety">${p.safety}</div>
    <div class="actions"><button class="btn ghost" id="no">Doesn't match</button><button class="btn primary" id="yes">Pair</button></div>`, () => { S.pairing = null; });
  $("#no").onclick = () => { wsSend({ t: "signal", to: p.peer.id, data: { k: "pair-no" } }); closeSheet(); };
  $("#yes").onclick = () => {
    S.paired[p.peer.id] = { pub: p.peer.pub, name: p.peer.name, kind: p.peer.kind };
    savePaired();
    wsSend({ t: "signal", to: p.peer.id, data: { k: "pair-ok", pub: Id.pub, name: S.name, kind } });
    updateServer();
    pairDone(true);
  };
}
function pairDone(ok, why) {
  const name = S.pairing && S.pairing.peer ? S.pairing.peer.name : "device";
  S.pairing = null;
  sheetOnClose = null;
  closeSheet();
  toast(ok ? "Paired with " + name : why || "Pairing failed");
}

// ---------- accounts ----------
// Devices signed in to the same account link up automatically (their keys get pinned like a pairing).
function autoPairMine() {
  let changed = false;
  for (const p of S.peers) {
    if (!p.mine || !p.pub) continue;
    if (S.paired[p.id]) continue; // never overwrite a key we already trust
    S.paired[p.id] = { pub: p.pub, name: p.name, kind: p.kind };
    changed = true;
  }
  if (changed) { savePaired(); updateServer(); }
}

// Signing in is a full screen of its own: nothing else in Beam works until it's done.
const AUTH = {
  signin: {
    title: "Sign in", lede: "Use your Beam username and PIN. This device stays signed in.", cta: "Sign in", busy: "Signing in…",
    switchTo: "invite", switchLead: "New here?", switchLabel: "Use an invite code",
  },
  invite: {
    title: "Join with an invite", lede: "Enter the username and code from your invite, then choose a PIN you'll remember.", cta: "Join Beam", busy: "Joining…",
    switchTo: "signin", switchLead: "Already have a PIN?", switchLabel: "Sign in",
  },
  setup: {
    title: "Set up Beam", lede: "You're the first one here. Create the admin account, then invite everyone else from Settings.", cta: "Create admin account", busy: "Creating…",
  },
};
function showSignIn(mode) {
  const A = AUTH[mode] || AUTH.signin;
  S.signInMode = mode;
  S.authOpen = true;
  if (!$("#sheet").hidden) { sheetOnClose = null; closeSheet(); }
  const text = (id, label, attrs = "", hint = "") => `<label class="field"><span class="label">${label}</span><input type="text" id="${id}" ${attrs}>${hint ? `<span class="hint">${hint}</span>` : ""}</label>`;
  const pin = (id, label, ac, hint = "") => `<label class="field"><span class="label">${label}</span><span class="pin-wrap"><input type="password" id="${id}" inputmode="numeric" pattern="[0-9]*" maxlength="12" autocomplete="${ac}"><button type="button" class="reveal" data-for="${id}">Show</button></span>${hint ? `<span class="hint">${hint}</span>` : ""}</label>`;
  const user = 'autocapitalize="none" autocorrect="off" spellcheck="false" autocomplete="username"';
  const fields = mode === "setup"
    ? text("su_code", "Setup code", 'autocomplete="off" autocapitalize="characters" spellcheck="false"', "The one-time code you got when Beam was put on its server.") +
      text("su_name", "Your name", 'autocomplete="name"') + text("su_user", "Username", user, "Short and lowercase, like will.") +
      pin("su_pin", "Choose a PIN", "new-password", "6 to 12 digits.") + pin("su_pin2", "Confirm PIN", "new-password")
    : mode === "invite"
    ? text("in_user", "Username", user) + text("in_code", "Invite code", 'autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="ABCD-2345"', "It works once, for 7 days.") +
      pin("in_pin", "Choose a PIN", "new-password", "6 to 12 digits.") + pin("in_pin2", "Confirm PIN", "new-password")
    : text("li_user", "Username", user) + pin("li_pin", "PIN", "current-password");
  $("#authBody").innerHTML = `<img class="auth-mark" src="/icon.svg" alt="" width="52" height="52">
    <h1>${A.title}</h1><p class="lede">${A.lede}</p>
    <form id="authForm" novalidate>${fields}
      <div class="alert" id="authErr" role="alert" hidden></div>
      <button class="btn primary" id="go" type="submit">${A.cta}</button>
    </form>
    ${A.switchTo ? `<p class="switch">${A.switchLead} <button type="button" id="${A.switchTo === "invite" ? "inv" : "back"}">${A.switchLabel}</button></p>` : ""}
    <p class="foot">${mode === "setup" ? "The setup code stops working once the admin account exists." : "Beam is invite-only. Ask the person who runs your Beam for an invite."}</p>`;
  $("#auth").hidden = false;
  const v = (id) => ($("#" + id) ? $("#" + id).value.trim() : "");
  const p = mode === "setup" ? "su" : mode === "invite" ? "in" : "li";
  $("#authForm").onsubmit = (e) => {
    e.preventDefault();
    const pinOk = /^\d{6,12}$/.test(v(p + "_pin"));
    const problem =
      mode === "setup" && !v("su_code") ? "Enter the setup code." :
      mode === "setup" && !v("su_name") ? "Enter your name." :
      !v(p + "_user") ? "Enter your username." :
      mode === "invite" && !v("in_code") ? "Enter your invite code." :
      !v(p + "_pin") ? "Enter your PIN." :
      mode !== "signin" && !pinOk ? "PINs are 6 to 12 digits." :
      mode !== "signin" && v(p + "_pin") !== v(p + "_pin2") ? "The two PINs don't match." :
      !ws || ws.readyState !== 1 ? "Can't reach Beam. Check your connection and try again." : "";
    if (problem) return authError(problem);
    authBusy(true);
    if (mode === "setup") wsSend({ t: "setup", code: v("su_code"), name: v("su_name"), user: v("su_user"), pin: v("su_pin") });
    else if (mode === "invite") wsSend({ t: "login", user: v("in_user"), invite: v("in_code"), pin: v("in_pin") });
    else wsSend({ t: "login", user: v("li_user"), pin: v("li_pin") });
  };
  document.querySelectorAll("#authBody .reveal").forEach((b) => (b.onclick = () => {
    const i = $("#" + b.dataset.for), show = i.type === "password";
    i.type = show ? "text" : "password";
    b.textContent = show ? "Hide" : "Show";
  }));
  if ($("#inv")) $("#inv").onclick = () => showSignIn("invite");
  if ($("#back")) $("#back").onclick = () => showSignIn("signin");
  const first = $("#authBody input");
  if (first) first.focus();
}
let authTimer = 0;
function authBusy(on) {
  const go = $("#go"), A = AUTH[S.signInMode] || AUTH.signin;
  clearTimeout(authTimer);
  if (!go || !S.authOpen) return;
  go.disabled = on;
  go.textContent = on ? A.busy : A.cta;
  if (on) {
    $("#authErr").hidden = true;
    authTimer = setTimeout(() => authError("Can't reach Beam. Check your connection and try again."), 9000);
  }
}
function authError(msg) {
  if (!S.authOpen) return;
  authBusy(false);
  const box = $("#authErr");
  box.textContent = msg;
  box.hidden = false;
  if (S.signInMode === "signin" && $("#li_user").value) { $("#li_pin").focus(); $("#li_pin").select(); }
}
function hideSignIn() {
  clearTimeout(authTimer);
  S.authOpen = false;
  $("#auth").hidden = true;
  $("#authBody").innerHTML = "";
}

function showChangePin() {
  const pinAttrs = 'inputmode="numeric" autocomplete="off" maxlength="12"';
  openSheet(`<h2>Change PIN</h2>
    <input type="password" id="op" placeholder="Current PIN" ${pinAttrs} style="margin-top:10px">
    <input type="password" id="np" placeholder="New PIN (6–12 digits)" ${pinAttrs} style="margin-top:10px">
    <input type="password" id="np2" placeholder="New PIN again" ${pinAttrs} style="margin-top:10px">
    <div class="actions"><button class="btn ghost" id="cx">Cancel</button><button class="btn primary" id="go">Change</button></div>`);
  $("#cx").onclick = closeSheet;
  $("#go").onclick = () => {
    if ($("#np").value !== $("#np2").value) return toast("The new PINs don't match.");
    wsSend({ t: "pin-change", old: $("#op").value, pin: $("#np").value });
  };
}

function showPeople() {
  const users = S.adminList || [];
  const rows = users.map((u) => {
    const status = u.status === "invited" ? (u.invite ? `Invited · code <b>${esc(u.invite)}</b>` : "Invite expired") : `${u.devices.length} device${u.devices.length === 1 ? "" : "s"}${u.locked ? " · locked (too many wrong PINs)" : ""}`;
    const devs = u.devices.map((d) => `<div class="item" style="padding-left:14px"><span>${esc(d.name)} <span class="sub">${d.online ? "online" : "offline"}</span></span><button class="btn ghost small" data-rmdev="${d.id}">Sign out</button></div>`).join("");
    const self = S.user && u.username === S.user.username;
    return `<div class="item" style="flex-wrap:wrap"><span style="min-width:0">${esc(u.name)} <span class="sub">${esc(u.username)}${u.admin ? " · admin" : ""}</span><br><span class="sub">${status}</span></span>
      <span class="row" style="gap:6px;display:flex"><button class="btn ghost small" data-reset="${esc(u.username)}">${u.status === "invited" ? "New code" : "Reset PIN"}</button>${self ? "" : `<button class="btn danger small" data-rm="${esc(u.username)}">Remove</button>`}</span></div>${devs}`;
  }).join("");
  openSheet(`<h2>People</h2><p>Only people you add here can use Beam.</p>
    <div class="list">${rows || '<div class="item"><span class="sub">Loading…</span></div>'}</div>
    <h3>Add someone</h3>
    <input type="text" id="np_name" placeholder="Their name">
    <input type="text" id="np_user" placeholder="Username (e.g. sarah)" autocapitalize="none" style="margin-top:8px">
    <div class="actions"><button class="btn ghost" id="cx">Done</button><button class="btn primary" id="add">Add</button></div>`, () => { S.adminOpen = false; });
  $("#cx").onclick = closeSheet;
  $("#add").onclick = () => wsSend({ t: "admin-add", name: $("#np_name").value.trim(), user: $("#np_user").value.trim() });
  document.querySelectorAll("[data-reset]").forEach((b) => (b.onclick = () => wsSend({ t: "admin-reset", user: b.dataset.reset })));
  document.querySelectorAll("[data-rm]").forEach((b) => (b.onclick = () => { if (confirm(`Remove ${b.dataset.rm}? Their devices are signed out right away.`)) wsSend({ t: "admin-remove", user: b.dataset.rm }); }));
  document.querySelectorAll("[data-rmdev]").forEach((b) => (b.onclick = () => wsSend({ t: "admin-remove-device", id: b.dataset.rmdev })));
}

function showInvite(m) {
  S.inviteOpen = true;
  const msg = `You're invited to Beam!\nOpen ${location.origin} (or the Beam app), tap "I have an invite code", and enter:\nUsername: ${m.user}\nInvite code: ${m.code}\nThen choose your own PIN. The code works for 7 days.`;
  sheetOnClose = null;
  openSheet(`<h2>${m.reset ? "New code for " : "Invite "}${esc(m.name)}</h2>
    <p>Send ${esc(m.name)} their username and this code. They'll choose their own PIN.</p>
    <div class="list"><div class="item"><span>Username</span><b>${esc(m.user)}</b></div></div>
    <div class="code">${esc(m.code)}</div><p><small>Works once, for 7 days.</small></p>
    <div class="actions"><button class="btn ghost" id="cp">Copy message</button><button class="btn primary" id="ok">Done</button></div>`, () => { S.inviteOpen = false; if (S.adminOpen) { showPeople(); wsSend({ t: "admin-list" }); } });
  $("#cp").onclick = () => navigator.clipboard.writeText(msg).then(() => toast("Copied"));
  $("#ok").onclick = closeSheet;
}

// ---------- start ----------
(async function start() {
  if (!window.isSecureContext || !crypto.subtle || !window.RTCPeerConnection) {
    $("#empty").textContent = "This browser can't run Beam (needs HTTPS and WebRTC).";
    return;
  }
  await Id.load();
  // Drop files anywhere on the main screen to choose who gets them.
  const stage = document.querySelector(".stage");
  stage.ondragover = (e) => { if (!e.target.closest(".peer")) { e.preventDefault(); stage.classList.add("drop"); } };
  stage.ondragleave = () => stage.classList.remove("drop");
  stage.ondrop = (e) => {
    stage.classList.remove("drop");
    if (e.target.closest(".peer") || !S.user) return;
    e.preventDefault();
    const text = !(e.dataTransfer.files || []).length && (e.dataTransfer.getData("text/uri-list") || e.dataTransfer.getData("text/plain"));
    const entries = [...(e.dataTransfer.items || [])].map((i) => (i.kind === "file" && i.webkitGetAsEntry ? i.webkitGetAsEntry() : null));
    const plain = [...(e.dataTransfer.files || [])];
    (async () => {
      const files = entries.some(Boolean) ? await filesFromEntries(entries.filter(Boolean)) : plain;
      if (files.length || text) showRecipients(files, text || null);
    })();
  };
  const q = new URLSearchParams(location.search);
  const shared = [q.get("title"), q.get("text"), q.get("url")].filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join("\n");
  if (shared) { S.pending = { files: [], text: shared }; history.replaceState(null, "", location.pathname + (nativeKind ? "?app=" + nativeKind : "")); }
  if ("serviceWorker" in navigator && !native) navigator.serviceWorker.register("/sw.js").catch(() => {});
  // Opened on an iPhone from the Shortcut setup QR code: just the instructions, no sign-in.
  const sc = q.get("sc");
  if (sc && /^[A-Za-z0-9_-]{20,64}$/.test(sc)) { document.documentElement.classList.add("guest-mode"); render(); return shortcutSetup(sc); }
  render();
  connect();
})();

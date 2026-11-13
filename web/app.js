// Beam web client. Also runs inside the Mac app, which adds a native bridge
// (window.webkit.messageHandlers.beam) for notifications and saving to Downloads.
"use strict";

const CHUNK = 64 * 1024;
const HIGH_WATER = 4 * 1024 * 1024;
const OFFER_TIMEOUT = 120000;
// Native hosts: the Mac app (WKWebView) or the Windows app (WebView2).
const native = (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.beam) ||
  (window.chrome && window.chrome.webview && new URLSearchParams(location.search).get("app") === "windows" ? { postMessage: (o) => window.chrome.webview.postMessage(o) } : null);
const nativeKind = native ? (window.webkit ? "mac" : "windows") : null;
const revealLabel = nativeKind === "windows" ? "Show in folder" : "Show in Finder";
const $ = (s) => document.querySelector(s);

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
const S = {
  name: store.get("name", defaultName(kind)),
  nearby: store.get("nearby", true),
  autoAccept: store.get("autoAccept", true),
  paired: store.get("paired", {}), // id -> {pub, name, kind}
  peers: [],
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
    wsTimer = setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 30000);
  };
}
const wsSend = (o) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); };
setInterval(() => { if (ws && ws.readyState === 1) ws.send("ping"); }, 30000);
document.addEventListener("visibilitychange", () => { if (!document.hidden && (!ws || ws.readyState > 1)) { backoff = 1000; connect(); } });
window.addEventListener("online", () => { backoff = 1000; connect(); });

async function onServer(m) {
  switch (m.t) {
    case "challenge":
      wsSend({ t: "hello", pub: Id.pub, sig: await Id.sign("beam-hello:" + m.nonce), name: S.name, kind, nearby: S.nearby, watch: Object.keys(S.paired) });
      break;
    case "welcome":
      if (m.id !== Id.id) console.warn("server id mismatch");
      S.online = true;
      backoff = 1000;
      if (Array.isArray(m.ice) && m.ice.length) S.ice = m.ice;
      render();
      break;
    case "peers":
      S.peers = m.peers || [];
      // keep paired names fresh
      for (const p of S.peers) if (S.paired[p.id] && S.paired[p.id].pub === p.pub && S.paired[p.id].name !== p.name) { S.paired[p.id].name = p.name; savePaired(); }
      render();
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
    case "pair-error":
      if (S.pairing) { S.pairing.error = m.reason; showPairJoin(); }
      break;
  }
}
function updateServer() { wsSend({ t: "update", name: S.name, nearby: S.nearby, watch: Object.keys(S.paired) }); }

// ---------- signaling between devices ----------
const RESUME_WINDOW = 15 * 60 * 1000; // how long a dropped transfer waits to pick back up
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
  const items = files.map((f) => ({ name: cleanName(f.name), size: f.size, type: f.type || "application/octet-stream" }));
  const blobs = files.slice();
  if (text) {
    const b = new Blob([text], { type: "text/plain" });
    items.push({ name: "Text", size: b.size, type: "text/plain", text: true });
    blobs.push(b);
  }
  if (!items.length) return;
  const t = { tid: randId(), dir: "out", peer, items, blobs, total: items.reduce((a, b) => a + b.size, 0), done: 0, state: "preparing", gen: 0 };
  S.tx.set(t.tid, t);
  renderTx(t);
  await connectOut(t, "offer");
  setTimeout(() => { if (t.state === "waiting") { wsSend({ t: "signal", to: peer.id, data: { k: "cancel", tid: t.tid } }); fail(t, "No response"); } }, OFFER_TIMEOUT);
}

// Opens a new connection for a transfer: the first offer, or a resume after a drop.
async function connectOut(t, mode) {
  const gen = ++t.gen;
  closePc(t);
  const live = () => t.gen === gen && !["done", "failed"].includes(t.state);
  const pc = (t.pc = new RTCPeerConnection({ iceServers: S.ice }));
  const dc = (t.dc = pc.createDataChannel("beam", { ordered: true }));
  dc.binaryType = "arraybuffer";
  dc.bufferedAmountLowThreshold = 1024 * 1024;
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
  dc.onclose = () => { if (live()) dropped(t, gen); };
  pc.onconnectionstatechange = () => { if (live() && pc.connectionState === "failed") dropped(t, gen); };
  await pc.setLocalDescription(await pc.createOffer());
  await gathered(pc);
  if (!live()) return;
  const sdp = pc.localDescription.sdp;
  const data = { k: mode, tid: t.tid, gen, sdp, sig: await Id.sign(`beam-${mode}|${t.tid}|${t.peer.id}|${sdp}`), pub: Id.pub };
  if (mode === "offer") Object.assign(data, { name: S.name, kind, files: t.items });
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
  if (t.dir === "out") setTimeout(() => retry(t), 2000 + Math.random() * 1000);
  else setTimeout(() => { if (t.state === "paused" && Date.now() - t.pausedAt >= RESUME_WINDOW) fail(t, "Connection lost"); }, RESUME_WINDOW + 1000);
}

function retry(t) {
  if (t.state !== "paused") return;
  if (Date.now() - t.pausedAt > RESUME_WINDOW) return fail(t, "Connection lost");
  if (!S.online) return setTimeout(() => retry(t), 3000);
  t.state = "reconnecting";
  renderTx(t);
  connectOut(t, "resume").catch(() => dropped(t, t.gen));
}

// ---------- receiving ----------
async function onOffer(from, d) {
  if (S.tx.has(d.tid)) return;
  if (!(await trustedKey(from, d.pub))) return;
  if (!(await verifySig(d.pub, d.sig, `beam-offer|${d.tid}|${Id.id}|${d.sdp}`))) return;
  const items = (Array.isArray(d.files) ? d.files : []).slice(0, 500).map((f) => ({
    name: cleanName(f.name), size: Math.max(0, Number(f.size) || 0), type: String(f.type || "application/octet-stream").slice(0, 100), text: !!f.text,
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
  if (items.length === 1) return items[0].text ? "some text" : items[0].name;
  const photos = items.every((i) => /^image\//.test(i.type));
  return items.length + (photos ? " photos" : " files");
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
    if (!dir && gesture && t.total >= BIG) dir = await pickFolder().catch(() => null);
    if (dir) { t.sink = "fs"; t.dirHandle = dir; }
  }
  await answerConn(t, t.sdp, t.gen);
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
  wsSend({ t: "signal", to: t.peer.id, data: { k: "decline", tid: t.tid } });
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
        else t.received.push({ name: it.name, url: URL.createObjectURL(blob), size: it.size });
      },
      abort: () => { parts = []; },
    };
  }
  if (t.sink === "native") {
    const nid = t.tid + ":" + index;
    native.postMessage({ op: "saveBegin", id: nid, name: it.name });
    return {
      write: (b) => native.postMessage({ op: "saveChunk", id: nid, b64: b64(b) }),
      close: () => t.received.push({ name: it.name, nid }) && native.postMessage({ op: "saveEnd", id: nid }),
      abort: () => native.postMessage({ op: "saveAbort", id: nid }),
    };
  }
  // straight to disk
  let ws = null, name = it.name;
  const ready = (async () => {
    const dot = it.name.lastIndexOf(".");
    const base = dot > 0 ? it.name.slice(0, dot) : it.name, ext = dot > 0 ? it.name.slice(dot) : "";
    for (let n = 2; ; n++) {
      try { await t.dirHandle.getFileHandle(name); name = `${base} (${n})${ext}`; } catch { break; }
    }
    const fh = await t.dirHandle.getFileHandle(name, { create: true });
    ws = await fh.createWritable();
  })();
  return {
    write: async (b) => { await ready; await ws.write(b); },
    close: async () => { await ready; await ws.close(); t.received.push({ name, folder: t.dirHandle.name }); },
    abort: async () => { try { await ready; await ws.abort(); await t.dirHandle.removeEntry(name); } catch {} },
  };
}

function setupReceive(t, dc, gen) {
  const live = () => t.gen === gen && !["done", "failed"].includes(t.state);
  dc.binaryType = "arraybuffer";
  let sentFrom = false;
  const opened = () => {
    if (!live() || sentFrom) return;
    sentFrom = true;
    t.state = "receiving";
    t.pausedAt = null;
    renderTx(t);
    // Tell the sender where to start: the beginning, or wherever we got to before a drop.
    dc.send(JSON.stringify(t.curDone ? { t: "from", i: t.cur + 1, off: 0 } : { t: "from", i: t.cur, off: t.got }));
  };
  dc.onopen = opened;
  if (dc.readyState === "open") opened();
  dc.onclose = () => { if (live() && ["receiving", "reconnecting", "connecting"].includes(t.state)) dropped(t, gen); };
  // Counters update immediately (so a resume point is always right); disk writes run in order behind them.
  const later = (fn) => { t.q = t.q.then(fn).catch((e) => fail(t, e && e.message ? e.message : "Couldn't save")); };
  dc.onmessage = (e) => {
    if (!live()) return;
    if (typeof e.data === "string") {
      const m = JSON.parse(e.data);
      if (m.t === "file") {
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

function finish(t) {
  if (t.state === "done" || t.state === "failed") return;
  t.state = "done";
  renderTx(t);
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
  closePc(t);
  if (t.w) { const w = t.w; t.w = null; t.q = t.q.then(() => w.abort()).catch(() => {}); }
  renderTx(t);
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
  action(tag, act) {
    const t = S.tx.get(tag);
    if (!t) return;
    if (act === "accept") accept(t, false);
    else if (act === "decline") decline(t);
  },
  saved(id, path) {
    for (const t of S.tx.values()) for (const r of t.received) if (r.nid === id) { r.path = path; renderTx(t); }
  },
  saveFailed(id) {
    for (const t of S.tx.values()) if (t.dir === "in" && id.startsWith(t.tid + ":")) fail(t, "Couldn't save to Downloads");
  },
};
function nativeStatus() { if (native) native.postMessage({ op: "status", online: S.online, peers: S.peers.length }); }

// ---------- UI ----------
const ICONS = {
  phone: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M16 1H8a3 3 0 0 0-3 3v16a3 3 0 0 0 3 3h8a3 3 0 0 0 3-3V4a3 3 0 0 0-3-3zm1 19a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h1.5l.5 1h4l.5-1H16a1 1 0 0 1 1 1z"/></svg>',
  laptop: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M4 5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10h1.5a.5.5 0 0 1 .5.5v1A2.5 2.5 0 0 1 19.5 19h-15A2.5 2.5 0 0 1 2 16.5v-1a.5.5 0 0 1 .5-.5H4zm2 0v10h12V5z"/></svg>',
  desktop: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M3 4a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-5v2h2a1 1 0 1 1 0 2H8a1 1 0 1 1 0-2h2v-2H5a2 2 0 0 1-2-2zm2 0v11h14V4z"/></svg>',
  tablet: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M18 1H6a3 3 0 0 0-3 3v16a3 3 0 0 0 3 3h12a3 3 0 0 0 3-3V4a3 3 0 0 0-3-3zm1 19a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1zm-7-1.5a1 1 0 1 0 0-2 1 1 0 0 0 0 2z"/></svg>',
  globe: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm6.93 6h-2.95a15.7 15.7 0 0 0-1.38-3.56A8.03 8.03 0 0 1 18.93 8zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14a8.2 8.2 0 0 1 0-4h3.38a16.5 16.5 0 0 0 0 4zm.81 2h2.95c.32 1.25.78 2.45 1.38 3.56A8 8 0 0 1 5.07 16zM8.02 8H5.07a8 8 0 0 1 4.33-3.56A15.7 15.7 0 0 0 8.02 8zM12 19.96A14.1 14.1 0 0 1 10.09 16h3.82A14.1 14.1 0 0 1 12 19.96zM14.34 14H9.66a14.7 14.7 0 0 1 0-4h4.68a14.7 14.7 0 0 1 0 4zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95a8.03 8.03 0 0 1-4.33 3.56zM16.36 14a16.5 16.5 0 0 0 0-4h3.38a8.2 8.2 0 0 1 0 4z"/></svg>',
  file: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zm4 18H6V4h7v5h5z"/></svg>',
  text: '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M5 4v3h5.5v12h3V7H19V4z"/></svg>',
};
const iconFor = (k) => ({ android: ICONS.phone, iphone: ICONS.phone, mac: ICONS.laptop, windows: ICONS.desktop, chromebook: ICONS.laptop, ipad: ICONS.tablet })[k] || ICONS.globe;

function render() {
  document.body.classList.toggle("offline", !S.online);
  const box = $("#peers");
  const ids = new Set(S.peers.map((p) => p.id));
  for (const el of [...box.children]) if (!ids.has(el.dataset.id)) el.remove();
  for (const p of S.peers) {
    let el = box.querySelector(`[data-id="${p.id}"]`);
    const paired = !!S.paired[p.id] && S.paired[p.id].pub === p.pub;
    const html = `<div class="avatar">${iconFor(p.kind)}<svg class="ring" viewBox="0 0 82 82"><circle cx="41" cy="41" r="38" stroke-dasharray="238.8" stroke-dashoffset="238.8"/></svg></div>
      <div class="name">${esc(p.name)}</div><div class="tag ${paired ? "paired" : ""}">${paired ? "My device" : "Nearby"}</div>`;
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
        const peer = S.peers.find((x) => x.id === p.id);
        const files = [...(e.dataTransfer.files || [])];
        const text = !files.length && (e.dataTransfer.getData("text/uri-list") || e.dataTransfer.getData("text/plain"));
        if (peer && (files.length || text)) sendItems(peer, files, text || null);
      };
      box.appendChild(el);
    }
    if (el.dataset.html !== html) { el.innerHTML = html; el.dataset.html = html; }
  }
  $("#empty").style.display = S.peers.length ? "none" : "";
  $("#empty").innerHTML = S.online
    ? 'Looking for devices…<br><small>Open Beam on another device on this WiFi, or pair one in Settings.</small>'
    : "Connecting…";
  $("#me").innerHTML = S.pendingText ? "<b>Tap a device to send what you shared</b>" : `Visible as <b>${esc(S.name)}</b> · ${S.nearby ? "Everyone nearby" : "My devices only"}`;
  for (const t of S.tx.values()) if (t.dir === "out") renderRing(t);
  nativeStatus();
}

function renderRing(t) {
  const el = document.querySelector(`.peer[data-id="${t.peer.id}"] .ring circle`);
  if (!el) return;
  const active = ["waiting", "connecting", "sending", "finishing", "paused", "reconnecting"].includes(t.state);
  const frac = active ? (t.total ? t.done / t.total : 0) : 0;
  el.style.strokeDashoffset = String(238.8 * (1 - (t.state === "waiting" ? 0.04 : frac)));
}

const STATE_TEXT = { paused: "Connection lost — will resume", reconnecting: "Reconnecting…", preparing: "Preparing…", waiting: "Waiting for them to accept…", asking: "Wants to send you this", connecting: "Connecting…", sending: "Sending", receiving: "Receiving", finishing: "Finishing…", done: "Done", failed: "" };

function renderTxList() {
  const box = $("#transfers");
  const tids = new Set(S.tx.keys());
  for (const el of [...box.children]) if (!tids.has(el.dataset.tid)) el.remove();
  for (const t of [...S.tx.values()].reverse()) renderTx(t);
}

function renderTx(t) {
  const box = $("#transfers");
  let el = box.querySelector(`[data-tid="${t.tid}"]`);
  if (!S.tx.has(t.tid)) { if (el) el.remove(); return; }
  if (!el) {
    el = document.createElement("div");
    el.dataset.tid = t.tid;
    box.prepend(el);
  }
  el.className = "tx " + t.state;
  const what = describeItems(t.items);
  const title = (t.dir === "out" ? "To " : "From ") + t.peer.name;
  const sub = t.state === "failed" ? t.reason : t.state === "done" ? (t.dir === "out" ? "Sent " : "Received ") + what : STATE_TEXT[t.state] + " · " + what + (t.total ? " · " + fmtSize(t.total) : "");
  let body = "";
  if (["sending", "receiving", "paused", "reconnecting"].includes(t.state)) body += `<div class="bar"><i style="width:${t.total ? (100 * t.done) / t.total : 0}%"></i></div>`;
  if (t.received && t.received.length) {
    body += '<div class="files">';
    for (const r of t.received) {
      if (r.text) {
        const isUrl = /^https?:\/\/\S+$/.test(r.text.trim());
        body += `<div class="text">${esc(r.text)}</div><div class="row">` +
          (isUrl ? `<a class="btn primary small" href="${esc(r.text.trim())}" target="_blank" rel="noopener noreferrer">Open link</a>` : "<span></span>") +
          `<button class="btn ghost small" data-copy="${esc(r.text)}">Copy</button></div>`;
      } else if (r.url) {
        body += `<div class="file"><span>${esc(r.name)}</span><a class="btn ghost small" href="${r.url}" download="${esc(r.name)}">Save</a></div>`;
      } else if ("folder" in r) {
        body += `<div class="file"><span>${esc(r.name)}</span><span class="sub">Saved${r.folder ? " in " + esc(r.folder) : ""}</span></div>`;
      } else if (r.nid) {
        body += `<div class="file"><span>${esc(r.name)}</span>${r.path ? `<button class="btn ghost small" data-reveal="${esc(r.path)}">${revealLabel}</button>` : '<span class="sub">Saving…</span>'}</div>`;
      }
    }
    body += "</div>";
  }
  let btns = "";
  if (t.state === "asking") btns = `<button class="btn primary small" data-act="accept">Accept</button><button class="btn ghost small" data-act="decline">Decline</button>`;
  else if (!["done", "failed"].includes(t.state)) btns = `<button class="btn ghost small" data-act="cancel">Cancel</button>`;
  else btns = `<button class="btn ghost small" data-act="clear">✕</button>`;
  const html = `<div class="row"><div style="min-width:0"><div class="title">${esc(title)}</div><div class="sub">${esc(sub)}</div></div><div class="row">${btns}</div></div>${body}`;
  if (el.dataset.html !== html) {
    el.innerHTML = html;
    el.dataset.html = html;
    el.querySelectorAll("[data-act]").forEach((b) => (b.onclick = () => {
      const a = b.dataset.act;
      if (a === "accept") accept(t, true);
      else if (a === "decline") decline(t);
      else if (a === "cancel") cancelTx(t);
      else if (a === "clear") { for (const r of t.received || []) if (r.url) URL.revokeObjectURL(r.url); S.tx.delete(t.tid); renderTxList(); }
    }));
    el.querySelectorAll("[data-copy]").forEach((b) => (b.onclick = () => { navigator.clipboard.writeText(b.dataset.copy).then(() => toast("Copied")); }));
    el.querySelectorAll("[data-reveal]").forEach((b) => (b.onclick = () => native && native.postMessage({ op: "reveal", path: b.dataset.reveal })));
  }
  if (t.dir === "out") renderRing(t);
}

let progressRaf = 0;
function renderProgress(t) {
  if (progressRaf) return;
  progressRaf = requestAnimationFrame(() => {
    progressRaf = 0;
    const bar = document.querySelector(`[data-tid="${t.tid}"] .bar i`);
    if (bar) bar.style.width = (t.total ? (100 * t.done) / t.total : 0) + "%";
    if (t.dir === "out") renderRing(t);
  });
}

function toast(msg) {
  const d = document.createElement("div");
  d.className = "toast";
  d.textContent = msg;
  document.body.appendChild(d);
  setTimeout(() => d.remove(), 2200);
}

// ----- sheets -----
let sheetOnClose = null;
function openSheet(html, onClose) {
  $("#sheetBody").innerHTML = html;
  $("#sheet").hidden = false;
  sheetOnClose = onClose || null;
}
function closeSheet() {
  $("#sheet").hidden = true;
  $("#sheetBody").innerHTML = "";
  const f = sheetOnClose;
  sheetOnClose = null;
  if (f) f();
}
$("#sheet").addEventListener("click", (e) => { if (e.target.id === "sheet") closeSheet(); });

function chooseWhat(peerId) {
  const p = S.peers.find((x) => x.id === peerId);
  if (!p) return;
  if (S.pendingText) { const v = S.pendingText; S.pendingText = null; render(); return sendItems(p, [], v); }
  openSheet(`<h2>Send to ${esc(p.name)}</h2>
    <div class="big-choice">
      <button class="btn ghost" id="pickFiles">${ICONS.file}Files or photos</button>
      <button class="btn ghost" id="pickText">${ICONS.text}Text or link</button>
    </div>`);
  $("#pickFiles").onclick = () => {
    closeSheet();
    const inp = $("#filePick");
    inp.value = "";
    inp.onchange = () => { const peer = S.peers.find((x) => x.id === peerId); if (peer && inp.files.length) sendItems(peer, [...inp.files], null); };
    inp.click();
  };
  $("#pickText").onclick = () => {
    openSheet(`<h2>Send text to ${esc(p.name)}</h2><textarea id="txt" placeholder="Paste a link or type something"></textarea>
      <div class="actions"><button class="btn ghost" id="cx">Cancel</button><button class="btn primary" id="go">Send</button></div>`);
    $("#txt").focus();
    $("#cx").onclick = closeSheet;
    $("#go").onclick = () => { const v = $("#txt").value; const peer = S.peers.find((x) => x.id === peerId); closeSheet(); if (peer && v.trim()) sendItems(peer, [], v); };
  };
}

const prompts = new Set();
function promptIncoming(t) {
  prompts.add(t.tid);
  notify(t.tid, t.peer.name + " wants to send you " + describeItems(t.items), (t.total ? fmtSize(t.total) + " · " : "") + "Device " + shortId(t.peer.id), true);
  if (!$("#sheet").hidden) return; // the transfer card has Accept/Decline too
  const list = t.items.slice(0, 6).map((i) => `<div class="item"><span>${esc(i.text ? "Text" : i.name)}</span><span class="sub">${i.text ? "" : fmtSize(i.size)}</span></div>`).join("") +
    (t.items.length > 6 ? `<div class="item"><span class="sub">and ${t.items.length - 6} more</span></div>` : "");
  openSheet(`<h2>${esc(t.peer.name)}</h2><p>wants to send you ${esc(describeItems(t.items))}${t.total ? " (" + fmtSize(t.total) + ")" : ""}</p>
    <p><small>Device ${shortId(t.peer.id)}${t.peer.paired ? " · My device" : ""}</small></p>
    <div class="list">${list}</div>${sizeNote(t)}
    <div class="actions"><button class="btn ghost" id="dec">Decline</button><button class="btn primary" id="acc">Accept</button></div>`);
  $("#sheet").dataset.tid = t.tid;
  $("#acc").onclick = () => accept(t, true);
  $("#dec").onclick = () => decline(t);
}
// Browsers without save-to-disk keep the whole file in memory until you tap Save.
function sizeNote(t) {
  if (native) return "";
  if (canSaveToDisk) return t.total >= BIG ? `<p><small>Large transfer: you'll pick a folder and it saves straight to disk.</small></p>` : "";
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
  openSheet(`<h2>Settings</h2>
    <h3>This device</h3>
    <input type="text" id="nm" maxlength="40" value="${esc(S.name)}">
    <label class="opt"><span>Everyone nearby can see me<small>Off = only your paired devices</small></span><input type="checkbox" class="toggle" id="nb" ${S.nearby ? "checked" : ""}></label>
    <label class="opt"><span>Auto-accept from my devices<small>Files from paired devices arrive without asking</small></span><input type="checkbox" class="toggle" id="aa" ${S.autoAccept ? "checked" : ""}></label>
    ${!native && "Notification" in window && Notification.permission !== "granted" ? '<label class="opt"><span>Notifications<small>Alerts when this tab is in the background</small></span><button class="btn ghost small" id="nt">Enable</button></label>' : ""}
    ${canSaveToDisk ? `<label class="opt"><span>Save received files to<small>${store.get("saveDirName", null) ? esc(store.get("saveDirName")) + " (any size)" : "Downloads via Save buttons · big transfers ask for a folder"}</small></span><button class="btn ghost small" id="sf">Choose…</button></label>` : ""}
    <p><small>Device code ${shortId(Id.id)}</small></p>
    <h3>My devices</h3>
    <p>Paired devices find each other anywhere, not just on the same WiFi.</p>
    <div class="list">${paired.map(([id, p]) => `<div class="item"><span>${esc(p.name)} <span class="sub">${shortId(id)}</span></span><button class="btn danger small" data-unpair="${id}">Remove</button></div>`).join("") || '<div class="item"><span class="sub">None yet</span></div>'}</div>
    <div class="actions"><button class="btn ghost" id="pShow">Show a code</button><button class="btn ghost" id="pJoin">Enter a code</button></div>
    <div class="actions"><button class="btn primary" id="done">Done</button></div>`, saveSettings);
  $("#done").onclick = closeSheet;
  $("#pShow").onclick = () => { saveSettings(); S.pairing = { mode: "show" }; wsSend({ t: "pair-code" }); showPairShow(); };
  $("#pJoin").onclick = () => { saveSettings(); S.pairing = { mode: "join" }; showPairJoin(); };
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
function saveSettings() {
  const nm = $("#nm"), nb = $("#nb"), aa = $("#aa");
  if (!nm) return;
  const name = nm.value.trim().slice(0, 40) || S.name;
  const changed = name !== S.name || nb.checked !== S.nearby;
  S.name = name; S.nearby = nb.checked; S.autoAccept = aa.checked;
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

// ---------- start ----------
(async function start() {
  if (!window.isSecureContext || !crypto.subtle || !window.RTCPeerConnection) {
    $("#empty").textContent = "This browser can't run Beam (needs HTTPS and WebRTC).";
    return;
  }
  await Id.load();
  const q = new URLSearchParams(location.search);
  const shared = [q.get("title"), q.get("text"), q.get("url")].filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join("\n");
  if (shared) { S.pendingText = shared; history.replaceState(null, "", location.pathname + (nativeKind ? "?app=" + nativeKind : "")); }
  if ("serviceWorker" in navigator && !native) navigator.serviceWorker.register("/sw.js").catch(() => {});
  render();
  connect();
})();

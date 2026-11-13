# Beam

An AirDrop-style way to send files, photos, text and links between your devices.

- **Web app**: https://beam.wahromyk.workers.dev works on any device with a modern browser (iPhone, Windows, Chromebook…). You can install it as an app from the browser menu.
- **Android app**: runs in the background, appears in the share sheet, and asks to accept from a notification. Download: `https://github.com/<you>/beam/releases/download/android/beam.apk`
- **Windows app**: lives in the system tray, starts with Windows, shows Accept/Decline notifications, and saves to Downloads. Download: `https://github.com/<you>/beam/releases/download/windows/Beam.exe`
- **Mac app**: lives in the menu bar, stays reachable in the background, and saves straight to Downloads. Download: `https://github.com/<you>/beam/releases/download/mac/Beam-mac.zip`

## How it finds devices

- **Nearby**: devices on the same WiFi (same public internet address) see each other automatically, like AirDrop's "Everyone".
- **My devices**: pair once (Settings → Show a code / Enter a code, then check both screens show the same 6-digit number). Paired devices see each other anywhere, even on different networks, and can skip the Accept prompt.

## Security

- Files go **directly between devices** over a WebRTC data channel, encrypted with DTLS. The Cloudflare server only introduces devices and never sees file contents.
- Each device has its own P-256 key (Android Keystore, or a non-exportable WebCrypto key). Its device ID is a hash of that key, and the server makes every device prove it owns its key before it can appear.
- Each side **signs** its connection offer/answer, which includes the encryption fingerprint. So the server can't swap in its own keys to read a transfer.
- Pairing shows a 6-digit safety number on both screens. If the server tried to substitute a key, the numbers wouldn't match. After pairing, a paired device's key is pinned.
- Nothing is accepted without your OK, except from paired devices with auto-accept on (you can turn that off).

## Layout

| Folder | What it is |
|---|---|
| `server/` | Cloudflare Worker + Durable Object: presence, signaling relay, pairing codes, optional TURN |
| `web/` | The web app (also what runs inside the Mac app) |
| `android/` | Native Android app (Java, Google's WebRTC library, OkHttp) |
| `mac/` | Swift menu-bar app wrapping the web app, with notifications and Downloads saving |
| `windows/` | C# tray app (WebView2) wrapping the web app, with toast notifications and Downloads saving |

GitHub Actions builds and publishes everything on push to `main`.

## Secrets (repository settings → Secrets and variables → Actions)

| Secret | Used for |
|---|---|
| `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` | Deploying the server and web app |
| `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD` | Signing the APK (key alias defaults to `weather`; set the `ANDROID_KEY_ALIAS` variable if yours differs) |
| `TURN_KEY_ID`, `TURN_KEY_API_TOKEN` *(optional)* | Cloudflare Realtime TURN relay, for networks where devices can't connect directly (e.g. some phone carriers) |

Set the `BEAM_URL` variable if the server lives somewhere other than `https://beam.wahromyk.workers.dev`.

## Protocol (for anyone adding a client)

Server WebSocket `/ws`: server sends `{t:"challenge", nonce}`; client replies `{t:"hello", pub, sig, name, kind, nearby, watch}` where `pub` is base64 SPKI, `sig` is a raw (r‖s) ECDSA-P256-SHA256 signature over `beam-hello:<nonce>`, and `watch` lists paired device IDs. Device ID = first 32 hex chars of SHA-256(SPKI).

Signals relayed as `{t:"signal", to, data}`:
- `offer {tid, sdp, sig, pub, name, kind, files:[{name,size,type,text?}]}`, signed over `beam-offer|<tid>|<receiverId>|<sdp>`
- `answer {tid, sdp, sig, pub}`, signed over `beam-answer|<tid>|<senderId>|<sdp>`
- `decline`, `cancel`, `pair`, `pair-ok`, `pair-no`

Data channel `beam`: `{t:"file",i}` → binary chunks (64 KB) → `{t:"eof",i}` … `{t:"done"}`; the receiver answers `{t:"ok"}`. Either side may send `{t:"cancel"}`.

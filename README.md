# Beam

An AirDrop-style way to send files, photos, text and links between your devices.

- **Web app**: https://beam.wahromyk.workers.dev works on any device with a modern browser (iPhone, Windows, Chromebook…). You can install it as an app from the browser menu.
- **Android app**: saves received photos to the gallery (Pictures/Beam), videos to the gallery (Movies/Beam) and everything else to Files (Downloads/Beam). It runs in the background, appears in the share sheet, and asks to accept from a notification. Download: `https://github.com/<you>/beam/releases/download/android/beam.apk`
- **Windows app**: lives in the system tray, starts with Windows, shows Accept/Decline notifications, and saves to Downloads. Download: `https://github.com/<you>/beam/releases/download/windows/Beam.exe`
- **Progress card**: while the Mac or Windows app is sending or receiving and its window isn't in front, a small card in the lower-right corner of the screen shows the progress. Clicking it opens Beam.
- **Mac app**: lives in the menu bar, stays reachable in the background, and saves straight to Downloads. Download: `https://github.com/<you>/beam/releases/download/mac/Beam-mac.zip`

## How it finds devices

- **Nearby**: devices on the same WiFi (same public internet address) see each other automatically, like AirDrop's "Everyone".
- **My devices**: pair once (Settings → Show a code / Enter a code, then check both screens show the same 6-digit number). Paired devices see each other anywhere, even on different networks, and can skip the Accept prompt.

## One engine, two routes

Every device shows up once in the list, however Beam can reach it, and Beam picks the route:

- **Internet route** (WebRTC): devices on the same WiFi, or anywhere for paired devices. Works on every platform.
- **Direct route** (Android ↔ Android): phones find each other over Bluetooth Low Energy and send over a direct Wi-Fi link via Google's Nearby Connections, with no router or internet needed. Used when the other phone is right next to you but not on your network.

Both routes speak the same protocol (`from` / `file` / `eof` / `done`), so a transfer can start on one and finish on the other. If the route drops mid-transfer, the sender reconnects over whichever route can reach the other device, preferring the direct one, and carries on from the last byte received (for up to 15 minutes).

On the direct route each side signs Nearby's link token with its Beam key (`beam-nearby|<token hex>|<deviceId>`), so a paired device can't be impersonated and nobody can sit in the middle. Phones set to "my devices only" only show up for their paired devices.

## Accounts

Only people the admin adds can use Beam. The first person to open Beam enters the one-time **setup code** (the `BEAM_SETUP_CODE` repository secret) and becomes the admin. From Settings → Manage people, the admin adds someone (name + username) and gets an invite code for them; they enter their username + code on their first device and choose their own PIN. Further devices sign in with username + PIN and stay signed in.

- Devices on the same account see each other anywhere and link up automatically (keys pinned like a pairing).
- PINs are stored as salted PBKDF2 hashes. Five wrong PINs from one network lock that network out for 15 minutes; 25 wrong tries from anywhere in an hour lock the account for the rest of the hour.
- The admin can reset a PIN (new invite code), sign out a single device, or remove a person; removed devices are disconnected immediately.
- A device that isn't signed in can't see anyone or be seen.

## Updates

- Server and web app: live as soon as a change is pushed. The Mac and Windows apps load their interface from the server, so most changes reach them instantly too.
- Each build publishes `android.json`, `mac.json` and `windows.json` (version + SHA-256) next to its download.
- Windows and Mac: the app checks every 6 hours, downloads and verifies the new version in the background, and swaps it in with a quick restart when no transfer is running. Tray / menu bar → "Check for updates" checks now.
- Android: an "Update available" notification; tapping it downloads, verifies and installs over the old version (Android asks you to confirm).

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
| `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD` | Signing the APK (key alias `beam`) |
| `WINDOWS_SIGN_PFX_B64`, `WINDOWS_SIGN_PASSWORD` | Signing Beam's small Windows identity package, which puts Beam in the Windows Share window (certificate `CN=Beam Share`; its public half is `windows/identity/beam-share.cer`) |
| `TURN_KEY_ID`, `TURN_KEY_API_TOKEN` *(optional)* | Cloudflare Realtime TURN relay, for networks where devices can't connect directly (e.g. some phone carriers) |

Set the `BEAM_URL` variable if the server lives somewhere other than `https://beam.wahromyk.workers.dev`.

## Protocol (for anyone adding a client)

Server WebSocket `/ws`: server sends `{t:"challenge", nonce}`; client replies `{t:"hello", pub, sig, name, kind, nearby, watch}` where `pub` is base64 SPKI, `sig` is a raw (r‖s) ECDSA-P256-SHA256 signature over `beam-hello:<nonce>`, and `watch` lists paired device IDs. Device ID = first 32 hex chars of SHA-256(SPKI).

Signals relayed as `{t:"signal", to, data}`:
- `offer {tid, sdp, sig, pub, name, kind, files:[{name,size,type,text?}]}`, signed over `beam-offer|<tid>|<receiverId>|<sdp>`
- `answer {tid, sdp, sig, pub}`, signed over `beam-answer|<tid>|<senderId>|<sdp>`
- `resume {tid, gen, sdp, sig, pub}`, signed over `beam-resume|<tid>|<receiverId>|<sdp>`: the sender reconnecting after a drop (allowed for 15 minutes)
- `complete {tid}`: the receiver already has everything
- `decline`, `cancel`, `pair`, `pair-ok`, `pair-no`

Data channel `beam`: on open the receiver sends `{t:"from", i, off}` (where to start: `0,0` for a new transfer, or the file and byte it reached before a drop). The sender then sends `{t:"file", i, off}` → binary chunks (64 KB) → `{t:"eof", i}` … `{t:"done"}`; the receiver answers `{t:"ok"}`. Either side may send `{t:"cancel"}`.

## Size limits

- Android, Mac and Windows apps write to disk as data arrives: any size.
- Chrome and Edge tabs save straight into a folder you pick (asked once, for transfers over 100 MB): any size.
- Safari and Firefox tabs hold the file in memory until you tap Save: roughly 1–2 GB on a computer, a few hundred MB on a phone.
- A dropped connection resumes from the last byte received, for up to 15 minutes.

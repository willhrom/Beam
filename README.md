# Beam

An AirDrop-style way to send files, photos, text and links between your devices.

- **Web app**: works on any device with a modern browser (iPhone, Windows, Chromebook…). You can install it as an app from the browser menu.
- **Android app**: runs in the background, appears in the share sheet, and asks to accept from a notification. Photos and videos go to the gallery; other files go to Downloads/Beam or a folder you pick in Settings.
- **Windows app**: lives in the system tray, starts with Windows, appears in the Share window and right-click menu, and saves to Downloads or a folder you pick. iPhone photos (HEIC) and videos (HEVC) can be converted to JPG and MP4 as they arrive.
- **Mac app**: lives in the menu bar, stays reachable in the background, and saves to Downloads or a folder you pick.
- **Progress card**: while the Mac or Windows app is sending or receiving and its window isn't in front, a small card in the lower-right corner shows the progress.

Downloads are on this repository's **Releases** page. The apps keep themselves up to date.

## How it finds devices

- **Nearby**: devices on the same WiFi see each other automatically, like AirDrop's "Everyone".
- **My devices**: devices signed in to the same account see each other anywhere. You can also pair with someone else's device (Settings → Show a code / Enter a code, then check both screens show the same 6-digit number).

## One engine, two routes

Every device shows up once in the list, however Beam can reach it, and Beam picks the route:

- **Internet route** (WebRTC): works on every platform. When a network blocks direct connections, transfers go through an encrypted relay.
- **Direct route** (Android ↔ Android): phones find each other over Bluetooth and send over a direct Wi-Fi link, with no router or internet needed.

Both routes speak the same protocol, so a transfer can start on one and finish on the other. If a connection drops mid-transfer, Beam reconnects and carries on from the last byte received.

## Accounts

Only people the admin adds can use Beam. The admin adds someone from Settings → Manage people and gives them an invite code; they choose their own PIN on their first device. Further devices sign in with username and PIN and stay signed in.

- Devices on the same account find each other anywhere and link up automatically.
- PINs are stored as salted hashes, and repeated wrong PINs lock sign-in for a while.
- The admin can reset a PIN, sign out a device, or remove a person.
- When a device signs in to an account, that account's other devices are alerted.
- A device that isn't signed in can't see anyone or be seen.

## Privacy and security

- Files go **directly between devices** over an encrypted WebRTC connection. The server only introduces devices; it never sees file contents. The relay, when used, only passes along encrypted data.
- Each device has its own key. The server makes every device prove it owns its key, and each side signs its connection details, so the server can't swap in its own keys to read a transfer.
- Pairing shows a safety number on both screens; if anything tried to substitute a key, the numbers wouldn't match.
- Nothing is accepted without your OK, except from your own devices with auto-accept on (you can turn that off).
- The server can see device names, who sends to whom, and file names and sizes. Never file contents.
- Transfer history is kept only on each device.

## Features

- **Folders and batches**: send whole folders (the structure is recreated on the other side) or up to 5,000 files at once.
- **Several devices**: tick any number of devices and send once.
- **Any size**: the Android, Mac and Windows apps and Chrome/Edge write to disk as data arrives. Safari and Firefox tabs hold the file in memory until you tap Save (roughly 1–2 GB on a computer, a few hundred MB on a phone).
- **Settings → Test connection** checks the server, direct connections, the relay and your devices.
- **Settings → Transfer history** shows the last 200 transfers on that device.

## Layout

| Folder | What it is |
|---|---|
| `server/` | Cloudflare Worker: sign-in, finding devices, passing connection details |
| `web/` | The web app (also what runs inside the Mac and Windows apps) |
| `android/` | Native Android app (Java) |
| `mac/` | Swift menu-bar app |
| `windows/` | C# tray app (WebView2) |

GitHub Actions builds and publishes everything on push to `main`. Deployment keys are kept in the repository's private settings, never in the code.

// Beam for Mac: a menu-bar app that keeps the Beam web app running in the background,
// so other devices can send to this Mac at any time. Files are saved straight to Downloads.
import Cocoa
import WebKit
import UserNotifications
import ServiceManagement
import UniformTypeIdentifiers
import CryptoKit

let serverURL = URL(string: "__BEAM_URL__")!
let repo = "__REPO__"

/// Keeps Beam up to date: checks the latest release every few hours, downloads it in the
/// background, and swaps it in (a quick relaunch) when no transfer is running.
final class Updater {
  weak var app: AppDelegate?
  var ready: URL?          // the new Beam.app, unpacked and waiting
  var readyVersion = 0
  var checking = false
  static var current: Int { Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0 }

  init(_ app: AppDelegate) { self.app = app }

  func start() {
    DispatchQueue.main.asyncAfter(deadline: .now() + 30) { self.check(loud: false) }
    Timer.scheduledTimer(withTimeInterval: 6 * 3600, repeats: true) { _ in self.check(loud: false) }
    Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { _ in self.tryApply() }
  }

  func say(_ text: String) { app?.notify(tag: "update", title: "Beam", body: text, request: false) }

  func check(loud: Bool) {
    guard !checking, !repo.hasPrefix("__"),
          let url = URL(string: "https://github.com/\(repo)/releases/download/mac/mac.json") else { return }
    checking = true
    URLSession.shared.dataTask(with: url) { data, _, _ in
      DispatchQueue.main.async {
        defer { self.checking = false }
        guard let data = data, let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = j["version"] as? Int, let zip = (j["url"] as? String).flatMap(URL.init(string:)),
              let sha = (j["sha256"] as? String)?.lowercased() else {
          if loud { self.say("Couldn't check for updates right now.") }
          return
        }
        if version <= Updater.current || version <= self.readyVersion {
          if loud { self.say(version <= Updater.current ? "Beam is up to date." : "An update is ready and will install shortly.") }
          return
        }
        self.download(zip, sha: sha, version: version, loud: loud)
      }
    }.resume()
  }

  func download(_ zip: URL, sha: String, version: Int, loud: Bool) {
    checking = true
    URLSession.shared.downloadTask(with: zip) { file, _, _ in
      defer { DispatchQueue.main.async { self.checking = false } }
      guard let file = file, let data = try? Data(contentsOf: file, options: .mappedIfSafe) else { return }
      let got = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
      guard got == sha else { DispatchQueue.main.async { if loud { self.say("The update download was damaged. It'll try again later.") } }; return }
      // Unpack next to the installed app (same disk), so the swap is a simple rename.
      let fm = FileManager.default
      let installed = Bundle.main.bundleURL
      guard let work = try? fm.url(for: .itemReplacementDirectory, in: .userDomainMask, appropriateFor: installed, create: true) else { return }
      let zipCopy = work.appendingPathComponent("Beam-mac.zip")
      try? fm.removeItem(at: zipCopy)
      guard (try? fm.copyItem(at: file, to: zipCopy)) != nil else { return }
      let p = Process()
      p.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
      p.arguments = ["-x", "-k", zipCopy.path, work.path]
      guard (try? p.run()) != nil else { return }
      p.waitUntilExit()
      let newApp = work.appendingPathComponent("Beam.app")
      guard p.terminationStatus == 0, fm.fileExists(atPath: newApp.path) else { return }
      DispatchQueue.main.async {
        self.ready = newApp
        self.readyVersion = version
        if loud { self.say("Updating to version 1.0.\(version)…") }
        self.tryApply()
      }
    }.resume()
  }

  /// Swap in the new version once nothing is being sent or received, then relaunch.
  func tryApply() {
    guard let newApp = ready, let app = app, !app.busy else { return }
    let installed = Bundle.main.bundleURL
    guard FileManager.default.isWritableFile(atPath: installed.deletingLastPathComponent().path) else {
      ready = nil
      say("Move Beam to your Applications folder so it can update itself.")
      return
    }
    do {
      _ = try FileManager.default.replaceItemAt(installed, withItemAt: newApp)
    } catch {
      ready = nil
      return
    }
    ready = nil
    let relaunch = Process()
    relaunch.executableURL = URL(fileURLWithPath: "/bin/sh")
    relaunch.arguments = ["-c", "sleep 1; /usr/bin/open \"\(installed.path)\""]
    try? relaunch.run()
    NSApp.terminate(nil)
  }
}

// Lets the page read a shared Finder file piece by piece (so big videos never load all at once).
final class FileReader: NSObject, WKScriptMessageHandlerWithReply {
  weak var app: AppDelegate?
  init(_ app: AppDelegate) { self.app = app }
  func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage, replyHandler: @escaping (Any?, String?) -> Void) {
    guard m.frameInfo.isMainFrame, m.frameInfo.securityOrigin.host == serverURL.host,
          let d = m.body as? [String: Any], let fid = d["fid"] as? String, let url = app?.shared[fid],
          let off = (d["off"] as? NSNumber)?.uint64Value, let len = (d["len"] as? NSNumber)?.intValue, len > 0, len <= 8 << 20
    else { replyHandler(nil, "bad request"); return }
    DispatchQueue.global(qos: .userInitiated).async {
      do {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        try h.seek(toOffset: off)
        let data = try h.read(upToCount: len) ?? Data()
        let b64 = data.base64EncodedString()
        DispatchQueue.main.async { replyHandler(b64, nil) }
      } catch {
        DispatchQueue.main.async { replyHandler(nil, "read failed") }
      }
    }
  }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, WKScriptMessageHandler, WKUIDelegate,
  WKNavigationDelegate, UNUserNotificationCenterDelegate, NSDraggingDestination {
  var shared: [String: URL] = [:]   // files handed to Beam from Finder, by id
  var busy = false                   // a transfer is running (don't restart for an update)
  lazy var updater = Updater(self)
  var pendingShare: [URL] = []
  var pageReady = false
  var statusItem: NSStatusItem!
  var window: NSWindow!
  var web: WKWebView!
  var saves: [String: (handle: FileHandle, url: URL)] = [:]
  var activity: NSObjectProtocol?
  var online = false
  var loginItem: NSMenuItem!

  func applicationDidFinishLaunching(_ n: Notification) {
    // Stop macOS from napping the app, so it stays reachable.
    activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiatedAllowingIdleSystemSleep], reason: "Stay reachable for Beam")
    buildMainMenu()
    buildWindow()
    buildStatusItem()
    let center = UNUserNotificationCenter.current()
    center.delegate = self
    let accept = UNNotificationAction(identifier: "accept", title: "Accept", options: [])
    let decline = UNNotificationAction(identifier: "decline", title: "Decline", options: [.destructive])
    center.setNotificationCategories([UNNotificationCategory(identifier: "request", actions: [accept, decline], intentIdentifiers: [], options: [])])
    center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    if !UserDefaults.standard.bool(forKey: "launchedBefore") {
      UserDefaults.standard.set(true, forKey: "launchedBefore")
      try? SMAppService.mainApp.register()
      showWindow()
    }
    updateLoginItem()
    // Finder: right-click → Services (or Quick Actions) → "Send with Beam"
    NSApp.servicesProvider = self
    NSUpdateDynamicServices()
    updater.start()
  }

  // MARK: - Sending files from Finder

  /// Finder → Services → "Send with Beam"
  @objc func sendFiles(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
    let urls = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
    share(urls)
  }

  /// Finder → Open With → Beam, or files dropped on the app
  func application(_ application: NSApplication, open urls: [URL]) { share(urls) }

  // Files dropped on the menu bar icon
  func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { .copy }
  func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation { .copy }
  func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
    let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
    share(urls)
    return !urls.isEmpty
  }

  func share(_ urls: [URL]) {
    let files = urls.filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
    guard !files.isEmpty else { return }
    showWindow()
    if !pageReady { pendingShare += files; return }
    var list: [[String: Any]] = []
    for u in files {
      let fid = UUID().uuidString
      shared[fid] = u
      let size = (try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
      let type = UTType(filenameExtension: u.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
      list.append(["fid": fid, "name": u.lastPathComponent, "size": size, "type": type])
    }
    if let data = try? JSONSerialization.data(withJSONObject: list), let json = String(data: data, encoding: .utf8) {
      js("beamNative.share(\(json))")
    }
  }

  func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
    pageReady = true
    if !pendingShare.isEmpty {
      let p = pendingShare
      pendingShare = []
      DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.share(p) }
    }
  }

  func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
    showWindow()
    return true
  }

  // MARK: - UI

  func buildMainMenu() {
    // Needed so copy/paste work in text fields even though the app has no menu bar of its own.
    let main = NSMenu()
    let appItem = NSMenuItem()
    let appMenu = NSMenu()
    appMenu.addItem(withTitle: "Close Window", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
    appMenu.addItem(withTitle: "Quit Beam", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
    appItem.submenu = appMenu
    main.addItem(appItem)
    let editItem = NSMenuItem()
    let edit = NSMenu(title: "Edit")
    edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
    edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
    edit.addItem(.separator())
    edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
    edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
    edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
    edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
    editItem.submenu = edit
    main.addItem(editItem)
    NSApp.mainMenu = main
  }

  func buildWindow() {
    let config = WKWebViewConfiguration()
    config.userContentController.add(self, name: "beam")
    config.userContentController.addScriptMessageHandler(FileReader(self), contentWorld: .page, name: "beamFile")
    config.websiteDataStore = .default()
    if #available(macOS 14.0, *) {
      config.preferences.inactiveSchedulingPolicy = .none // keep running while hidden
    }
    web = WKWebView(frame: NSRect(x: 0, y: 0, width: 440, height: 680), configuration: config)
    web.uiDelegate = self
    web.navigationDelegate = self
    web.setValue(false, forKey: "drawsBackground")
    window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 440, height: 680),
                      styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                      backing: .buffered, defer: false)
    window.title = "Beam"
    window.titlebarAppearsTransparent = true
    window.backgroundColor = NSColor(red: 0.043, green: 0.051, blue: 0.071, alpha: 1)
    window.appearance = NSAppearance(named: .darkAqua)
    window.isReleasedWhenClosed = false
    window.minSize = NSSize(width: 340, height: 480)
    window.delegate = self
    window.contentView = web
    window.center()
    window.setFrameAutosaveName("BeamMain")
    web.load(URLRequest(url: URL(string: serverURL.absoluteString + "/?app=mac")!))
  }

  func buildStatusItem() {
    statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    if let b = statusItem.button {
      let img = NSImage(systemSymbolName: "dot.radiowaves.left.and.right", accessibilityDescription: "Beam")
      img?.isTemplate = true
      b.image = img
      b.appearsDisabled = true
    }
    let menu = NSMenu()
    menu.addItem(withTitle: "Open Beam", action: #selector(openFromMenu), keyEquivalent: "")
    menu.addItem(.separator())
    loginItem = menu.addItem(withTitle: "Open at Login", action: #selector(toggleLogin), keyEquivalent: "")
    menu.addItem(withTitle: "Reload", action: #selector(reload), keyEquivalent: "")
    menu.addItem(withTitle: "Check for Updates…", action: #selector(checkUpdates), keyEquivalent: "")
    let ver = menu.addItem(withTitle: "Version 1.0.\(Updater.current)", action: nil, keyEquivalent: "")
    ver.isEnabled = false
    menu.addItem(.separator())
    menu.addItem(withTitle: "Quit Beam", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
    for item in menu.items where item.action != #selector(NSApplication.terminate(_:)) { item.target = self }
    statusItem.menu = menu
    // Accept files dropped onto the menu bar icon.
    statusItem.button?.window?.registerForDraggedTypes([.fileURL])
    statusItem.button?.window?.delegate = self
  }

  @objc func openFromMenu() { showWindow() }
  @objc func reload() { web.reload() }
  @objc func checkUpdates() { updater.check(loud: true) }

  @objc func toggleLogin() {
    do {
      if SMAppService.mainApp.status == .enabled { try SMAppService.mainApp.unregister() } else { try SMAppService.mainApp.register() }
    } catch {
      SMAppService.openSystemSettingsLoginItems()
    }
    updateLoginItem()
  }

  func updateLoginItem() {
    loginItem?.state = SMAppService.mainApp.status == .enabled ? .on : .off
  }

  func showWindow() {
    NSApp.activate(ignoringOtherApps: true)
    window.makeKeyAndOrderFront(nil)
  }

  // Closing the window only hides it; Beam keeps running in the menu bar.
  func windowShouldClose(_ sender: NSWindow) -> Bool {
    guard sender === window else { return true }
    window.orderOut(nil)
    return false
  }

  // MARK: - Web page permissions

  func isOurs(_ url: URL?) -> Bool {
    guard let u = url else { return false }
    return u.scheme == serverURL.scheme && u.host == serverURL.host
  }

  func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
    let url = action.request.url
    if url?.scheme == "blob" || url?.scheme == "about" || isOurs(url) {
      decisionHandler(.allow)
    } else {
      if let u = url, ["http", "https", "mailto"].contains(u.scheme ?? "") { NSWorkspace.shared.open(u) }
      decisionHandler(.cancel)
    }
  }

  func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
    if let u = action.request.url, ["http", "https"].contains(u.scheme ?? "") { NSWorkspace.shared.open(u) }
    return nil
  }

  func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping ([URL]?) -> Void) {
    let panel = NSOpenPanel()
    panel.allowsMultipleSelection = parameters.allowsMultipleSelection
    panel.canChooseFiles = true
    panel.canChooseDirectories = false
    NSApp.activate(ignoringOtherApps: true)
    panel.begin { r in completionHandler(r == .OK ? panel.urls : nil) }
  }

  func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { retryLoad() }
  func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { retryLoad() }
  func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { pageReady = false; webView.reload() }

  func retryLoad() {
    DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
      guard let self = self, !self.online else { return }
      self.web.reload()
    }
  }

  // MARK: - Bridge from the page

  func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
    guard m.frameInfo.isMainFrame, m.frameInfo.securityOrigin.host == serverURL.host,
          let d = m.body as? [String: Any], let op = d["op"] as? String else { return }
    let id = d["id"] as? String ?? ""
    switch op {
    case "status":
      online = d["online"] as? Bool ?? false
      busy = d["busy"] as? Bool ?? false
      statusItem.button?.appearsDisabled = !online
      statusItem.button?.toolTip = online ? "Beam — \(d["peers"] as? Int ?? 0) devices nearby" : "Beam — connecting…"
    case "notify":
      notify(tag: d["tag"] as? String ?? "", title: d["title"] as? String ?? "Beam", body: d["body"] as? String ?? "", request: d["actions"] as? Bool ?? false)
    case "unnotify":
      if let tag = d["tag"] as? String { UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [tag]) }
    case "show":
      showWindow()
    case "saveBegin":
      startSave(id: id, name: d["name"] as? String ?? "file")
    case "saveChunk":
      guard let s = saves[id], let b64 = d["b64"] as? String, let data = Data(base64Encoded: b64) else { return }
      do { try s.handle.write(contentsOf: data) } catch { abortSave(id: id); js("beamNative.saveFailed(\(q(id)))") }
    case "saveEnd":
      guard let s = saves.removeValue(forKey: id) else { return }
      try? s.handle.close()
      js("beamNative.saved(\(q(id)), \(q(s.url.path)))")
    case "saveAbort":
      abortSave(id: id)
    case "reveal":
      if let p = d["path"] as? String {
        let u = URL(fileURLWithPath: p)
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        if u.standardizedFileURL.path.hasPrefix(downloads.standardizedFileURL.path) { NSWorkspace.shared.activateFileViewerSelecting([u]) }
      }
    default:
      break
    }
  }

  func startSave(id: String, name: String) {
    let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
    var safe = (name as NSString).lastPathComponent.replacingOccurrences(of: ":", with: "_")
    while safe.hasPrefix(".") { safe.removeFirst() }
    if safe.isEmpty { safe = "file" }
    let base = (safe as NSString).deletingPathExtension, ext = (safe as NSString).pathExtension
    var url = downloads.appendingPathComponent(safe)
    var n = 2
    while FileManager.default.fileExists(atPath: url.path) {
      url = downloads.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
      n += 1
    }
    guard FileManager.default.createFile(atPath: url.path, contents: nil), let h = try? FileHandle(forWritingTo: url) else {
      js("beamNative.saveFailed(\(q(id)))")
      return
    }
    saves[id] = (h, url)
  }

  func abortSave(id: String) {
    guard let s = saves.removeValue(forKey: id) else { return }
    try? s.handle.close()
    try? FileManager.default.removeItem(at: s.url)
  }

  func js(_ code: String) { web.evaluateJavaScript(code, completionHandler: nil) }

  func q(_ s: String) -> String {
    let data = try? JSONSerialization.data(withJSONObject: [s], options: [])
    let arr = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
    return String(arr.dropFirst().dropLast())
  }

  // MARK: - Notifications

  func notify(tag: String, title: String, body: String, request: Bool) {
    let c = UNMutableNotificationContent()
    c.title = title
    c.body = body
    c.sound = .default
    c.userInfo = ["tag": tag]
    if request { c.categoryIdentifier = "request" }
    UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: tag, content: c, trigger: nil))
  }

  func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                              withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
    DispatchQueue.main.async { done(self.window.isKeyWindow ? [] : [.banner, .sound]) }
  }

  func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                              withCompletionHandler done: @escaping () -> Void) {
    let tag = response.notification.request.content.userInfo["tag"] as? String ?? ""
    DispatchQueue.main.async {
      switch response.actionIdentifier {
      case "accept": self.js("beamNative.action(\(self.q(tag)), 'accept')")
      case "decline": self.js("beamNative.action(\(self.q(tag)), 'decline')")
      default: self.showWindow()
      }
      done()
    }
  }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()

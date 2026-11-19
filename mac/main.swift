// Beam for Mac: a menu-bar app that keeps the Beam web app running in the background,
// so other devices can send to this Mac at any time. Files are saved to Downloads, or a folder picked in Settings.
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

/// A small card in the lower-right corner of the screen showing a transfer's progress
/// while Beam's window isn't in front. It never takes focus; clicking it opens Beam.
final class ProgressPopup {
  final class Card: NSVisualEffectView {
    var onClick: (() -> Void)?
    override func mouseDown(with event: NSEvent) { onClick?() }
  }

  let panel: NSPanel
  let card: Card
  let icon = NSImageView()
  let haloView = NSView()
  let halo = CAShapeLayer()
  let title = NSTextField(labelWithString: "")
  let detail = NSTextField(labelWithString: "")
  let percent = NSTextField(labelWithString: "")
  // A slim custom bar, so it can glide and shimmer.
  let barView = NSView()
  let fill = CALayer()
  let shine = CAGradientLayer()
  var hideWork: DispatchWorkItem?
  var shownState = ""
  var showing = false
  var gen = 0
  var calm: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }
  let blue = NSColor(red: 0.36, green: 0.78, blue: 1, alpha: 1)

  init(onClick: @escaping () -> Void) {
    let w: CGFloat = 324, h: CGFloat = 78
    panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: w, height: h), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
    panel.level = .statusBar
    panel.isOpaque = false
    panel.backgroundColor = .clear
    panel.hasShadow = true
    panel.hidesOnDeactivate = false
    panel.isReleasedWhenClosed = false
    panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
    panel.appearance = NSAppearance(named: .darkAqua)

    card = Card(frame: NSRect(x: 0, y: 0, width: w, height: h))
    card.material = .hudWindow
    card.blendingMode = .behindWindow
    card.state = .active
    card.wantsLayer = true
    card.layer?.cornerRadius = 14
    card.layer?.masksToBounds = true

    // A soft ring that breathes around the icon while data moves.
    haloView.frame = NSRect(x: 4, y: 20, width: 54, height: 54)
    haloView.wantsLayer = true
    halo.frame = CGRect(x: 10, y: 10, width: 34, height: 34)
    halo.path = CGPath(ellipseIn: CGRect(x: 0, y: 0, width: 34, height: 34), transform: nil)
    halo.fillColor = nil
    halo.lineWidth = 1.6
    halo.strokeColor = blue.cgColor
    halo.opacity = 0
    haloView.layer?.addSublayer(halo)

    icon.frame = NSRect(x: 14, y: 30, width: 34, height: 34)
    icon.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 27, weight: .medium)
    icon.wantsLayer = true
    title.frame = NSRect(x: 60, y: 46, width: w - 60 - 62, height: 18)
    title.font = .systemFont(ofSize: 13, weight: .semibold)
    title.lineBreakMode = .byTruncatingMiddle
    percent.frame = NSRect(x: w - 62, y: 46, width: 48, height: 17)
    percent.alignment = .right
    percent.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
    percent.textColor = .secondaryLabelColor
    detail.frame = NSRect(x: 60, y: 28, width: w - 74, height: 16)
    detail.font = .systemFont(ofSize: 12)
    detail.textColor = .secondaryLabelColor
    detail.lineBreakMode = .byTruncatingTail

    barView.frame = NSRect(x: 60, y: 13, width: w - 74, height: 4)
    barView.wantsLayer = true
    barView.layer?.backgroundColor = NSColor(white: 1, alpha: 0.1).cgColor
    barView.layer?.cornerRadius = 2
    barView.layer?.masksToBounds = true
    fill.frame = CGRect(x: 0, y: 0, width: 0, height: 4)
    fill.cornerRadius = 2
    fill.backgroundColor = blue.cgColor
    fill.masksToBounds = true
    shine.colors = [NSColor(white: 1, alpha: 0).cgColor, NSColor(white: 1, alpha: 0.6).cgColor, NSColor(white: 1, alpha: 0).cgColor]
    shine.startPoint = CGPoint(x: 0, y: 0.5)
    shine.endPoint = CGPoint(x: 1, y: 0.5)
    shine.frame = CGRect(x: -46, y: 0, width: 46, height: 4)
    fill.addSublayer(shine)
    barView.layer?.addSublayer(fill)

    let parts: [NSView] = [haloView, icon, title, percent, detail, barView]
    for part in parts { card.addSubview(part) }
    panel.contentView = card
    card.onClick = { [weak self] in
      self?.slideAway()
      onClick()
    }
  }

  func target() -> NSRect {
    let area = (NSScreen.main ?? NSScreen.screens.first)?.visibleFrame ?? .zero
    return NSRect(x: area.maxX - panel.frame.width - 16, y: area.minY + 16, width: panel.frame.width, height: panel.frame.height)
  }

  func set(show: Bool, title newTitle: String, detail newDetail: String, pct: Double, state: String, dir: String, hold: Int) {
    hideWork?.cancel()
    let ended = state == "done" || state == "failed"
    if !show || (ended && hold <= 0) {
      slideAway()
      return
    }
    var fraction = max(0, min(1, pct.isFinite ? pct : 0))
    if state == "done" { fraction = 1 }
    title.stringValue = newTitle
    detail.stringValue = newDetail
    percent.stringValue = ended ? "" : "\(Int(fraction * 100))%"
    let symbol = state == "done" ? "checkmark.circle.fill" : state == "failed" ? "exclamationmark.circle.fill"
      : state == "paused" ? "pause.circle.fill" : dir == "out" ? "arrow.up.circle.fill" : "arrow.down.circle.fill"
    icon.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
    let tint: NSColor = state == "done" ? .systemGreen : state == "failed" ? .systemRed : state == "paused" ? .secondaryLabelColor : blue
    icon.contentTintColor = tint

    // The bar glides to its new length.
    CATransaction.begin()
    CATransaction.setAnimationDuration(calm ? 0 : 0.35)
    CATransaction.setAnimationTimingFunction(CAMediaTimingFunction(name: .easeOut))
    fill.frame = CGRect(x: 0, y: 0, width: barView.bounds.width * CGFloat(fraction), height: 4)
    fill.backgroundColor = tint.cgColor
    CATransaction.commit()
    shine.frame = CGRect(x: -46, y: 0, width: 46, height: 4)

    let active = state == "active"
    if active && !calm {
      if shine.animation(forKey: "sweep") == nil {
        let sweep = CABasicAnimation(keyPath: "position.x")
        sweep.fromValue = -23
        sweep.toValue = barView.bounds.width + 23
        sweep.duration = 1.3
        sweep.repeatCount = .infinity
        shine.add(sweep, forKey: "sweep")
      }
      if halo.animation(forKey: "breathe") == nil {
        let grow = CABasicAnimation(keyPath: "transform.scale")
        grow.fromValue = 1
        grow.toValue = 1.45
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0.7
        fade.toValue = 0
        let group = CAAnimationGroup()
        group.animations = [grow, fade]
        group.duration = 1.6
        group.repeatCount = .infinity
        halo.add(group, forKey: "breathe")
      }
    } else {
      shine.removeAnimation(forKey: "sweep")
      halo.removeAnimation(forKey: "breathe")
    }

    if state != shownState {
      if state == "done" || state == "failed" { pop(icon) }
      if state == "failed" { shake() }
      shownState = state
    }

    let to = target()
    if !showing {
      showing = true
      gen += 1
      if calm {
        panel.setFrame(to, display: true)
        panel.alphaValue = 1
        panel.orderFrontRegardless()
      } else {
        // Slide up from just below the corner and fade in, with a little overshoot.
        panel.alphaValue = 0
        panel.setFrame(to.offsetBy(dx: 0, dy: -26), display: true)
        panel.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { ctx in
          ctx.duration = 0.42
          ctx.timingFunction = CAMediaTimingFunction(controlPoints: 0.34, 1.45, 0.64, 1)
          panel.animator().setFrame(to, display: true)
          panel.animator().alphaValue = 1
        }
      }
    } else if panel.frame.origin != to.origin && panel.alphaValue >= 1 {
      panel.setFrame(to, display: true)
    }
    if ended {
      let work = DispatchWorkItem { [weak self] in self?.slideAway() }
      hideWork = work
      DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(max(500, hold)), execute: work)
    }
  }

  /// Slide down a little and fade out.
  func slideAway() {
    hideWork?.cancel()
    guard showing else { return }
    showing = false
    shownState = ""
    gen += 1
    let mine = gen
    if calm { panel.orderOut(nil); return }
    let from = panel.frame
    NSAnimationContext.runAnimationGroup({ ctx in
      ctx.duration = 0.24
      ctx.timingFunction = CAMediaTimingFunction(name: .easeIn)
      panel.animator().setFrame(from.offsetBy(dx: 0, dy: -18), display: true)
      panel.animator().alphaValue = 0
    }, completionHandler: { [weak self] in
      guard let self = self, self.gen == mine else { return }
      self.panel.orderOut(nil)
      self.panel.alphaValue = 1
    })
  }

  /// The icon pops: shrinks, overshoots, settles. (View layers scale from their corner, so scale around the middle by hand.)
  func pop(_ v: NSView) {
    guard !calm, let layer = v.layer else { return }
    let c = CGPoint(x: v.bounds.midX, y: v.bounds.midY)
    func scaled(_ k: CGFloat) -> NSValue {
      var t = CATransform3DMakeTranslation(c.x, c.y, 0)
      t = CATransform3DScale(t, k, k, 1)
      t = CATransform3DTranslate(t, -c.x, -c.y, 0)
      return NSValue(caTransform3D: t)
    }
    let a = CAKeyframeAnimation(keyPath: "transform")
    a.values = [scaled(0.5), scaled(1.22), scaled(0.94), scaled(1)]
    a.keyTimes = [0, 0.45, 0.75, 1]
    a.duration = 0.55
    a.timingFunctions = [CAMediaTimingFunction(name: .easeOut), CAMediaTimingFunction(name: .easeInEaseOut), CAMediaTimingFunction(name: .easeOut)]
    layer.add(a, forKey: "pop")
  }

  func shake() {
    guard !calm, let layer = card.layer else { return }
    let a = CAKeyframeAnimation(keyPath: "transform.translation.x")
    a.values = [0, -8, 8, -6, 6, -3, 3, 0]
    a.duration = 0.45
    layer.add(a, forKey: "shake")
  }
}

/// "Someone wants to send you something" cards. While Beam's window isn't in front, each request
/// drops down from the top-right corner with Accept and Decline, and stays until it's answered
/// (or the sender gives up). Several requests stack downwards.
final class RequestCards: NSObject {
  final class Card {
    let tag: String
    let panel: NSPanel
    var answered = false
    init(tag: String, panel: NSPanel) { self.tag = tag; self.panel = panel }
  }
  var cards: [Card] = []
  let onAnswer: (String, String) -> Void
  let onOpen: () -> Void
  var calm: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }
  let blue = NSColor(red: 0.36, green: 0.78, blue: 1, alpha: 1)
  let w: CGFloat = 340, h: CGFloat = 112, gap: CGFloat = 10

  init(onAnswer: @escaping (String, String) -> Void, onOpen: @escaping () -> Void) {
    self.onAnswer = onAnswer
    self.onOpen = onOpen
    super.init()
  }

  func has(_ tag: String) -> Bool { cards.contains { $0.tag == tag } }

  func slot(_ i: Int) -> NSRect {
    let area = (NSScreen.main ?? NSScreen.screens.first)?.visibleFrame ?? .zero
    return NSRect(x: area.maxX - w - 16, y: area.maxY - 12 - h - CGFloat(i) * (h + gap), width: w, height: h)
  }

  func show(tag: String, title: String, body: String) {
    if has(tag) { return }
    let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: w, height: h), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
    panel.level = .statusBar
    panel.isOpaque = false
    panel.backgroundColor = .clear
    panel.hasShadow = true
    panel.hidesOnDeactivate = false
    panel.isReleasedWhenClosed = false
    panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
    panel.appearance = NSAppearance(named: .darkAqua)
    panel.becomesKeyOnlyIfNeeded = true

    let v = NSVisualEffectView(frame: NSRect(x: 0, y: 0, width: w, height: h))
    v.material = .hudWindow
    v.blendingMode = .behindWindow
    v.state = .active
    v.wantsLayer = true
    v.layer?.cornerRadius = 16
    v.layer?.masksToBounds = true
    v.layer?.borderWidth = 1
    v.layer?.borderColor = blue.withAlphaComponent(0.35).cgColor

    let icon = NSImageView(frame: NSRect(x: 14, y: h - 48, width: 34, height: 34))
    icon.image = NSImage(systemSymbolName: "arrow.down.circle.fill", accessibilityDescription: nil)
    icon.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 27, weight: .medium)
    icon.contentTintColor = blue
    let t = NSTextField(wrappingLabelWithString: title)
    t.frame = NSRect(x: 58, y: h - 50, width: w - 72, height: 38)
    t.font = .systemFont(ofSize: 13, weight: .semibold)
    t.maximumNumberOfLines = 2
    t.lineBreakMode = .byTruncatingTail
    let b = NSTextField(labelWithString: body)
    b.frame = NSRect(x: 58, y: h - 68, width: w - 72, height: 16)
    b.font = .systemFont(ofSize: 11.5)
    b.textColor = .secondaryLabelColor
    b.lineBreakMode = .byTruncatingTail

    let decline = NSButton(title: "Decline", target: nil, action: nil)
    decline.bezelStyle = .rounded
    decline.frame = NSRect(x: w - 14 - 96 - 8 - 96, y: 12, width: 96, height: 30)
    let accept = NSButton(title: "Accept", target: nil, action: nil)
    accept.bezelStyle = .rounded
    accept.bezelColor = NSColor(red: 0.18, green: 0.42, blue: 1, alpha: 1)
    accept.keyEquivalent = "\r"
    accept.frame = NSRect(x: w - 14 - 96, y: 12, width: 96, height: 30)
    let open = NSButton(title: "Open Beam", target: nil, action: nil)
    open.isBordered = false
    open.font = .systemFont(ofSize: 11.5, weight: .medium)
    open.contentTintColor = blue
    open.frame = NSRect(x: 54, y: 16, width: 80, height: 22)

    for part in [icon, t, b, decline, accept, open] as [NSView] { v.addSubview(part) }
    panel.contentView = v
    let card = Card(tag: tag, panel: panel)
    accept.target = self; accept.action = #selector(tapped(_:)); accept.identifier = NSUserInterfaceItemIdentifier("accept|" + tag)
    decline.target = self; decline.action = #selector(tapped(_:)); decline.identifier = NSUserInterfaceItemIdentifier("decline|" + tag)
    open.target = self; open.action = #selector(tapped(_:)); open.identifier = NSUserInterfaceItemIdentifier("open|" + tag)
    cards.append(card)

    // Drop down from just above its spot, with a little bounce.
    let to = slot(cards.count - 1)
    if calm {
      panel.setFrame(to, display: true)
      panel.orderFrontRegardless()
    } else {
      panel.alphaValue = 0
      panel.setFrame(to.offsetBy(dx: 0, dy: 34), display: true)
      panel.orderFrontRegardless()
      NSAnimationContext.runAnimationGroup { ctx in
        ctx.duration = 0.45
        ctx.timingFunction = CAMediaTimingFunction(controlPoints: 0.34, 1.5, 0.64, 1)
        panel.animator().setFrame(to, display: true)
        panel.animator().alphaValue = 1
      }
    }
    NSSound(named: "Glass")?.play()
  }

  @objc func tapped(_ sender: NSButton) {
    let parts = (sender.identifier?.rawValue ?? "").split(separator: "|", maxSplits: 1).map(String.init)
    guard parts.count == 2 else { return }
    let (what, tag) = (parts[0], parts[1])
    if what == "open" { onOpen(); close(tag); return }
    if let c = cards.first(where: { $0.tag == tag }) { c.answered = true }
    onAnswer(tag, what)
    close(tag)
  }

  /// Slides a card away (answered, cancelled, timed out or handled in Beam's window) and moves the rest up.
  func close(_ tag: String) {
    guard let i = cards.firstIndex(where: { $0.tag == tag }) else { return }
    let card = cards.remove(at: i)
    let p = card.panel
    if calm { p.orderOut(nil) } else {
      let from = p.frame
      NSAnimationContext.runAnimationGroup({ ctx in
        ctx.duration = 0.22
        ctx.timingFunction = CAMediaTimingFunction(name: .easeIn)
        p.animator().setFrame(from.offsetBy(dx: 40, dy: 0), display: true)
        p.animator().alphaValue = 0
      }, completionHandler: { p.orderOut(nil) })
    }
    for (j, c) in cards.enumerated() {
      let to = slot(j)
      if calm { c.panel.setFrame(to, display: true) } else {
        NSAnimationContext.runAnimationGroup { ctx in
          ctx.duration = 0.3
          ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
          c.panel.animator().setFrame(to, display: true)
        }
      }
    }
  }

  func closeAll() { for c in cards.map({ $0.tag }) { close(c) } }
}

/// Drop folders: watches chosen folders and hands newly saved files to the page, which sends them to
/// that folder's device. Files stay where they are; Beam remembers what it already sent.
final class DropFolders {
  struct Folder: Codable { var path: String; var to: String; var toName: String }
  var folders: [Folder] = []
  var sent = Set<String>()                 // "path|size|modified" already sent (or there when the folder was added)
  var lastLook: [String: String] = [:]     // path -> key at the previous look: unchanged twice in a row = done saving
  var inFlight: [String: (keys: [String], paths: [String], folder: String)] = [:]
  var busy = Set<String>()
  var retryAfter: [String: Date] = [:]
  var timer: Timer?
  weak var app: AppDelegate?
  static let skip: Set<String> = ["tmp", "part", "crdownload", "download", "partial", "icloud", "sb"]

  init() {
    if let data = UserDefaults.standard.data(forKey: "dropFolders"), let f = try? JSONDecoder().decode([Folder].self, from: data) { folders = f }
    sent = Set(UserDefaults.standard.stringArray(forKey: "dropSent") ?? [])
  }

  func save() {
    UserDefaults.standard.set(try? JSONEncoder().encode(folders), forKey: "dropFolders")
    var list = Array(sent)
    if list.count > 20000 { list = Array(list.suffix(20000)) }
    UserDefaults.standard.set(list, forKey: "dropSent")
  }

  func start() {
    timer?.invalidate()
    timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in self?.tick() }
  }

  /// The files directly inside a folder (not hidden, not half-downloaded), with a key that changes if the file does.
  func files(in dir: String) -> [(url: URL, key: String)] {
    let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
    guard let items = try? FileManager.default.contentsOfDirectory(at: URL(fileURLWithPath: dir), includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]) else { return [] }
    var out: [(URL, String)] = []
    for f in items {
      guard let v = try? f.resourceValues(forKeys: Set(keys)), v.isRegularFile == true else { continue }
      if Self.skip.contains(f.pathExtension.lowercased()) || f.lastPathComponent.hasPrefix("~$") { continue }
      let mod = Int((v.contentModificationDate ?? Date()).timeIntervalSince1970)
      out.append((f, "\(f.path)|\(v.fileSize ?? 0)|\(mod)"))
    }
    return out
  }

  func add(path: String, to: String, toName: String) {
    for f in files(in: path) { sent.insert(f.key) } // what's already there isn't sent
    folders.removeAll { $0.path == path }
    folders.append(Folder(path: path, to: to, toName: toName))
    save()
    report()
  }

  func remove(path: String) {
    folders.removeAll { $0.path == path }
    save()
    report()
  }

  func report() {
    guard let app = app else { return }
    let list = folders.map { ["path": $0.path, "name": URL(fileURLWithPath: $0.path).lastPathComponent, "to": $0.to, "toName": $0.toName] }
    if let data = try? JSONSerialization.data(withJSONObject: list), let json = String(data: data, encoding: .utf8) {
      app.js("beamNative.dropFolders(\(json))")
    }
  }

  func tick() {
    guard let app = app, app.pageReady else { return }
    for f in folders {
      if let r = retryAfter[f.path], r > Date() { continue }
      var ready: [(url: URL, key: String)] = []
      for file in files(in: f.path) {
        if sent.contains(file.key) || busy.contains(file.url.path) { continue }
        if lastLook[file.url.path] == file.key { ready.append(file) } else { lastLook[file.url.path] = file.key }
        if ready.count >= 500 { break }
      }
      guard !ready.isEmpty else { continue }
      let batch = UUID().uuidString
      inFlight[batch] = (ready.map { $0.key }, ready.map { $0.url.path }, f.path)
      for r in ready { busy.insert(r.url.path) }
      var list: [[String: Any]] = []
      for (u, _) in ready {
        let fid = UUID().uuidString
        app.shared[fid] = u
        let size = (try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        let type = UTType(filenameExtension: u.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        list.append(["fid": fid, "name": u.lastPathComponent, "size": size, "type": type, "path": ""])
      }
      if let data = try? JSONSerialization.data(withJSONObject: list), let json = String(data: data, encoding: .utf8) {
        app.js("beamNative.dropSend(\(json), \(app.q(f.to)), \(app.q(batch)), \(app.q(URL(fileURLWithPath: f.path).lastPathComponent)))")
      }
    }
  }

  /// The page says how a batch went. Sent (or declined): never send those files again. Otherwise try again in a minute.
  func done(batch: String, result: String) {
    guard let b = inFlight.removeValue(forKey: batch) else { return }
    for p in b.paths { busy.remove(p) }
    if result == "done" || result == "declined" {
      sent.formUnion(b.keys)
      save()
    } else {
      retryAfter[b.folder] = Date().addingTimeInterval(60)
    }
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
  lazy var popup = ProgressPopup(onClick: { [weak self] in self?.showWindow() })
  let drops = DropFolders()
  lazy var requests = RequestCards(
    onAnswer: { [weak self] tag, what in
      guard let self = self else { return }
      self.js("beamNative.action(\(self.q(tag)), \(self.q(what)))")
    },
    onOpen: { [weak self] in self?.showWindow() })
  var pendingShare: [URL] = []
  var pageReady = false
  var statusItem: NSStatusItem!
  var window: NSWindow!
  var web: WKWebView!
  var saves: [String: (handle: FileHandle, url: URL)] = [:]
  var savedPaths = Set<String>()
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
    drops.app = self
    drops.start()
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
    // Folders are sent as their files, each tagged with its path inside the folder.
    var files: [(URL, String)] = []
    let fm = FileManager.default
    for u in urls {
      let v = try? u.resourceValues(forKeys: [.isRegularFileKey, .isDirectoryKey])
      if v?.isRegularFile == true { files.append((u, "")) }
      else if v?.isDirectory == true, let e = fm.enumerator(at: u, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]) {
        let baseLen = u.deletingLastPathComponent().path.count + 1
        for case let f as URL in e {
          if files.count >= 5000 { break }
          guard (try? f.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else { continue }
          let rel = String(f.deletingLastPathComponent().path.dropFirst(baseLen))
          files.append((f, rel))
        }
      }
    }
    guard !files.isEmpty else { return }
    showWindow()
    if !pageReady { pendingShare += urls; return }
    var list: [[String: Any]] = []
    for (u, rel) in files {
      let fid = UUID().uuidString
      shared[fid] = u
      let size = (try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
      let type = UTType(filenameExtension: u.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
      list.append(["fid": fid, "name": u.lastPathComponent, "size": size, "type": type, "path": rel])
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
    window.backgroundColor = NSColor(red: 0.059, green: 0.086, blue: 0.149, alpha: 1)
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
    case "progress":
      // Beam's own window already shows progress when it's in front.
      let inFront = window.isVisible && NSApp.isActive && window.isKeyWindow
      popup.set(show: (d["show"] as? Bool ?? false) && !inFront,
                title: d["title"] as? String ?? "", detail: d["detail"] as? String ?? "",
                pct: (d["pct"] as? NSNumber)?.doubleValue ?? 0,
                state: d["state"] as? String ?? "active", dir: d["dir"] as? String ?? "in",
                hold: (d["hold"] as? NSNumber)?.intValue ?? 0)
    case "notify":
      let tag = d["tag"] as? String ?? "", title = d["title"] as? String ?? "Beam", body = d["body"] as? String ?? ""
      let request = d["actions"] as? Bool ?? false
      let inFront = window.isVisible && NSApp.isActive && window.isKeyWindow
      if request && !inFront {
        // Beam's own drop-down card, with buttons that are always visible.
        requests.show(tag: tag, title: title, body: body)
      } else if !(request && inFront) {
        notify(tag: tag, title: title, body: body, request: request)
      }
    case "unnotify":
      if let tag = d["tag"] as? String {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [tag])
        requests.close(tag)
      }
    case "openUrl":
      // A link from one of my own devices: open it in the default browser (web links only).
      if let str = d["url"] as? String, let u = URL(string: str), ["http", "https"].contains(u.scheme?.lowercased() ?? "") { NSWorkspace.shared.open(u) }
    case "show":
      showWindow()
    case "saveBegin":
      startSave(id: id, name: d["name"] as? String ?? "file", path: d["path"] as? String ?? "")
    case "saveChunk":
      guard let s = saves[id], let b64 = d["b64"] as? String, let data = Data(base64Encoded: b64) else { return }
      do { try s.handle.write(contentsOf: data) } catch { abortSave(id: id); js("beamNative.saveFailed(\(q(id)))") }
    case "saveEnd":
      guard let s = saves.removeValue(forKey: id) else { return }
      try? s.handle.close()
      savedPaths.insert(s.url.standardizedFileURL.path)
      js("beamNative.saved(\(q(id)), \(q(s.url.path)))")
    case "saveAbort":
      abortSave(id: id)
    case "reveal":
      // Only files Beam saved itself, in Downloads or the chosen folder (past or present).
      if let p = d["path"] as? String {
        let u = URL(fileURLWithPath: p).standardizedFileURL
        if savedPaths.contains(u.path) || u.path.hasPrefix(downloadsDir().path + "/") || u.path.hasPrefix(saveRoot().path + "/") { NSWorkspace.shared.activateFileViewerSelecting([u]) }
      }
    case "getDropFolders":
      drops.report()
    case "dropAdd":
      let to = d["to"] as? String ?? "", toName = d["toName"] as? String ?? "device"
      let panel = NSOpenPanel()
      panel.canChooseDirectories = true
      panel.canChooseFiles = false
      panel.canCreateDirectories = true
      panel.prompt = "Watch This Folder"
      panel.message = "New files saved here will be sent to \(toName)"
      NSApp.activate(ignoringOtherApps: true)
      panel.begin { r in
        if r == .OK, let u = panel.url { self.drops.add(path: u.standardizedFileURL.path, to: to, toName: toName) }
      }
    case "dropRemove":
      if let p = d["path"] as? String { drops.remove(path: p) }
    case "dropDone":
      drops.done(batch: d["batch"] as? String ?? "", result: d["result"] as? String ?? "failed")
    case "getVersion":
      js("beamNative.appVersion('1.0.\(Updater.current)')")
    case "checkUpdates":
      updater.check(loud: true)
    case "getSaveDir":
      sendSaveDir()
    case "pickSaveDir":
      let panel = NSOpenPanel()
      panel.canChooseDirectories = true
      panel.canChooseFiles = false
      panel.canCreateDirectories = true
      panel.allowsMultipleSelection = false
      panel.prompt = "Save Here"
      panel.message = "Choose where Beam saves the files you receive"
      panel.directoryURL = saveRoot()
      NSApp.activate(ignoringOtherApps: true)
      panel.begin { r in
        if r == .OK, let u = panel.url {
          let p = u.standardizedFileURL.path
          if p == self.downloadsDir().path { UserDefaults.standard.removeObject(forKey: "saveDir") }
          else { UserDefaults.standard.set(p, forKey: "saveDir") }
        }
        self.sendSaveDir()
      }
    case "resetSaveDir":
      UserDefaults.standard.removeObject(forKey: "saveDir")
      sendSaveDir()
    default:
      break
    }
  }

  /// One safe path component: no slashes, no leading dots, never "." or "..".
  func safeName(_ s: String) -> String {
    var n = (s as NSString).lastPathComponent.replacingOccurrences(of: ":", with: "_").replacingOccurrences(of: "/", with: "_")
    while n.hasPrefix(".") { n.removeFirst() }
    return n.isEmpty ? "file" : n
  }

  func downloadsDir() -> URL { FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].standardizedFileURL }

  /// The folder picked in Settings, or Downloads. If the picked folder is gone
  /// (a drive that's unplugged, a folder that was deleted) files land in Downloads instead.
  func saveRoot() -> URL {
    if let p = UserDefaults.standard.string(forKey: "saveDir") {
      var isDir: ObjCBool = false
      if FileManager.default.fileExists(atPath: p, isDirectory: &isDir), isDir.boolValue, FileManager.default.isWritableFile(atPath: p) {
        return URL(fileURLWithPath: p, isDirectory: true)
      }
    }
    return downloadsDir()
  }

  func sendSaveDir() {
    let root = saveRoot()
    let isDefault = root.path == downloadsDir().path
    let home = FileManager.default.homeDirectoryForCurrentUser.standardizedFileURL.path
    var shown = root.path
    if shown.hasPrefix(home + "/") { shown = "~" + shown.dropFirst(home.count) }
    let missing = isDefault && UserDefaults.standard.string(forKey: "saveDir") != nil
    let label = missing ? "Downloads, because the chosen folder isn't available" : isDefault ? "Downloads" : shown
    js("beamNative.saveDir(\(q(root.path)), \(q(label)), \(isDefault && !missing))")
  }

  /// `path` is the folder inside the transfer this file belongs to ("Trip/Day 1"), recreated under the save folder.
  func startSave(id: String, name: String, path: String) {
    let downloads = saveRoot()
    var folder = downloads
    for part in path.split(separator: "/").map(String.init).filter({ !$0.isEmpty && $0 != "." && $0 != ".." }).prefix(20) {
      folder = folder.appendingPathComponent(safeName(part))
    }
    if folder != downloads { try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true) }
    let safe = safeName(name)
    let base = (safe as NSString).deletingPathExtension, ext = (safe as NSString).pathExtension
    var url = folder.appendingPathComponent(safe)
    var n = 2
    while FileManager.default.fileExists(atPath: url.path) {
      url = folder.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
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

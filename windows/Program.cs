// Beam for Windows: a tray app that keeps the Beam web app running in the background,
// so other devices can send to this PC at any time. Files are saved straight to Downloads.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO.Pipes;
using System.Linq;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using CommunityToolkit.WinUI.Notifications;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Win32;

namespace Beam
{
    static class Program
    {
        public const string Server = "__BEAM_URL__";
        public const string Repo = "__REPO__";

        [STAThread]
        static void Main(string[] args)
        {
            if (Array.Exists(args, a => a == "--uninstall")) { Installer.Uninstall(); return; }
            // Opened from Downloads (or anywhere else)? Install into the user's Programs folder and run from there.
            if (!Installer.IsInstalledCopy && !Array.Exists(args, a => a == "--portable"))
            {
                if (Installer.Install(args)) return;
            }
            // "Send with Beam" from Explorer runs: Beam.exe --send <file> [<file> ...]
            var files = new List<string>();
            int si = Array.IndexOf(args, "--send");
            if (si >= 0) for (int i = si + 1; i < args.Length; i++) if (File.Exists(args[i])) files.Add(Path.GetFullPath(args[i]));

            bool updated = Array.Exists(args, a => a == "--updated");
            var mutex = new Mutex(true, "Beam.SingleInstance", out bool first);
            // After an update, the old version is still closing: wait for it to let go.
            for (int i = 0; updated && !first && i < 60; i++)
            {
                Thread.Sleep(250);
                mutex.Dispose();
                mutex = new Mutex(true, "Beam.SingleInstance", out first);
            }
            using var showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, "Beam.ShowWindow");
            if (!first)
            {
                // Beam is already running: hand it the files (if any) and bring its window up.
                if (files.Count > 0)
                {
                    try
                    {
                        using var pipe = new NamedPipeClientStream(".", MainForm.PipeName, PipeDirection.Out);
                        pipe.Connect(3000);
                        using var w = new StreamWriter(pipe);
                        foreach (var f in files) w.WriteLine(f);
                    }
                    catch { }
                }
                showSignal.Set();
                return;
            }

            Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            bool background = Array.Exists(args, a => a == "--background") || ToastNotificationManagerCompat.WasCurrentProcessToastActivated();
            if (updated) Updater.CleanUp();
            var form = new MainForm(!background || files.Count > 0);
            if (files.Count > 0) form.QueueShare(files);
            new Thread(() =>
            {
                while (showSignal.WaitOne())
                {
                    try { form.BeginInvoke(new Action(form.ShowMain)); } catch { }
                }
            }) { IsBackground = true }.Start();
            Application.Run(new ApplicationContext());
            GC.KeepAlive(mutex);
        }
    }

    sealed class MainForm : Form
    {
        readonly WebView2 web = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = Color.FromArgb(11, 13, 18) };
        readonly NotifyIcon tray = new NotifyIcon();
        readonly ToolStripMenuItem startupItem = new ToolStripMenuItem("Start with Windows");
        readonly Dictionary<string, (FileStream stream, string path)> saves = new();
        readonly Uri server = new Uri(Program.Server);
        bool online, quitting, pageReady;
        public bool Busy;
        readonly Updater updater;
        readonly ToolStripMenuItem updateItem = new ToolStripMenuItem("Check for updates");
        CoreWebView2Environment env;
        readonly List<string> shareQueue = new();
        System.Windows.Forms.Timer shareTimer;
        public const string PipeName = "Beam.SendFiles";

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

        public MainForm(bool showNow)
        {
            Text = "Beam";
            ClientSize = new Size(440, 680);
            MinimumSize = new Size(360, 500);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(11, 13, 18);
            Icon = LoadIcon();
            ShowInTaskbar = true;
            Controls.Add(web);

            tray.Icon = Icon;
            tray.Text = "Beam — connecting…";
            tray.Visible = true;
            tray.DoubleClick += (s, e) => ShowMain();
            tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowMain(); };
            var menu = new ContextMenuStrip();
            menu.Items.Add("Open Beam", null, (s, e) => ShowMain());
            menu.Items.Add(new ToolStripSeparator());
            startupItem.Click += (s, e) => SetStartup(!startupItem.Checked);
            menu.Items.Add(startupItem);
            menu.Items.Add("Reload", null, (s, e) => web.CoreWebView2?.Reload());
            updateItem.Click += (s, e) => updater.CheckNow(true);
            menu.Items.Add(updateItem);
            menu.Items.Add(new ToolStripMenuItem("Version 1.0." + Updater.Current) { Enabled = false });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Quit Beam", null, (s, e) => Quit());
            tray.ContextMenuStrip = menu;

            // First run: start with Windows by default (like AirDrop, it should just be there).
            using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Beam"))
            {
                if (k.GetValue("launchedBefore") == null)
                {
                    k.SetValue("launchedBefore", 1);
                    SetStartup(true);
                }
            }
            if (IsStartup()) SetStartup(true); // keep the path current if the exe moved
            startupItem.Checked = IsStartup();
            InstallExplorerMenus();
            StartPipeServer();
            ListenForQuit();
            updater = new Updater(this);
            updater.Start();

            // Create the window handles without showing it, so the page loads in the background.
            CreateHandle();
            _ = web.Handle;
            ToastNotificationManagerCompat.OnActivated += OnToast;
            _ = InitWeb();
            if (showNow) ShowMain();
        }

        static Icon LoadIcon()
        {
            using var s = typeof(MainForm).Assembly.GetManifestResourceStream("Beam.beam.ico");
            return s != null ? new Icon(s) : SystemIcons.Application;
        }

        async Task InitWeb()
        {
            var dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Beam", "WebView2");
            var opts = new CoreWebView2EnvironmentOptions
            {
                // Keep the connection alive while the window is hidden.
                AdditionalBrowserArguments = "--disable-background-timer-throttling --disable-renderer-backgrounding --disable-backgrounding-occluded-windows"
            };
            try
            {
                env = await CoreWebView2Environment.CreateAsync(null, dataDir, opts);
                await web.EnsureCoreWebView2Async(env);
            }
            catch (Exception ex)
            {
                MessageBox.Show("Beam needs the Microsoft Edge WebView2 Runtime, which comes with Windows 10 and 11.\n\n" + ex.Message, "Beam");
                return;
            }
            var core = web.CoreWebView2;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = true;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.IsZoomControlEnabled = false;
            core.WebMessageReceived += OnMessage;
            core.NavigationStarting += (s, e) =>
            {
                if (!IsOurs(e.Uri)) { e.Cancel = true; OpenExternal(e.Uri); }
            };
            core.NewWindowRequested += (s, e) => { e.Handled = true; OpenExternal(e.Uri); };
            core.NavigationCompleted += (s, e) =>
            {
                if (!e.IsSuccess) { RetryLater(); return; }
                pageReady = true;
                if (shareQueue.Count > 0) { shareTimer?.Stop(); shareTimer?.Start(); }
            };
            core.ProcessFailed += (s, e) => { pageReady = false; try { core.Reload(); } catch { } };
            core.Navigate(Program.Server + "/?app=windows");
        }

        bool IsOurs(string uri)
        {
            if (uri.StartsWith("blob:") || uri.StartsWith("about:") || uri.StartsWith("data:")) return true;
            return Uri.TryCreate(uri, UriKind.Absolute, out var u) && u.Scheme == server.Scheme && u.Host == server.Host;
        }

        static void OpenExternal(string uri)
        {
            if (Uri.TryCreate(uri, UriKind.Absolute, out var u) && (u.Scheme == "http" || u.Scheme == "https" || u.Scheme == "mailto"))
                Process.Start(new ProcessStartInfo(u.AbsoluteUri) { UseShellExecute = true });
        }

        void RetryLater()
        {
            var t = new System.Windows.Forms.Timer { Interval = 5000 };
            t.Tick += (s, e) => { t.Stop(); t.Dispose(); if (!online) web.CoreWebView2?.Reload(); };
            t.Start();
        }

        // Another copy of Beam (the installer, or Uninstall) asks this one to close.
        void ListenForQuit()
        {
            new Thread(() =>
            {
                using var ev = new EventWaitHandle(false, EventResetMode.AutoReset, Installer.QuitEvent);
                ev.WaitOne();
                try { BeginInvoke(new Action(Quit)); } catch { Environment.Exit(0); }
            }) { IsBackground = true }.Start();
        }

        // ---------- "Send with Beam" from File Explorer ----------
        void InstallExplorerMenus()
        {
            var exe = Environment.ProcessPath;
            try
            {
                // Right-click a file → "Send with Beam" (Windows 11: under "Show more options")
                using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\*\shell\Beam"))
                {
                    k.SetValue("", "Send with Beam");
                    k.SetValue("Icon", $"\"{exe}\",0");
                    using var c = k.CreateSubKey("command");
                    c.SetValue("", $"\"{exe}\" --send \"%1\"");
                }
            }
            catch { }
            try
            {
                // Right-click → Send to → Beam (handles many files at once)
                Installer.CreateLink(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "Beam.lnk"), exe, "--send", "Send with Beam");
            }
            catch { }
        }

        void RemoveExplorerMenus()
        {
            try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\*\shell\Beam", false); } catch { }
            try { File.Delete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "Beam.lnk")); } catch { }
        }

        // Other launches of Beam.exe (one per file when several are right-clicked) pass their files here.
        void StartPipeServer()
        {
            new Thread(() =>
            {
                while (true)
                {
                    try
                    {
                        using var pipe = new NamedPipeServerStream(PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte);
                        pipe.WaitForConnection();
                        using var r = new StreamReader(pipe);
                        var got = new List<string>();
                        string line;
                        while ((line = r.ReadLine()) != null) if (File.Exists(line)) got.Add(line);
                        if (got.Count > 0) BeginInvoke(new Action(() => QueueShare(got)));
                    }
                    catch { Thread.Sleep(500); }
                }
            }) { IsBackground = true }.Start();
        }

        // Collect files for a moment (Explorer may launch us once per file), then hand them to the page.
        public void QueueShare(IEnumerable<string> paths)
        {
            foreach (var p in paths) if (!shareQueue.Contains(p)) shareQueue.Add(p);
            if (shareTimer == null)
            {
                shareTimer = new System.Windows.Forms.Timer { Interval = 700 };
                shareTimer.Tick += (s, e) => { shareTimer.Stop(); FlushShare(); };
            }
            shareTimer.Stop();
            shareTimer.Start();
            ShowMain();
        }

        void FlushShare()
        {
            if (!pageReady || env == null || web.CoreWebView2 == null || shareQueue.Count == 0) return;
            var handles = new List<object>();
            foreach (var p in shareQueue)
            {
                try { handles.Add(env.CreateWebFileSystemFileHandle(p, CoreWebView2FileSystemHandlePermission.ReadOnly)); } catch { }
            }
            shareQueue.Clear();
            if (handles.Count > 0) web.CoreWebView2.PostWebMessageAsJson("{\"op\":\"share\"}", handles);
        }

        // ---------- window ----------
        public void ShowMain()
        {
            if (!Visible) Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
            BringToFront();
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            int dark = 1; // dark title bar
            try { DwmSetWindowAttribute(Handle, 20, ref dark, sizeof(int)); } catch { }
        }

        // Closing the window only hides it; Beam keeps running in the tray.
        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!quitting && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                return;
            }
            base.OnFormClosing(e);
        }

        /// <summary>Restart into the freshly installed version.</summary>
        public void RestartForUpdate(string exe)
        {
            var args = "--updated" + (Visible ? "" : " --background");
            try { Process.Start(new ProcessStartInfo(exe, args) { UseShellExecute = false }); } catch { return; }
            Quit();
        }

        public void Balloon(string title, string text) => tray.ShowBalloonTip(5000, title, text, ToolTipIcon.Info);

        public void Quit()
        {
            quitting = true;
            tray.Visible = false;
            foreach (var s in saves.Values) { try { s.stream.Dispose(); File.Delete(s.path); } catch { } }
            Application.Exit();
            Environment.Exit(0);
        }

        // ---------- start with Windows ----------
        static bool IsStartup()
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue("Beam") != null;
        }

        void SetStartup(bool on)
        {
            using var k = Registry.CurrentUser.CreateSubKey(RunKey);
            if (on) k.SetValue("Beam", $"\"{Environment.ProcessPath}\" --background");
            else k.DeleteValue("Beam", false);
            startupItem.Checked = on;
        }

        // ---------- bridge from the page ----------
        void OnMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
        {
            if (!IsOurs(e.Source)) return;
            JsonElement d;
            try { d = JsonDocument.Parse(e.WebMessageAsJson).RootElement; } catch { return; }
            string Str(string name) => d.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
            string id = Str("id") ?? "";
            switch (Str("op"))
            {
                case "status":
                    online = d.TryGetProperty("online", out var o) && o.ValueKind == JsonValueKind.True;
                    Busy = d.TryGetProperty("busy", out var bz) && bz.ValueKind == JsonValueKind.True;
                    int peers = d.TryGetProperty("peers", out var p) && p.ValueKind == JsonValueKind.Number ? p.GetInt32() : 0;
                    tray.Text = online ? $"Beam — {peers} device{(peers == 1 ? "" : "s")} nearby" : "Beam — connecting…";
                    break;
                case "notify":
                    Notify(Str("tag") ?? "", Str("title") ?? "Beam", Str("body") ?? "",
                        d.TryGetProperty("actions", out var a) && a.ValueKind == JsonValueKind.True);
                    break;
                case "unnotify":
                    try { ToastNotificationManagerCompat.History.Remove(Short(Str("tag") ?? "")); } catch { }
                    break;
                case "show":
                    ShowMain();
                    break;
                case "saveBegin":
                    StartSave(id, Str("name") ?? "file");
                    break;
                case "saveChunk":
                    if (saves.TryGetValue(id, out var sv))
                    {
                        try
                        {
                            var bytes = Convert.FromBase64String(Str("b64") ?? "");
                            sv.stream.Write(bytes, 0, bytes.Length);
                        }
                        catch
                        {
                            AbortSave(id);
                            Js($"beamNative.saveFailed({Q(id)})");
                        }
                    }
                    break;
                case "saveEnd":
                    if (saves.Remove(id, out var done))
                    {
                        done.stream.Dispose();
                        Js($"beamNative.saved({Q(id)}, {Q(done.path)})");
                    }
                    break;
                case "saveAbort":
                    AbortSave(id);
                    break;
                case "reveal":
                    var path = Str("path");
                    if (path != null && File.Exists(path) && Path.GetFullPath(path).StartsWith(Downloads(), StringComparison.OrdinalIgnoreCase))
                        Process.Start("explorer.exe", $"/select,\"{path}\"");
                    break;
            }
        }

        static string Downloads()
        {
            try
            {
                SHGetKnownFolderPath(new Guid("374DE290-123F-4565-9164-39C4925E467B"), 0, IntPtr.Zero, out var p);
                if (!string.IsNullOrEmpty(p)) return Path.GetFullPath(p);
            }
            catch { }
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        }

        void StartSave(string id, string name)
        {
            var safe = Path.GetFileName(name ?? "");
            foreach (var c in Path.GetInvalidFileNameChars()) safe = safe.Replace(c, '_');
            safe = safe.TrimStart('.').Trim();
            if (safe.Length == 0) safe = "file";
            var dir = Downloads();
            Directory.CreateDirectory(dir);
            var baseName = Path.GetFileNameWithoutExtension(safe);
            var ext = Path.GetExtension(safe);
            var path = Path.Combine(dir, safe);
            for (int n = 2; File.Exists(path); n++) path = Path.Combine(dir, $"{baseName} ({n}){ext}");
            try
            {
                saves[id] = (new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.Read), path);
            }
            catch
            {
                Js($"beamNative.saveFailed({Q(id)})");
            }
        }

        void AbortSave(string id)
        {
            if (!saves.Remove(id, out var s)) return;
            try { s.stream.Dispose(); File.Delete(s.path); } catch { }
        }

        void Js(string code) => web.CoreWebView2?.ExecuteScriptAsync(code);
        static string Q(string s) => JsonSerializer.Serialize(s);
        static string Short(string tag) => tag.Length > 60 ? tag.Substring(0, 60) : tag;

        // ---------- notifications ----------
        void Notify(string tag, string title, string body, bool request)
        {
            try
            {
                var b = new ToastContentBuilder().AddArgument("tag", tag).AddText(title).AddText(body);
                if (request)
                {
                    b.AddButton(new ToastButton().SetContent("Accept").AddArgument("action", "accept").AddArgument("tag", tag));
                    b.AddButton(new ToastButton().SetContent("Decline").AddArgument("action", "decline").AddArgument("tag", tag));
                }
                b.Show(t => { t.Tag = Short(tag); });
            }
            catch
            {
                tray.ShowBalloonTip(5000, title, body, ToolTipIcon.None);
            }
        }

        void OnToast(ToastNotificationActivatedEventArgsCompat e)
        {
            var args = ToastArguments.Parse(e.Argument);
            args.TryGetValue("tag", out string tag);
            args.TryGetValue("action", out string action);
            BeginInvoke(new Action(() =>
            {
                if ((action == "accept" || action == "decline") && tag != null) Js($"beamNative.action({Q(tag)}, {Q(action)})");
                else ShowMain();
            }));
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) { tray.Dispose(); web.Dispose(); }
            base.Dispose(disposing);
        }

        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
        static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid id, uint flags, IntPtr token, out string path);
    }
    /// <summary>
    /// Keeps Beam up to date: checks the latest release every few hours, downloads it in the
    /// background, and swaps it in (a quick restart) when no transfer is running.
    /// </summary>
    sealed class Updater
    {
        static readonly string ManifestUrl = $"https://github.com/{Program.Repo}/releases/download/windows/windows.json";
        public static int Current => typeof(Updater).Assembly.GetName().Version?.Build ?? 0;
        static string Exe => Environment.ProcessPath;
        static string NewPath => Exe + ".new";
        static string OldPath => Exe + ".old";

        readonly MainForm form;
        readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer { Interval = 6 * 3600 * 1000 };
        readonly System.Windows.Forms.Timer applyTimer = new System.Windows.Forms.Timer { Interval = 15000 };
        static readonly System.Net.Http.HttpClient http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        bool checking;
        int ready; // version downloaded and waiting to be applied

        public Updater(MainForm form)
        {
            this.form = form;
            timer.Tick += (s, e) => CheckNow(false);
            applyTimer.Tick += (s, e) => TryApply();
        }

        public void Start()
        {
            timer.Start();
            var first = new System.Windows.Forms.Timer { Interval = 30000 };
            first.Tick += (s, e) => { first.Stop(); first.Dispose(); CheckNow(false); };
            first.Start();
        }

        /// <summary>Removes the previous version left behind by the last update.</summary>
        public static void CleanUp()
        {
            new Thread(() =>
            {
                for (int i = 0; i < 20; i++)
                {
                    try { if (File.Exists(OldPath)) File.Delete(OldPath); return; } catch { Thread.Sleep(500); }
                }
            }) { IsBackground = true }.Start();
        }

        public async void CheckNow(bool loud)
        {
            if (checking || Program.Repo.StartsWith("__")) return;
            checking = true;
            try
            {
                var json = await http.GetStringAsync(ManifestUrl);
                var d = JsonDocument.Parse(json).RootElement;
                int version = d.GetProperty("version").GetInt32();
                if (version <= Current || version <= ready)
                {
                    if (loud) form.Balloon("Beam", version <= Current ? "Beam is up to date." : "An update is ready and will install shortly.");
                    return;
                }
                // Download next to Beam.exe, check it, then wait for a quiet moment.
                var url = d.GetProperty("url").GetString();
                var want = d.GetProperty("sha256").GetString().ToLowerInvariant();
                using (var res = await http.GetAsync(url, System.Net.Http.HttpCompletionOption.ResponseHeadersRead))
                {
                    res.EnsureSuccessStatusCode();
                    using var src = await res.Content.ReadAsStreamAsync();
                    using var dst = new FileStream(NewPath, FileMode.Create, FileAccess.Write);
                    await src.CopyToAsync(dst);
                }
                string got;
                using (var f = File.OpenRead(NewPath)) got = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(f)).ToLowerInvariant();
                if (got != want) { File.Delete(NewPath); if (loud) form.Balloon("Beam", "The update download was damaged. It'll try again later."); return; }
                ready = version;
                if (loud) form.Balloon("Beam", "Updating to version 1.0." + version + "…");
                applyTimer.Start();
                TryApply();
            }
            catch (UnauthorizedAccessException)
            {
                if (loud) form.Balloon("Beam", "Beam can't update itself in this folder. Move Beam.exe to a folder you own, like Documents.");
            }
            catch
            {
                if (loud) form.Balloon("Beam", "Couldn't check for updates right now.");
            }
            finally { checking = false; }
        }

        // Swap in the new version once nothing is being sent or received.
        void TryApply()
        {
            if (ready == 0 || form.Busy || !File.Exists(NewPath)) return;
            applyTimer.Stop();
            try
            {
                if (File.Exists(OldPath)) File.Delete(OldPath);
                File.Move(Exe, OldPath);       // Windows lets a running program be renamed
                File.Move(NewPath, Exe);
            }
            catch
            {
                try { if (!File.Exists(Exe) && File.Exists(OldPath)) File.Move(OldPath, Exe); } catch { }
                return;
            }
            form.RestartForUpdate(Exe);
        }
    }
    /// <summary>
    /// Installs Beam for the current user (no admin rights needed): copies it to
    /// %LOCALAPPDATA%\Programs\Beam, adds a Start menu shortcut and an entry in
    /// Settings → Apps so it can be uninstalled like any other app.
    /// </summary>
    static class Installer
    {
        public const string QuitEvent = "Beam.Quit";
        const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Beam";
        public static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Beam");
        public static string Exe => Path.Combine(Dir, "Beam.exe");
        static string StartMenuLink => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Beam.lnk");

        public static bool IsInstalledCopy =>
            string.Equals(Path.GetFullPath(Environment.ProcessPath), Path.GetFullPath(Exe), StringComparison.OrdinalIgnoreCase);

        /// <summary>Asks a running Beam to quit and waits for it to close.</summary>
        static bool CloseRunning()
        {
            if (!Mutex.TryOpenExisting("Beam.SingleInstance", out var m)) return true;
            m.Dispose();
            try { using var ev = new EventWaitHandle(false, EventResetMode.AutoReset, QuitEvent); ev.Set(); } catch { }
            for (int i = 0; i < 40; i++)
            {
                Thread.Sleep(250);
                if (!Mutex.TryOpenExisting("Beam.SingleInstance", out var m2)) return true;
                m2.Dispose();
            }
            return false;
        }

        /// <returns>true if this process should exit (the installed copy has been started).</returns>
        public static bool Install(string[] args)
        {
            try
            {
                if (!CloseRunning())
                {
                    MessageBox.Show("Beam is already running. Right-click the Beam icon in the system tray, choose Quit Beam, then open this file again.", "Beam");
                    return true;
                }
                Directory.CreateDirectory(Dir);
                var src = Environment.ProcessPath;
                if (File.Exists(Exe))
                {
                    var old = Exe + ".old";
                    try { if (File.Exists(old)) File.Delete(old); } catch { }
                    try { File.Delete(Exe); } catch { File.Move(Exe, old); }
                }
                File.Copy(src, Exe);
                CreateLink(StartMenuLink, Exe, "", "Beam: send files to your devices");
                using (var k = Registry.CurrentUser.CreateSubKey(UninstallKey))
                {
                    var v = typeof(Installer).Assembly.GetName().Version;
                    k.SetValue("DisplayName", "Beam");
                    k.SetValue("DisplayVersion", $"1.0.{v?.Build ?? 0}");
                    k.SetValue("Publisher", "Beam");
                    k.SetValue("DisplayIcon", Exe + ",0");
                    k.SetValue("InstallLocation", Dir);
                    k.SetValue("UninstallString", $"\"{Exe}\" --uninstall");
                    k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                    k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                    k.SetValue("EstimatedSize", (int)(new FileInfo(Exe).Length / 1024), RegistryValueKind.DWord);
                }
                // Start the installed copy (passing along any files to send) and step aside.
                var pass = string.Join(" ", Array.ConvertAll(args, a => "\"" + a + "\""));
                Process.Start(new ProcessStartInfo(Exe, pass) { UseShellExecute = false });
                return true;
            }
            catch (Exception e)
            {
                // Couldn't install; just run from where we are.
                Debug.WriteLine(e);
                return false;
            }
        }

        public static void CreateLink(string link, string target, string args, string description)
        {
            var shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType == null) return;
            dynamic shell = Activator.CreateInstance(shellType);
            dynamic lnk = shell.CreateShortcut(link);
            lnk.TargetPath = target;
            lnk.Arguments = args;
            lnk.IconLocation = target + ",0";
            lnk.Description = description;
            lnk.Save();
        }

        public static void Uninstall()
        {
            if (MessageBox.Show("Remove Beam from this PC? This signs this PC out of Beam and deletes its settings.", "Beam",
                    MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            CloseRunning();
            void Try(Action a) { try { a(); } catch { } }
            Try(() => { using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true); k?.DeleteValue("Beam", false); });
            Try(() => Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\*\shell\Beam", false));
            Try(() => Registry.CurrentUser.DeleteSubKeyTree(@"Software\Beam", false));
            Try(() => Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false));
            Try(() => File.Delete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "Beam.lnk")));
            Try(() => File.Delete(StartMenuLink));
            Try(() => ToastNotificationManagerCompat.Uninstall());
            var data = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Beam");
            // The program can't delete itself while running, so a short-lived helper finishes the job.
            Try(() => Process.Start(new ProcessStartInfo("cmd.exe",
                $"/c ping 127.0.0.1 -n 3 > nul & rmdir /s /q \"{Dir}\" & rmdir /s /q \"{data}\"")
            { CreateNoWindow = true, UseShellExecute = false }));
            MessageBox.Show("Beam was removed.", "Beam");
        }
    }
}

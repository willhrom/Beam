// Beam for Windows: a tray app that keeps the Beam web app running in the background,
// so other devices can send to this PC at any time. Files are saved straight to Downloads.
using System;
using System.Collections.Generic;
using System.Diagnostics;
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

        [STAThread]
        static void Main(string[] args)
        {
            using var mutex = new Mutex(true, "Beam.SingleInstance", out bool first);
            using var showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, "Beam.ShowWindow");
            if (!first)
            {
                showSignal.Set(); // Beam is already running: just bring its window up
                return;
            }

            Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            bool background = Array.Exists(args, a => a == "--background") || ToastNotificationManagerCompat.WasCurrentProcessToastActivated();
            var form = new MainForm(!background);
            new Thread(() =>
            {
                while (showSignal.WaitOne())
                {
                    try { form.BeginInvoke(new Action(form.ShowMain)); } catch { }
                }
            }) { IsBackground = true }.Start();
            Application.Run(new ApplicationContext());
        }
    }

    sealed class MainForm : Form
    {
        readonly WebView2 web = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = Color.FromArgb(11, 13, 18) };
        readonly NotifyIcon tray = new NotifyIcon();
        readonly ToolStripMenuItem startupItem = new ToolStripMenuItem("Start with Windows");
        readonly Dictionary<string, (FileStream stream, string path)> saves = new();
        readonly Uri server = new Uri(Program.Server);
        bool online, quitting;

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
                var env = await CoreWebView2Environment.CreateAsync(null, dataDir, opts);
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
            core.NavigationCompleted += (s, e) => { if (!e.IsSuccess) RetryLater(); };
            core.ProcessFailed += (s, e) => { try { core.Reload(); } catch { } };
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

        void Quit()
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
}

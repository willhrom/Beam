package app.beam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements Beam.Listener {
  private static final int PICK = 1;
  private Beam beam;
  private GridLayout peersGrid;
  private TextView status, empty;
  private LinearLayout transfers;
  private String peersKey = "";
  private String pickFor;
  private AlertDialog incomingDialog, pairDialog;
  private String incomingTid;

  static String fmtSize(long n) {
    if (n < 1024) return n + " B";
    if (n < 1048576) return (n / 1024) + " KB";
    if (n < 1073741824L) return String.format("%.1f MB", n / 1048576.0);
    return String.format("%.2f GB", n / 1073741824.0);
  }

  @Override protected void onCreate(Bundle s) {
    super.onCreate(s);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    beam = Beam.get(this);
    build();
    BeamService.start(this);
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 2);
    } else {
      askBattery();
    }
  }

  @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) { askBattery(); }

  /** Android may pause background apps to save battery; ask once to be left running. */
  private void askBattery() {
    PowerManager pm = getSystemService(PowerManager.class);
    if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
    if (getSharedPreferences("beam", MODE_PRIVATE).getBoolean("askedBattery", false)) return;
    getSharedPreferences("beam", MODE_PRIVATE).edit().putBoolean("askedBattery", true).apply();
    Ui.dialog(this).setTitle("Stay reachable")
        .setMessage("To receive files while your phone is asleep, let Beam run without battery restrictions.")
        .setPositiveButton("Allow", (d, w) -> openBatterySetting())
        .setNegativeButton("Not now", null).show();
  }

  private void openBatterySetting() {
    try {
      startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
    } catch (Exception e) {
      startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }
  }

  @Override protected void onResume() {
    super.onResume();
    beam.listeners.add(this);
    beam.kick();
    render();
    // show any request that arrived while we were away
    for (Transfer t : beam.tx.values()) if (t.incoming && Transfer.ASKING.equals(t.state)) { showIncoming(t); break; }
  }

  @Override protected void onPause() {
    super.onPause();
    beam.listeners.remove(this);
  }

  // ---------- layout ----------
  private void build() {
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    ScrollView sv = new ScrollView(this);
    sv.setBackgroundColor(Ui.BG);
    sv.setFillViewport(true);
    LinearLayout root = Ui.col(this);
    int p = Ui.dp(this, 18);
    root.setPadding(p, p, p, p);
    sv.addView(root);

    LinearLayout head = Ui.row(this);
    TextView title = Ui.text(this, "Beam", 24, Ui.TEXT, true);
    head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(Ui.button(this, "Settings", 0x18FFFFFF, Ui.TEXT, v -> showSettings()));
    root.addView(head);

    status = Ui.text(this, "", 13, Ui.DIM, false);
    root.addView(status, Ui.margins(this, -1, -2, 0, 6, 0, 0));

    peersGrid = new GridLayout(this);
    peersGrid.setColumnCount(3);
    LinearLayout.LayoutParams gp = Ui.margins(this, -2, -2, 0, 28, 0, 8);
    gp.gravity = Gravity.CENTER_HORIZONTAL;
    root.addView(peersGrid, gp);

    empty = Ui.text(this, "", 14, Ui.DIM, false);
    empty.setGravity(Gravity.CENTER);
    root.addView(empty, Ui.margins(this, -1, -2, 0, 30, 0, 30));

    transfers = Ui.col(this);
    root.addView(transfers, Ui.margins(this, -1, -2, 0, 16, 0, 0));
    setContentView(sv);
  }

  @Override public void onChange() { render(); }

  @Override public void onEvent(String type, Object arg) {
    if ("incoming".equals(type)) showIncoming((Transfer) arg);
    else if ("pairing".equals(type)) showPairing();
    else if ("toast".equals(type)) Toast.makeText(this, (String) arg, Toast.LENGTH_SHORT).show();
  }

  private void render() {
    status.setText(!beam.prefs.enabled ? "Off — turn on in Settings" : !beam.online ? "Connecting…"
        : "Visible as " + beam.prefs.name + " · " + (beam.prefs.nearby ? "Everyone nearby" : "My devices only"));

    // peers (rebuilt only when something about them changed)
    StringBuilder key = new StringBuilder();
    for (Beam.Peer p : beam.peers) key.append(p.id).append(p.name).append(noteFor(p)).append(beam.prefs.paired.containsKey(p.id)).append('|');
    if (!key.toString().equals(peersKey)) {
      peersKey = key.toString();
      peersGrid.removeAllViews();
      for (Beam.Peer p : beam.peers) {
        LinearLayout b = Ui.peerBubble(this, p, beam.prefs.paired.containsKey(p.id), noteFor(p));
        b.setOnClickListener(v -> chooseWhat(p.id));
        peersGrid.addView(b);
      }
    }
    empty.setVisibility(beam.peers.isEmpty() ? View.VISIBLE : View.GONE);
    empty.setText(beam.online ? "Looking for devices…\nOpen Beam on another device on this WiFi,\nor pair one in Settings." : "");

    // transfers, newest first
    transfers.removeAllViews();
    List<Transfer> list = new ArrayList<>(beam.tx.values());
    for (int i = list.size() - 1; i >= 0; i--) transfers.addView(card(list.get(i)), Ui.margins(this, -1, -2, 0, 0, 0, 10));
  }

  private String noteFor(Beam.Peer p) {
    for (Transfer t : beam.tx.values()) {
      if (t.incoming || !t.peerId.equals(p.id)) continue;
      if (Transfer.WAITING.equals(t.state)) return "Waiting…";
      if (Transfer.CONNECTING.equals(t.state)) return "Connecting…";
      if (Transfer.PAUSED.equals(t.state) || Transfer.RECONNECTING.equals(t.state)) return "Reconnecting…";
      if (Transfer.SENDING.equals(t.state)) return (t.total > 0 ? (int) (100 * t.done / t.total) : 0) + "%";
    }
    return null;
  }

  private View card(Transfer t) {
    LinearLayout c = Ui.col(this);
    int pad = Ui.dp(this, 14);
    c.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
    c.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 14)));

    LinearLayout top = Ui.row(this);
    LinearLayout words = Ui.col(this);
    words.addView(Ui.oneLine(Ui.text(this, (t.incoming ? "From " : "To ") + t.peerName, 14, Ui.TEXT, true)));
    String sub;
    if (Transfer.FAILED.equals(t.state)) sub = t.reason;
    else if (Transfer.DONE.equals(t.state)) sub = (t.incoming ? "Received " : "Sent ") + t.describe() + (t.incoming && hasFiles(t) ? " · in Downloads/Beam" : "");
    else sub = stateText(t.state) + " · " + t.describe() + (t.total > 0 ? " · " + fmtSize(t.total) : "");
    int subColor = Transfer.FAILED.equals(t.state) ? Ui.BAD : Transfer.DONE.equals(t.state) ? Ui.GOOD : Ui.DIM;
    words.addView(Ui.oneLine(Ui.text(this, sub, 12.5f, subColor, false)));
    top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout btns = Ui.row(this);
    if (Transfer.ASKING.equals(t.state)) {
      btns.addView(Ui.button(this, "Decline", 0x18FFFFFF, Ui.TEXT, v -> beam.decline(t.tid)), Ui.margins(this, -2, -2, 6, 0, 0, 0));
      btns.addView(Ui.button(this, "Accept", Ui.ACCENT, Color.WHITE, v -> beam.accept(t.tid)), Ui.margins(this, -2, -2, 6, 0, 0, 0));
    } else if (t.active()) {
      btns.addView(Ui.button(this, "Cancel", 0x18FFFFFF, Ui.TEXT, v -> beam.cancel(t.tid)));
    } else {
      btns.addView(Ui.button(this, "✕", 0x18FFFFFF, Ui.TEXT, v -> beam.clear(t.tid)));
    }
    top.addView(btns);
    c.addView(top);

    if (Transfer.SENDING.equals(t.state) || Transfer.RECEIVING.equals(t.state) || Transfer.PAUSED.equals(t.state) || Transfer.RECONNECTING.equals(t.state)) {
      ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      bar.setMax(1000);
      bar.setProgress(t.total > 0 ? (int) (1000 * t.done / t.total) : 0);
      bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT2));
      c.addView(bar, Ui.margins(this, -1, Ui.dp(this, 8), 0, 8, 0, 0));
    }

    if (t.incoming && Transfer.DONE.equals(t.state)) {
      for (Transfer.Item it : t.items) {
        if (it.text && it.textValue != null) {
          TextView tv = Ui.text(this, it.textValue, 14, Ui.TEXT, false);
          tv.setTextIsSelectable(true);
          tv.setBackground(Ui.round(0x66000000, Ui.dp(this, 10)));
          int p2 = Ui.dp(this, 10);
          tv.setPadding(p2, p2, p2, p2);
          c.addView(tv, Ui.margins(this, -1, -2, 0, 8, 0, 0));
          LinearLayout r = Ui.row(this);
          String s = it.textValue.trim();
          if (s.matches("^https?://\\S+$")) r.addView(Ui.button(this, "Open link", Ui.ACCENT, Color.WHITE, v -> open(Uri.parse(s), null)), Ui.margins(this, -2, -2, 0, 0, 8, 0));
          r.addView(Ui.button(this, "Copy", 0x18FFFFFF, Ui.TEXT, v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Beam", it.textValue));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
          }));
          c.addView(r, Ui.margins(this, -1, -2, 0, 8, 0, 0));
        } else if (it.saved != null) {
          LinearLayout r = Ui.row(this);
          r.addView(Ui.oneLine(Ui.text(this, it.name, 13.5f, Ui.TEXT, false)), new LinearLayout.LayoutParams(0, -2, 1));
          r.addView(Ui.button(this, "Open", 0x18FFFFFF, Ui.TEXT, v -> open(it.saved, it.type)));
          c.addView(r, Ui.margins(this, -1, -2, 0, 8, 0, 0));
        }
      }
    }
    return c;
  }

  private static boolean hasFiles(Transfer t) {
    for (Transfer.Item i : t.items) if (!i.text) return true;
    return false;
  }

  private static String stateText(String s) {
    switch (s) {
      case Transfer.PREPARING: return "Preparing…";
      case Transfer.WAITING: return "Waiting for them to accept…";
      case Transfer.ASKING: return "Wants to send you this";
      case Transfer.CONNECTING: return "Connecting…";
      case Transfer.SENDING: return "Sending";
      case Transfer.RECEIVING: return "Receiving";
      case Transfer.FINISHING: return "Finishing…";
      case Transfer.PAUSED: return "Connection lost — will resume";
      case Transfer.RECONNECTING: return "Reconnecting…";
      default: return s;
    }
  }

  private void open(Uri u, String type) {
    try {
      Intent i = new Intent(Intent.ACTION_VIEW);
      if (type != null) i.setDataAndType(u, type); else i.setData(u);
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(i);
    } catch (Exception e) {
      Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show();
    }
  }

  // ---------- sending ----------
  private void chooseWhat(String peerId) {
    Beam.Peer p = beam.peer(peerId);
    if (p == null) return;
    Ui.dialog(this).setTitle("Send to " + p.name)
        .setItems(new String[] { "Files or photos", "Text or link" }, (d, which) -> {
          if (which == 0) {
            pickFor = peerId;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, PICK);
          } else {
            askText(peerId);
          }
        }).show();
  }

  private void askText(String peerId) {
    Beam.Peer p = beam.peer(peerId);
    if (p == null) return;
    EditText e = new EditText(this);
    e.setHint("Paste a link or type something");
    e.setMinLines(3);
    ClipboardManager cm = getSystemService(ClipboardManager.class);
    AlertDialog dlg = Ui.dialog(this).setTitle("Send text to " + p.name).setView(pad(e))
        .setPositiveButton("Send", (d, w) -> {
          Beam.Peer peer = beam.peer(peerId);
          String s = e.getText().toString();
          if (peer != null && !s.trim().isEmpty()) Items.build(this, new ArrayList<>(), s, false, (items, err) -> { if (items != null) beam.send(peer, items); });
        })
        .setNeutralButton("Paste", null)
        .setNegativeButton("Cancel", null).create();
    dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
      ClipData clip = cm.getPrimaryClip();
      if (clip != null && clip.getItemCount() > 0) e.setText(clip.getItemAt(0).coerceToText(this));
    }));
    dlg.show();
  }

  private View pad(View v) {
    LinearLayout l = Ui.col(this);
    int p = Ui.dp(this, 20);
    l.setPadding(p, Ui.dp(this, 8), p, 0);
    l.addView(v, Ui.lp(-1, -2));
    return l;
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req != PICK || res != RESULT_OK || data == null) return;
    List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
    else if (data.getData() != null) uris.add(data.getData());
    for (Uri u : uris) {
      try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
    }
    final String peerId = pickFor;
    Items.build(this, uris, null, false, (items, err) -> {
      Beam.Peer p = beam.peer(peerId);
      if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
      else if (p == null) Toast.makeText(this, "That device went away", Toast.LENGTH_SHORT).show();
      else if (!items.isEmpty()) beam.send(p, items);
    });
  }

  // ---------- incoming ----------
  private void showIncoming(Transfer t) {
    if (incomingDialog != null && incomingDialog.isShowing()) return;
    incomingTid = t.tid;
    StringBuilder msg = new StringBuilder("wants to send you " + t.describe());
    if (t.total > 0) msg.append(" (").append(fmtSize(t.total)).append(")");
    msg.append("\n\n");
    for (int i = 0; i < Math.min(6, t.items.size()); i++) {
      Transfer.Item it = t.items.get(i);
      msg.append("• ").append(it.text ? "Text" : it.name).append(it.text ? "" : "  " + fmtSize(it.size)).append('\n');
    }
    if (t.items.size() > 6) msg.append("and ").append(t.items.size() - 6).append(" more\n");
    msg.append("\n").append(t.paired ? "My device" : "Device " + Crypto.shortId(t.peerId));
    incomingDialog = Ui.dialog(this).setTitle(t.peerName).setMessage(msg.toString())
        .setPositiveButton("Accept", (d, w) -> beam.accept(t.tid))
        .setNegativeButton("Decline", (d, w) -> beam.decline(t.tid))
        .setCancelable(false).create();
    incomingDialog.show();
    beam.main.postDelayed(new Runnable() {
      @Override public void run() {
        if (incomingDialog == null || !incomingDialog.isShowing() || !t.tid.equals(incomingTid)) return;
        if (!Transfer.ASKING.equals(t.state)) incomingDialog.dismiss();
        else beam.main.postDelayed(this, 500);
      }
    }, 500);
  }

  // ---------- settings & pairing ----------
  private void showSettings() {
    LinearLayout l = Ui.col(this);
    int p = Ui.dp(this, 20);
    l.setPadding(p, Ui.dp(this, 8), p, 0);
    EditText name = new EditText(this);
    name.setText(beam.prefs.name);
    name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    l.addView(label("Device name"));
    l.addView(name);
    Switch on = sw("Run in the background", beam.prefs.enabled);
    Switch nb = sw("Everyone nearby can see me", beam.prefs.nearby);
    Switch aa = sw("Auto-accept from my devices", beam.prefs.autoAccept);
    l.addView(on);
    l.addView(nb);
    l.addView(aa);
    l.addView(Ui.text(this, "Device code " + Crypto.shortId(beam.me.id), 12, Ui.DIM, false), Ui.margins(this, -1, -2, 0, 8, 0, 0));

    l.addView(label("My devices"));
    if (beam.prefs.paired.isEmpty()) l.addView(Ui.text(this, "None yet. Paired devices find each other anywhere, not just on the same WiFi.", 13, Ui.DIM, false));
    final AlertDialog[] self = new AlertDialog[1];
    for (Prefs.Paired pd : new ArrayList<>(beam.prefs.paired.values())) {
      LinearLayout r = Ui.row(this);
      r.addView(Ui.oneLine(Ui.text(this, pd.name + "  " + Crypto.shortId(pd.id), 14, Ui.TEXT, false)), new LinearLayout.LayoutParams(0, -2, 1));
      r.addView(Ui.button(this, "Remove", 0x22F87171, Ui.BAD, v -> {
        beam.unpair(pd.id);
        self[0].dismiss();
        showSettings();
      }));
      l.addView(r, Ui.margins(this, -1, -2, 0, 4, 0, 4));
    }
    LinearLayout pr = Ui.row(this);
    pr.addView(Ui.button(this, "Show a code", 0x18FFFFFF, Ui.TEXT, v -> { self[0].dismiss(); beam.pairShow(); }), new LinearLayout.LayoutParams(0, -2, 1));
    pr.addView(new View(this), Ui.lp(Ui.dp(this, 10), 1));
    pr.addView(Ui.button(this, "Enter a code", 0x18FFFFFF, Ui.TEXT, v -> { self[0].dismiss(); beam.pairJoin(); }), new LinearLayout.LayoutParams(0, -2, 1));
    l.addView(pr, Ui.margins(this, -1, -2, 0, 10, 0, 0));
    PowerManager pm = getSystemService(PowerManager.class);
    if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
      l.addView(Ui.button(this, "Allow background use (battery)", 0x18FFFFFF, Ui.TEXT, v -> openBatterySetting()), Ui.margins(this, -1, -2, 0, 10, 0, 0));
    }
    ScrollView sv = new ScrollView(this);
    sv.addView(l);
    self[0] = Ui.dialog(this).setTitle("Settings").setView(sv)
        .setPositiveButton("Done", null).create();
    self[0].setOnDismissListener(d -> {
      String n = Beam.trim(name.getText().toString());
      boolean wasOn = beam.prefs.enabled;
      beam.prefs.name = n.isEmpty() ? beam.prefs.name : n;
      beam.prefs.nearby = nb.isChecked();
      beam.prefs.autoAccept = aa.isChecked();
      beam.prefs.enabled = on.isChecked();
      beam.prefs.save();
      if (wasOn && !beam.prefs.enabled) BeamService.stop(this);
      else if (!wasOn && beam.prefs.enabled) BeamService.start(this);
      beam.updateServer();
    });
    self[0].show();
  }

  private TextView label(String s) {
    TextView t = Ui.text(this, s.toUpperCase(), 12, Ui.DIM, true);
    t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
    return t;
  }

  private Switch sw(String label, boolean on) {
    Switch s = new Switch(this);
    s.setText(label);
    s.setChecked(on);
    s.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
    return s;
  }

  private void showPairing() {
    if (pairDialog != null) { pairDialog.setOnDismissListener(null); pairDialog.dismiss(); pairDialog = null; }
    Beam.Pairing p = beam.pairing;
    if (p == null) return;
    AlertDialog.Builder b = Ui.dialog(this);
    if (p.show && p.peer == null) {
      String code = p.code == null ? "······" : p.code.substring(0, 3) + " " + p.code.substring(3);
      b.setTitle("Pair a device").setView(pad(bigText("On your other device, open Beam → Settings → Enter a code, and type:", code, "Expires in 10 minutes.")))
          .setNegativeButton("Cancel", null);
    } else if (p.show) {
      b.setTitle("Pair with " + p.peer.name + "?").setView(pad(bigText("Make sure " + p.peer.name + " shows this same number:", p.safety, null)))
          .setPositiveButton("Pair", (d, w) -> beam.pairConfirm(true))
          .setNegativeButton("Doesn't match", (d, w) -> beam.pairConfirm(false));
    } else if (p.peer == null) {
      EditText e = new EditText(this);
      e.setInputType(InputType.TYPE_CLASS_NUMBER);
      e.setHint("123456");
      e.setTextSize(24);
      e.setGravity(Gravity.CENTER);
      LinearLayout l = Ui.col(this);
      l.addView(Ui.text(this, "Shown on your other device under Settings → Show a code.", 14, Ui.DIM, false));
      l.addView(e);
      if (p.error != null) l.addView(Ui.text(this, p.error, 14, Ui.BAD, false));
      b.setTitle("Enter pairing code").setView(pad(l))
          .setPositiveButton("Pair", (d, w) -> {
            String c = e.getText().toString().replaceAll("\\D", "");
            if (c.length() == 6) beam.pairSubmit(c);
          })
          .setNegativeButton("Cancel", null);
    } else {
      b.setTitle("Pairing with " + p.peer.name).setView(pad(bigText("Check that " + p.peer.name + " shows this same number, then accept there:", p.safety, null)))
          .setNegativeButton("Cancel", null);
    }
    pairDialog = b.create();
    final Beam.Pairing mine = p;
    pairDialog.setOnDismissListener(d -> {
      // Closing the dialog cancels pairing, except while we're waiting on the server's reply to a code.
      if (beam.pairing == mine && !mine.submitted) beam.pairCancel();
    });
    pairDialog.show();
  }

  private View bigText(String top, String big, String bottom) {
    LinearLayout l = Ui.col(this);
    l.addView(Ui.text(this, top, 14, Ui.DIM, false));
    TextView c = Ui.text(this, big, 32, Ui.ACCENT2, true);
    c.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
    c.setGravity(Gravity.CENTER);
    c.setLetterSpacing(0.15f);
    c.setTextIsSelectable(true);
    l.addView(c, Ui.margins(this, -1, -2, 0, 16, 0, 16));
    if (bottom != null) l.addView(Ui.text(this, bottom, 12, Ui.DIM, false));
    return l;
  }
}

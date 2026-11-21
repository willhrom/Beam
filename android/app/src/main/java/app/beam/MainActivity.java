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
  private static final int PICK = 1, PICK_FOLDER = 3, PICK_SAVE_DIR = 4;
  private Beam beam;
  private GridLayout peersGrid;
  private TextView status, empty;
  private android.widget.Button manyBtn;
  private LinearLayout transfers;
  private String peersKey = "";
  // Views kept between redraws, so animations aren't cut short and progress updates are cheap.
  private final java.util.Map<String, LinearLayout> bubbles = new java.util.HashMap<>();
  private final java.util.Map<String, String> bubbleKeys = new java.util.HashMap<>();
  private final java.util.Map<String, View> cards = new java.util.HashMap<>();
  private final java.util.Map<String, String> cardKeys = new java.util.HashMap<>();
  private final java.util.Map<String, ProgressBar> bars = new java.util.HashMap<>();
  private final java.util.Map<String, String> txSeen = new java.util.HashMap<>();
  private final java.util.Set<String> peersSeen = new java.util.HashSet<>();
  private android.widget.FrameLayout overlay;
  private View radarBox;
  private LinearLayout emptyBox;
  private android.animation.ValueAnimator pulseAnim;
  private final List<android.animation.ValueAnimator> radarAnims = new ArrayList<>();
  private final java.util.Map<String, View[]> orbits = new java.util.HashMap<>();
  private final java.util.Map<String, android.animation.ValueAnimator> knocks = new java.util.HashMap<>();
  private boolean updateOffered;
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
    UpdateJob.schedule(this);
    BeamService.start(this);
    List<String> need = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      need.add(Manifest.permission.POST_NOTIFICATIONS);
    }
    // Bluetooth + nearby Wi-Fi, for offline sharing with other phones
    for (String p : Nearby.permissions()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
    if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 2);
    else askBattery();
  }

  @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
    beam.nearby.refresh(); // starts advertising, and discovery if this screen is showing
    askBattery();
  }

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

  /** Beam's screen is showing (so it may open a link right away). */
  static volatile boolean inFront;

  @Override protected void onResume() {
    super.onResume();
    inFront = true;
    beam.listeners.add(this);
    beam.start(); // always connected while Beam is open, whatever the background setting
    beam.kick();
    beam.nearby.discover(true);
    render();
    if (beam.needLogin) showSignIn(null);
    // show any request that arrived while we were away
    for (Transfer t : beam.tx.values()) if (t.incoming && Transfer.ASKING.equals(t.state)) { showIncoming(t); break; }
  }

  @Override protected void onDestroy() {
    if (signInDialog != null) { signInDialog.dismiss(); signInDialog = null; }
    for (android.animation.ValueAnimator ra : radarAnims) ra.cancel();
    if (pulseAnim != null) pulseAnim.cancel();
    super.onDestroy();
  }

  @Override protected void onStop() {
    super.onStop();
    // Not staying reachable in the background: disconnect once the screen is closed and nothing is moving.
    if (!beam.prefs.enabled && !BeamService.running) stopWhenIdle();
  }

  private void stopWhenIdle() {
    beam.main.postDelayed(() -> {
      if (beam.prefs.enabled || BeamService.running || hasWindowFocus()) return;
      for (Transfer t : beam.tx.values()) if (t.active() && !Transfer.ASKING.equals(t.state)) { stopWhenIdle(); return; }
      beam.stop();
    }, 5000);
  }

  @Override protected void onPause() {
    super.onPause();
    inFront = false;
    beam.listeners.remove(this);
    beam.nearby.discover(false);
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
    manyBtn = Ui.button(this, "Send to several devices", 0x18FFFFFF, Ui.TEXT, v -> chooseWhatMany());
    manyBtn.setVisibility(View.GONE);
    root.addView(manyBtn, Ui.margins(this, -2, -2, 0, 10, 0, 0));

    peersGrid = new GridLayout(this);
    peersGrid.setColumnCount(3);
    LinearLayout.LayoutParams gp = Ui.margins(this, -1, -2, 0, 28, 0, 8); // full width: the three columns share it
    gp.gravity = Gravity.CENTER_HORIZONTAL;
    root.addView(peersGrid, gp);

    // "Looking for devices": rings keep spreading out of a dot, like the web app's sonar.
    // The sonar and its text sit in the middle of the empty space.
    emptyBox = Ui.col(this);
    emptyBox.setGravity(Gravity.CENTER);
    android.widget.FrameLayout rb = new android.widget.FrameLayout(this);
    for (int i = 0; i < 3; i++) {
      View ring = new View(this);
      android.graphics.drawable.GradientDrawable rg = new android.graphics.drawable.GradientDrawable();
      rg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
      rg.setStroke(Ui.dp(this, 1.5f), Ui.ACCENT2);
      ring.setBackground(rg);
      ring.setAlpha(0f);
      rb.addView(ring, new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 220), Ui.dp(this, 220), Gravity.CENTER));
      android.animation.ValueAnimator ra = Fx.radar(ring);
      ra.setStartDelay(i * 900L);
      radarAnims.add(ra);
    }
    // Two faint fixed rings, so the sonar reads even when it's still.
    for (int d : new int[] { 120, 200 }) {
      View fixed = new View(this);
      android.graphics.drawable.GradientDrawable fg = new android.graphics.drawable.GradientDrawable();
      fg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
      fg.setStroke(Ui.dp(this, 1), Ui.LINE);
      fixed.setBackground(fg);
      rb.addView(fixed, new android.widget.FrameLayout.LayoutParams(Ui.dp(this, d), Ui.dp(this, d), Gravity.CENTER));
    }
    View dot = new View(this);
    android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
    dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    dg.setColor(Ui.ACCENT2);
    dot.setBackground(dg);
    rb.addView(dot, new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14), Gravity.CENTER));
    radarBox = rb;
    emptyBox.addView(rb, Ui.lp(Ui.dp(this, 240), Ui.dp(this, 240)));

    empty = Ui.text(this, "", 14, Ui.DIM, false);
    empty.setGravity(Gravity.CENTER);
    emptyBox.addView(empty, Ui.margins(this, -1, -2, 0, 12, 0, 0));
    root.addView(emptyBox, new LinearLayout.LayoutParams(-1, 0, 1));

    transfers = Ui.col(this);
    root.addView(transfers, Ui.margins(this, -1, -2, 0, 16, 0, 0));

    // A see-through layer over everything, for bursts.
    android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
    frame.addView(sv, new android.widget.FrameLayout.LayoutParams(-1, -1));
    overlay = new android.widget.FrameLayout(this);
    overlay.setClickable(false);
    overlay.setFocusable(false);
    frame.addView(overlay, new android.widget.FrameLayout.LayoutParams(-1, -1));
    setContentView(frame);

    // While sending, the device you're sending to gently pulses.
    pulseAnim = android.animation.ValueAnimator.ofFloat(0, 1);
    pulseAnim.setDuration(900);
    pulseAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
    pulseAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
    pulseAnim.addUpdateListener(a -> {
      float f = (float) a.getAnimatedValue();
      float phase = (android.os.SystemClock.uptimeMillis() % 1400) / 1400f;
      for (Transfer t : beam.tx.values()) {
        if (!(Transfer.SENDING.equals(t.state) || Transfer.RECEIVING.equals(t.state) || Transfer.FINISHING.equals(t.state))) continue;
        LinearLayout b = bubbles.get(t.peerId);
        View circle = b != null && b.getChildCount() > 0 ? b.getChildAt(0) : null;
        if (circle != null) {
          circle.setScaleX(1f + 0.07f * f);
          circle.setScaleY(1f + 0.07f * f);
        }
        // Two dots circle the device while data flows (clockwise out, the other way in).
        View[] dots = orbits.get(t.tid);
        if (dots == null) { dots = new View[] { Fx.dot(overlay), Fx.dot(overlay) }; orbits.put(t.tid, dots); }
        Fx.orbit(overlay, circle, dots, phase, t.incoming);
      }
    });
  }

  @Override public void onChange() { render(); }

  @Override public void onEvent(String type, Object arg) {
    if ("incoming".equals(type)) showIncoming((Transfer) arg);
    else if ("pairing".equals(type)) showPairing();
    else if ("toast".equals(type)) Toast.makeText(this, (String) arg, Toast.LENGTH_SHORT).show();
    else if ("login".equals(type)) showSignIn(null);
  }

  private void render() {
    if (Updater.available != null && Updater.available.version > Updater.current() && !updateOffered) {
      updateOffered = true;
      Ui.dialog(this).setTitle("Beam update available").setMessage("Version 1.0." + Updater.available.version + " is ready to install.")
          .setPositiveButton("Install", (d, w) -> startActivity(new Intent(this, UpdateActivity.class)))
          .setNegativeButton("Later", null).show();
    }
    status.setText(beam.needLogin ? "Sign in to use Beam"
        : !beam.online ? (beam.nearby.usable() ? "No internet · offline sharing with nearby phones" : "Connecting…")
        : "Visible as " + beam.prefs.name + " · " + (beam.prefs.nearby ? "Everyone nearby" : "My devices only")
          + (beam.prefs.enabled ? "" : "\nOnly while Beam is open. To receive when it's closed, turn on \"Stay reachable\" in Settings."));

    // peers: each bubble is rebuilt only when something about that device changed
    List<Beam.Peer> all = beam.allPeers();
    List<View> want = new ArrayList<>();
    java.util.Set<String> ids = new java.util.HashSet<>();
    int fresh = 0;
    for (Beam.Peer p : all) {
      ids.add(p.id);
      boolean mine = beam.prefs.paired.containsKey(p.id) || p.mine;
      String k = p.name + "|" + noteFor(p) + "|" + mine + "|" + beam.isOfflineOnly(p.id) + "|" + p.kind + "|" + beam.isFav(p.id);
      LinearLayout b = bubbles.get(p.id);
      if (b == null || !k.equals(bubbleKeys.get(p.id))) {
        b = Ui.peerBubble(this, p, mine, noteFor(p), beam.isOfflineOnly(p.id));
        if (beam.isFav(p.id)) Ui.addStar(this, b);
        b.setOnClickListener(v -> chooseWhat(p.id));
        Fx.squishOnTouch(b);
        bubbles.put(p.id, b);
        bubbleKeys.put(p.id, k);
      }
      if (peersSeen.add(p.id)) Fx.popIn(b, fresh++);
      want.add(b);
    }
    // My account's devices that are offline: dimmed, after the ones I can send to.
    for (Beam.Peer p : beam.offline) {
      if (ids.contains(p.id)) continue;
      ids.add(p.id);
      String k = "off|" + p.name + "|" + p.kind + "|" + beam.isFav(p.id);
      LinearLayout b = bubbles.get(p.id);
      if (b == null || !k.equals(bubbleKeys.get(p.id))) {
        b = Ui.offlineBubble(this, p);
        if (beam.isFav(p.id)) Ui.addStar(this, b);
        b.setAlpha(0.5f);
        b.setOnClickListener(v -> offlineDevice(p));
        bubbles.put(p.id, b);
        bubbleKeys.put(p.id, k);
      }
      if (peersSeen.add(p.id)) Fx.popIn(b, fresh++);
      want.add(b);
    }
    bubbles.keySet().retainAll(ids);
    bubbleKeys.keySet().retainAll(ids);
    peersSeen.retainAll(ids);
    if (!sameChildren(peersGrid, want)) {
      peersGrid.removeAllViews();
      for (View v : want) {
        Fx.detach(v);
        // Three equal columns that always fit the screen.
        GridLayout.LayoutParams glp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        glp.width = 0;
        peersGrid.addView(v, glp);
      }
    }
    empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    emptyBox.setVisibility(all.isEmpty() && beam.offline.isEmpty() ? View.VISIBLE : View.GONE);
    boolean looking = all.isEmpty() && (beam.online || beam.nearby.usable());
    radarBox.setVisibility(looking ? View.VISIBLE : View.GONE);
    for (android.animation.ValueAnimator ra : radarAnims) {
      if (looking && Fx.on() && !ra.isStarted()) ra.start();
      else if (!looking && ra.isStarted()) ra.cancel();
    }
    manyBtn.setVisibility(all.size() > 1 ? View.VISIBLE : View.GONE);
    empty.setText(beam.online || beam.nearby.usable() ? "Looking for devices…\nOpen Beam on another device on this WiFi,\nor pair one in Settings." : "");

    // transfers, newest first: a card is rebuilt only when its state or contents change;
    // progress just moves the existing bar.
    List<Transfer> list = new ArrayList<>(beam.tx.values());
    List<View> wantCards = new ArrayList<>();
    java.util.Set<String> tids = new java.util.HashSet<>();
    boolean anyMoving = false;
    for (int i = list.size() - 1; i >= 0; i--) {
      Transfer t = list.get(i);
      tids.add(t.tid);
      int saved = 0;
      for (Transfer.Item it : t.items) if (it.saved != null || it.textValue != null) saved++;
      String k = t.state + "|" + t.reason + "|" + t.direct() + "|" + saved + "|" + t.describe();
      View c = cards.get(t.tid);
      if (c == null || !k.equals(cardKeys.get(t.tid))) {
        c = card(t);
        cards.put(t.tid, c);
        cardKeys.put(t.tid, k);
      }
      ProgressBar bar = bars.get(t.tid);
      if (bar != null && bar.getParent() != null && t.total > 0) bar.setProgress((int) (1000 * t.shown() / t.total));
      wantCards.add(c);
      if (Transfer.SENDING.equals(t.state) || Transfer.RECEIVING.equals(t.state) || Transfer.FINISHING.equals(t.state)) anyMoving = true;

      // React to state changes.
      String prev = txSeen.put(t.tid, t.state);
      LinearLayout pb = bubbles.get(t.peerId);
      final View circle = pb != null && pb.getChildCount() > 0 ? pb.getChildAt(0) : null;
      if (prev == null) {
        Fx.slideIn(c);
        // You hit send: the file flies up to the device.
        if (!t.incoming) {
          int files = 0;
          for (Transfer.Item it : t.items) if (!it.text) files++;
          final int nFiles = files;
          beam.main.postDelayed(() -> Fx.launch(overlay, circle, nFiles, null), 40);
        }
      }
      // Someone wants to send you something: their device wiggles until you answer.
      boolean asking = Transfer.ASKING.equals(t.state) && circle != null && Fx.on();
      android.animation.ValueAnimator kn = knocks.get(t.tid);
      if (asking && kn == null) { kn = Fx.knock(circle); knocks.put(t.tid, kn); kn.start(); }
      else if (!asking && kn != null) { kn.cancel(); knocks.remove(t.tid); }
      if (prev != null && !prev.equals(t.state)) {
        if (!(Transfer.SENDING.equals(t.state) || Transfer.RECEIVING.equals(t.state) || Transfer.FINISHING.equals(t.state))) {
          View[] dots = orbits.remove(t.tid);
          if (dots != null) for (View d : dots) overlay.removeView(d);
        }
        if (Transfer.DONE.equals(t.state)) {
          Fx.bounce(circle);
          View badge = c.findViewWithTag("badge");
          Fx.bounce(badge);
          final View target = t.incoming || circle == null ? badge : circle;
          final int n = t.incoming ? 18 : 12;
          beam.main.postDelayed(() -> Fx.burst(overlay, target, n), 60);
          if (t.incoming) beam.main.postDelayed(() -> Fx.confetti(overlay, 46), 120);
          else beam.main.postDelayed(() -> Fx.rise(overlay, circle, "Sent"), 120);
        } else if (Transfer.FAILED.equals(t.state) && !Transfer.ASKING.equals(prev)) {
          Fx.shake(c);
        }
      }
    }
    cards.keySet().retainAll(tids);
    cardKeys.keySet().retainAll(tids);
    bars.keySet().retainAll(tids);
    txSeen.keySet().retainAll(tids);
    for (java.util.Iterator<java.util.Map.Entry<String, View[]>> it = orbits.entrySet().iterator(); it.hasNext(); ) {
      java.util.Map.Entry<String, View[]> e = it.next();
      if (!tids.contains(e.getKey())) { for (View d : e.getValue()) overlay.removeView(d); it.remove(); }
    }
    for (java.util.Iterator<java.util.Map.Entry<String, android.animation.ValueAnimator>> it = knocks.entrySet().iterator(); it.hasNext(); ) {
      java.util.Map.Entry<String, android.animation.ValueAnimator> e = it.next();
      if (!tids.contains(e.getKey())) { e.getValue().cancel(); it.remove(); }
    }
    if (!sameChildren(transfers, wantCards)) {
      transfers.removeAllViews();
      for (View v : wantCards) { Fx.detach(v); transfers.addView(v, Ui.margins(this, -1, -2, 0, 0, 0, 10)); }
    }
    if (anyMoving && Fx.on() && !pulseAnim.isStarted()) pulseAnim.start();
    else if (!anyMoving && pulseAnim.isStarted()) {
      pulseAnim.cancel();
      for (View[] dots : orbits.values()) for (View d : dots) overlay.removeView(d);
      orbits.clear();
      for (LinearLayout b : bubbles.values()) if (b.getChildCount() > 0) { b.getChildAt(0).setScaleX(1f); b.getChildAt(0).setScaleY(1f); }
    }
  }

  private static boolean sameChildren(android.view.ViewGroup g, List<View> want) {
    if (g.getChildCount() != want.size()) return false;
    for (int i = 0; i < want.size(); i++) if (g.getChildAt(i) != want.get(i)) return false;
    return true;
  }

  private String noteFor(Beam.Peer p) {
    for (Transfer t : beam.tx.values()) {
      if (t.incoming || !t.peerId.equals(p.id)) continue;
      if (Transfer.WAITING.equals(t.state)) return "Waiting…";
      if (Transfer.CONNECTING.equals(t.state)) return "Connecting…";
      if (Transfer.PAUSED.equals(t.state) || Transfer.RECONNECTING.equals(t.state)) return "Reconnecting…";
      if (Transfer.SENDING.equals(t.state)) return (t.total > 0 ? (int) (100 * t.shown() / t.total) : 0) + "%";
    }
    return null;
  }

  private View card(Transfer t) {
    LinearLayout c = Ui.col(this);
    int pad = Ui.dp(this, 14);
    c.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
    c.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 14)));

    LinearLayout top = Ui.row(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    // A round badge: arrow while moving, check when done, cross when it failed.
    boolean ok = Transfer.DONE.equals(t.state), bad = Transfer.FAILED.equals(t.state);
    TextView badge = Ui.text(this, ok ? "✓" : bad ? "!" : t.incoming ? "↓" : "↑", 15, ok ? Ui.GOOD : bad ? Ui.BAD : Ui.ACCENT2, true);
    badge.setGravity(Gravity.CENTER);
    badge.setTag("badge");
    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
    bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    bg.setColor(ok ? 0x1F4ADE9A : bad ? 0x1FFF7B7B : 0x1F5CC8FF);
    badge.setBackground(bg);
    top.addView(badge, Ui.margins(this, Ui.dp(this, 34), Ui.dp(this, 34), 0, 0, 12, 0));
    LinearLayout words = Ui.col(this);
    TextView who = Ui.text(this, (t.incoming ? "From " : "To ") + t.peerName, 14, Ui.TEXT, true);
    who.setMaxLines(3);
    who.setEllipsize(android.text.TextUtils.TruncateAt.END);
    words.addView(who);
    String sub;
    if (Transfer.FAILED.equals(t.state)) sub = t.reason;
    else if (Transfer.DONE.equals(t.state)) sub = (t.incoming ? "Received " : "Sent ") + t.describe() + (t.incoming && t.savedWhere() != null ? " · " + t.savedWhere() : "");
    else sub = stateText(t.state) + (t.direct() ? " · direct" : "") + " · " + t.describe() + (t.total > 0 ? " · " + fmtSize(t.total) : "");
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
      if (t.canSendAgain()) btns.addView(Ui.button(this, "Send again", 0x18FFFFFF, Ui.TEXT, v -> beam.sendAgain(t.tid)), Ui.margins(this, -2, -2, 0, 0, 6, 0));
      btns.addView(Ui.button(this, "✕", 0x18FFFFFF, Ui.TEXT, v -> beam.clear(t.tid)));
    }
    top.addView(btns);
    c.addView(top);

    if (Transfer.SENDING.equals(t.state) || Transfer.RECEIVING.equals(t.state) || Transfer.FINISHING.equals(t.state) || Transfer.PAUSED.equals(t.state) || Transfer.RECONNECTING.equals(t.state)) {
      ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      bar.setMax(1000);
      bar.setProgress(t.total > 0 ? (int) (1000 * t.shown() / t.total) : 0);
      bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT2));
      c.addView(bar, Ui.margins(this, -1, Ui.dp(this, 8), 0, 8, 0, 0));
      bars.put(t.tid, bar);
    }

    if (t.incoming && Transfer.DONE.equals(t.state)) {
      int shown = 0, more = 0;
      for (Transfer.Item it : t.items) {
        // Long batches list the first few files; thousands of rows would make the screen crawl.
        if (!it.text && it.saved != null && ++shown > 25) { more++; continue; }
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
      if (more > 0) c.addView(Ui.text(this, "and " + more + " more file" + (more == 1 ? "" : "s"), 13, Ui.DIM, false), Ui.margins(this, -1, -2, 0, 8, 0, 0));
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
  /** One of my devices that isn't connected right now. */
  private void offlineDevice(Beam.Peer p) {
    Ui.dialog(this).setCustomTitle(Ui.title(this, p.name + " is offline"))
        .setMessage("Last seen " + ago(p.seen) + ". When Beam is open on it again, it'll be ready to send to here.\n\n"
            + "Replaced this device, or don't use it any more? Forget it to remove it from your account. Signing in on it again brings it back.")
        .setPositiveButton("OK", null)
        .setNegativeButton("Forget device", (d, w) -> {
          beam.forgetDevice(p.id);
          Toast.makeText(this, p.name + " removed from your account", Toast.LENGTH_SHORT).show();
        }).show();
  }

  static String ago(long ms) {
    if (ms <= 0) return "a while ago";
    long m = Math.round((System.currentTimeMillis() - ms) / 60000.0);
    if (m < 2) return "just now";
    if (m < 60) return m + " minutes ago";
    long h = Math.round(m / 60.0);
    if (h < 24) return h + (h == 1 ? " hour ago" : " hours ago");
    long d = Math.round(h / 24.0);
    return d + (d == 1 ? " day ago" : " days ago");
  }

  private void chooseWhat(String peerId) {
    Beam.Peer p = beam.peer(peerId);
    if (p == null) return;
    boolean fav = beam.isFav(peerId);
    Ui.dialog(this).setCustomTitle(Ui.title(this, "Send to " + p.name))
        .setNeutralButton(fav ? "★ Unpin" : "☆ Pin to top", (d, w) -> {
          beam.toggleFav(peerId);
          Toast.makeText(this, p.name + (fav ? " unpinned" : " pinned to the top"), Toast.LENGTH_SHORT).show();
        })
        .setItems(new String[] { "Files or photos", "A folder", "Text or link" }, (d, which) -> {
          if (which == 0) {
            pickFor = peerId;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, PICK);
          } else if (which == 1) {
            pickFor = peerId;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
          } else {
            askText(peerId);
          }
        }).show();
  }

  /** "Send to several devices": pick what to send, then tick the devices. */
  private void chooseWhatMany() {
    Ui.dialog(this).setTitle("Send to several devices")
        .setItems(new String[] { "Files or photos", "A folder", "Text or link" }, (d, which) -> {
          pickFor = null; // null = ask which devices afterwards
          if (which == 0) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK);
          else if (which == 1) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
          else askTextMany();
        }).show();
  }

  private void askTextMany() {
    EditText e = new EditText(this);
    e.setHint("Paste a link or type something");
    e.setMinLines(3);
    Ui.dialog(this).setTitle("Send text to several devices").setView(pad(e))
        .setPositiveButton("Next", (d, w) -> {
          String s = e.getText().toString();
          if (!s.trim().isEmpty()) Items.build(this, new ArrayList<>(), s, false, (items, err) -> { if (items != null) pickRecipients(items); });
        })
        .setNegativeButton("Cancel", null).show();
  }

  /** The device list with checkboxes: pick any number, then one Send. */
  private void pickRecipients(final List<Transfer.Item> items) {
    final List<Beam.Peer> peers = beam.allPeers();
    if (peers.isEmpty()) { Toast.makeText(this, "No devices around right now", Toast.LENGTH_SHORT).show(); return; }
    String[] names = new String[peers.size()];
    final boolean[] on = new boolean[peers.size()];
    for (int i = 0; i < peers.size(); i++) names[i] = peers.get(i).name + (beam.prefs.paired.containsKey(peers.get(i).id) || peers.get(i).mine ? "  ·  my device" : "");
    long total = 0;
    for (Transfer.Item it : items) total += it.size;
    String what = items.size() == 1 ? (items.get(0).text ? "text" : items.get(0).name) : items.size() + " items";
    final AlertDialog[] dlg = new AlertDialog[1];
    dlg[0] = Ui.dialog(this).setTitle("Send " + what + (total > 0 ? " (" + fmtSize(total) + ")" : ""))
        .setMultiChoiceItems(names, on, (d, which, checked) -> {
          on[which] = checked;
          int n = 0;
          for (boolean b : on) if (b) n++;
          android.widget.Button go = dlg[0].getButton(AlertDialog.BUTTON_POSITIVE);
          go.setEnabled(n > 0);
          go.setText(n > 1 ? "Send to " + n : "Send");
        })
        .setPositiveButton("Send", (d, w) -> {
          List<Beam.Peer> chosen = new ArrayList<>();
          for (int i = 0; i < peers.size(); i++) if (on[i] && beam.peer(peers.get(i).id) != null) chosen.add(peers.get(i));
          if (chosen.isEmpty()) { for (Transfer.Item it : items) if (it.temp != null) it.temp.delete(); return; }
          beam.sendToMany(chosen, items);
          if (chosen.size() > 1) Toast.makeText(this, "Sending to " + chosen.size() + " devices", Toast.LENGTH_SHORT).show();
        })
        .setNegativeButton("Cancel", (d, w) -> { for (Transfer.Item it : items) if (it.temp != null) it.temp.delete(); })
        .create();
    dlg[0].setOnShowListener(x -> dlg[0].getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false));
    dlg[0].show();
  }

  private void askText(String peerId) {
    Beam.Peer p = beam.peer(peerId);
    if (p == null) return;
    EditText e = new EditText(this);
    e.setHint("Paste a link or type something");
    e.setMinLines(3);
    ClipboardManager cm = getSystemService(ClipboardManager.class);
    AlertDialog dlg = Ui.dialog(this).setCustomTitle(Ui.title(this, "Send text to " + p.name)).setView(pad(e))
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
    if (res != RESULT_OK || data == null) return;
    if (req == PICK_SAVE_DIR) {
      Uri tree = data.getData();
      if (tree == null) return;
      try {
        getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      } catch (Exception e) {
        Toast.makeText(this, "Beam can't save to that folder. Pick another one", Toast.LENGTH_LONG).show();
        return;
      }
      releaseSaveDir();
      beam.prefs.saveTree = tree.toString();
      beam.prefs.saveTreeName = folderName(tree);
      beam.prefs.save();
      receivingPage();
      return;
    }
    if (req == PICK_FOLDER) {
      final Uri tree = data.getData();
      if (tree == null) return;
      final String peerId = pickFor;
      Toast.makeText(this, "Reading the folder…", Toast.LENGTH_SHORT).show();
      Items.buildFolder(this, tree, (items, err) -> {
        if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
        if (items.isEmpty()) { Toast.makeText(this, "That folder is empty", Toast.LENGTH_SHORT).show(); return; }
        if (peerId == null) { pickRecipients(items); return; }
        Beam.Peer p = beam.peer(peerId);
        if (p == null) Toast.makeText(this, "That device went away", Toast.LENGTH_SHORT).show();
        else beam.send(p, items);
      });
      return;
    }
    if (req != PICK) return;
    List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
    else if (data.getData() != null) uris.add(data.getData());
    for (Uri u : uris) {
      try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
    }
    final String peerId = pickFor;
    Items.build(this, uris, null, false, (items, err) -> {
      if (err == null && peerId == null) { pickRecipients(items); return; }
      Beam.Peer p = peerId == null ? null : beam.peer(peerId);
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
  private final AlertDialog[] self0 = new AlertDialog[1];

  private void showSettings() {
    final AlertDialog[] self = self0;
    LinearLayout l = page();

    // Who's signed in (tap for PIN and sign out)
    LinearLayout who = Ui.row(this);
    who.setGravity(Gravity.CENTER_VERTICAL);
    who.setBackground(Ui.round(Ui.BG, Ui.dp(this, 14)));
    who.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
    String nm = beam.account == null ? "?" : beam.account.optString("name", beam.account.optString("username", "?")).trim();
    TextView av = Ui.text(this, nm.isEmpty() ? "?" : nm.substring(0, 1).toUpperCase(), 16, Color.WHITE, true);
    av.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable avBg = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[] { Ui.ACCENT, Ui.ACCENT2 });
    avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    av.setBackground(avBg);
    who.addView(av, Ui.margins(this, Ui.dp(this, 38), Ui.dp(this, 38), 0, 0, 12, 0));
    LinearLayout whoTxt = Ui.col(this);
    whoTxt.addView(Ui.text(this, beam.account == null ? "Not signed in" : beam.account.optString("name"), 15, Ui.TEXT, true));
    if (beam.account != null) whoTxt.addView(Ui.text(this, "Signed in as " + beam.account.optString("username"), 12, Ui.DIM, false));
    who.addView(whoTxt, new LinearLayout.LayoutParams(0, -2, 1));
    who.addView(Ui.chevron(this));
    who.setOnClickListener(v -> { self[0].dismiss(); accountPage(); });
    l.addView(who, Ui.margins(this, -1, -2, 0, 4, 0, 0));

    // This device
    l.addView(label("This device"));
    LinearLayout dev = group();
    EditText name = new EditText(this);
    name.setText(beam.prefs.name);
    name.setSingleLine(true);
    name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    name.setTextColor(Ui.TEXT);
    name.setBackground(null);
    name.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    LinearLayout nameRow = Ui.row(this);
    nameRow.setGravity(Gravity.CENTER_VERTICAL);
    nameRow.addView(Ui.iconTile(this, "name", false), Ui.margins(this, Ui.dp(this, 34), Ui.dp(this, 34), 0, 0, 14, 0));
    nameRow.addView(Ui.text(this, "Name", 15, Ui.TEXT, false));
    nameRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    addRow(dev, nameRow);
    Switch nb = switchRow(dev, "eye", "Visible to everyone nearby", "Off: only your own and paired devices see it", beam.prefs.nearby);
    nb.setOnCheckedChangeListener((v, on) -> { beam.prefs.nearby = on; beam.prefs.save(); beam.updateServer(); });
    Switch on = switchRow(dev, "radio", "Stay reachable when Beam is closed", "Beam always works while it's open", beam.prefs.enabled);
    on.setOnCheckedChangeListener((v, isOn) -> {
      beam.prefs.enabled = isOn;
      beam.prefs.save();
      if (isOn) BeamService.start(this); else BeamService.stop(this);
    });
    navRow(dev, "receive", "Receiving", receivingSummary(), false, v -> { self[0].dismiss(); receivingPage(); });
    int pairedN = beam.prefs.paired.size();
    navRow(dev, "pair", "Paired devices", pairedN > 0 ? pairedN + " paired" : "Send with someone else's device", false, v -> { self[0].dismiss(); pairedPage(); });
    l.addView(dev);

    // Help
    l.addView(label("Help"));
    LinearLayout help = group();
    navRow(help, "history", "Transfer history", "What this phone sent and received", false, v -> { self[0].dismiss(); showHistory(); });
    navRow(help, "test", "Test connection", "Server, relay and your devices", false, v -> { self[0].dismiss(); new ConnTest(this, beam).show(); });
    boolean newer = Updater.available != null && Updater.available.version > Updater.current();
    navRow(help, "update", newer ? "Install version 1.0." + Updater.available.version : "Check for updates", "Version 1.0." + Updater.current(), false, v -> {
      if (Updater.available != null && Updater.available.version > Updater.current()) { self[0].dismiss(); startActivity(new Intent(this, UpdateActivity.class)); }
      else Updater.check(beam, true);
    });
    PowerManager pm = getSystemService(PowerManager.class);
    if (!pm.isIgnoringBatteryOptimizations(getPackageName())) navRow(help, "battery", "Allow background use", "So Android doesn't put Beam to sleep", false, v -> openBatterySetting());
    l.addView(help);
    l.addView(Ui.text(this, "Device code " + Crypto.shortId(beam.me.id), 12, Ui.DIM, false), Ui.margins(this, -1, -2, 2, 8, 0, 0));

    self[0] = showPage("Settings", l, null);
    // The name saves when Settings closes (so it isn't sent on every letter).
    self[0].setOnDismissListener(d -> {
      String n = Beam.trim(name.getText().toString());
      if (!n.isEmpty() && !n.equals(beam.prefs.name)) { beam.prefs.name = n; beam.prefs.save(); beam.updateServer(); }
    });
  }

  private String receivingSummary() {
    String where = beam.prefs.saveTree != null ? beam.prefs.saveTreeName : "Downloads/Beam";
    return (beam.prefs.galleryMedia ? "Photos to gallery, files to " : "Saves to ") + where + ". Auto-accept " + (beam.prefs.autoAccept ? "on" : "off");
  }

  /** A Settings page: scrolling content with Done, and a "‹ Settings" back button on sub-pages. */
  private LinearLayout page() {
    LinearLayout l = Ui.col(this);
    int p = Ui.dp(this, 18);
    l.setPadding(p, Ui.dp(this, 4), p, Ui.dp(this, 8));
    return l;
  }

  private AlertDialog showPage(String title, LinearLayout l, Runnable back) {
    ScrollView sv = new ScrollView(this);
    sv.addView(l);
    Fx.stagger(l);
    AlertDialog.Builder b = Ui.dialog(this).setCustomTitle(Ui.title(this, title)).setView(sv).setPositiveButton("Done", null);
    if (back != null) b.setNeutralButton("‹ Settings", (d, w) -> back.run());
    return b.show();
  }

  private void receivingPage() {
    LinearLayout l = page();
    final AlertDialog[] self = new AlertDialog[1];
    LinearLayout where = group();
    Switch gm = switchRow(where, "Photos and videos go to the gallery", "Pictures/Beam and Movies/Beam", beam.prefs.galleryMedia);
    gm.setOnCheckedChangeListener((v, on) -> { beam.prefs.galleryMedia = on; beam.prefs.save(); });
    LinearLayout fr = Ui.row(this);
    fr.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout frTxt = Ui.col(this);
    frTxt.addView(Ui.text(this, "Files save to", 15, Ui.TEXT, false));
    frTxt.addView(Ui.text(this, beam.prefs.saveTree != null ? beam.prefs.saveTreeName : "Downloads/Beam", 12, Ui.DIM, false));
    fr.addView(frTxt, new LinearLayout.LayoutParams(0, -2, 1));
    if (beam.prefs.saveTree != null) fr.addView(Ui.button(this, "Downloads", 0x18FFFFFF, Ui.TEXT, v -> {
      releaseSaveDir();
      beam.prefs.saveTree = null;
      beam.prefs.saveTreeName = null;
      beam.prefs.save();
      self[0].dismiss();
      receivingPage();
    }), Ui.margins(this, -2, -2, 0, 0, 8, 0));
    fr.addView(Ui.button(this, "Choose…", 0x18FFFFFF, Ui.TEXT, v -> {
      self[0].dismiss();
      try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), PICK_SAVE_DIR); }
      catch (Exception e) { Toast.makeText(this, "No app on this phone can pick folders", Toast.LENGTH_LONG).show(); }
    }));
    addRow(where, fr);
    l.addView(where);
    l.addView(label("From my devices"));
    LinearLayout mine = group();
    Switch aa = switchRow(mine, "Auto-accept", "What they send arrives without asking", beam.prefs.autoAccept);
    aa.setOnCheckedChangeListener((v, on) -> { beam.prefs.autoAccept = on; beam.prefs.save(); });
    Switch ol = switchRow(mine, "Open links automatically", "A link from one of your devices opens in your browser", beam.prefs.openLinks);
    ol.setOnCheckedChangeListener((v, on) -> { beam.prefs.openLinks = on; beam.prefs.save(); });
    l.addView(mine);
    self[0] = showPage("Receiving", l, this::showSettings);
  }

  private void pairedPage() {
    LinearLayout l = page();
    final AlertDialog[] self = new AlertDialog[1];
    l.addView(Ui.text(this, "Your own devices find each other automatically. Pair to send with someone else's device, even when it's not nearby.", 14, Ui.DIM, false), Ui.margins(this, -1, -2, 2, 4, 0, 0));
    // Old copies left by sign-in trouble or re-pairing: one tap clears them.
    java.util.List<java.util.List<String>> dup = beam.duplicates();
    java.util.Set<String> dupAll = new java.util.HashSet<>(dup.get(0));
    dupAll.addAll(dup.get(1));
    final int n = dupAll.size();
    if (n > 0) {
      LinearLayout dg = group();
      navRow(dg, "sync", "Remove " + n + " duplicate" + (n == 1 ? "" : "s"), "Old copies left by sign-in trouble or re-pairing", false, v ->
        Ui.dialog(this).setMessage("Remove " + n + " old cop" + (n == 1 ? "y" : "ies") + "? The devices you use now stay paired and signed in.")
          .setNegativeButton("Cancel", null)
          .setPositiveButton("Remove", (dd, w) -> { beam.removeDuplicates(); Toast.makeText(this, "Duplicates removed", Toast.LENGTH_SHORT).show(); self[0].dismiss(); beam.main.postDelayed(this::pairedPage, 400); })
          .show());
      l.addView(dg, Ui.margins(this, -1, -2, 0, 12, 0, 0));
    }
    if (!beam.prefs.paired.isEmpty()) {
      LinearLayout pg = group();
      java.util.Set<String> on = Beam.onlineIds(beam.peers);
      java.util.Set<String> mine = new java.util.HashSet<>();
      for (Beam.Peer p : beam.peers) if (p.mine) mine.add(p.id);
      for (Beam.Peer p : beam.offlineAll) mine.add(p.id);
      for (Prefs.Paired pd : new ArrayList<>(beam.prefs.paired.values())) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout t = Ui.col(this);
        t.addView(Ui.text(this, pd.name, 15, Ui.TEXT, false));
        String st = dup.get(0).contains(pd.id) ? "Old copy" : on.contains(pd.id) ? (mine.contains(pd.id) ? "My device, online now" : "Online now") : mine.contains(pd.id) ? "My device" : "Not online now";
        t.addView(Ui.text(this, st + ", " + Crypto.shortId(pd.id), 12, Ui.DIM, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(Ui.button(this, "Remove", 0x22F87171, Ui.BAD, v -> { beam.unpair(pd.id); self[0].dismiss(); pairedPage(); }));
        addRow(pg, r);
      }
      l.addView(pg, Ui.margins(this, -1, -2, 0, 12, 0, 0));
    }
    l.addView(label("Pair a device"));
    LinearLayout how = group();
    navRow(how, "qr", "Show a pairing code", "On this phone, for someone else to enter", false, v -> { self[0].dismiss(); beam.pairShow(); });
    navRow(how, "keys", "Enter a pairing code", "From someone else's device", false, v -> { self[0].dismiss(); beam.pairJoin(); });
    l.addView(how);
    self[0] = showPage("Paired devices", l, this::showSettings);
  }

  private void accountPage() {
    LinearLayout l = page();
    final AlertDialog[] self = new AlertDialog[1];
    LinearLayout acc = group();
    LinearLayout me = Ui.col(this);
    me.addView(Ui.text(this, beam.account == null ? "Not signed in" : beam.account.optString("name"), 15, Ui.TEXT, true));
    if (beam.account != null) me.addView(Ui.text(this, "Username " + beam.account.optString("username") + (beam.account.optBoolean("admin") ? ". You manage this Beam" : ""), 12, Ui.DIM, false));
    addRow(acc, me);
    if (beam.account != null && beam.account.optBoolean("admin")) navRow(acc, "people", "Manage people", "Opens in your browser", false, v -> open(Uri.parse(Beam.SERVER), null));
    navRow(acc, "pin", "Change PIN", null, false, v -> { self[0].dismiss(); showChangePin(); });
    l.addView(acc);
    LinearLayout out = group();
    navRow(out, "out", "Sign out of this phone", null, true, v -> Ui.dialog(this).setMessage("Sign out of Beam on this phone?")
        .setPositiveButton("Sign out", (d, w) -> { self[0].dismiss(); beam.logout(); }).setNegativeButton("Cancel", null).show());
    l.addView(out, Ui.margins(this, -1, -2, 0, 12, 0, 0));
    self[0] = showPage("Account", l, this::showSettings);
  }

  /** Lets go of the old picked folder's permission (Android only keeps a limited number). */
  private void releaseSaveDir() {
    if (beam.prefs.saveTree == null) return;
    try { getContentResolver().releasePersistableUriPermission(Uri.parse(beam.prefs.saveTree), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
    catch (Exception ignored) {}
  }

  /** "primary:Documents/Church" -> "Documents/Church"; an SD card shows as "SD card/…". */
  static String folderName(Uri tree) {
    try {
      String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
      int colon = id.indexOf(':');
      String vol = colon >= 0 ? id.substring(0, colon) : "", rest = colon >= 0 ? id.substring(colon + 1) : id;
      String where = "primary".equals(vol) ? "" : vol.isEmpty() ? "" : "SD card/";
      return rest.isEmpty() ? (where.isEmpty() ? "Phone storage" : "SD card") : where + rest;
    } catch (Exception e) {
      return "Chosen folder";
    }
  }

  // ---------- history ----------
  private void showHistory() {
    org.json.JSONArray h = History.list(this);
    LinearLayout l = Ui.col(this);
    int p = Ui.dp(this, 20);
    l.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
    final AlertDialog[] self = new AlertDialog[1];
    if (h.length() == 0) l.addView(Ui.text(this, "Nothing sent or received on this phone yet.", 14, Ui.DIM, false));
    for (int i = 0; i < h.length(); i++) {
      final org.json.JSONObject e = h.optJSONObject(i);
      if (e == null) continue;
      boolean in = e.optBoolean("in");
      int count = e.optInt("count");
      org.json.JSONArray names = e.optJSONArray("names");
      String folder = e.optString("folder");
      String what = !folder.isEmpty() ? "Folder \"" + folder + "\"" + (count > 1 ? " (" + count + " files)" : "")
          : count > 0 ? (names != null && names.length() > 0 ? names.optString(0) : "File") + (count > 1 ? " and " + (count - 1) + " more" : "")
          : !e.optString("text").isEmpty() ? "Text" : "Nothing";
      List<String> meta = new ArrayList<>();
      meta.add((in ? "From " : "To ") + e.optString("peer"));
      if (e.optLong("size") > 0) meta.add(fmtSize(e.optLong("size")));
      meta.add(whenText(e.optLong("at")));
      LinearLayout row = Ui.row(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      TextView arrow = Ui.text(this, in ? "↓" : "↑", 16, in ? Ui.ACCENT : Ui.DIM, true);
      arrow.setGravity(Gravity.CENTER);
      row.addView(arrow, Ui.margins(this, Ui.dp(this, 26), -2, 0, 0, 10, 0));
      LinearLayout txt = Ui.col(this);
      txt.addView(Ui.text(this, what, 15, Ui.TEXT, false));
      txt.addView(Ui.text(this, String.join(", ", meta), 12, Ui.DIM, false));
      if (!e.optBoolean("ok")) txt.addView(Ui.text(this, e.optString("reason").isEmpty() ? "Didn't finish" : e.optString("reason"), 12, Ui.BAD, false));
      else if (in && !e.optString("where").isEmpty()) txt.addView(Ui.text(this, "Saved " + e.optString("where"), 12, Ui.DIM, false));
      row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
      final org.json.JSONArray uris = e.optJSONArray("uris");
      final String text = e.optString("text");
      if (in && uris != null && uris.length() > 0) {
        row.addView(Ui.button(this, "Open", 0x18FFFFFF, Ui.TEXT, v -> openSaved(uris.optJSONObject(0))));
      } else if (!text.isEmpty()) {
        row.addView(Ui.button(this, "Copy", 0x18FFFFFF, Ui.TEXT, v -> {
          getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Beam", text));
          Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }));
      }
      l.addView(row, Ui.margins(this, -1, -2, 0, 8, 0, 8));
    }
    l.addView(Ui.text(this, "Kept on this phone only. The last " + History.MAX + " transfers.", 12, Ui.DIM, false), Ui.margins(this, -1, -2, 0, 8, 0, 0));
    ScrollView sv = new ScrollView(this);
    sv.addView(l);
    Fx.stagger(l);
    AlertDialog.Builder b = Ui.dialog(this).setTitle("Transfer history").setView(sv).setPositiveButton("Done", null);
    if (h.length() > 0) b.setNeutralButton("Clear", (d, w) -> Ui.dialog(this).setMessage("Clear the transfer history on this phone? Files you received stay where they are.")
        .setPositiveButton("Clear", (d2, w2) -> History.clear(this)).setNegativeButton("Cancel", null).show());
    self[0] = b.show();
  }

  private void openSaved(org.json.JSONObject f) {
    if (f == null) return;
    try {
      Uri u = Uri.parse(f.optString("uri"));
      Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, f.optString("type", "*/*")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      i.setClipData(ClipData.newRawUri("", u));
      startActivity(i);
    } catch (Exception e) {
      Toast.makeText(this, "That file was moved or deleted", Toast.LENGTH_SHORT).show();
    }
  }

  String whenText(long ms) {
    java.util.Calendar c = java.util.Calendar.getInstance(), now = java.util.Calendar.getInstance();
    c.setTimeInMillis(ms);
    String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(ms));
    if (c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)) return "Today " + time;
    now.add(java.util.Calendar.DAY_OF_YEAR, -1);
    if (c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)) return "Yesterday " + time;
    return new java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(new java.util.Date(ms)) + ", " + time;
  }

  // ---------- signing in ----------
  // A full screen of its own: nothing else in Beam works until it's done.
  private android.app.Dialog signInDialog;
  private String signInMode, signInCta;
  private TextView signInError;
  private android.widget.Button signInGo;

  /** mode: "signin", "invite" or "setup" (null = whichever applies). */
  private void showSignIn(String mode) {
    if (!beam.needLogin) {
      if (signInDialog != null) { signInDialog.dismiss(); signInDialog = null; }
      return;
    }
    if (mode == null) mode = beam.needSetup ? "setup" : signInMode != null && !"setup".equals(signInMode) ? signInMode : "signin";
    // Same screen already showing (an error came back, or we reconnected): keep what was typed.
    if (signInDialog != null && signInDialog.isShowing() && mode.equals(signInMode)) {
      if (beam.loginError != null) { authError(beam.loginError); beam.loginError = null; }
      else authBusy(false);
      return;
    }
    if (signInDialog != null) signInDialog.dismiss();
    signInMode = mode;
    final String m = mode;
    final boolean setup = "setup".equals(m), invite = "invite".equals(m);

    LinearLayout col = Ui.col(this);
    int pad = Ui.dp(this, 28);
    col.setPadding(pad, Ui.dp(this, 64), pad, pad);
    android.widget.ImageView mark = new android.widget.ImageView(this);
    mark.setImageResource(R.mipmap.ic_launcher);
    col.addView(mark, Ui.lp(Ui.dp(this, 56), Ui.dp(this, 56)));
    TextView heading = Ui.text(this, setup ? "Set up Beam" : invite ? "Join with an invite" : "Sign in", 28, Ui.TEXT, true);
    heading.setLetterSpacing(-0.02f);
    col.addView(heading, Ui.margins(this, -1, -2, 0, 24, 0, 6));
    col.addView(Ui.text(this, setup ? "You're the first one here. Create the admin account, then invite everyone else from Settings."
        : invite ? "Enter the username and code from your invite, then choose a PIN you'll remember."
        : "Use your Beam username and PIN. This phone stays signed in.", 15, Ui.DIM, false), Ui.margins(this, -1, -2, 0, 0, 0, 14));

    int text = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    int pinType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    final EditText code = authField(col, setup ? "Setup code" : "Invite code", setup ? null : "It works once, for 7 days.", text | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, setup || invite);
    final EditText name = authField(col, "Your name", null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, setup);
    final EditText user = authField(col, "Username", null, text, true);
    final EditText pin = authField(col, setup || invite ? "Choose a PIN" : "PIN", setup || invite ? "6 to 12 digits." : null, pinType, true);
    final EditText pin2 = authField(col, "Confirm PIN", null, pinType, setup || invite);

    signInError = Ui.text(this, "", 14, 0xFFFFB9B9, false);
    android.graphics.drawable.GradientDrawable errBg = Ui.round(0x17FF7B7B, Ui.dp(this, 10));
    errBg.setStroke(Ui.dp(this, 1), 0x59FF7B7B);
    signInError.setBackground(errBg);
    int ep = Ui.dp(this, 12);
    signInError.setPadding(ep, Ui.dp(this, 10), ep, Ui.dp(this, 10));
    signInError.setVisibility(View.GONE);
    col.addView(signInError, Ui.margins(this, -1, -2, 0, 14, 0, 0));

    signInCta = setup ? "Create admin account" : invite ? "Join Beam" : "Sign in";
    signInGo = Ui.button(this, signInCta, Ui.ACCENT, Color.WHITE, v -> {
      String u = user.getText().toString().trim(), p = pin.getText().toString();
      String problem = setup && code.getText().toString().trim().isEmpty() ? "Enter the setup code."
          : setup && name.getText().toString().trim().isEmpty() ? "Enter your name."
          : u.isEmpty() ? "Enter your username."
          : invite && code.getText().toString().trim().isEmpty() ? "Enter your invite code."
          : p.isEmpty() ? "Enter your PIN."
          : (setup || invite) && !p.matches("\\d{6,12}") ? "PINs are 6 to 12 digits."
          : (setup || invite) && !p.equals(pin2.getText().toString()) ? "The two PINs don't match." : null;
      if (problem != null) { authError(problem); return; }
      authBusy(true);
      if (setup) beam.setup(code.getText().toString().trim(), name.getText().toString().trim(), u, p);
      else if (invite) beam.join(u, code.getText().toString().trim(), p);
      else beam.login(u, p);
    });
    signInGo.setTextSize(16);
    signInGo.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
    col.addView(signInGo, Ui.margins(this, -1, -2, 0, 18, 0, 0));

    if (!setup) {
      TextView other = Ui.text(this, invite ? "Already have a PIN? Sign in" : "New here? Use an invite code", 14, Ui.ACCENT2, true);
      other.setGravity(Gravity.CENTER);
      other.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
      other.setOnClickListener(v -> showSignIn(invite ? "signin" : "invite"));
      col.addView(other, Ui.lp(-1, -2));
    }
    TextView foot = Ui.text(this, setup ? "The setup code stops working once the admin account exists."
        : "Beam is invite-only. Ask the person who runs your Beam for an invite.", 12.5f, Ui.DIM, false);
    foot.setGravity(Gravity.CENTER);
    col.addView(foot, Ui.margins(this, -1, -2, 0, 14, 0, 0));

    ScrollView sv = new ScrollView(this);
    sv.setFillViewport(true);
    sv.setBackgroundColor(Ui.BG);
    sv.addView(col);
    android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
    dlg.setContentView(sv);
    dlg.setCancelable(false);
    // Back leaves Beam rather than dismissing the sign-in.
    dlg.setOnKeyListener((d, keyCode, ev) -> {
      if (keyCode == android.view.KeyEvent.KEYCODE_BACK && ev.getAction() == android.view.KeyEvent.ACTION_UP) { moveTaskToBack(true); return true; }
      return false;
    });
    if (dlg.getWindow() != null) {
      dlg.getWindow().setStatusBarColor(Ui.BG);
      dlg.getWindow().setNavigationBarColor(Ui.BG);
      dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    signInDialog = dlg;
    dlg.show();
    if (beam.loginError != null) { authError(beam.loginError); beam.loginError = null; }
  }

  /** A labelled, rounded input for the sign-in screen. */
  private EditText authField(LinearLayout col, String label, String hint, int type, boolean show) {
    EditText e = new EditText(this);
    e.setInputType(type);
    e.setSingleLine(true);
    e.setTextColor(Ui.TEXT);
    e.setTextSize(16);
    android.graphics.drawable.GradientDrawable bg = Ui.round(Ui.CARD, Ui.dp(this, 10));
    bg.setStroke(Ui.dp(this, 1), Ui.LINE);
    e.setBackground(bg);
    int h = Ui.dp(this, 14);
    e.setPadding(h, Ui.dp(this, 12), h, Ui.dp(this, 12));
    if (!show) return e;
    col.addView(Ui.text(this, label, 13, Ui.TEXT, true), Ui.margins(this, -1, -2, 0, 14, 0, 6));
    col.addView(e, Ui.lp(-1, -2));
    if (hint != null) col.addView(Ui.text(this, hint, 12.5f, Ui.DIM, false), Ui.margins(this, -1, -2, 0, 6, 0, 0));
    return e;
  }

  private void authBusy(boolean on) {
    if (signInGo == null) return;
    signInGo.setEnabled(!on);
    signInGo.setAlpha(on ? 0.6f : 1f);
    signInGo.setText(on ? "One moment…" : signInCta);
    if (!on) return;
    signInError.setVisibility(View.GONE);
    final android.app.Dialog mine = signInDialog;
    beam.main.postDelayed(() -> {
      if (signInDialog == mine && mine != null && mine.isShowing() && !signInGo.isEnabled()) authError("Can't reach Beam. Check your connection and try again.");
    }, 9000);
  }

  private void authError(String msg) {
    if (signInError == null) return;
    authBusy(false);
    signInError.setText(msg);
    signInError.setVisibility(View.VISIBLE);
  }

  private EditText field(LinearLayout l, String hint, int type, boolean show) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setInputType(type);
    e.setSingleLine(true);
    if (show) l.addView(e, Ui.margins(this, -1, -2, 0, 6, 0, 0));
    return e;
  }

  private void showChangePin() {
    LinearLayout l = Ui.col(this);
    int t = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    EditText old = field(l, "Current PIN", t, true), p1 = field(l, "New PIN (6–12 digits)", t, true), p2 = field(l, "New PIN again", t, true);
    Ui.dialog(this).setTitle("Change PIN").setView(pad(l))
        .setPositiveButton("Change", (d, w) -> {
          if (!p1.getText().toString().equals(p2.getText().toString())) { Toast.makeText(this, "The new PINs don't match", Toast.LENGTH_SHORT).show(); return; }
          beam.changePin(old.getText().toString(), p1.getText().toString());
        })
        .setNegativeButton("Cancel", null).show();
  }

  private TextView label(String s) {
    TextView t = Ui.text(this, s, 13, Ui.DIM, true);
    t.setPadding(Ui.dp(this, 2), Ui.dp(this, 18), 0, Ui.dp(this, 6));
    return t;
  }

  /** A rounded box that holds a group of settings rows. */
  private LinearLayout group() {
    LinearLayout g = Ui.col(this);
    g.setBackground(Ui.round(0x0AFFFFFF, Ui.dp(this, 14)));
    g.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
    return g;
  }

  /** Adds a row to a group, with a divider above it if it isn't the first. */
  private void addRow(LinearLayout g, View row) {
    if (g.getChildCount() > 0) {
      View line = new View(this);
      line.setBackgroundColor(Ui.LINE);
      g.addView(line, Ui.lp(-1, 1));
    }
    row.setPadding(row.getPaddingLeft(), Ui.dp(this, 10), row.getPaddingRight(), Ui.dp(this, 10));
    g.addView(row, Ui.lp(-1, -2));
  }

  private Switch switchRow(LinearLayout g, String title, String sub, boolean on) { return switchRow(g, null, title, sub, on); }

  private Switch switchRow(LinearLayout g, String icon, String title, String sub, boolean on) {
    LinearLayout r = Ui.row(this);
    r.setGravity(Gravity.CENTER_VERTICAL);
    if (icon != null) r.addView(Ui.iconTile(this, icon, false), Ui.margins(this, Ui.dp(this, 34), Ui.dp(this, 34), 0, 0, 14, 0));
    LinearLayout t = Ui.col(this);
    t.addView(Ui.text(this, title, 15, Ui.TEXT, false));
    if (sub != null) t.addView(Ui.text(this, sub, 12, Ui.DIM, false));
    r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    Switch s = new Switch(this);
    Ui.styleSwitch(s);
    s.setChecked(on);
    r.addView(s);
    r.setOnClickListener(v -> s.toggle());
    addRow(g, r);
    return s;
  }

  /** A row that opens something: a small tinted icon, a title and a line under it, and a chevron. */
  private void navRow(LinearLayout g, String icon, String title, String sub, boolean danger, View.OnClickListener click) {
    LinearLayout r = Ui.row(this);
    r.setGravity(Gravity.CENTER_VERTICAL);
    if (icon != null) r.addView(Ui.iconTile(this, icon, danger), Ui.margins(this, Ui.dp(this, 34), Ui.dp(this, 34), 0, 0, 14, 0));
    LinearLayout t = Ui.col(this);
    t.addView(Ui.text(this, title, 15, danger ? Ui.BAD : Ui.TEXT, false));
    if (sub != null) t.addView(Ui.text(this, sub, 12, Ui.DIM, false));
    r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    if (!danger) r.addView(Ui.chevron(this));
    android.util.TypedValue tv = new android.util.TypedValue();
    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
    r.setForeground(getDrawable(tv.resourceId));
    r.setClickable(true);
    r.setOnClickListener(click);
    addRow(g, r);
  }

  /** The old signature, for rows without an icon. */
  private void navRow(LinearLayout g, String title, String sub, boolean danger, View.OnClickListener click) {
    navRow(g, null, title, sub, danger, click);
  }

  private Switch sw(String label, boolean on) {
    Switch s = new Switch(this);
    Ui.styleSwitch(s);
    s.setTextColor(Ui.TEXT);
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

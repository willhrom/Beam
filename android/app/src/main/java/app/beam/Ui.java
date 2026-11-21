package app.beam;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for building the screens in code. */
final class Ui {
  // The same palette as the web and desktop apps.
  static final int BG = 0xFF0F1626, CARD = 0xFF172136, LINE = 0xFF2A3752, TEXT = 0xFFEDF2FA, DIM = 0xFF93A1B8,
      ACCENT = 0xFF2F6BFF, ACCENT2 = 0xFF5CC8FF, GOOD = 0xFF4ADE9A, BAD = 0xFFFF7B7B;

  static int dp(Context c, float v) {
    return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
  }

  static TextView text(Context c, String s, float sp, int color, boolean bold) {
    TextView t = new TextView(c);
    t.setText(s);
    t.setTextSize(sp);
    t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
    return t;
  }

  static TextView oneLine(TextView t) {
    t.setSingleLine(true);
    t.setEllipsize(TextUtils.TruncateAt.END);
    return t;
  }

  /** Beam's switch, the same as on the web: a roomy pill (dark when off, Beam's blue gradient when on)
   *  with a white knob that has a soft shadow. */
  static void styleSwitch(android.widget.Switch s) {
    Context c = s.getContext();
    int h = dp(c, 28), w = dp(c, 48), knob = dp(c, 22), pad = dp(c, 3);
    // Knob: a white circle, inset so it sits inside the track.
    GradientDrawable k = new GradientDrawable();
    k.setShape(GradientDrawable.OVAL);
    k.setColor(0xFFFFFFFF);
    k.setSize(knob, knob);
    k.setStroke(dp(c, 0.5f), 0x33000000);
    android.graphics.drawable.InsetDrawable thumb = new android.graphics.drawable.InsetDrawable(k, pad);
    // Track: off and on.
    GradientDrawable off = new GradientDrawable();
    off.setCornerRadius(h / 2f);
    off.setColor(0xFF26314A);
    off.setStroke(dp(c, 1), 0x12FFFFFF);
    off.setSize(w, h);
    GradientDrawable on = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { ACCENT, ACCENT2 });
    on.setCornerRadius(h / 2f);
    on.setSize(w, h);
    android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
    track.addState(new int[] { android.R.attr.state_checked }, on);
    track.addState(new int[] {}, off);
    track.setEnterFadeDuration(180);
    track.setExitFadeDuration(180);
    s.setThumbDrawable(thumb);
    s.setTrackDrawable(track);
    s.setThumbTintList(null);
    s.setTrackTintList(null);
    s.setSwitchMinWidth(w);
    s.setShowText(false);
    s.setBackground(null);
    s.setElevation(0);
    s.setSwitchPadding(dp(c, 12));
  }

  static GradientDrawable round(int color, float radiusPx) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(radiusPx);
    return g;
  }

  static Button button(Context c, String label, int bg, int fg, View.OnClickListener l) {
    Button b = new Button(c);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextColor(fg);
    b.setTextSize(14);
    b.setTypeface(Typeface.DEFAULT_BOLD);
    b.setBackground(round(bg, dp(c, 10)));
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    int h = dp(c, 14), v = dp(c, 8);
    b.setPadding(h, v, h, v);
    b.setStateListAnimator(null);
    b.setOnClickListener(l);
    return b;
  }

  static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  static LinearLayout col(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

  static LinearLayout.LayoutParams margins(Context c, int w, int h, float l, float t, float r, float b) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
    p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    return p;
  }

  /** A small gold star on a device's circle: it's pinned to the top. */
  static void addStar(Context c, LinearLayout bubble) {
    if (bubble.getChildCount() == 0) return;
    View circle = bubble.getChildAt(0);
    bubble.removeViewAt(0);
    android.widget.FrameLayout f = new android.widget.FrameLayout(c);
    f.setClipChildren(false);
    f.addView(circle, circle.getLayoutParams() != null ? new android.widget.FrameLayout.LayoutParams(circle.getLayoutParams().width, circle.getLayoutParams().height) : new android.widget.FrameLayout.LayoutParams(-2, -2));
    TextView star = text(c, "★", 11, 0xFF3A2A00, true);
    star.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
    g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    g.setColor(0xFFFFD166);
    g.setStroke(dp(c, 2), BG);
    star.setBackground(g);
    android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(dp(c, 22), dp(c, 22), Gravity.TOP | Gravity.END);
    lp.setMargins(0, -dp(c, 2), -dp(c, 2), 0);
    f.addView(star, lp);
    bubble.addView(f, 0, circle.getLayoutParams() instanceof LinearLayout.LayoutParams ? circle.getLayoutParams() : new LinearLayout.LayoutParams(-2, -2));
  }

  /** A pop-up title that wraps instead of cutting off (for long device names). */
  static TextView title(Context c, String s) {
    TextView t = text(c, s, 19, TEXT, true);
    t.setPadding(dp(c, 24), dp(c, 20), dp(c, 24), dp(c, 6));
    t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
    return t;
  }

  static AlertDialog.Builder dialog(Context c) {
    return new AlertDialog.Builder(c, R.style.BeamDialog);
  }

  static String emoji(String kind) {
    if (kind == null) return "🌐";
    switch (kind) {
      case "android": case "iphone": return "📱";
      case "mac": case "chromebook": return "💻";
      case "windows": return "🖥️";
      case "ipad": return "📲";
      default: return "🌐";
    }
  }

  /** A round device bubble with a name underneath, used on the main screen and in the share sheet. */
  /** One of my devices that's offline: no ring, a grey "Offline" label (dimmed by the caller). */
  static LinearLayout offlineBubble(Context c, Beam.Peer p) {
    LinearLayout box = peerBubble(c, p, true, null, false);
    TextView tag = (TextView) box.getChildAt(box.getChildCount() - 1);
    tag.setText("Offline");
    tag.setTextColor(DIM);
    return box;
  }

  static LinearLayout peerBubble(Context c, Beam.Peer p, boolean paired, String note, boolean offline) {
    LinearLayout box = col(c);
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    int pad = dp(c, 6);
    box.setPadding(pad, pad, pad, pad);
    TextView circle = text(c, emoji(p.kind), 30, Color.WHITE, false);
    circle.setGravity(Gravity.CENTER);
    GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFF2A3A5C, 0xFF1B2436 });
    g.setShape(GradientDrawable.OVAL);
    if (note != null) g.setStroke(dp(c, 3), ACCENT2);
    circle.setBackground(g);
    box.addView(circle, lp(dp(c, 72), dp(c, 72)));
    // Long names wrap onto a second line; press and hold to see the whole thing.
    TextView name = text(c, p.name, 13, TEXT, false);
    name.setMaxLines(3);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    name.setGravity(Gravity.CENTER);
    box.addView(name, margins(c, -1, -2, 0, 6, 0, 0)); // as wide as its column
    box.setOnLongClickListener(v -> {
      android.widget.Toast.makeText(c, p.name, android.widget.Toast.LENGTH_LONG).show();
      return true;
    });
    String label = note != null ? note : (paired ? "My device" : "Nearby") + (offline ? " · offline" : "");
    TextView tag = text(c, label, 11, note != null || paired ? ACCENT2 : DIM, false);
    tag.setGravity(Gravity.CENTER);
    box.addView(tag, lp(-2, -2));
    return box;
  }
}

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
    box.addView(name, margins(c, dp(c, 108), -2, 0, 6, 0, 0));
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

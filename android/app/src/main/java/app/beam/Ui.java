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
  static final int BG = 0xFF0B0D12, CARD = 0xFF161A22, LINE = 0xFF262C38, TEXT = 0xFFEEF1F6, DIM = 0xFF8B93A3,
      ACCENT = 0xFF3B82F6, ACCENT2 = 0xFF22D3EE, GOOD = 0xFF34D399, BAD = 0xFFF87171;

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

  static AlertDialog.Builder dialog(Context c) {
    return new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert);
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
  static LinearLayout peerBubble(Context c, Beam.Peer p, boolean paired, String note) {
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
    TextView name = oneLine(text(c, p.name, 13, TEXT, false));
    name.setGravity(Gravity.CENTER);
    box.addView(name, margins(c, dp(c, 96), -2, 0, 6, 0, 0));
    TextView tag = text(c, note != null ? note : paired ? "My device" : "Nearby", 11, note != null || paired ? ACCENT2 : DIM, false);
    tag.setGravity(Gravity.CENTER);
    box.addView(tag, lp(-2, -2));
    return box;
  }
}

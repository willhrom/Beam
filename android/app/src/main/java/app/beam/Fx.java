package app.beam;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Small moments of motion: devices pop in, a device you're sending to pulses, finished
 * transfers land with a bounce and a burst, failures shake. Android's own "remove
 * animations" setting turns all of it off.
 */
final class Fx {
  private Fx() {}

  static boolean on() { return ValueAnimator.areAnimatorsEnabled(); }

  /** A new device: grows in with a little overshoot, one after another. */
  static void popIn(View v, int order) {
    if (!on()) return;
    v.setScaleX(0.3f); v.setScaleY(0.3f); v.setAlpha(0f); v.setTranslationY(Ui.dp(v.getContext(), 20));
    v.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0).setStartDelay(order * 70L).setDuration(520)
        .setInterpolator(new OvershootInterpolator(2.2f)).start();
  }

  /** Presses squish the view; letting go springs it back. */
  static void squishOnTouch(View v) {
    v.setOnTouchListener((view, e) -> {
      if (!on()) return false;
      if (e.getAction() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(110).setInterpolator(new DecelerateInterpolator()).start();
      else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
        view.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new OvershootInterpolator(3f)).start();
      return false; // let the click through
    });
  }

  /** Something arrived: a quick jump. */
  static void bounce(View v) {
    if (!on() || v == null) return;
    v.animate().cancel();
    v.animate().scaleX(1.25f).scaleY(1.25f).translationY(-Ui.dp(v.getContext(), 6)).setDuration(160).setInterpolator(new DecelerateInterpolator())
        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).translationY(0).setDuration(520).setInterpolator(new OvershootInterpolator(3f)).start()).start();
  }

  static void slideIn(View v) {
    if (!on()) return;
    v.setAlpha(0f);
    v.setTranslationY(-Ui.dp(v.getContext(), 14));
    v.animate().alpha(1f).translationY(0).setDuration(380).setInterpolator(new DecelerateInterpolator(2f)).start();
  }

  static void shake(View v) {
    if (!on()) return;
    float d = Ui.dp(v.getContext(), 7);
    ValueAnimator a = ValueAnimator.ofFloat(0, 1);
    a.setDuration(480);
    a.addUpdateListener(x -> {
      float f = (float) x.getAnimatedValue();
      v.setTranslationX((float) (Math.sin(f * Math.PI * 4) * d * (1 - f)));
    });
    a.start();
  }

  /** A ring of little pieces bursting out of `target`, drawn on `overlay` (which covers the screen). */
  static void burst(FrameLayout overlay, View target, int count) {
    if (!on() || overlay == null || target == null || !target.isAttachedToWindow()) return;
    Context c = overlay.getContext();
    int[] at = new int[2], base = new int[2];
    target.getLocationInWindow(at);
    overlay.getLocationInWindow(base);
    float cx = at[0] - base[0] + target.getWidth() / 2f, cy = at[1] - base[1] + target.getHeight() / 2f;
    int[] colors = { Ui.ACCENT2, Ui.GOOD, 0xFFFFFFFF, Ui.ACCENT };
    for (int i = 0; i < count; i++) {
      View p = new View(c);
      boolean longOne = i % 3 == 0;
      int w = Ui.dp(c, longOne ? 3 : 6), h = Ui.dp(c, longOne ? 10 : 6);
      GradientDrawable g = new GradientDrawable();
      g.setColor(colors[i % colors.length]);
      g.setCornerRadius(Ui.dp(c, 2));
      p.setBackground(g);
      overlay.addView(p, new FrameLayout.LayoutParams(w, h));
      p.setX(cx - w / 2f);
      p.setY(cy - h / 2f);
      double ang = (i / (double) count) * Math.PI * 2 + Math.random() * 0.4;
      float dist = Ui.dp(c, 46) * (0.7f + (float) Math.random() * 0.6f);
      p.setRotation((float) Math.toDegrees(ang) + 90);
      p.animate().translationXBy((float) Math.cos(ang) * dist).translationYBy((float) Math.sin(ang) * dist + Ui.dp(c, 8))
          .rotationBy(120).alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(650 + (long) (Math.random() * 350))
          .setInterpolator(new DecelerateInterpolator(2f)).withEndAction(() -> overlay.removeView(p)).start();
    }
  }

  /** The "looking for devices" ping: a ring that keeps spreading out of `ring`. */
  static ValueAnimator radar(View ring) {
    ValueAnimator a = ValueAnimator.ofFloat(0, 1);
    a.setDuration(2700);
    a.setRepeatCount(ValueAnimator.INFINITE);
    a.setInterpolator(new AccelerateInterpolator(0.6f));
    a.addUpdateListener(x -> {
      float f = (float) x.getAnimatedValue();
      ring.setScaleX(0.08f + f * 0.92f);
      ring.setScaleY(0.08f + f * 0.92f);
      ring.setAlpha(0.85f * (1 - f));
    });
    return a;
  }

  static void detach(View v) {
    ViewGroup parent = (ViewGroup) v.getParent();
    if (parent != null) parent.removeView(v);
  }

  private static float[] centerIn(FrameLayout overlay, View v) {
    int[] at = new int[2], base = new int[2];
    v.getLocationInWindow(at);
    overlay.getLocationInWindow(base);
    return new float[] { at[0] - base[0] + v.getWidth() * v.getScaleX() / 2f + (v.getWidth() - v.getWidth() * v.getScaleX()) / 2f,
                         at[1] - base[1] + v.getHeight() / 2f };
  }

  /** Sending starts: a little file card arcs up from the bottom of the screen to the device, which catches it. */
  static void launch(FrameLayout overlay, View target, int files, Runnable caught) {
    if (!on() || overlay == null || target == null || !target.isAttachedToWindow()) { if (caught != null) caught.run(); return; }
    Context c = overlay.getContext();
    float[] to = centerIn(overlay, target);
    float fx = overlay.getWidth() / 2f, fy = overlay.getHeight() - Ui.dp(c, 90);
    TextView card = Ui.text(c, files > 1 ? String.valueOf(files) : "", 12, Ui.ACCENT, true);
    card.setGravity(android.view.Gravity.CENTER);
    GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFFFFFFFF, 0xFFCFE9FF });
    g.setCornerRadius(Ui.dp(c, 6));
    g.setStroke(Ui.dp(c, 1), Ui.ACCENT2);
    card.setBackground(g);
    card.setElevation(Ui.dp(c, 8));
    int w = Ui.dp(c, 30), h = Ui.dp(c, 38);
    overlay.addView(card, new FrameLayout.LayoutParams(w, h));
    float mx = (fx + to[0]) / 2f - Ui.dp(c, 70), my = (fy + to[1]) / 2f;
    ValueAnimator a = ValueAnimator.ofFloat(0, 1);
    a.setDuration(700);
    a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
    a.addUpdateListener(x -> {
      float k = (float) x.getAnimatedValue(), u = 1 - k;
      float px = u * u * fx + 2 * u * k * mx + k * k * to[0], py = u * u * fy + 2 * u * k * my + k * k * to[1];
      card.setX(px - w / 2f);
      card.setY(py - h / 2f);
      card.setRotation(-25 + k * 385);
      float sc = 0.6f + (float) Math.sin(k * Math.PI) * 0.6f - k * 0.3f;
      card.setScaleX(sc);
      card.setScaleY(sc);
      card.setAlpha(k > 0.9f ? (1 - k) / 0.1f : 1f);
    });
    a.addListener(new android.animation.AnimatorListenerAdapter() {
      @Override public void onAnimationEnd(android.animation.Animator an) {
        overlay.removeView(card);
        catchIt(target);
        burst(overlay, target, 8);
        if (caught != null) caught.run();
      }
    });
    a.start();
  }

  /** The device squishes as it catches something. */
  static void catchIt(View v) {
    if (!on() || v == null) return;
    v.animate().scaleX(0.86f).scaleY(0.94f).setDuration(120).withEndAction(() ->
        v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new OvershootInterpolator(3.5f)).start()).start();
  }

  /** Someone wants to send you something: their device wiggles until you answer. */
  static ValueAnimator knock(View v) {
    ValueAnimator a = ValueAnimator.ofFloat(0, 1);
    a.setDuration(1600);
    a.setRepeatCount(ValueAnimator.INFINITE);
    a.addUpdateListener(x -> {
      float f = (float) x.getAnimatedValue();
      float r = f < 0.55f ? 0 : (float) (Math.sin((f - 0.55f) / 0.45f * Math.PI * 4) * 9 * (1 - (f - 0.55f) / 0.45f));
      v.setRotation(r);
    });
    a.addListener(new android.animation.AnimatorListenerAdapter() {
      @Override public void onAnimationCancel(android.animation.Animator an) { v.setRotation(0); }
    });
    return a;
  }

  /** Confetti falling from the top of the screen, for things you receive. */
  static void confetti(FrameLayout overlay, int n) {
    if (!on() || overlay == null || overlay.getWidth() == 0) return;
    Context c = overlay.getContext();
    int[] colors = { Ui.ACCENT2, Ui.GOOD, 0xFFFFFFFF, Ui.ACCENT, 0xFFFFD166, 0xFFFF8FAB };
    float wd = overlay.getWidth(), ht = overlay.getHeight();
    for (int i = 0; i < n; i++) {
      View p = new View(c);
      boolean longOne = i % 4 == 0;
      int w = Ui.dp(c, longOne ? 4 : 7), h = Ui.dp(c, longOne ? 15 : 11);
      GradientDrawable g = new GradientDrawable();
      g.setColor(colors[i % colors.length]);
      g.setCornerRadius(Ui.dp(c, 1.5f));
      p.setBackground(g);
      overlay.addView(p, new FrameLayout.LayoutParams(w, h));
      p.setX((float) Math.random() * wd);
      p.setY(-h);
      float drift = (float) (Math.random() - 0.5) * Ui.dp(c, 120);
      p.animate().translationYBy(ht + h * 2).translationXBy(drift).rotationBy((float) (Math.random() - 0.5) * 900)
          .setStartDelay((long) (Math.random() * 300)).setDuration(1700 + (long) (Math.random() * 1200))
          .setInterpolator(new android.view.animation.LinearInterpolator()).withEndAction(() -> overlay.removeView(p)).start();
    }
  }

  /** A "Sent" pill floats up out of the device you sent to. */
  static void rise(FrameLayout overlay, View target, String label) {
    if (!on() || overlay == null || target == null || !target.isAttachedToWindow()) return;
    Context c = overlay.getContext();
    TextView pill = Ui.text(c, label, 13, 0xFF062417, true);
    int pad = Ui.dp(c, 10);
    pill.setPadding(pad, Ui.dp(c, 4), pad, Ui.dp(c, 4));
    pill.setBackground(Ui.round(Ui.GOOD, Ui.dp(c, 20)));
    overlay.addView(pill, new FrameLayout.LayoutParams(-2, -2));
    float[] at = centerIn(overlay, target);
    pill.post(() -> {
      pill.setX(at[0] - pill.getWidth() / 2f);
      pill.setY(at[1] - pill.getHeight() / 2f - Ui.dp(c, 10));
      pill.setAlpha(0f);
      pill.setScaleX(0.4f);
      pill.setScaleY(0.4f);
      pill.animate().translationYBy(-Ui.dp(c, 36)).alpha(1f).scaleX(1.1f).scaleY(1.1f).setDuration(420).setInterpolator(new OvershootInterpolator(2f))
          .withEndAction(() -> pill.animate().translationYBy(-Ui.dp(c, 24)).alpha(0f).scaleX(1f).scaleY(1f).setDuration(700).withEndAction(() -> overlay.removeView(pill)).start()).start();
    });
  }

  /** Two dots orbiting a device while data moves. Call every frame with a phase 0..1. */
  static void orbit(FrameLayout overlay, View target, View[] dots, float phase, boolean incoming) {
    if (target == null || !target.isAttachedToWindow()) { for (View d : dots) d.setAlpha(0f); return; }
    float[] cxy = centerIn(overlay, target);
    float r = target.getWidth() / 2f + Ui.dp(target.getContext(), 7);
    for (int i = 0; i < dots.length; i++) {
      double ang = (incoming ? -1 : 1) * phase * Math.PI * 2 + i * Math.PI;
      View d = dots[i];
      d.setAlpha(1f);
      d.setX((float) (cxy[0] + Math.cos(ang) * r - d.getLayoutParams().width / 2f));
      d.setY((float) (cxy[1] + Math.sin(ang) * r - d.getLayoutParams().height / 2f));
    }
  }

  static View dot(FrameLayout overlay) {
    Context c = overlay.getContext();
    View d = new View(c);
    GradientDrawable g = new GradientDrawable();
    g.setShape(GradientDrawable.OVAL);
    g.setColor(0xFFFFFFFF);
    g.setStroke(Ui.dp(c, 2), Ui.ACCENT2);
    d.setBackground(g);
    d.setElevation(Ui.dp(c, 4));
    overlay.addView(d, new FrameLayout.LayoutParams(Ui.dp(c, 9), Ui.dp(c, 9)));
    return d;
  }

  /** The rows of a pop-up drift up into place one after another as it opens. */
  static void stagger(ViewGroup g) {
    if (!on()) return;
    float dy = Ui.dp(g.getContext(), 12);
    for (int i = 0; i < g.getChildCount(); i++) {
      View v = g.getChildAt(i);
      v.setAlpha(0f);
      v.setTranslationY(dy);
      v.animate().alpha(1f).translationY(0).setStartDelay(90 + Math.min(i, 14) * 28L).setDuration(320)
          .setInterpolator(new DecelerateInterpolator(2f)).start();
    }
  }
}

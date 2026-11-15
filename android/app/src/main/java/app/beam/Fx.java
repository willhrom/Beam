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
    a.setDuration(2200);
    a.setRepeatCount(ValueAnimator.INFINITE);
    a.setInterpolator(new AccelerateInterpolator(0.6f));
    a.addUpdateListener(x -> {
      float f = (float) x.getAnimatedValue();
      ring.setScaleX(0.4f + f * 1.6f);
      ring.setScaleY(0.4f + f * 1.6f);
      ring.setAlpha(0.8f * (1 - f));
    });
    return a;
  }

  static void detach(View v) {
    ViewGroup parent = (ViewGroup) v.getParent();
    if (parent != null) parent.removeView(v);
  }
}

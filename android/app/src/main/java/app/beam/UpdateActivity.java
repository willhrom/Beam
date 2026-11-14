package app.beam;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;

/** "Update Beam?" → download with progress → Android's install confirmation. */
public class UpdateActivity extends Activity {
  static UpdateActivity showing;
  private AlertDialog dialog;
  private TextView text;
  private ProgressBar bar;

  @Override protected void onCreate(Bundle s) {
    super.onCreate(s);
    showing = this;
    getSystemService(NotificationManager.class).cancel(Updater.NOTIFY_ID);
    LinearLayout l = Ui.col(this);
    int p = Ui.dp(this, 20);
    l.setPadding(p, Ui.dp(this, 8), p, 0);
    text = Ui.text(this, "Checking…", 14, Ui.DIM, false);
    l.addView(text);
    bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    bar.setMax(100);
    bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT2));
    l.addView(bar, Ui.margins(this, -1, Ui.dp(this, 8), 0, 12, 0, 4));
    dialog = Ui.dialog(this).setTitle("Update Beam").setView(l).setNegativeButton("Later", (d, w) -> finish()).setCancelable(false).create();
    dialog.show();
    new Thread(this::run, "beam-update").start();
  }

  private void run() {
    try {
      Updater.Release r = Updater.available != null ? Updater.available : Updater.fetch();
      if (r.version <= Updater.current()) { ui("Beam is up to date.", 100); done(); return; }
      ui("Downloading version 1.0." + r.version + "…", 0);
      File apk = Updater.download(this, r, pct -> ui("Downloading version 1.0." + r.version + "…", pct));
      ui("Installing…", 100);
      Updater.install(this, apk);
    } catch (Exception e) {
      ui(e.getMessage() != null ? e.getMessage() : "Update failed", 0);
      done();
    }
  }

  private void ui(String s, int pct) {
    runOnUiThread(() -> { text.setText(s); bar.setProgress(pct); });
  }

  private void done() {
    runOnUiThread(() -> { if (dialog != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Close"); });
  }

  /** Called with the installer's result (failures only: on success Beam restarts as the new version). */
  void installFailed(String why) {
    ui(why, 0);
    done();
  }

  @Override protected void onDestroy() {
    if (showing == this) showing = null;
    if (dialog != null) dialog.dismiss();
    super.onDestroy();
  }
}

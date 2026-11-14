package app.beam;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Notification buttons (Accept, Decline, Cancel, Copy) and starting on boot. */
public class ActionReceiver extends BroadcastReceiver {
  static final String ACCEPT = "app.beam.ACCEPT", DECLINE = "app.beam.DECLINE", CANCEL = "app.beam.CANCEL", COPY = "app.beam.COPY",
      INSTALL_STATUS = "app.beam.INSTALL_STATUS";

  @Override public void onReceive(Context c, Intent i) {
    String a = i.getAction();
    if (a == null) return;
    Beam b = Beam.get(c);
    String tid = i.getStringExtra("tid");
    switch (a) {
      case Intent.ACTION_BOOT_COMPLETED:
      case Intent.ACTION_MY_PACKAGE_REPLACED:
        BeamService.start(c);
        break;
      case ACCEPT: b.accept(tid); break;
      case DECLINE: b.decline(tid); break;
      case CANCEL: b.cancel(tid); break;
      case INSTALL_STATUS: {
        // From Android's installer while updating Beam.
        int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1);
        if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
          Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
          if (confirm != null) {
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(confirm); } catch (Exception ignored) {}
          }
        } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
          String msg = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
          UpdateActivity u = UpdateActivity.showing;
          if (u != null) u.installFailed("Update didn't install" + (msg != null ? ": " + msg : "."));
        }
        break;
      }
      case COPY:
        ClipboardManager cm = c.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Beam", i.getStringExtra("text")));
        Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
        break;
    }
  }
}

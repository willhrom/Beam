package app.beam;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Notification buttons (Accept, Decline, Cancel, Copy) and starting on boot. */
public class ActionReceiver extends BroadcastReceiver {
  static final String ACCEPT = "app.beam.ACCEPT", DECLINE = "app.beam.DECLINE", CANCEL = "app.beam.CANCEL", COPY = "app.beam.COPY";

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
      case COPY:
        ClipboardManager cm = c.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Beam", i.getStringExtra("text")));
        Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
        break;
    }
  }
}

package app.beam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** All of Beam's notifications. */
final class Notify {
  static final int STATUS_ID = 1;
  static final String CH_STATUS = "status", CH_REQUEST = "request", CH_PROGRESS = "progress", CH_DONE = "done";
  private final Context c;
  private final NotificationManager nm;

  Notify(Context c) {
    this.c = c;
    nm = c.getSystemService(NotificationManager.class);
    NotificationChannel s = new NotificationChannel(CH_STATUS, "Ready to receive", NotificationManager.IMPORTANCE_MIN);
    s.setDescription("Keeps Beam reachable in the background. You can hide this one.");
    s.setShowBadge(false);
    NotificationChannel r = new NotificationChannel(CH_REQUEST, "Incoming requests", NotificationManager.IMPORTANCE_HIGH);
    NotificationChannel p = new NotificationChannel(CH_PROGRESS, "Transfer progress", NotificationManager.IMPORTANCE_LOW);
    p.setShowBadge(false);
    NotificationChannel d = new NotificationChannel(CH_DONE, "Received", NotificationManager.IMPORTANCE_DEFAULT);
    nm.createNotificationChannel(s);
    nm.createNotificationChannel(r);
    nm.createNotificationChannel(p);
    nm.createNotificationChannel(d);
  }

  private PendingIntent openApp() {
    Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private PendingIntent action(String act, String tid, int code) {
    Intent i = new Intent(c, ActionReceiver.class).setAction(act).putExtra("tid", tid);
    return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private static int idFor(Transfer t) { return 1000 + (t.tid.hashCode() & 0xffff); }

  Notification status(Beam b) {
    String text = b.needLogin ? "Open Beam to sign in" : !b.online ? "Connecting…" : b.prefs.nearby ? "Visible to everyone nearby as " + b.prefs.name : "Visible to your devices as " + b.prefs.name;
    return new Notification.Builder(c, CH_STATUS)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("Beam is ready")
        .setContentText(text)
        .setContentIntent(openApp())
        .setOngoing(true)
        .setShowWhen(false)
        .build();
  }

  void refreshStatus(Beam b) {
    if (BeamService.running) nm.notify(STATUS_ID, status(b));
  }

  void request(Transfer t) {
    int id = idFor(t);
    String size = t.total > 0 ? " (" + MainActivity.fmtSize(t.total) + ")" : "";
    Notification n = new Notification.Builder(c, CH_REQUEST)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(t.peerName + " wants to send you " + t.describe())
        .setContentText((t.paired ? "My device" : "Device " + Crypto.shortId(t.peerId)) + size)
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setContentIntent(openApp())
        .setAutoCancel(true)
        .addAction(new Notification.Action.Builder(null, "Decline", action(ActionReceiver.DECLINE, t.tid, id * 2)).build())
        .addAction(new Notification.Action.Builder(null, "Accept", action(ActionReceiver.ACCEPT, t.tid, id * 2 + 1)).build())
        .build();
    nm.notify(id, n);
  }

  void cancelRequest(Transfer t) {
    nm.cancel(idFor(t));
  }

  void transfer(Transfer t) {
    int id = idFor(t) + 70000;
    boolean paused = Transfer.PAUSED.equals(t.state) || Transfer.RECONNECTING.equals(t.state);
    boolean moving = paused || Transfer.SENDING.equals(t.state) || Transfer.RECEIVING.equals(t.state) || Transfer.CONNECTING.equals(t.state);
    if (moving) {
      int pct = t.total > 0 ? (int) (100 * t.done / t.total) : 0;
      Notification n = new Notification.Builder(c, CH_PROGRESS)
          .setSmallIcon(R.drawable.ic_stat)
          .setContentTitle((t.incoming ? "Receiving from " : "Sending to ") + t.peerName)
          .setContentText(paused ? "Connection lost — reconnecting…" : t.describe())
          .setProgress(100, pct, Transfer.CONNECTING.equals(t.state))
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .setContentIntent(openApp())
          .addAction(new Notification.Action.Builder(null, "Cancel", action(ActionReceiver.CANCEL, t.tid, id)).build())
          .build();
      nm.notify(id, n);
    } else {
      nm.cancel(id);
      if (!t.incoming && Transfer.FAILED.equals(t.state) && t.reason != null && !"Cancelled".equals(t.reason)) {
        nm.notify(id + 1, new Notification.Builder(c, CH_DONE).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Couldn't send to " + t.peerName).setContentText(t.reason).setAutoCancel(true).setContentIntent(openApp()).build());
      }
    }
  }

  void received(Transfer t) {
    int id = idFor(t) + 140000;
    Notification.Builder b = new Notification.Builder(c, CH_DONE).setSmallIcon(R.drawable.ic_stat).setAutoCancel(true);
    Transfer.Item first = t.items.get(0);
    if (t.items.size() == 1 && first.text && first.textValue != null) {
      String s = first.textValue.trim();
      b.setContentTitle("From " + t.peerName).setContentText(s).setStyle(new Notification.BigTextStyle().bigText(s));
      Intent copy = new Intent(c, ActionReceiver.class).setAction(ActionReceiver.COPY).putExtra("text", s);
      b.addAction(new Notification.Action.Builder(null, "Copy", PendingIntent.getBroadcast(c, id, copy, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
      if (s.matches("^https?://\\S+$")) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(s)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        b.setContentIntent(PendingIntent.getActivity(c, id, view, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      } else {
        b.setContentIntent(openApp());
      }
    } else {
      String where = t.savedWhere();
      b.setContentTitle("From " + t.peerName).setContentText("Saved " + t.describe() + (where != null ? " " + where : ""));
      Intent open;
      if (t.items.size() == 1 && first.saved != null) {
        open = new Intent(Intent.ACTION_VIEW).setDataAndType(first.saved, first.type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        open.setClipData(ClipData.newRawUri("", first.saved));
      } else {
        // Several photos/videos: open the first one in the gallery app. Files: open Downloads.
        boolean allMedia = true;
        for (Transfer.Item it : t.items) if (it.text || it.place == null || it.place == Transfer.Place.FILES) allMedia = false;
        if (allMedia && first.saved != null) {
          open = new Intent(Intent.ACTION_VIEW).setDataAndType(first.saved, Transfer.mimeOf(first)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
          open.setClipData(ClipData.newRawUri("", first.saved));
        } else {
          open = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
      }
      b.setContentIntent(PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }
    nm.notify(id, b.build());
  }
}

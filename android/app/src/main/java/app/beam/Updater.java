package app.beam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Keeps Beam up to date: checks the latest release every few hours and, when there's a
 * newer one, offers to install it over this version (Android asks you to confirm).
 */
final class Updater {
  static final int NOTIFY_ID = 7;
  private static final long EVERY = 6 * 3600 * 1000L;
  private static final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();

  interface Progress { void update(int percent); }

  static final class Release {
    final int version;
    final String url, sha256;
    Release(int version, String url, String sha256) { this.version = version; this.url = url; this.sha256 = sha256; }
  }

  static volatile Release available;

  static int current() { return BuildConfig.VERSION_CODE; }

  /** Starts the periodic check (call once when Beam starts). */
  static void schedule(Beam beam) {
    beam.main.postDelayed(new Runnable() {
      @Override public void run() {
        check(beam, false);
        beam.main.postDelayed(this, EVERY);
      }
    }, 30_000);
  }

  /** Looks for a newer version in the background. `loud`: report "up to date" too. */
  static void check(Beam beam, boolean loud) {
    new Thread(() -> {
      Release r = null;
      String err = null;
      try {
        r = fetch();
      } catch (Exception e) {
        err = "Couldn't check for updates";
      }
      final Release rel = r;
      final String error = err;
      beam.main.post(() -> {
        if (rel != null && rel.version > current()) {
          available = rel;
          notifyAvailable(beam.app, rel);
          beam.changed();
        } else if (loud) {
          beam.event("toast", error != null ? error : "Beam is up to date");
        }
      });
    }, "beam-update-check").start();
  }

  static Release fetch() throws Exception {
    try (Response res = http.newCall(new Request.Builder().url(BuildConfig.UPDATE_URL).build()).execute()) {
      if (!res.isSuccessful() || res.body() == null) throw new Exception("HTTP " + res.code());
      JSONObject j = new JSONObject(res.body().string());
      return new Release(j.getInt("version"), j.getString("url"), j.getString("sha256").toLowerCase());
    }
  }

  private static void notifyAvailable(Context c, Release r) {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel("update", "Updates", NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent pi = PendingIntent.getActivity(c, 7, new Intent(c, UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    nm.notify(NOTIFY_ID, new Notification.Builder(c, "update")
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("Beam update available")
        .setContentText("Tap to install version 1.0." + r.version)
        .setContentIntent(pi)
        .setAutoCancel(true)
        .build());
  }

  /** Downloads and checks the new APK. Runs on a background thread. */
  static File download(Context c, Release r, Progress progress) throws Exception {
    File out = new File(c.getCacheDir(), "update.apk");
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    try (Response res = http.newCall(new Request.Builder().url(r.url).build()).execute()) {
      if (!res.isSuccessful() || res.body() == null) throw new Exception("Download failed (" + res.code() + ")");
      long total = res.body().contentLength();
      try (InputStream in = res.body().byteStream(); OutputStream o = new FileOutputStream(out)) {
        byte[] buf = new byte[256 * 1024];
        long got = 0;
        int n, last = -1;
        while ((n = in.read(buf)) > 0) {
          o.write(buf, 0, n);
          sha.update(buf, 0, n);
          got += n;
          int pct = total > 0 ? (int) (100 * got / total) : 0;
          if (pct != last) { last = pct; progress.update(pct); }
        }
      }
    }
    if (!Crypto.hex(sha.digest()).equals(r.sha256)) {
      out.delete();
      throw new Exception("The download was damaged. Try again.");
    }
    return out;
  }

  /** Hands the APK to Android's installer. Android then asks the person to confirm. */
  static void install(Context c, File apk) throws Exception {
    PackageInstaller pi = c.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    params.setAppPackageName(c.getPackageName());
    // Once Beam has installed itself once, later updates can go through without a prompt.
    if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
    int id = pi.createSession(params);
    try (PackageInstaller.Session s = pi.openSession(id)) {
      try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("beam.apk", 0, apk.length())) {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        s.fsync(out);
      }
      Intent status = new Intent(c, ActionReceiver.class).setAction(ActionReceiver.INSTALL_STATUS);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
      s.commit(PendingIntent.getBroadcast(c, 8, status, flags).getIntentSender());
    }
  }
}

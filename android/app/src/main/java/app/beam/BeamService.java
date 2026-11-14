package app.beam;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps Beam connected in the background so other devices can find this phone at any time. */
public class BeamService extends Service implements Beam.Listener {
  static volatile boolean running;

  private Beam beam;
  private PowerManager.WakeLock wake;
  private WifiManager.WifiLock wifi;
  private ConnectivityManager.NetworkCallback netCb;
  private boolean lastOnline;

  static void start(Context c) {
    if (!Beam.get(c).prefs.enabled) return;
    try {
      c.startForegroundService(new Intent(c, BeamService.class));
    } catch (Exception e) {
      // Android may refuse while the app is in the background; it starts next time the app opens.
      Beam.get(c).start();
    }
  }

  /** Stops staying reachable in the background. Beam keeps working while its screen is open. */
  static void stop(Context c) {
    c.stopService(new Intent(c, BeamService.class));
  }

  @Override public void onCreate() {
    super.onCreate();
    running = true;
    beam = Beam.get(this);
    if (Build.VERSION.SDK_INT >= 34) startForeground(Notify.STATUS_ID, beam.notify.status(beam), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    else startForeground(Notify.STATUS_ID, beam.notify.status(beam));
    beam.listeners.add(this);
    beam.start();

    PowerManager pm = getSystemService(PowerManager.class);
    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beam:transfer");
    wake.setReferenceCounted(false);
    WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
    wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "beam:transfer");
    wifi.setReferenceCounted(false);

    ConnectivityManager cm = getSystemService(ConnectivityManager.class);
    netCb = new ConnectivityManager.NetworkCallback() {
      @Override public void onAvailable(Network n) { beam.main.post(beam::reconnect); }
    };
    try { cm.registerDefaultNetworkCallback(netCb); } catch (Exception ignored) {}
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    beam.start();
    return START_STICKY;
  }

  @Override public void onDestroy() {
    running = false;
    beam.listeners.remove(this);
    try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
    if (wake.isHeld()) wake.release();
    if (wifi.isHeld()) wifi.release();
    super.onDestroy();
  }

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override public void onChange() {
    if (beam.online != lastOnline) {
      lastOnline = beam.online;
      beam.notify.refreshStatus(beam);
    }
    boolean busy = false;
    for (Transfer t : beam.tx.values()) if (t.active() && !Transfer.ASKING.equals(t.state)) busy = true;
    if (busy) {
      if (!wake.isHeld()) wake.acquire(60 * 60 * 1000L);
      if (!wifi.isHeld()) wifi.acquire();
    } else {
      if (wake.isHeld()) wake.release();
      if (wifi.isHeld()) wifi.release();
    }
  }

  @Override public void onEvent(String type, Object arg) {}
}

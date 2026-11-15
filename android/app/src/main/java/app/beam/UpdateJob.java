package app.beam;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Checks for a new version of Beam every few hours, through Android's job scheduler.
 * It runs even when Beam is switched off or closed, so a broken or old build can
 * always be replaced by a fixed one.
 */
public class UpdateJob extends JobService {
  private static final int JOB_ID = 71;

  static void schedule(Context c) {
    try {
      JobScheduler js = c.getSystemService(JobScheduler.class);
      if (js == null || js.getPendingJob(JOB_ID) != null) return;
      js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
          .setPeriodic(6 * 3600 * 1000L, 2 * 3600 * 1000L)
          .setPersisted(true)
          .build());
    } catch (Exception ignored) {}
  }

  @Override public boolean onStartJob(JobParameters params) {
    new Thread(() -> {
      try {
        Updater.Release r = Updater.fetch();
        if (r.version > Updater.current()) {
          Updater.available = r;
          Updater.notifyAvailable(getApplicationContext(), r);
        }
      } catch (Exception ignored) {}
      jobFinished(params, false);
    }, "beam-update-job").start();
    return true;
  }

  @Override public boolean onStopJob(JobParameters params) { return true; }
}

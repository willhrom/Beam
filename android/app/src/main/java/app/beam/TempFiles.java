package app.beam;

import java.util.List;

/** Deletes files copied for a send to several devices once every one of those transfers has ended. */
final class TempFiles {
  private TempFiles() {}

  static void track(Beam beam, final List<Transfer.Item> items, final List<Transfer> transfers) {
    beam.main.postDelayed(new Runnable() {
      @Override public void run() {
        for (Transfer t : transfers) if (t.active()) { beam.main.postDelayed(this, 2000); return; }
        for (Transfer.Item i : items) if (i.temp != null) { i.temp.delete(); i.temp = null; }
      }
    }, 2000);
  }
}

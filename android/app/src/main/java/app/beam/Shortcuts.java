package app.beam;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Icon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts your devices in the Android share sheet's top row (like AirDrop's row of people):
 * share a video, tap "Will's Mac", and it goes straight there.
 */
final class Shortcuts {
  static final String CATEGORY = "app.beam.category.DEVICE";
  static final String PREFIX = "dev_";
  private static String lastKey = "";

  /** Paired devices first, then the ones you've sent to most recently. */
  static void update(Beam beam) {
    Map<String, String[]> devs = new LinkedHashMap<>();
    for (String[] r : beam.prefs.recent) if (beam.prefs.paired.containsKey(r[0])) devs.put(r[0], new String[] { beam.prefs.paired.get(r[0]).name, r[2] });
    for (Prefs.Paired p : beam.prefs.paired.values()) if (!devs.containsKey(p.id)) devs.put(p.id, new String[] { p.name, p.kind });
    for (String[] r : beam.prefs.recent) if (!devs.containsKey(r[0])) devs.put(r[0], new String[] { r[1], r[2] });
    ShortcutManager sm = beam.app.getSystemService(ShortcutManager.class);
    if (sm == null) return;
    int max = Math.min(4, sm.getMaxShortcutCountPerActivity());
    StringBuilder key = new StringBuilder();
    List<ShortcutInfo> list = new ArrayList<>();
    int rank = 0;
    for (Map.Entry<String, String[]> e : devs.entrySet()) {
      if (rank >= max) break;
      String name = e.getValue()[0], kind = e.getValue()[1];
      key.append(e.getKey()).append(name).append(kind).append('|');
      list.add(new ShortcutInfo.Builder(beam.app, PREFIX + e.getKey())
          .setShortLabel(name)
          .setLongLabel("Send to " + name)
          .setIcon(icon(beam.app, kind))
          .setIntent(new Intent(beam.app, MainActivity.class).setAction(Intent.ACTION_VIEW))
          .setCategories(Collections.singleton(CATEGORY))
          .setLongLived(true)
          .setRank(rank++)
          .build());
    }
    if (key.toString().equals(lastKey)) return;
    try {
      sm.setDynamicShortcuts(list);
      lastKey = key.toString();
    } catch (Exception ignored) {
      // Android limits how often background apps can update these; we'll try again next change.
    }
  }

  static void used(Context c, String id) {
    try { c.getSystemService(ShortcutManager.class).reportShortcutUsed(PREFIX + id); } catch (Exception ignored) {}
  }

  /** A round Beam-blue icon with the device's emoji. */
  private static Icon icon(Context c, String kind) {
    int size = 216; // adaptive icons are 108dp with the visible part in the middle 72dp
    Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    Canvas cv = new Canvas(b);
    Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    bg.setShader(new LinearGradient(0, 0, size, size, 0xFF22D3EE, 0xFF2563EB, Shader.TileMode.CLAMP));
    cv.drawRect(0, 0, size, size, bg);
    Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    t.setTextSize(78);
    t.setTextAlign(Paint.Align.CENTER);
    Paint.FontMetrics fm = t.getFontMetrics();
    cv.drawText(Ui.emoji(kind), size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, t);
    return Icon.createWithAdaptiveBitmap(b);
  }
}

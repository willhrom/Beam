package app.beam;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** The last 200 transfers on this phone, kept on the phone only. */
final class History {
  static final int MAX = 200;

  private static SharedPreferences sp(Context c) { return c.getSharedPreferences("beam-history", Context.MODE_PRIVATE); }

  static synchronized JSONArray list(Context c) {
    try { return new JSONArray(sp(c).getString("list", "[]")); } catch (Exception e) { return new JSONArray(); }
  }

  static synchronized void clear(Context c) { sp(c).edit().putString("list", "[]").apply(); }

  static synchronized void record(Context c, Transfer t) {
    try {
      JSONObject e = new JSONObject();
      int count = 0;
      long size = 0;
      JSONArray names = new JSONArray(), uris = new JSONArray();
      StringBuilder text = new StringBuilder();
      for (Transfer.Item i : t.items) {
        if (i.text) {
          if (i.textValue != null && text.length() < 4000) text.append(text.length() > 0 ? "\n" : "").append(i.textValue);
          continue;
        }
        count++;
        size += i.size;
        if (names.length() < 3) names.put(i.name);
        if (i.saved != null && uris.length() < 20) uris.put(new JSONObject().put("uri", i.saved.toString()).put("type", Transfer.mimeOf(i)));
      }
      String folder = t.topFolder();
      e.put("id", t.tid).put("at", System.currentTimeMillis()).put("in", t.incoming).put("peer", t.peerName)
          .put("ok", Transfer.DONE.equals(t.state)).put("reason", t.reason == null ? "" : t.reason)
          .put("count", count).put("size", size).put("names", names).put("folder", folder == null ? "" : folder)
          .put("text", text.length() > 4000 ? text.substring(0, 4000) : text.toString()).put("uris", uris);
      String where = t.incoming ? t.savedWhere() : null;
      if (where != null) e.put("where", where);
      JSONArray old = list(c), out = new JSONArray().put(e);
      for (int i = 0; i < old.length() && out.length() < MAX; i++) if (!t.tid.equals(old.getJSONObject(i).optString("id"))) out.put(old.get(i));
      sp(c).edit().putString("list", out.toString()).apply();
    } catch (Exception ignored) {}
  }
}

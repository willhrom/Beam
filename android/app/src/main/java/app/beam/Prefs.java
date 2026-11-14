package app.beam;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Settings and the list of paired devices. */
final class Prefs {
  static final class Paired {
    final String id, pub, kind;
    String name;
    Paired(String id, String pub, String name, String kind) { this.id = id; this.pub = pub; this.name = name; this.kind = kind; }
  }

  private final SharedPreferences sp;
  String name;
  boolean nearby, autoAccept, enabled, offline;
  final Map<String, Paired> paired = new LinkedHashMap<>();
  final List<String[]> recent = new ArrayList<>(); // {id, name, kind}, most recent first

  Prefs(Context c) {
    sp = c.getSharedPreferences("beam", Context.MODE_PRIVATE);
    String def = null;
    try { def = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME); } catch (Exception ignored) {}
    if (def == null || def.isEmpty()) def = Build.MODEL;
    name = sp.getString("name", def);
    nearby = sp.getBoolean("nearby", true);
    autoAccept = sp.getBoolean("autoAccept", true);
    enabled = sp.getBoolean("enabled", true);
    offline = sp.getBoolean("offline", true);
    try {
      JSONObject o = new JSONObject(sp.getString("paired", "{}"));
      for (Iterator<String> it = o.keys(); it.hasNext(); ) {
        String id = it.next();
        JSONObject p = o.getJSONObject(id);
        paired.put(id, new Paired(id, p.getString("pub"), p.optString("name", "Device"), p.optString("kind", "web")));
      }
    } catch (Exception ignored) {}
    try {
      org.json.JSONArray a = new org.json.JSONArray(sp.getString("recent", "[]"));
      for (int i = 0; i < a.length(); i++) {
        org.json.JSONArray r = a.getJSONArray(i);
        recent.add(new String[] { r.getString(0), r.getString(1), r.getString(2) });
      }
    } catch (Exception ignored) {}
  }

  void sentTo(String id, String name, String kind) {
    for (int i = recent.size() - 1; i >= 0; i--) if (recent.get(i)[0].equals(id)) recent.remove(i);
    recent.add(0, new String[] { id, name, kind });
    while (recent.size() > 6) recent.remove(recent.size() - 1);
    save();
  }

  void save() {
    JSONObject o = new JSONObject();
    try {
      for (Paired p : paired.values()) o.put(p.id, new JSONObject().put("pub", p.pub).put("name", p.name).put("kind", p.kind));
    } catch (Exception ignored) {}
    org.json.JSONArray rec = new org.json.JSONArray();
    for (String[] r : recent) rec.put(new org.json.JSONArray().put(r[0]).put(r[1]).put(r[2]));
    sp.edit().putString("recent", rec.toString()).putString("name", name).putBoolean("nearby", nearby).putBoolean("autoAccept", autoAccept)
        .putBoolean("enabled", enabled).putBoolean("offline", offline).putString("paired", o.toString()).apply();
  }

  List<String> watch() { return new ArrayList<>(paired.keySet()); }
}

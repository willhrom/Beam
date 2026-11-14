package app.beam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * "Beam" in the Android share sheet. Two ways in:
 *  - tap Beam, then pick a device here;
 *  - tap a device in the share sheet's top row (a sharing shortcut): it sends straight away.
 *
 * Files are read straight from the app that shared them (no copy, so big videos start at
 * once). Android only lets us read them while this screen's task exists, so after sending
 * it steps into the background and closes itself when the transfer is over.
 */
public class ShareActivity extends Activity implements Beam.Listener {
  private static final long WAIT_FOR_DEVICE = 20_000;
  private Beam beam;
  private final List<Uri> uris = new ArrayList<>();
  private String text;
  private AlertDialog dialog;
  private LinearLayout row;
  private TextView hint;
  private String key = "";
  private String target;      // device chosen from the share sheet's top row
  private boolean busy;
  private Transfer sending;   // our transfer, once started
  private List<Transfer> sendingAll;
  private long opened;

  @Override protected void onCreate(Bundle s) {
    super.onCreate(s);
    beam = Beam.get(this);
    Intent i = getIntent();
    if (Intent.ACTION_SEND.equals(i.getAction())) {
      Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
      if (u != null) uris.add(u);
      else {
        String subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
        CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        text = t != null ? t.toString() : subject;
      }
    } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
      ArrayList<Uri> list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (list != null) uris.addAll(list);
    }
    if (uris.isEmpty() && (text == null || text.trim().isEmpty())) { finish(); return; }
    String sc = i.getStringExtra(Intent.EXTRA_SHORTCUT_ID);
    if (sc != null && sc.startsWith(Shortcuts.PREFIX)) target = sc.substring(Shortcuts.PREFIX.length());
    opened = System.currentTimeMillis();
    BeamService.start(this);
    beam.start();

    LinearLayout box = Ui.col(this);
    int p = Ui.dp(this, 16);
    box.setPadding(p, Ui.dp(this, 4), p, 0);
    hint = Ui.text(this, "", 13, Ui.DIM, false);
    box.addView(hint);
    HorizontalScrollView hs = new HorizontalScrollView(this);
    row = Ui.row(this);
    row.setGravity(Gravity.CENTER);
    hs.addView(row);
    box.addView(hs, Ui.margins(this, -1, -2, 0, 12, 0, 4));
    sendBtn = Ui.button(this, "Send", Ui.ACCENT, android.graphics.Color.WHITE, v -> send());
    sendBtn.setVisibility(android.view.View.GONE);
    box.addView(sendBtn, Ui.margins(this, -1, -2, 0, 8, 0, 4));
    String what = !uris.isEmpty() ? (uris.size() == 1 ? "1 item" : uris.size() + " items") : "text";
    String title = target != null ? "Beam " + what + " to " + targetName() : "Beam " + what + " to…";
    dialog = Ui.dialog(this).setTitle(title).setView(box).setNegativeButton("Cancel", null).create();
    dialog.setOnDismissListener(d -> { if (!busy) finish(); });
    dialog.show();
    if (target != null) beam.main.postDelayed(this::render, WAIT_FOR_DEVICE + 100);
    render();
  }

  private String targetName() {
    Prefs.Paired pp = beam.prefs.paired.get(target);
    if (pp != null) return pp.name;
    for (String[] r : beam.prefs.recent) if (r[0].equals(target)) return r[1];
    return "your device";
  }

  @Override protected void onResume() {
    super.onResume();
    beam.listeners.add(this);
    beam.kick();
    beam.nearby.discover(true);
    render();
  }

  @Override protected void onPause() {
    super.onPause();
    // Keep listening while a transfer we started is still running in the background.
    if (sending == null) beam.listeners.remove(this);
    beam.nearby.discover(false);
  }

  @Override protected void onDestroy() {
    beam.listeners.remove(this);
    if (dialog != null) { dialog.setOnDismissListener(null); dialog.dismiss(); }
    super.onDestroy();
  }

  @Override public void onChange() {
    if (sending != null) {
      boolean any = false;
      for (Transfer t : sendingAll) if (t.active()) any = true;
      if (!any) finish(); // all done or failed: we no longer need access to the files
      return;
    }
    render();
  }

  @Override public void onEvent(String type, Object arg) {}

  private void render() {
    if (row == null || busy || isFinishing()) return;
    List<Beam.Peer> all = beam.allPeers();
    if (target != null) {
      Beam.Peer p = beam.peer(target);
      if (p != null) { chosen.add(p.id); send(); return; }
      boolean gaveUp = System.currentTimeMillis() - opened > WAIT_FOR_DEVICE;
      hint.setText(gaveUp ? targetName() + " isn't reachable right now. Pick another device, or open Beam on it." : "Looking for " + targetName() + "…");
    } else {
      hint.setText(all.isEmpty() ? (beam.online || beam.nearby.usable() ? "Looking for devices… Open Beam on the other device." : "Connecting…") : chosen.isEmpty() ? "Tap a device, or several" : "Tap more to add, then Send");
    }
    StringBuilder k = new StringBuilder();
    for (Beam.Peer p : all) k.append(p.id).append(p.name).append(beam.isOfflineOnly(p.id)).append('|');
    if (k.toString().equals(key)) return;
    key = k.toString();
    row.removeAllViews();
    for (Beam.Peer p : all) {
      LinearLayout b = Ui.peerBubble(this, p, beam.prefs.paired.containsKey(p.id), chosen.contains(p.id) ? "Selected" : null, beam.isOfflineOnly(p.id));
      b.setOnClickListener(v -> toggle(p.id));
      row.addView(b);
    }
  }

  // Tap a device: it's selected. Tap another to add it. "Send" goes to all of them.
  private final java.util.LinkedHashSet<String> chosen = new java.util.LinkedHashSet<>();
  private android.widget.Button sendBtn;

  private void toggle(String peerId) {
    if (busy) return;
    if (!chosen.remove(peerId)) chosen.add(peerId);
    if (chosen.size() == 1 && target != null) { send(); return; } // picked from the share sheet's top row: go
    key = ""; // redraw selection rings
    render();
    if (sendBtn != null) {
      sendBtn.setVisibility(chosen.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
      sendBtn.setText(chosen.size() > 1 ? "Send to " + chosen.size() + " devices" : "Send");
    }
  }

  private void send() {
    if (busy || chosen.isEmpty()) return;
    busy = true;
    hint.setText("Preparing…");
    row.setAlpha(0.4f);
    final List<String> ids = new ArrayList<>(chosen);
    Items.build(this, uris, text, false, (items, err) -> {
      if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); finish(); return; }
      List<Beam.Peer> peers = new ArrayList<>();
      for (String id : ids) { Beam.Peer p = beam.peer(id); if (p != null) peers.add(p); }
      if (peers.isEmpty()) {
        for (Transfer.Item it : items) if (it.temp != null) it.temp.delete();
        Toast.makeText(this, "That device went away", Toast.LENGTH_SHORT).show();
        finish();
        return;
      }
      List<Transfer> started = peers.size() == 1 ? java.util.Collections.singletonList(beam.send(peers.get(0), items)) : beam.sendToMany(peers, items);
      sending = started.get(0);
      sendingAll = started;
      Toast.makeText(this, "Sending to " + (peers.size() == 1 ? peers.get(0).name : peers.size() + " devices") + " — progress is in your notifications", Toast.LENGTH_SHORT).show();
      dialog.setOnDismissListener(null);
      dialog.dismiss();
      // Step out of the way but stay alive, so we can keep reading the shared files.
      if (!moveTaskToBack(true)) finish();
    });
  }
}

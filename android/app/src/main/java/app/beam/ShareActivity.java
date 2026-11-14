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

/** "Beam" in the Android share sheet: pick a nearby device and the file goes straight there. */
public class ShareActivity extends Activity implements Beam.Listener {
  private Beam beam;
  private final List<Uri> uris = new ArrayList<>();
  private String text;
  private AlertDialog dialog;
  private LinearLayout row;
  private TextView hint;
  private String key = "";
  private boolean busy;

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
    String what = !uris.isEmpty() ? (uris.size() == 1 ? "1 item" : uris.size() + " items") : "text";
    dialog = Ui.dialog(this).setTitle("Beam " + what + " to…").setView(box)
        .setNegativeButton("Cancel", null).create();
    dialog.setOnDismissListener(d -> { if (!busy) finish(); });
    dialog.show();
    render();
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
    beam.listeners.remove(this);
    beam.nearby.discover(false);
  }

  @Override protected void onDestroy() {
    if (dialog != null) { dialog.setOnDismissListener(null); dialog.dismiss(); }
    super.onDestroy();
  }

  @Override public void onChange() { render(); }
  @Override public void onEvent(String type, Object arg) {}

  private void render() {
    if (row == null || busy) return;
    List<Beam.Peer> all = beam.allPeers();
    hint.setText(all.isEmpty() ? (beam.online || beam.nearby.usable() ? "Looking for devices… Open Beam on the other device." : "Connecting…") : "Tap a device");
    StringBuilder k = new StringBuilder();
    for (Beam.Peer p : all) k.append(p.id).append(p.name).append(beam.isOfflineOnly(p.id)).append('|');
    if (k.toString().equals(key)) return;
    key = k.toString();
    row.removeAllViews();
    for (Beam.Peer p : all) {
      LinearLayout b = Ui.peerBubble(this, p, beam.prefs.paired.containsKey(p.id), null, beam.isOfflineOnly(p.id));
      b.setOnClickListener(v -> send(p.id));
      row.addView(b);
    }
  }

  private void send(String peerId) {
    if (busy) return;
    busy = true;
    hint.setText("Preparing…");
    row.setAlpha(0.4f);
    Items.build(this, uris, text, true, (items, err) -> {
      Beam.Peer p = beam.peer(peerId);
      if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
      else if (p == null) {
        for (Transfer.Item it : items) if (it.temp != null) it.temp.delete();
        Toast.makeText(this, "That device went away", Toast.LENGTH_SHORT).show();
      } else {
        beam.send(p, items);
        Toast.makeText(this, "Sending to " + p.name + " — progress is in your notifications", Toast.LENGTH_SHORT).show();
      }
      finish();
    });
  }
}

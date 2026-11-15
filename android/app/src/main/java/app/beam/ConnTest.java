package app.beam;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Settings > Test connection: checks the server, a direct internet path, the relay, and devices. */
final class ConnTest {
  private final MainActivity a;
  private final Beam beam;
  private final TextView summary;
  private final LinearLayout rows;
  private final TextView[][] cells = new TextView[4][];
  private AlertDialog dlg;
  private boolean gone;

  ConnTest(MainActivity a, Beam beam) {
    this.a = a;
    this.beam = beam;
    LinearLayout l = Ui.col(a);
    int p = Ui.dp(a, 20);
    l.setPadding(p, Ui.dp(a, 8), p, Ui.dp(a, 8));
    summary = Ui.text(a, "Testing…", 14, Ui.DIM, false);
    l.addView(summary);
    rows = Ui.col(a);
    l.addView(rows, Ui.margins(a, -1, -2, 0, 10, 0, 0));
    String[] titles = { "Beam server", "Direct connection", "Relay", "Your devices" };
    for (int i = 0; i < 4; i++) {
      LinearLayout r = Ui.row(a);
      r.setGravity(Gravity.CENTER_VERTICAL);
      LinearLayout t = Ui.col(a);
      t.addView(Ui.text(a, titles[i], 15, Ui.TEXT, false));
      TextView detail = Ui.text(a, "Checking…", 12, Ui.DIM, false);
      t.addView(detail);
      r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
      TextView verdict = Ui.text(a, "…", 13, Ui.DIM, true);
      r.addView(verdict);
      rows.addView(r, Ui.margins(a, -1, -2, 0, 8, 0, 8));
      cells[i] = new TextView[] { detail, verdict };
    }
    dlg = Ui.dialog(a).setTitle("Connection test").setView(l)
        .setPositiveButton("Done", null).setNeutralButton("Test again", null).create();
    dlg.setOnDismissListener(d -> gone = true);
  }

  void show() {
    dlg.show();
    dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> { dlg.dismiss(); new ConnTest(a, beam).show(); });
    run();
  }

  private void set(int i, Boolean ok, String detail) {
    if (gone) return;
    cells[i][0].setText(detail);
    cells[i][1].setText(ok == null ? "…" : ok ? "OK" : "Problem");
    cells[i][1].setTextColor(ok == null ? Ui.DIM : ok ? Ui.GOOD : Ui.BAD);
  }

  private void run() {
    // 1. Server
    if (!beam.online) {
      set(0, false, beam.needLogin ? "Not signed in" : "Can't reach it. Check the internet connection");
      afterServer(null);
      return;
    }
    final long t0 = System.currentTimeMillis();
    final String n = Long.toHexString(t0);
    final boolean[] answered = { false };
    beam.pongWait = pong -> {
      answered[0] = true;
      // The answer carries fresh relay details, so the test doesn't use stale ones.
      org.json.JSONArray fresh = pong.optJSONArray("ice");
      if (fresh != null && fresh.length() > 0) { beam.ice = fresh; beam.iceAt = System.currentTimeMillis(); }
      set(0, true, "Connected, " + (System.currentTimeMillis() - t0) + " ms round trip");
      afterServer(pong);
    };
    try { beam.wsSend(new JSONObject().put("t", "ping").put("n", n)); } catch (Exception ignored) {}
    beam.main.postDelayed(() -> {
      if (answered[0]) return;
      beam.pongWait = null;
      set(0, false, "Not answering");
      afterServer(null);
    }, 6000);
  }

  private void afterServer(JSONObject pong) {
    // 2. Direct path (STUN only)
    gather(Transfer.iceServers(beam.ice, "stun"), false, types -> {
      boolean publicOk = types.contains("srflx");
      set(1, publicOk, publicOk ? "This network allows direct transfers" : types.contains("host") ? "Only to devices on this same network" : "Blocked on this network");
      // 3. Relay (TURN only)
      List<PeerConnection.IceServer> turn = Transfer.iceServers(beam.ice, "turn");
      if (turn.isEmpty()) {
        String why = pong != null ? pong.optString("relayError", "") : "";
        if ("null".equals(why)) why = "";
        set(2, false, pong != null && pong.optBoolean("relay") ? "The server couldn't get relay access" + (why.isEmpty() ? "" : ": " + why) : "Not set up yet");
        finish(pong != null, publicOk, false);
      } else {
        gather(turn, true, rt -> {
          boolean relayOk = rt.contains("relay");
          set(2, relayOk, relayOk ? "Ready for networks that block direct transfers" : "Can't reach the relay from this network");
          finish(pong != null, publicOk, relayOk);
        });
      }
    });
  }

  private void finish(boolean server, boolean direct, boolean relay) {
    int n = beam.peers.size();
    set(3, n > 0, n > 0 ? n + " reachable now" : "None online right now");
    if (!gone) summary.setText(!server ? "Beam can't send anything until it reaches the server."
        : direct || relay ? "Transfers should work from this network."
        : "Transfers will only work with devices on this same network, or phone to phone nearby.");
  }

  /** Gathers ICE candidates for a throwaway connection and reports which kinds showed up. */
  private void gather(List<PeerConnection.IceServer> servers, boolean relayOnly, Consumer<Set<String>> done) {
    final Set<String> types = new HashSet<>();
    final boolean[] reported = { false };
    final PeerConnection[] pcBox = new PeerConnection[1];
    final Runnable report = () -> {
      if (reported[0]) return;
      reported[0] = true;
      try { if (pcBox[0] != null) pcBox[0].dispose(); } catch (Exception ignored) {}
      done.accept(new HashSet<>(types));
    };
    try {
      PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(servers);
      if (relayOnly) cfg.iceTransportsType = PeerConnection.IceTransportsType.RELAY;
      PeerConnection pc = Transfer.factory(beam.app).createPeerConnection(cfg, new PeerConnection.Observer() {
        public void onSignalingChange(PeerConnection.SignalingState s) {}
        public void onIceConnectionChange(PeerConnection.IceConnectionState s) {}
        public void onIceConnectionReceivingChange(boolean b) {}
        public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
          if (s == PeerConnection.IceGatheringState.COMPLETE) beam.main.post(report);
        }
        public void onIceCandidate(IceCandidate c) {
          java.util.regex.Matcher m = java.util.regex.Pattern.compile(" typ (\\w+)").matcher(c.sdp);
          if (m.find()) synchronized (types) { types.add(m.group(1)); }
        }
        public void onIceCandidatesRemoved(IceCandidate[] c) {}
        public void onAddStream(MediaStream s) {}
        public void onRemoveStream(MediaStream s) {}
        public void onDataChannel(DataChannel d) {}
        public void onRenegotiationNeeded() {}
        public void onAddTrack(RtpReceiver r, MediaStream[] s) {}
      });
      if (pc == null) { beam.main.post(report); return; }
      pcBox[0] = pc;
      pc.createDataChannel("test", new DataChannel.Init());
      pc.createOffer(new SdpObserver() {
        public void onCreateSuccess(SessionDescription d) {
          pc.setLocalDescription(new SdpObserver() {
            public void onCreateSuccess(SessionDescription x) {}
            public void onSetSuccess() {}
            public void onCreateFailure(String e) {}
            public void onSetFailure(String e) { beam.main.post(report); }
          }, d);
        }
        public void onSetSuccess() {}
        public void onCreateFailure(String e) { beam.main.post(report); }
        public void onSetFailure(String e) {}
      }, new MediaConstraints());
    } catch (Exception e) {
      beam.main.post(report);
      return;
    }
    beam.main.postDelayed(report, 6000);
  }
}

package com.limelight.yugi;

import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Server -> phone events: one Server-Sent Events stream from yugipc-bridge (GET /events).
 * A background thread blocks on the socket (no polling). Parsing happens there too, and the UI
 * thread only gets a tiny runnable per event. Reconnects with backoff (1 s .. 30 s).
 * The server sends a comment every 20 s, so a dead link is noticed within the read timeout.
 */
public final class YugiEvents {
    /** Caret state from yugi-caret-watch.py (desktop pixels). */
    public static final class Caret {
        public final boolean editable;
        public final float x, y, w, h, screenW, screenH;
        public final boolean hasRect;

        Caret(JSONObject o) {
            editable = o.optBoolean("editable");
            hasRect = o.has("y") && o.has("h");
            x = (float) o.optDouble("x", Double.NaN);
            y = (float) o.optDouble("y", Double.NaN);
            w = (float) o.optDouble("w", 0);
            h = (float) o.optDouble("h", 0);
            screenW = (float) o.optDouble("sw", 0);
            screenH = (float) o.optDouble("sh", 0);
        }
    }

    public interface Listener {
        /** UI thread. {@code replay} is true for the state sent on (re)connect. */
        void onCaret(Caret c, boolean replay);
    }

    /** A video that started on the desktop and should play here (event "media", never replayed). */
    public interface MediaListener {
        /** UI thread. */
        void onMedia(YugiPlayer.Request r);
    }

    private volatile MediaListener mediaListener;

    public void setMediaListener(MediaListener l) { mediaListener = l; }

    private static final long READ_TIMEOUT_S = 50;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private volatile boolean running;
    private volatile Call call;
    private Thread thread;

    public YugiEvents(Listener l) { listener = l; }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "YugiEvents");
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        Call c = call;
        if (c != null) c.cancel();
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private String url() {
        return base() + "/events?role=phone"; // role=phone: this app can take media (hybrid stage 2)
    }

    static String base() {
        return "http://" + YugiConfig.host + ":" + YugiConfig.WHIP_PORT;
    }

    private static final OkHttpClient POSTER = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build();

    /** POST /media/state (fire and forget, off the UI thread): opened | playing | paused | closed | error. */
    public static void postMediaState(String id, String state, double t, String reason) {
        try {
            JSONObject o = new JSONObject().put("id", id).put("state", state)
                    .put("t", Double.isNaN(t) || t < 0 ? 0 : t);
            if (reason != null) o.put("reason", reason);
            okhttp3.RequestBody body = okhttp3.RequestBody.create(o.toString(),
                    okhttp3.MediaType.get("application/json"));
            POSTER.newCall(new Request.Builder().url(base() + "/media/state").post(body).build())
                    .enqueue(new okhttp3.Callback() {
                        @Override public void onFailure(Call call, java.io.IOException e) {
                            LimeLog.info("YugiEvents: media state " + state + ": " + e.getMessage());
                        }
                        @Override public void onResponse(Call call, Response res) { res.close(); }
                    });
        } catch (Exception e) {
            LimeLog.warning("YugiEvents: media state " + e);
        }
    }

    private void loop() {
        long backoff = 1000;
        while (running) {
            boolean gotData = false;
            try {
                Call c = http.newCall(new Request.Builder().url(url())
                        .header("Accept", "text/event-stream").build());
                call = c;
                try (Response res = c.execute()) {
                    if (res.isSuccessful() && res.body() != null) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(res.body().byteStream(), StandardCharsets.UTF_8));
                        String line, event = null;
                        boolean replay = true; // until the server's "ready" marker
                        while (running && (line = r.readLine()) != null) {
                            gotData = true;
                            if (line.startsWith("event:")) {
                                event = line.substring(6).trim();
                            } else if (line.startsWith("data:") && event != null) {
                                if ("ready".equals(event)) replay = false;
                                else dispatch(event, line.substring(5).trim(), replay);
                            } else if (line.isEmpty()) {
                                event = null;
                            }
                        }
                    } else if (res.code() == 404) {
                        backoff = 30_000; // bridge without /events yet: check rarely
                    }
                }
            } catch (Exception e) {
                if (running) LimeLog.info("YugiEvents: " + e.getMessage());
            } finally {
                call = null;
            }
            if (!running) break;
            if (gotData) backoff = 1000;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                break;
            }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }

    private void dispatch(String event, String data, boolean replay) {
        if ("media".equals(event)) {
            MediaListener ml = mediaListener;
            if (ml == null || replay) return;
            try {
                YugiPlayer.Request r = YugiPlayer.Request.parse(new JSONObject(data));
                if (r != null) ui.post(() -> { if (running) ml.onMedia(r); });
            } catch (Exception e) {
                LimeLog.warning("YugiEvents: bad media " + e);
            }
            return;
        }
        if (!"caret".equals(event)) return;
        try {
            Caret c = new Caret(new JSONObject(data));
            ui.post(() -> { if (running) listener.onCaret(c, replay); });
        } catch (Exception e) {
            LimeLog.warning("YugiEvents: bad caret " + e);
        }
    }
}

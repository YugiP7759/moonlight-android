package com.limelight.yugi;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;

import com.limelight.LimeLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Self-measurement: what one visit to the desktop stream costs in battery.
 *
 * <p>A visit opens when the stream connects and closes when the stream really stops (background,
 * screen off, back to the games screen, a drop). Fold and rotation re-streams stay inside one visit.
 * Each closed visit (at least a minute) is kept on the phone (last {@link #KEEP}, SharedPreferences
 * "yugi_power") and sent to yugipc-bridge as {@code POST /phone/power} (a JSON array). Unsent visits
 * wait for the next stream (an older bridge answers 404: they stay queued, capped at KEEP).
 *
 * <p>Precision: whole percents are too coarse for a 10 minute visit, so the battery's charge counter
 * (µAh) is recorded at both ends too. A visit during which the phone charged is marked charging.
 * Nothing polls: two sticky-broadcast reads per visit.
 */
public final class YugiPowerLog {
    private YugiPowerLog() {}

    static final String PREFS = "yugi_power";
    static final String KEY_LOG = "log";
    static final String KEY_UNSENT = "unsent";
    static final int KEEP = 50;
    static final long MIN_VISIT_MS = 60_000;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build();

    // the open visit (main thread)
    private static long startWall, startElapsed;
    private static int startPct = -1, restreams;
    private static long startCharge = Long.MIN_VALUE;
    /** A fold re-stream left the stream at this time (elapsed); 0 while streaming. */
    private static long restreamAt;
    /** A re-stream that didn't come back within this long ends the visit unmeasured. */
    static final long RESTREAM_GRACE_MS = 120_000;
    private static boolean charged, open, everSmooth, everLow;
    private static int fps, kbps, width, height;
    private static String screen, net;

    /** The stream is up (first frame). Opens a visit, or counts a re-stream inside the open one. */
    public static synchronized void streamStarted(Context ctx, int w, int h, int streamFps, int streamKbps) {
        Context app = ctx.getApplicationContext();
        YugiPower.Profile p = YugiPower.current;
        long now = android.os.SystemClock.elapsedRealtime();
        if (open && restreamAt > 0 && now - restreamAt > RESTREAM_GRACE_MS) open = false; // never came back
        restreamAt = 0;
        if (open) {
            restreams++;
        } else {
            open = true;
            restreams = 0;
            startWall = System.currentTimeMillis();
            startElapsed = android.os.SystemClock.elapsedRealtime();
            Battery b = Battery.read(app);
            startPct = b.pct;
            startCharge = b.chargeUah;
            charged = b.plugged;
            everSmooth = false;
            everLow = false;
            flush(app);
        }
        fps = streamFps;
        kbps = streamKbps;
        width = w;
        height = h;
        screen = YugiConfig.isWide(w, h) ? "cover" : "inner";
        net = network(app);
        if (streamFps > YugiPower.DESKTOP_FPS) everSmooth = true;
        if (p != null && p.low) everLow = true;
    }

    /** A fold / rotation / profile re-stream: the visit stays open for the next stream. */
    public static synchronized void streamRestarting() {
        if (open) restreamAt = android.os.SystemClock.elapsedRealtime();
    }

    /** The stream really stopped (not a fold re-stream): closes the visit, keeps it and sends it. */
    public static synchronized void streamStopped(Context ctx, String reason) {
        if (!open) return;
        open = false;
        restreamAt = 0;
        Context app = ctx.getApplicationContext();
        long ms = android.os.SystemClock.elapsedRealtime() - startElapsed;
        if (ms < MIN_VISIT_MS) return;
        Battery b = Battery.read(app);
        try {
            JSONObject o = new JSONObject();
            o.put("start", startWall);
            o.put("minutes", Math.round(ms / 6000.0) / 10.0);
            o.put("pct_start", startPct);
            o.put("pct_end", b.pct);
            if (startPct >= 0 && b.pct >= 0) {
                int drop = startPct - b.pct;
                o.put("pct_drop", drop);
                o.put("pct_per_hour", Math.round(drop * 36_000_000.0 / ms) / 10.0);
            }
            if (startCharge != Long.MIN_VALUE && b.chargeUah != Long.MIN_VALUE) {
                long usedUah = startCharge - b.chargeUah;
                o.put("mah_used", Math.round(usedUah / 100.0) / 10.0);
                o.put("ma_avg", Math.round(usedUah * 3_600_000.0 / ms / 1000.0 * 10) / 10.0);
                if (usedUah < 0) charged = true;
            }
            if (b.plugged) charged = true;
            o.put("charging", charged);
            o.put("fps", fps);
            o.put("kbps", kbps);
            o.put("size", width + "x" + height);
            o.put("screen", screen);
            o.put("net", net);
            o.put("smooth", everSmooth);
            o.put("low_battery", everLow);
            o.put("restreams", restreams);
            o.put("reason", reason);
            o.put("model", Build.MODEL);
            append(app, KEY_LOG, o);
            append(app, KEY_UNSENT, o);
            LimeLog.info("YugiPowerLog: " + o);
        } catch (Exception e) {
            LimeLog.warning("YugiPowerLog: " + e);
        }
        flush(app);
    }

    /** The visits kept on the phone, newest last. */
    public static JSONArray log(Context ctx) { return read(ctx.getApplicationContext(), KEY_LOG); }

    static synchronized void resetForTest(Context ctx) {
        open = false;
        prefs(ctx.getApplicationContext()).edit().clear().commit();
    }

    // ---------------------------------------------------------------- storage

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONArray read(Context app, String key) {
        try {
            return new JSONArray(prefs(app).getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void append(Context app, String key, JSONObject o) {
        JSONArray a = read(app, key);
        a.put(o);
        JSONArray kept = new JSONArray();
        for (int i = Math.max(0, a.length() - KEEP); i < a.length(); i++) kept.put(a.opt(i));
        prefs(app).edit().putString(key, kept.toString()).apply();
    }

    /** Sends the queued visits; on 2xx they leave the queue. */
    private static void flush(Context app) {
        final JSONArray unsent = read(app, KEY_UNSENT);
        if (unsent.length() == 0) return;
        final int n = unsent.length();
        try {
            HTTP.newCall(new Request.Builder().url(YugiEvents.base() + "/phone/power")
                    .post(RequestBody.create(unsent.toString(), MediaType.get("application/json"))).build())
                    .enqueue(new okhttp3.Callback() {
                        @Override public void onFailure(Call call, java.io.IOException e) {
                            LimeLog.info("YugiPowerLog: send " + e.getMessage());
                        }
                        @Override public void onResponse(Call call, Response res) {
                            boolean ok = res.isSuccessful();
                            res.close();
                            if (ok) dropSent(app, n);
                        }
                    });
        } catch (Exception e) {
            LimeLog.warning("YugiPowerLog: send " + e);
        }
    }

    /** Removes the first {@code n} queued visits (more may have been queued meanwhile). */
    static synchronized void dropSent(Context app, int n) {
        JSONArray a = read(app, KEY_UNSENT);
        JSONArray rest = new JSONArray();
        for (int i = n; i < a.length(); i++) rest.put(a.opt(i));
        prefs(app).edit().putString(KEY_UNSENT, rest.toString()).apply();
    }

    // ---------------------------------------------------------------- phone state

    static final class Battery {
        int pct = -1;
        long chargeUah = Long.MIN_VALUE;
        boolean plugged;

        static Battery read(Context app) {
            Battery b = new Battery();
            try {
                Intent i = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (i != null) {
                    int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    if (level >= 0 && scale > 0) b.pct = Math.round(level * 100f / scale);
                    b.plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                }
                BatteryManager bm = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int c = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                    if (c > 0 && c != Integer.MIN_VALUE) b.chargeUah = c;
                }
            } catch (Exception ignored) {
            }
            return b;
        }
    }

    private static String network(Context app) {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "?";
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (c == null) return "none";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cell";
            return "other";
        } catch (Exception e) {
            return "?";
        }
    }
}

package com.limelight.yugi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import com.limelight.LimeLog;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The phone's status bar for the desktop: the stream runs in full screen and hides Android's own
 * status bar, so battery (percent, charging), Wi-Fi bars and cellular bars go to yugipc-bridge
 * (POST /phone/status) and a plasmoid shows them next to the clock on the desktop.
 *
 * <p>Battery cost: nothing polls. The values come from system callbacks the phone delivers anyway
 * (sticky ACTION_BATTERY_CHANGED, the Wi-Fi network callback and RSSI_CHANGED, the telephony signal
 * strength listener). Each callback reduces its value to bars or a whole percent, and only a change
 * in those schedules a send: 2 s debounce, and never more often than every 30 s. Runs only while the
 * stream is up ({@link #start} when it starts, {@link #stop} in stopConnection / onStop). No new
 * permissions: ACCESS_NETWORK_STATE and ACCESS_WIFI_STATE are already there, the signal strength
 * listener needs none.
 */
public final class YugiPhoneStatus {
    private static final long DEBOUNCE_MS = 2_000;
    private static final long MIN_INTERVAL_MS = 30_000;
    private static final long FIRST_MS = 800; // the Wi-Fi and signal callbacks report their first value

    private static Handler ui;         // created in start() (keeps the class loadable in plain unit tests)
    private static OkHttpClient http;

    // All state below is touched on the main thread only.
    private static Context app;
    private static boolean running;
    private static int battery = -1, wifi = -1, cell = -1;
    private static boolean charging;
    private static String lastSent;     // payload the bridge has
    private static long lastSentAt;     // uptime of the last send
    private static boolean scheduled;

    private static BroadcastReceiver batteryRx, rssiRx;
    private static ConnectivityManager.NetworkCallback wifiCb;
    private static Object cellCb; // TelephonyCallback (31+) or PhoneStateListener

    private YugiPhoneStatus() {}

    /** Main thread. Starts listening and sends the current state right away. */
    public static void start(Context context) {
        if (running) return;
        running = true;
        app = context.getApplicationContext();
        if (ui == null) ui = new Handler(Looper.getMainLooper());
        if (http == null) http = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build();
        lastSent = null;
        lastSentAt = 0;
        try {
            startBattery();
            startWifi();
            startCell();
        } catch (Exception e) {
            LimeLog.warning("YugiPhoneStatus: start " + e);
        }
        changed();
    }

    /** Main thread. Idempotent. */
    public static void stop() {
        if (!running) return;
        running = false;
        ui.removeCallbacks(send);
        scheduled = false;
        try {
            if (batteryRx != null) app.unregisterReceiver(batteryRx);
            if (rssiRx != null) app.unregisterReceiver(rssiRx);
            if (wifiCb != null) {
                ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(wifiCb);
            }
            stopCell();
        } catch (Exception e) {
            LimeLog.warning("YugiPhoneStatus: stop " + e);
        }
        batteryRx = rssiRx = null;
        wifiCb = null;
        cellCb = null;
    }

    // ---------- battery ----------

    private static void startBattery() {
        batteryRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { onBattery(i); }
        };
        // Sticky: the current state comes back at once (and through onReceive)
        Intent now = app.registerReceiver(batteryRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (now != null) onBattery(now);
    }

    private static void onBattery(Intent i) {
        if (!running) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int pct = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        boolean chg = plugged != 0 && (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        if (pct != battery || chg != charging) {
            battery = pct;
            charging = chg;
            changed();
        }
    }

    // ---------- Wi-Fi ----------

    /** RSSI (dBm) to bars 0..4, roughly Android's own thresholds. */
    static int wifiBars(int rssi) {
        if (rssi == Integer.MIN_VALUE || rssi <= -127) return 0;
        if (rssi >= -55) return 4;
        if (rssi >= -66) return 3;
        if (rssi >= -77) return 2;
        if (rssi >= -88) return 1;
        return 0;
    }

    private static void startWifi() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        // Connected / gone, and the RSSI of a new Wi-Fi network (WifiInfo; no location needed for RSSI)
        wifiCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                int rssi = Integer.MIN_VALUE;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && caps.getTransportInfo() instanceof WifiInfo) {
                    rssi = ((WifiInfo) caps.getTransportInfo()).getRssi();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    rssi = caps.getSignalStrength();
                }
                final int bars = rssi == Integer.MIN_VALUE ? -2 : wifiBars(rssi);
                ui.post(() -> setWifi(bars == -2 ? Math.max(wifi, 0) : bars));
            }
            @Override public void onLost(Network n) { ui.post(() -> setWifi(-1)); }
        };
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        cm.registerNetworkCallback(req, wifiCb);

        // Signal changes while connected: the system already broadcasts them (no extra scans)
        rssiRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int rssi = i.getIntExtra(WifiManager.EXTRA_NEW_RSSI, Integer.MIN_VALUE);
                if (rssi != Integer.MIN_VALUE && wifi >= 0) setWifi(wifiBars(rssi));
            }
        };
        app.registerReceiver(rssiRx, new IntentFilter(WifiManager.RSSI_CHANGED_ACTION));
    }

    private static void setWifi(int bars) {
        if (!running || bars == wifi) return;
        wifi = bars;
        changed();
    }

    // ---------- cellular ----------

    private static void startCell() {
        TelephonyManager tm = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null || tm.getSimState() != TelephonyManager.SIM_STATE_READY) {
            cell = -1; // no SIM: no cellular icon
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            CellCallback cb = new CellCallback();
            tm.registerTelephonyCallback(app.getMainExecutor(), cb);
            cellCb = cb;
        } else {
            @SuppressWarnings("deprecation")
            PhoneStateListener l = new PhoneStateListener() {
                @Override public void onSignalStrengthsChanged(SignalStrength s) { onSignal(s); }
            };
            //noinspection deprecation
            tm.listen(l, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
            cellCb = l;
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private static final class CellCallback extends TelephonyCallback
            implements TelephonyCallback.SignalStrengthsListener {
        @Override public void onSignalStrengthsChanged(SignalStrength s) { onSignal(s); }
    }

    private static void stopCell() {
        if (cellCb == null) return;
        TelephonyManager tm = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cellCb instanceof TelephonyCallback) {
            tm.unregisterTelephonyCallback((TelephonyCallback) cellCb);
        } else if (cellCb instanceof PhoneStateListener) {
            //noinspection deprecation
            tm.listen((PhoneStateListener) cellCb, PhoneStateListener.LISTEN_NONE);
        }
    }

    private static void onSignal(SignalStrength s) {
        if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        final int bars = Math.max(0, Math.min(4, s.getLevel())); // 0..4 (SIGNAL_STRENGTH_GREAT = 4)
        ui.post(() -> {
            if (!running || bars == cell) return;
            cell = bars;
            changed();
        });
    }

    // ---------- send ----------

    private static String payload() {
        try {
            return new JSONObject().put("battery", Math.max(battery, 0)).put("charging", charging)
                    .put("wifi", wifi).put("cell", cell).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** A value changed: send it after the debounce, and not sooner than 30 s after the last send. */
    private static void changed() {
        if (!running || scheduled) return;
        long now = SystemClock.uptimeMillis();
        long at = lastSentAt == 0 ? now + FIRST_MS : Math.max(now + DEBOUNCE_MS, lastSentAt + MIN_INTERVAL_MS);
        scheduled = true;
        ui.postAtTime(send, at);
    }

    private static final Runnable send = () -> {
        scheduled = false;
        if (!running) return;
        final String body = payload();
        if (body == null || body.equals(lastSent)) return;
        lastSent = body;
        lastSentAt = SystemClock.uptimeMillis();
        http.newCall(new Request.Builder().url(YugiEvents.base() + "/phone/status")
                .post(RequestBody.create(body, MediaType.get("application/json"))).build())
                .enqueue(new okhttp3.Callback() {
                    @Override public void onFailure(Call call, java.io.IOException e) {
                        LimeLog.info("YugiPhoneStatus: " + e.getMessage());
                        // try again with the next change (or the 30 s window)
                        ui.post(() -> { if (body.equals(lastSent)) { lastSent = null; changed(); } });
                    }
                    @Override public void onResponse(Call call, Response res) {
                        // 404: an older bridge without /phone/status; nothing to retry
                        res.close();
                    }
                });
    };
}

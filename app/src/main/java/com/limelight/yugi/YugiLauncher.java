package com.limelight.yugi;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.IdentityManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.utils.DeviceUtils;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * YugiPC entry point: connect straight to the server and start streaming the desktop.
 * No PC list, no app grid. Moonlight's other screens stay in the APK but are not in this flow.
 */
public class YugiLauncher extends Activity {
    private static final String PREFS = "yugipc";
    private static final String KEY_CERT = "server_cert";
    private static final String KEY_LAST_W = "last_w";
    private static final String KEY_LAST_H = "last_h";
    private static final String KEY_LAST_FPS = "last_fps";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button retry;
    private Button exit;
    private boolean working;
    private boolean launchedGame;
    private Runnable pendingAutoRetry;
    private android.widget.ImageView frozen;
    private boolean resizeLaunch;
    private boolean askingPermissions;
    private long gameStartedAt;
    private int quickFailures;
    private static final String KEY_PERMS_ASKED = "perms_asked";
    private static final int REQ_PERMS = 7001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        YugiMedia.registerDefault();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (askingPermissions) return;
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PERMS_ASKED, false)) {
            askPermissions();
            return;
        }
        if (YugiTransition.pendingResize) {
            // Rotation / fold: keep the last frame on screen, blurred, and re-stream right away.
            YugiTransition.pendingResize = false;
            launchedGame = false;
            removeFrozen();
            frozen = YugiTransition.addFrozenFrame(this, false);
            int d = YugiTransition.rotationDelta;
            YugiTransition.animateIn(frozen, d == 1 ? 90f : d == 3 ? -90f : d == 2 ? 180f : 0f);
            resizeLaunch = true;
            connect();
            return;
        }
        if (launchedGame) {
            // Back from the stream (closed or dropped): reconnect shortly, never show a PC list.
            launchedGame = false;
            long ranFor = System.currentTimeMillis() - gameStartedAt;
            quickFailures = ranFor < 15000 ? quickFailures + 1 : 0;
            if (quickFailures >= 3) {
                // Keeps failing right away: stop looping and let the user retry.
                quickFailures = 0;
                showError("החיבור נופל שוב ושוב. נסה שוב בעוד רגע");
                return;
            }
            showStatus("מתחבר מחדש…", true);
            ui.post(() -> exit.setVisibility(View.VISIBLE));
            scheduleRetry(1500);
        } else if (!working) {
            connect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pendingAutoRetry != null) {
            ui.removeCallbacks(pendingAutoRetry);
            pendingAutoRetry = null;
        }
    }

    private void scheduleRetry(long delayMs) {
        pendingAutoRetry = () -> {
            pendingAutoRetry = null;
            connect();
        };
        ui.postDelayed(pendingAutoRetry, delayMs);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0B1220"));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        progress = new ProgressBar(this);
        root.addView(progress, new LinearLayout.LayoutParams(dp(48), dp(48)));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, dp(16));
        root.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        retry = new Button(this);
        retry.setText("נסה שוב");
        retry.setTypeface(Typeface.DEFAULT_BOLD);
        retry.setMinHeight(dp(48));
        retry.setMinWidth(dp(160));
        retry.setOnClickListener(v -> connect());
        retry.setVisibility(View.GONE);
        root.addView(retry);

        exit = new Button(this);
        exit.setText("יציאה");
        exit.setMinHeight(dp(48));
        exit.setMinWidth(dp(160));
        exit.setOnClickListener(v -> {
            if (pendingAutoRetry != null) ui.removeCallbacks(pendingAutoRetry);
            finishAndRemoveTask();
        });
        exit.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(exit, lp);

        setContentView(root);
    }

    private void showStatus(String text, boolean busy) {
        ui.post(() -> {
            status.setText(text);
            progress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
            retry.setVisibility(busy ? View.GONE : View.VISIBLE);
            exit.setVisibility(busy ? View.GONE : View.VISIBLE);
        });
    }

    private void removeFrozen() {
        if (frozen != null) {
            android.view.ViewGroup p = (android.view.ViewGroup) frozen.getParent();
            if (p != null) p.removeView(frozen);
            frozen = null;
        }
    }

    private void showError(String text) {
        working = false;
        resizeLaunch = false;
        YugiTransition.snapshot = null;
        ui.post(this::removeFrozen);
        showStatus(text, false);
    }

    // ---- first-run permissions ----

    private void askPermissions() {
        askingPermissions = true;
        new android.app.AlertDialog.Builder(this)
                .setTitle("הרשאות")
                .setMessage("YugiPC צריך גישה למיקרופון ולמצלמה כדי להעביר אותם למחשב בזמן החיבור. "
                        + "אפשר להשתיק או לכבות אותם בכל רגע מהכפתורים הקטנים במסך.")
                .setCancelable(false)
                .setPositiveButton("אישור", (d, w) -> {
                    List<String> perms = new ArrayList<>();
                    perms.add(android.Manifest.permission.RECORD_AUDIO);
                    perms.add(android.Manifest.permission.CAMERA);
                    if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
                    } else {
                        onPermissionsDone();
                    }
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) onPermissionsDone();
    }

    private void onPermissionsDone() {
        askingPermissions = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_PERMS_ASKED, true).apply();
        connect();
    }

    // ---- connection flow ----

    private void connect() {
        if (working) return;
        working = true;
        if (pendingAutoRetry != null) {
            ui.removeCallbacks(pendingAutoRetry);
            pendingAutoRetry = null;
        }
        showStatus("מתחבר למחשב…", true);
        final StreamSize size = computeStreamSize();
        new Thread(() -> runConnect(size), "YugiConnect").start();
    }

    private void runConnect(StreamSize size) {
        String uniqueId = new IdentityManager(this).getUniqueId();
        ComputerDetails.AddressTuple addr = new ComputerDetails.AddressTuple(YugiConfig.host, YugiConfig.HTTP_PORT);
        X509Certificate cert = loadCert();

        // 0. Embedded Tailscale (private builds): bring the tailnet up, then talk to 127.0.0.1
        boolean tunnel = YugiTunnel.isAvailable();
        if (tunnel) {
            showStatus("מתחבר לרשת…", true);
            if (!YugiTunnel.ensureReady(this)) {
                showError("אין חיבור לרשת. ודא שיש אינטרנט ונסה שוב");
                return;
            }
            showStatus("מתחבר למחשב…", true);
            addr = new ComputerDetails.AddressTuple(YugiConfig.host, YugiConfig.HTTP_PORT);
        }

        // 1. Reachability
        String serverInfo;
        NvHTTP http;
        try {
            http = new NvHTTP(addr, 0, uniqueId, cert, PlatformBinding.getCryptoProvider(this));
            serverInfo = http.getServerInfo(true);
        } catch (Exception e) {
            LimeLog.warning("YugiPC: server unreachable: " + e);
            showError(tunnel ? "אין חיבור למחשב. נסה שוב בעוד רגע"
                    : "אין חיבור. ודא ש-Tailscale פועל בטלפון");
            return;
        }

        try {
            ComputerDetails details = http.getComputerDetails(serverInfo);

            // 2. Pair automatically if needed
            if (cert == null || details.pairState != PairingManager.PairState.PAIRED) {
                showStatus("מצמד את הטלפון למחשב…", true);
                cert = autoPair(http, serverInfo);
                if (cert == null) {
                    showError("הצימוד נכשל. נסה שוב בעוד רגע");
                    return;
                }
                http = new NvHTTP(addr, 0, uniqueId, cert, PlatformBinding.getCryptoProvider(this));
                serverInfo = http.getServerInfo(true);
                details = http.getComputerDetails(serverInfo);
                if (details.pairState != PairingManager.PairState.PAIRED) {
                    clearCert();
                    showError("הצימוד נכשל. נסה שוב בעוד רגע");
                    return;
                }
            }

            // 3. Find the Desktop app
            NvApp desktop = null;
            List<NvApp> apps = http.getAppList();
            for (NvApp a : apps) {
                if (YugiConfig.APP_NAME.equalsIgnoreCase(a.getAppName())) { desktop = a; break; }
            }
            if (desktop == null && !apps.isEmpty()) desktop = apps.get(0);
            if (desktop == null) {
                showError("לא נמצא שולחן עבודה במחשב");
                return;
            }

            // 4. If a session runs at another size, quit it so the server switches resolution
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean sameSize = sp.getInt(KEY_LAST_W, 0) == size.width && sp.getInt(KEY_LAST_H, 0) == size.height
                    && sp.getInt(KEY_LAST_FPS, 0) == size.fps;
            if (details.runningGameId != 0 && !sameSize) {
                showStatus("מתאים את המסך…", true);
                try { http.quitApp(); } catch (Exception e) { LimeLog.warning("YugiPC: quit failed: " + e); }
            }

            // 5. Start streaming
            applyStreamPrefs(size);
            sp.edit().putInt(KEY_LAST_W, size.width).putInt(KEY_LAST_H, size.height).putInt(KEY_LAST_FPS, size.fps).apply();
            final Intent intent = buildGameIntent(details, desktop, uniqueId, cert);
            ui.post(() -> {
                working = false;
                launchedGame = true;
                gameStartedAt = System.currentTimeMillis();
                boolean seamless = resizeLaunch;
                resizeLaunch = false;
                if (seamless) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(intent);
                if (seamless) {
                    overridePendingTransition(0, 0);
                } else {
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                }
                // The Game activity shows its own copy of the frozen frame; drop ours once it is covered.
                ui.postDelayed(this::removeFrozen, 600);
            });
        } catch (Exception e) {
            LimeLog.warning("YugiPC: connect failed: " + e);
            showError("החיבור נכשל. נסה שוב");
        }
    }

    private X509Certificate autoPair(NvHTTP http, String serverInfo) {
        final String pin = PairingManager.generatePinString();
        final AtomicBoolean stop = new AtomicBoolean(false);
        // Submit the PIN to Sunshine's API while pair() waits for it.
        Thread pinThread = new Thread(() ->
                SunshinePairing.submitPin(pin, DeviceUtils.getModel(), 30000, stop), "YugiPin");
        pinThread.start();
        try {
            PairingManager pm = http.getPairingManager();
            PairingManager.PairState st = pm.pair(serverInfo, pin, null);
            if (st == PairingManager.PairState.PAIRED && pm.getPairedCert() != null) {
                saveCert(pm.getPairedCert());
                return pm.getPairedCert();
            }
            LimeLog.warning("YugiPC: pair state " + st);
        } catch (Exception e) {
            LimeLog.warning("YugiPC: pairing error: " + e);
        } finally {
            stop.set(true);
        }
        return null;
    }

    private Intent buildGameIntent(ComputerDetails computer, NvApp app, String uniqueId, X509Certificate cert) throws Exception {
        Intent i = new Intent(this, Game.class);
        i.putExtra(Game.EXTRA_HOST, YugiConfig.host);
        i.putExtra(Game.EXTRA_PORT, YugiConfig.HTTP_PORT);
        i.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort);
        i.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        i.putExtra(Game.EXTRA_APP_UUID, app.getAppUUID());
        i.putExtra(Game.EXTRA_APP_ID, app.getAppId());
        i.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        i.putExtra(Game.EXTRA_UNIQUEID, uniqueId);
        i.putExtra(Game.EXTRA_PC_UUID, computer.uuid);
        i.putExtra(Game.EXTRA_PC_NAME, computer.name);
        i.putExtra(Game.EXTRA_VDISPLAY, false);
        i.putExtra(Game.EXTRA_SERVER_COMMANDS, computer.serverCommands != null
                ? new ArrayList<>(computer.serverCommands) : new ArrayList<String>());
        i.putExtra(Game.EXTRA_SERVER_CERT, cert.getEncoded());
        i.putExtra(Game.EXTRA_YUGI, true);
        return i;
    }

    // ---- stream size / prefs ----

    static final class StreamSize {
        int width, height, fps;
    }

    private StreamSize computeStreamSize() {
        int w, h;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect b = getWindowManager().getCurrentWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        } else {
            Point p = new Point();
            getWindowManager().getDefaultDisplay().getRealSize(p);
            w = p.x;
            h = p.y;
        }
        boolean wide = YugiConfig.isWide(w, h);
        float scale = (wide ? YugiConfig.COVER_SCALE : YugiConfig.INNER_SCALE) * YugiConfig.RES_SCALE;
        int fps = wide ? YugiConfig.COVER_FPS : YugiConfig.INNER_FPS;
        if (YugiFps.fallback || maxRefreshRate() < fps - 1) fps = YugiConfig.FPS_FALLBACK;
        StreamSize s = new StreamSize();
        s.width = YugiConfig.align(w * scale);
        s.height = YugiConfig.align(h * scale);
        s.fps = fps;
        return s;
    }

    private float maxRefreshRate() {
        Display d = getWindowManager().getDefaultDisplay();
        float max = d.getRefreshRate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Display.Mode m : d.getSupportedModes()) max = Math.max(max, m.getRefreshRate());
        }
        return max;
    }

    /** Writes Moonlight's stream preferences: native screen size, max FPS, hardware decode, no stats overlay. */
    private void applyStreamPrefs(StreamSize s) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
                .putString("list_resolution", s.width + "x" + s.height)
                .putString("list_fps", String.valueOf(s.fps))
                .putBoolean("checkbox_enable_perf_overlay", false)
                .putBoolean("checkbox_enable_post_stream_toast", false)
                .putBoolean("checkbox_auto_orientation", true)
                .putBoolean("checkbox_auto_invert_video_resolution", false)
                .putString("list_video_scale_mode", "stretch")
                .putBoolean("checkbox_full_screen", true)
                .putBoolean("checkbox_unlock_fps", s.fps > 60)
                .putBoolean("checkbox_enable_microphone", false)
                .putBoolean("checkbox_enable_quit_dialog", false)
                .putInt("seekbar_bitrate_kbps", s.fps > 60 ? YugiConfig.BITRATE_120_KBPS : YugiConfig.BITRATE_60_KBPS)
                .putString("mouse_mode_list", YugiConfig.MOUSE_MODE)
                .putBoolean("checkbox_multi_touch_gestures", false)
                .putBoolean("checkbox_enable_commit_text", true) // IME text (incl. Hebrew) as UTF-8
                .putBoolean("checkbox_disable_warnings", true)
                .putBoolean("checkbox_enable_floating_button", false)
                .apply();
    }

    // ---- server cert persistence ----

    private X509Certificate loadCert() {
        String b64 = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CERT, null);
        if (b64 == null) return null;
        try {
            byte[] der = Base64.decode(b64, Base64.DEFAULT);
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    private void saveCert(X509Certificate cert) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_CERT, Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            LimeLog.warning("YugiPC: cannot save cert: " + e);
        }
    }

    private void clearCert() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_CERT).apply();
    }
}

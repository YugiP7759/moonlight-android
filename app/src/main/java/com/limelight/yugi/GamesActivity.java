package com.limelight.yugi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.limelight.LimeLog;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "משחקים": Boosteroid cloud gaming in a full-screen WebView, with our own editable virtual
 * gamepad (fed to the page through a Gamepad API polyfill), fit/fill display and FPS/ping stats.
 */
public class GamesActivity extends Activity {
    public static final String URL = "https://cloud.boosteroid.com/";
    private static final String PREFS = "yugipc_games";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private VirtualPadView pad;
    private TextView statsView;
    private View editButton;
    private TextView padToggle;
    private LinearLayout editBar;
    private SeekBar sizeBar, alphaBar;
    private View customView;
    private boolean statsOn;
    private String fit = "contain";
    private String injectJs;
    private boolean landscape;
    private String layoutKey;
    private YugiChat chat;

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            if (!statsOn) return;
            web.evaluateJavascript("(function(){if(!window.__yugi)return '';__yugi.stats();return __yugi.lastStats||'';})()",
                    v -> showStats(v));
            ui.postDelayed(this, 1000);
        }
    };

    /** Read by the injected JS on every navigator.getGamepads() call. */
    public static final class PadBridge {
        private final VirtualPadView pad;
        PadBridge(VirtualPadView p) { pad = p; }
        @JavascriptInterface
        public String state() { return pad.jsState(); }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        fit = sp.getString("fit", "contain");
        statsOn = sp.getBoolean("stats", false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        web = new WebView(this);
        root.addView(web, match());
        pad = new VirtualPadView(this);
        root.addView(pad, match());
        pad.setPadEnabled(sp.getBoolean("pad", true));

        setupWebView();
        buildOverlay();
        updateLayoutKey();
        goImmersive();

        web.loadUrl(URL);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);  // autoplay
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);            // sign-in popups open in place
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Present as mobile Chrome (same engine version), without the WebView "wv" marker
        String ua = WebSettings.getDefaultUserAgent(this)
                .replace("; wv)", ")")
                .replaceAll("Version/\\d+(\\.\\d+)* ", "");
        s.setUserAgentString(ua);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.emptySet());
        }
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new PadBridge(pad), "YugiPadNative");
        injectJs = loadAsset("yugi_games.js");
        boolean docStart = false;
        if (injectJs != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(web, injectJs, Collections.singleton("*"));
                docStart = true;
            } catch (Exception e) {
                LimeLog.warning("YugiPC games: document-start script failed: " + e);
            }
        }
        final boolean injectLate = !docStart;

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // everything stays inside the app (incl. sign-in)
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (injectLate && injectJs != null) view.evaluateJavascript(injectJs, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (injectLate && injectJs != null) view.evaluateJavascript(injectJs, null);
                applyPageSettings();
                CookieManager.getInstance().flush();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // WebRTC (mic for voice chat etc.) on Boosteroid only
                String host = request.getOrigin() != null ? request.getOrigin().getHost() : "";
                if (host != null && (host.equals("boosteroid.com") || host.endsWith(".boosteroid.com"))) {
                    request.grant(request.getResources());
                } else {
                    request.deny();
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // Element fullscreen: show it under our controller
                if (customView != null) root.removeView(customView);
                customView = view;
                root.addView(view, 1, match());
                goImmersive();
            }

            @Override
            public void onHideCustomView() {
                if (customView != null) {
                    root.removeView(customView);
                    customView = null;
                }
                goImmersive();
            }
        });
    }

    private String loadAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            byte[] buf = new byte[in.available()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LimeLog.warning("YugiPC games: no " + name + ": " + e);
            return null;
        }
    }

    private void applyPageSettings() {
        web.evaluateJavascript("window.__yugi&&(__yugi.setFit('" + fit + "'),__yugi.setHideSiteController("
                + pad.isPadEnabled() + "),__yugi.announce&&__yugi.announce())", null);
    }

    // ---------------------------------------------------------------- overlay UI

    private void buildOverlay() {
        statsView = new TextView(this);
        statsView.setTextColor(Color.WHITE);
        statsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statsView.setBackgroundColor(0x88000000);
        statsView.setPadding(dp(8), dp(4), dp(8), dp(4));
        statsView.setVisibility(statsOn ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        slp.leftMargin = dp(12);
        slp.topMargin = dp(12);
        root.addView(statsView, slp);
        if (statsOn) ui.post(statsTick);

        // Edit button: long press enters edit mode
        TextView edit = new TextView(this);
        edit.setText("✎");
        edit.setTextColor(Color.WHITE);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        edit.setGravity(Gravity.CENTER);
        edit.setContentDescription("עריכת שלט (לחיצה ארוכה)");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        edit.setBackground(bg);
        edit.setAlpha(0.4f);
        edit.setOnLongClickListener(v -> { pad.setEditing(true); return true; });
        edit.setOnClickListener(v -> android.widget.Toast.makeText(this,
                "לחיצה ארוכה לעריכת השלט", android.widget.Toast.LENGTH_SHORT).show());
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        elp.topMargin = dp(12);
        root.addView(edit, elp);
        editButton = edit;
        editButton.setVisibility(pad.isPadEnabled() ? View.VISIBLE : View.GONE);

        // Pad toggle: small fixed button left of the edit button; stays (faded) when the pad is hidden
        padToggle = new TextView(this);
        padToggle.setText("\uD83C\uDFAE");
        padToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        padToggle.setGravity(Gravity.CENTER);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setShape(GradientDrawable.OVAL);
        tbg.setColor(0x66000000);
        padToggle.setBackground(tbg);
        padToggle.setOnClickListener(v -> { if (!pad.isEditing()) togglePad(); });
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tlp.topMargin = dp(8);
        tlp.rightMargin = dp(56); // centre 56dp left of the edit button's centre
        root.addView(padToggle, tlp);
        updatePadToggle();

        buildEditBar();

        YugiNav.attachMenuButton(this, this::menuItems);
        chat = YugiChat.attach(this);
    }

    private List<YugiNav.Item> menuItems() {
        List<YugiNav.Item> items = new ArrayList<>();
        items.add(new YugiNav.Item("המחשב שלי", false, () -> YugiNav.openPc(this)));
        items.add(new YugiNav.Item("משחקים", true, null));
        items.add(new YugiNav.Item(pad.isPadEnabled() ? "הסתר שלט" : "הצג שלט", false, this::togglePad));
        items.add(new YugiNav.Item("cover".equals(fit) ? "תצוגה: מלא (לחץ להתאם)" : "תצוגה: התאם (לחץ למלא)",
                false, this::toggleFit));
        items.add(new YugiNav.Item(statsOn ? "הסתר סטטיסטיקות" : "סטטיסטיקות (FPS ופינג)", false, this::toggleStats));
        items.add(new YugiNav.Item("טען מחדש", false, () -> web.reload()));
        return items;
    }

    private void togglePad() {
        boolean on = !pad.isPadEnabled();
        pad.setPadEnabled(on);
        editButton.setVisibility(on ? View.VISIBLE : View.GONE);
        updatePadToggle();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("pad", on).apply();
        applyPageSettings();
    }

    private void updatePadToggle() {
        boolean on = pad.isPadEnabled();
        padToggle.setAlpha(on ? 0.45f : 0.3f);
        padToggle.setContentDescription(on ? "הסתר שלט" : "הצג שלט");
    }

    private void toggleFit() {
        fit = "cover".equals(fit) ? "contain" : "cover";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("fit", fit).apply();
        applyPageSettings();
    }

    private void toggleStats() {
        statsOn = !statsOn;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("stats", statsOn).apply();
        statsView.setVisibility(statsOn ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(statsTick);
        if (statsOn) {
            statsView.setText("…");
            ui.post(statsTick);
        }
    }

    private void showStats(String jsResult) {
        try {
            String s = jsResult;
            if (s == null || s.equals("null") || s.equals("\"\"")) {
                statsView.setText("אין סטרים");
                return;
            }
            // evaluateJavascript returns a JSON-encoded string
            s = new org.json.JSONTokener(s).nextValue().toString();
            JSONObject o = new JSONObject(s);
            String fps = o.isNull("fps") ? "–" : String.valueOf(o.getInt("fps"));
            String ping = o.isNull("ping") ? "–" : o.getInt("ping") + "ms";
            String res = o.optInt("w") > 0 ? "  " + o.optInt("w") + "x" + o.optInt("h") : "";
            statsView.setText("FPS " + fps + "   פינג " + ping + res);
        } catch (Exception e) {
            statsView.setText("אין סטרים");
        }
    }

    private void buildEditBar() {
        editBar = new LinearLayout(this);
        editBar.setOrientation(LinearLayout.VERTICAL);
        editBar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        editBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6111827);
        bg.setCornerRadius(dp(14));
        editBar.setBackground(bg);
        editBar.setVisibility(View.GONE);

        TextView hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setText("גרור כפתור כדי להזיז. בחר כפתור כדי לשנות גודל ושקיפות.");
        editBar.addView(hint);

        sizeBar = slider("גודל", 30, 220, v -> pad.setSelectedSize(v));
        alphaBar = slider("שקיפות", 10, 100, v -> pad.setSelectedAlpha(v / 100f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(this);
        save.setText("שמור");
        save.setOnClickListener(v -> { pad.saveLayout(); pad.setEditing(false); });
        Button cancel = new Button(this);
        cancel.setText("ביטול");
        cancel.setOnClickListener(v -> { pad.setEditing(false); pad.loadLayout(layoutKey, landscape); });
        Button reset = new Button(this);
        reset.setText("איפוס");
        reset.setOnClickListener(v -> pad.resetLayout(landscape));
        row.addView(save);
        row.addView(cancel);
        row.addView(reset);
        editBar.addView(row);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(320),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(60);
        root.addView(editBar, lp);

        pad.setEditListener(new VirtualPadView.EditListener() {
            @Override
            public void onEditModeChanged(boolean editing) {
                editBar.setVisibility(editing ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onSelectionChanged(VirtualPadView.Control c) {
                boolean has = c != null;
                sizeBar.setEnabled(has);
                alphaBar.setEnabled(has);
                if (has) {
                    sizeBar.setProgress(Math.round(c.sizeDp) - 30);
                    alphaBar.setProgress(Math.round(c.alpha * 100) - 10);
                }
            }
        });
    }

    private interface FloatSetter { void set(float v); }

    private SeekBar slider(String label, int min, int max, FloatSetter setter) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setText(label);
        t.setPadding(0, dp(8), 0, 0);
        editBar.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setEnabled(false);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) setter.set(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        editBar.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        return sb;
    }

    // ---------------------------------------------------------------- screen changes

    /** Layout per screen: cover / inner and landscape / portrait. */
    private void updateLayoutKey() {
        int w, h;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect b = getWindowManager().getCurrentWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        } else {
            android.graphics.Point p = new android.graphics.Point();
            getWindowManager().getDefaultDisplay().getRealSize(p);
            w = p.x;
            h = p.y;
        }
        landscape = w >= h;
        String key = (YugiConfig.isWide(w, h) ? "cover" : "inner") + "_" + (landscape ? "l" : "p");
        if (!key.equals(layoutKey)) {
            if (pad.isEditing()) pad.setEditing(false);
            layoutKey = key;
            pad.loadLayout(key, landscape);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateLayoutKey();
        goImmersive();
    }

    private void goImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (statsOn) { ui.removeCallbacks(statsTick); ui.post(statsTick); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause(); // pauses media while the desktop stream is in front
        ui.removeCallbacks(statsTick);
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (chat != null) chat.release();
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (chat != null && chat.isOpen()) {
            chat.toggle();
        } else if (pad.isEditing()) {
            pad.setEditing(false);
            pad.loadLayout(layoutKey, landscape);
        } else if (customView != null) {
            web.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null);
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            YugiNav.openPc(this);
        }
    }
}

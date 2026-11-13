package com.limelight.yugi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.PixelCopy;
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
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * "משחקים": Boosteroid cloud gaming in an edge-to-edge WebView, with our own pad (Gamepad API polyfill),
 * contain / cover / fill display, a forced 16:9 stream option, FPS/ping HUD and the Yugi chat.
 */
public class GamesActivity extends Activity {
    public static final String URL = "https://cloud.boosteroid.com/";
    private static final String PREFS = "yugipc_games";
    private static final String[] FITS = {"contain", "cover", "fill"};
    private static final String[] FIT_NAMES = {"התאם (פסים)", "מלא (חיתוך)", "מתוח"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private VirtualPadView pad;
    private YugiChrome chrome;
    private YugiChrome.Button padButton;
    private YugiPadEditor editor;
    private YugiChat chat;
    private View customView;
    private boolean statsOn;
    private String fit = "cover";
    private boolean force169 = true;
    private boolean siteMenu;
    private String injectJs;
    private boolean landscape;
    private String layoutKey;
    private int imeInset;
    /** Page the WebView showed when the screen went to the background (blanked so Boosteroid's stream stops). */
    private String stoppedUrl;
    /** The chat's file picker is up: a short trip, the game and the chat stay as they are. */
    private boolean pickingFile;

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            if (!statsOn) return;
            web.evaluateJavascript("(function(){if(!window.__yugi)return '';__yugi.stats();return __yugi.lastStats||'';})()",
                    GamesActivity.this::showStats);
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

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        fit = sp.getString("fit2", "cover");
        statsOn = sp.getBoolean("stats", false);
        force169 = sp.getBoolean("force169", true);

        root = new FrameLayout(this);
        root.setBackgroundColor(YugiTheme.BRAND_BG);
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

        // Edge to edge; the keyboard only pushes the page up when the chat is closed (login fields)
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int ime = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            if (ime != imeInset) {
                imeInset = ime;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) web.getLayoutParams();
                lp.bottomMargin = (chat != null && chat.isOpen()) ? 0 : ime;
                web.setLayoutParams(lp);
            }
            return insets;
        });

        web.loadUrl(URL);
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
        String ua = WebSettings.getDefaultUserAgent(this)
                .replace("; wv)", ")")
                .replaceAll("Version/\\d+(\\.\\d+)* ", "");
        s.setUserAgentString(ua);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.emptySet());
        }
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setBackgroundColor(Color.BLACK);

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
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (injectLate && injectJs != null) view.evaluateJavascript(injectJs, null);
                applyPageSettings();
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
                String host = request.getOrigin() != null ? request.getOrigin().getHost() : "";
                if (host != null && (host.equals("boosteroid.com") || host.endsWith(".boosteroid.com"))) {
                    request.grant(request.getResources());
                } else {
                    request.deny();
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) root.removeView(customView);
                customView = view;
                root.addView(view, 1, match());
                YugiTheme.raiseOverlays(root, view); // toolbar, chat and Yugi stay above the fullscreen video
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
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LimeLog.warning("YugiPC games: no " + name + ": " + e);
            return null;
        }
    }

    private void applyPageSettings() {
        web.evaluateJavascript("window.__yugi&&__yugi.configure(" + configJson() + ")", null);
    }

    private String configJson() {
        try {
            return new JSONObject()
                    .put("fit", fit)
                    .put("hideSiteController", pad.isPadEnabled())
                    .put("showSiteMenu", siteMenu)
                    .put("force169", force169)
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------------------------------------------------------------- overlay

    private void buildOverlay() {
        List<YugiChrome.Button> buttons = new ArrayList<>();
        // יוגי: the chrome's primary orb (one floating control); the chat grows out of it
        YugiChrome.Button yugi = new YugiChrome.Button(R.drawable.yugi_ic_yugi, "יוגי", () -> {
            if (chat != null) chat.toggle();
        });
        padButton = new YugiChrome.Button(R.drawable.yugi_ic_gamepad, "הצג או הסתר שלט", this::togglePad);
        padButton.active = pad.isPadEnabled();
        buttons.add(padButton);
        buttons.add(new YugiChrome.Button(R.drawable.yugi_ic_edit, "עריכת שלט", this::startEditing));
        buttons.add(new YugiChrome.Button(R.drawable.yugi_ic_menu, "תפריט",
                () -> YugiNav.showPanel(this, menuItems(), chrome.getReservedTop())));

        editor = new YugiPadEditor(this, new YugiPadEditor.Actions() {
            @Override public void onSave() { pad.saveLayout(); pad.setEditing(false); }
            @Override public void onCancel() { pad.setEditing(false); pad.loadLayout(layoutKey, landscape); }
            @Override public void onReset() { pad.resetLayout(landscape); }
            @Override public void onSize(float dp) { pad.setSelectedSize(dp); }
            @Override public void onAlpha(float a) { pad.setSelectedAlpha(a); }
        });
        root.addView(editor, match());
        pad.setEditListener(new VirtualPadView.EditListener() {
            @Override public void onEditModeChanged(boolean editing) {
                editor.setVisibility(editing ? View.VISIBLE : View.GONE);
                chrome.setChromeVisible(!editing);
            }
            @Override public void onSelectionChanged(VirtualPadView.Control c) {
                editor.setSelection(c, pad.selectedCenterY(), root.getHeight());
            }
        });

        chrome = new YugiChrome(this, buttons, YugiChat.isChatEnabled() ? yugi : null);
        root.addView(chrome, match());
        chrome.setReservedTopListener(pad::setTopInset);
        chrome.setHudVisible(statsOn);
        if (statsOn) {
            chrome.setStatsText("…");
            ui.post(statsTick);
        }
        chrome.poke();

        chat = YugiChat.attach(this, new YugiChat.Host() {
            @Override public void captureScreen(YugiChat.BitmapCallback cb) { snapshot(cb); }
            @Override public void pickFile() { pickingFile = true; YugiChat.openPicker(GamesActivity.this); }
            @Override public int reservedTop() { return chrome.getReservedTop(); }
            @Override public void onKeyboard(boolean shown) { chrome.setChromeVisible(!shown); }
            @Override public android.graphics.Rect anchorRect() { return chrome.primaryRectInWindow(); }
            @Override public boolean anchorRight() { return chrome.isDockedRight(); }
            @Override public void onChatOpen(boolean open) { chrome.setChatOpen(open); }
        });
        // web page fields (Boosteroid login, search) type with the YugiPC keyboard, never the phone's
        YugiWebKeyboard.attach(this, web, YugiFieldKeyboard.forActivity(this, chat));
    }

    private void startEditing() {
        if (!pad.isPadEnabled()) togglePad();
        pad.setEditing(true);
    }

    private List<YugiNav.Item> menuItems() {
        int fi = Math.max(0, Arrays.asList(FITS).indexOf(fit));
        List<YugiNav.Item> items = new ArrayList<>();
        items.add(new YugiNav.Item("המחשב שלי", false, () -> YugiNav.openPc(this)));
        items.add(new YugiNav.Item("משחקים", true, null));
        items.add(YugiNav.Item.section("מקור"));
        items.add(new YugiNav.Item("Boosteroid (ענן)", true, null));
        items.add(new YugiNav.Item("בטלפון (Steam)", false, () -> YugiNav.openPhoneGames(this)));
        items.add(YugiNav.Item.section("תצוגה"));
        items.add(new YugiNav.Item("תמונה: " + FIT_NAMES[fi], false, this::cycleFit));
        items.add(new YugiNav.Item(force169 ? "רזולוציה: 16:9 (\u20661920×1080\u2069)" : "רזולוציה: לפי המסך", false, this::toggle169));
        items.add(new YugiNav.Item(statsOn ? "סטטיסטיקות: מוצגות" : "סטטיסטיקות: מוסתרות", false, this::toggleStats));
        items.add(YugiNav.Item.section("שלט"));
        items.add(new YugiNav.Item("הגדרות שלט", false, this::openPadSettings));
        items.add(new YugiNav.Item(pad.getSettings().gyro ? "ג'ירוסקופ: פועל" : "ג'ירוסקופ: כבוי", false, this::toggleGyro));
        items.add(new YugiNav.Item("כפתור Menu (Start)", false, () -> pad.pulse(VirtualPadView.START, 150)));
        items.add(new YugiNav.Item("כפתור View (Back)", false, () -> pad.pulse(VirtualPadView.BACK, 150)));
        items.add(new YugiNav.Item("כפתור Xbox (בית)", false, () -> pad.pulse(VirtualPadView.HOME, 150)));
        items.add(YugiNav.Item.section("Boosteroid"));
        items.add(new YugiNav.Item(siteMenu ? "הסתר את התפריט של Boosteroid" : "התפריט של Boosteroid", false, this::toggleSiteMenu));
        items.add(new YugiNav.Item("טען מחדש", false, () -> web.reload()));
        return items;
    }

    private void togglePad() {
        boolean on = !pad.isPadEnabled();
        pad.setPadEnabled(on);
        padButton.setActive(on);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("pad", on).apply();
        applyPageSettings();
    }

    private void cycleFit() {
        int i = (Arrays.asList(FITS).indexOf(fit) + 1) % FITS.length;
        fit = FITS[i];
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("fit2", fit).apply();
        applyPageSettings();
        Toast.makeText(this, "תמונה: " + FIT_NAMES[i], Toast.LENGTH_SHORT).show();
    }

    private void toggle169() {
        force169 = !force169;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("force169", force169).apply();
        applyPageSettings();
        Toast.makeText(this, "ייכנס לתוקף בסשן הבא של Boosteroid", Toast.LENGTH_LONG).show();
    }

    private void toggleSiteMenu() {
        siteMenu = !siteMenu;
        applyPageSettings();
    }

    private void toggleGyro() {
        PadSettings s = pad.getSettings();
        s.gyro = !s.gyro;
        s.save(this);
        pad.applySettings(s);
    }

    private void openPadSettings() {
        final YugiPadSettingsView[] ref = new YugiPadSettingsView[1];
        ref[0] = new YugiPadSettingsView(this, pad.getSettings(), new YugiPadSettingsView.Listener() {
            @Override public void onChanged(PadSettings s) { pad.applySettings(s); }
            @Override public void onClose() { root.removeView(ref[0]); }
        });
        root.addView(ref[0], match());
    }

    private void toggleStats() {
        statsOn = !statsOn;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("stats", statsOn).apply();
        chrome.setHudVisible(statsOn);
        ui.removeCallbacks(statsTick);
        if (statsOn) {
            chrome.setStatsText("…");
            ui.post(statsTick);
        }
    }

    private void showStats(String jsResult) {
        try {
            if (jsResult == null || jsResult.equals("null") || jsResult.equals("\"\"")) {
                chrome.setStatsText("אין סטרים");
                return;
            }
            String s = new JSONTokener(jsResult).nextValue().toString();
            JSONObject o = new JSONObject(s);
            chrome.setStats(o.isNull("fps") ? null : o.getInt("fps"), o.isNull("ping") ? null : o.getInt("ping"),
                    o.optInt("w"), o.optInt("h"));
        } catch (Exception e) {
            chrome.setStatsText("אין סטרים");
        }
    }

    /** Current game frame: from the page's video element; falls back to a window copy. */
    private void snapshot(YugiChat.BitmapCallback cb) {
        web.evaluateJavascript("window.__yugi&&__yugi.snapshot?__yugi.snapshot():''", v -> {
            Bitmap b = null;
            try {
                String s = new JSONTokener(v).nextValue().toString();
                int comma = s.indexOf(',');
                if (s.startsWith("data:image") && comma > 0) {
                    byte[] data = Base64.decode(s.substring(comma + 1), Base64.DEFAULT);
                    b = BitmapFactory.decodeByteArray(data, 0, data.length);
                }
            } catch (Exception ignored) {}
            if (b != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                cb.onBitmap(b);
                return;
            }
            Bitmap win = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(getWindow(), win, r -> cb.onBitmap(r == PixelCopy.SUCCESS ? win : null), ui);
        });
    }

    // ---------------------------------------------------------------- screen changes

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
    protected void onStart() {
        super.onStart();
        pickingFile = false;
        // Back from the background: bring back the page that was blanked in onStop
        if (stoppedUrl != null) {
            String u = stoppedUrl;
            stoppedUrl = null;
            if ("about:blank".equals(web.getUrl()) && web.canGoBack()) web.goBack(); else web.loadUrl(u);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (pickingFile) return;
        // Out of sight (the PC screen, home, screen off): nothing keeps streaming or polling behind it.
        // REORDER_TO_FRONT keeps this activity alive, so Boosteroid's page is blanked rather than paused.
        if (chat != null && chat.isOpen()) chat.setOpen(false);
        if (web != null) {
            String u = web.getUrl();
            if (u != null && !u.startsWith("about:")) {
                stoppedUrl = u;
                web.loadUrl("about:blank");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        pad.setHostPaused(false);
        if (statsOn) { ui.removeCallbacks(statsTick); ui.post(statsTick); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        pad.setHostPaused(true);
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
            chat.setOpen(false);
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

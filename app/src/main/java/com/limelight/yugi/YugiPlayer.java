package com.limelight.yugi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.limelight.LimeLog;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hybrid stage 2: a video that starts on the server desktop plays here, decoded by the phone.
 * <p>
 * The desktop extension reports the video (event "media"); this opens a native player above the stream
 * without touching the stream itself. By default it stays exactly on the video's rect in the page, so it
 * looks like the video simply plays inside the desktop: a hero cut out of the current stream frame holds
 * the place and the real player crossfades in on its first frame (never an empty or black frame). The
 * extension reports every move of the video (event "media.rect": scroll, resize, tab hidden) and the
 * player follows it, clipped to the browser viewport; when the video is mostly off screen it docks as a
 * mini player in a bottom corner (tap: full screen) and comes back when the video scrolls back in.
 * Touches outside the player go to the stream as usual.
 * <p>
 * Full screen is a mode on a spring (Apple-like, interruptible): the 48dp button opposite the close
 * button, YouTube's own full-screen button, or a tap on the mini player. Back or dragging down in full
 * screen collapses it back into the page; Back in the page, or the close button, ends it: the position
 * goes back to the server ({@code POST /media/state closed}), where the desktop video seeks to it.
 * <p>
 * YouTube runs in a WebView embed (IFrame API, autoplay, start=t, playsinline) with m.youtube.com/watch as
 * the fallback; mp4 / HLS run in media3 ExoPlayer. While the player is open the stream audio is ducked.
 */
public final class YugiPlayer {
    // ---------------------------------------------------------------- request

    /** What the desktop extension posted (see yugipc-bridge media.go). */
    public static final class Request {
        public final String kind, id, vid, url, pageUrl, title;
        public final double t;
        /** Video rect in desktop pixels, and the desktop size; w == 0 when unknown. */
        public final float x, y, w, h, sw, sh;
        /** The browser viewport (x, y, w, h, sw, sh) in desktop pixels, or null when unknown. */
        final float[] view;

        Request(String kind, String id, String vid, String url, String pageUrl, String title, double t,
                float x, float y, float w, float h, float sw, float sh, float[] view) {
            this.kind = kind; this.id = id; this.vid = vid; this.url = url; this.pageUrl = pageUrl;
            this.title = title; this.t = t; this.x = x; this.y = y; this.w = w; this.h = h; this.sw = sw; this.sh = sh;
            this.view = view;
        }

        /** null if the event is not something this player can open. */
        public static Request parse(JSONObject o) {
            String kind = o.optString("kind"), id = o.optString("id");
            if (id.isEmpty()) return null;
            String vid = o.optString("vid"), url = o.optString("url");
            if ("youtube".equals(kind)) {
                if (!vid.matches("[\\w-]{6,20}")) return null;
            } else if ("mp4".equals(kind) || "hls".equals(kind)) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
            } else {
                return null;
            }
            double t = o.optDouble("t", 0);
            if (Double.isNaN(t) || t < 0) t = 0;
            JSONObject r = o.optJSONObject("rect");
            float x = 0, y = 0, w = 0, h = 0, sw = 0, sh = 0;
            if (r != null) {
                x = (float) r.optDouble("x", 0); y = (float) r.optDouble("y", 0);
                w = (float) r.optDouble("w", 0); h = (float) r.optDouble("h", 0);
                sw = (float) r.optDouble("sw", 0); sh = (float) r.optDouble("sh", 0);
                if (w < 2 || h < 2 || sw < 2 || sh < 2) { w = h = 0; }
            }
            return new Request(kind, id, vid, url, o.optString("pageUrl"), o.optString("title"), t, x, y, w, h, sw, sh,
                    deskRect(o.optJSONObject("view")));
        }

        boolean hasRect() { return w > 0 && h > 0; }
    }

    /** {x, y, w, h, sw, sh} of a rect in desktop pixels, or null when missing / degenerate. */
    static float[] deskRect(JSONObject r) {
        if (r == null) return null;
        float[] v = {(float) r.optDouble("x", 0), (float) r.optDouble("y", 0), (float) r.optDouble("w", 0),
                (float) r.optDouble("h", 0), (float) r.optDouble("sw", 0), (float) r.optDouble("sh", 0)};
        for (float f : v) if (Float.isNaN(f) || Float.isInfinite(f)) return null;
        return v[2] < 2 || v[3] < 2 || v[4] < 2 || v[5] < 2 ? null : v;
    }

    /** Event "media.rect": the redirected video moved on the desktop (scroll, resize) or left the screen. */
    static final class RectUpdate {
        final String id;
        final boolean hidden;
        final float[] rect, view; // deskRect, null when unknown

        RectUpdate(String id, boolean hidden, float[] rect, float[] view) {
            this.id = id; this.hidden = hidden; this.rect = rect; this.view = view;
        }

        static RectUpdate parse(JSONObject o) {
            String id = o.optString("id");
            if (id.isEmpty()) return null;
            float[] r = deskRect(o.optJSONObject("rect"));
            boolean hidden = o.optBoolean("hidden", false) || r == null;
            return new RectUpdate(id, hidden, r, deskRect(o.optJSONObject("view")));
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<YugiPlayer> current = new WeakReference<>(null);

    /** YugiEvents: event "media.rect" (any thread). The open player follows the video. */
    public static void onRectEvent(String json) {
        final RectUpdate u;
        try {
            u = RectUpdate.parse(new JSONObject(json));
        } catch (Exception e) {
            return;
        }
        if (u == null) return;
        MAIN.post(() -> {
            YugiPlayer p = current.get();
            if (p != null) p.updateRect(u);
        });
    }

    // ---------------------------------------------------------------- geometry (pure, unit tested)

    /** Where the desktop rect of the video is on the phone, given the stream view's bounds on the layer. */
    static RectF mapRect(Request r, RectF stream) {
        if (r == null || !r.hasRect() || stream.width() <= 0) return null;
        float sx = stream.width() / r.sw, sy = stream.height() / r.sh;
        RectF out = new RectF(stream.left + r.x * sx, stream.top + r.y * sy,
                stream.left + (r.x + r.w) * sx, stream.top + (r.y + r.h) * sy);
        // clamp to the stream: a video partly scrolled off the desktop still has a sane start
        if (!out.intersect(stream)) return null;
        return out.width() >= 8 && out.height() >= 8 ? out : null;
    }

    /** A desktop rect {x, y, w, h, sw, sh} on the phone, unclamped (it may reach past the stream). */
    static RectF mapDesk(float[] d, RectF stream) {
        if (d == null || stream.width() <= 0 || stream.height() <= 0) return null;
        float sx = stream.width() / d[4], sy = stream.height() / d[5];
        return new RectF(stream.left + d[0] * sx, stream.top + d[1] * sy,
                stream.left + (d[0] + d[2]) * sx, stream.top + (d[1] + d[3]) * sy);
    }

    /** Share of {@code r} inside {@code clip} (0..1). */
    static float visibleFraction(RectF r, RectF clip) {
        if (r == null || clip == null || r.width() <= 0 || r.height() <= 0) return 0f;
        RectF i = new RectF(r);
        if (!i.intersect(clip)) return 0f;
        return (i.width() * i.height()) / (r.width() * r.height());
    }

    /** The docked mini player: a bottom corner of a w x h layer, clear of the system bars. */
    static RectF miniRect(float aspect, float w, float h, float insetL, float insetR, float insetB,
                          float margin, float maxW, boolean left) {
        if (aspect <= 0) aspect = 16f / 9f;
        float mw = Math.min(maxW, w * (w > h ? 0.3f : 0.46f)), mh = mw / aspect;
        if (mh > h * 0.4f) { mh = h * 0.4f; mw = mh * aspect; }
        float l = left ? insetL + margin : w - insetR - margin - mw;
        float b = h - insetB - margin;
        return new RectF(l, b - mh, l + mw, b);
    }

    /** The video rect when fitted (letterboxed) into a box of w x h. */
    static RectF fit(float aspect, float w, float h) {
        if (aspect <= 0 || w <= 0 || h <= 0) return new RectF(0, 0, w, h);
        float vw = w, vh = w / aspect;
        if (vh > h) { vh = h; vw = h * aspect; }
        float l = (w - vw) / 2f, t = (h - vh) / 2f;
        return new RectF(l, t, l + vw, t + vh);
    }

    /** {@link #fit} inside a box placed anywhere. */
    static RectF fitInto(float aspect, RectF box) {
        RectF r = fit(aspect, box.width(), box.height());
        r.offset(box.left, box.top);
        return r;
    }

    /** Linear blend of two rects (p = 0 -> a, 1 -> b). */
    static RectF lerp(RectF a, RectF b, float p) {
        return new RectF(a.left + (b.left - a.left) * p, a.top + (b.top - a.top) * p,
                a.right + (b.right - a.right) * p, a.bottom + (b.bottom - a.bottom) * p);
    }

    /** youtube embed page, loaded with an https base URL so the iframe has a referrer. */
    static String youtubeHtml(String vid, double t) {
        int start = (int) Math.floor(Math.max(0, t));
        return "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;inset:0;width:100%;height:100%}</style>"
                + "</head><body><div id=p></div><script>"
                + "var t0=" + String.format(Locale.US, "%.3f", Math.max(0, t)) + ",pl,timer;"
                + "function onYouTubeIframeAPIReady(){pl=new YT.Player('p',{videoId:'" + vid + "',width:'100%',height:'100%',"
                + "playerVars:{autoplay:1,playsinline:1,start:" + start + ",rel:0,modestbranding:1,fs:1,origin:'" + YT_ORIGIN + "'},"
                + "events:{onReady:function(e){if(t0-" + start + ">0.5)e.target.seekTo(t0,true);e.target.playVideo();"
                + "timer=setInterval(function(){try{Yugi.onTime(pl.getCurrentTime())}catch(x){}},1000)},"
                + "onStateChange:function(e){try{Yugi.onState(e.data,pl.getCurrentTime())}catch(x){}},"
                + "onError:function(e){Yugi.onError(e.data)}}})}"
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
    }

    static String youtubeWatchUrl(String vid, double t) {
        return "https://m.youtube.com/watch?v=" + vid + "&t=" + (int) Math.floor(Math.max(0, t)) + "s";
    }

    // ---------------------------------------------------------------- constants

    static final String YT_BASE = "https://com.yugi.pc/";
    static final String YT_ORIGIN = "https://com.yugi.pc";
    /** Spring: Apple-like (no bounce on open, a little life on close), interruptible. */
    static final float STIFFNESS = 300f, DAMPING = 0.86f;
    static final long CROSSFADE_MS = 280;
    static final long FIRST_FRAME_TIMEOUT_MS = 12000;
    static final float DUCK_VOLUME = 0.15f;
    static final float CLOSE_DRAG_DP = 110f, CLOSE_FLING_DP_S = 900f;
    /** Following the page while it scrolls: stiff and critically damped, so it never wobbles behind it. */
    static final float FOLLOW_STIFFNESS = 900f;
    /** Mini player when less than this share of the video is in the viewport, back above the second. */
    static final float MINI_ENTER = 0.25f, MINI_EXIT = 0.4f;
    static final float MINI_MAX_DP = 320f, MINI_MARGIN_DP = 16f, MINI_RADIUS_DP = 12f;

    /** Game hooks. */
    public interface Host {
        /** Current desktop frame, or null (async, UI thread). */
        void capture(YugiChat.BitmapCallback cb);
        /** The stream view (for mapping the desktop rect). */
        View streamView();
        /** Player fully closed (the stream may re-stream at a new size now). */
        void onPlayerClosed();
    }

    // ---------------------------------------------------------------- state

    private final Activity activity;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Layer layer;
    private final View scrim;
    private final FrameLayout card;
    private final ImageView hero;
    private final FrameLayout content;
    private final View closeBtn;
    private final FullButton fsBtn;
    // positioned on the spring (placeChrome); the buttons inside fade with the controls
    private final FrameLayout closeWrap, fsWrap;
    private boolean chromeShown;
    private int safeLeft, safeTop, safeRight, safeBottom;
    private final Runnable hideChrome = () -> setChromeShown(false);
    private final Controls controls;

    private Request req;
    private float aspect = 16f / 9f;
    private float progress;        // 0 = in the page (or docked mini), 1 = full screen
    private boolean full;          // the mode progress is heading to
    private float dragY;           // finger offset while dragging down (full screen)
    private boolean closing;
    private boolean firstFrame;
    private double lastT;          // last known position (s)
    private boolean ytFallback;
    private View focusBeforeFull;

    // where the video is on the desktop (updated by "media.rect"; req itself never changes while open)
    private float[] deskVideo, deskView;
    private boolean deskHidden, mini;
    private RectF lastStream;
    /** Shown place when not full screen, in layer px: video box l,t,r,b; viewport clip l,t,r,b; corner radius. */
    private final float[] fv = new float[9];
    private boolean hasSrc;
    // the card's current transform (applyGeometry), for the clip and hit tests
    private float scaleNow = 1f, txNow, tyNow;

    private ExoPlayer exo;
    private TextureView texture;
    private WebView web;

    private final SpringAnimation progressSpring, dragSpring;
    private final SpringAnimation[] followSprings = new SpringAnimation[9];

    private YugiPlayer(Activity a, Host h) {
        activity = a;
        host = h;
        layer = new Layer(a);
        layer.setVisibility(View.GONE);
        layer.setClipChildren(false);

        scrim = new View(a);
        scrim.setBackgroundColor(0xFF000000);
        scrim.setAlpha(0f);
        layer.addView(scrim, match());

        card = new FrameLayout(a);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // geometry below is in plain left/top pixels
        card.setBackgroundColor(0xFF000000);
        card.setPivotX(0);
        card.setPivotY(0);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                RectF c = clipRect();
                float r = hasSrc ? fv[8] / Math.max(0.05f, scaleNow) : YugiTheme.dp(a, 14);
                r *= 1f - Math.max(0f, Math.min(1f, progress));
                o.setRoundRect(Math.round(c.left), Math.round(c.top), Math.round(c.right), Math.round(c.bottom), r);
            }
        });
        layer.addView(card, match());

        content = new FrameLayout(a);
        content.setAlpha(0f);
        card.addView(content, match());

        hero = new ImageView(a);
        hero.setScaleType(ImageView.ScaleType.FIT_XY);
        card.addView(hero, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT));

        controls = new Controls(a);
        layer.addView(controls.root, match());

        int touch = YugiTheme.dp(a, YugiTheme.TOUCH);
        // both always above the video (WebView / texture), in the two top corners of what is shown
        fsBtn = new FullButton(a);
        fsBtn.setOnClickListener(v -> setFull(!full));
        fsWrap = new FrameLayout(a);
        fsWrap.addView(fsBtn, new FrameLayout.LayoutParams(touch, touch));
        fsBtn.setVisibility(View.GONE);
        layer.addView(fsWrap, new FrameLayout.LayoutParams(touch, touch, Gravity.TOP | Gravity.LEFT));

        closeBtn = new CloseButton(a);
        closeBtn.setContentDescription("סגירה");
        closeBtn.setOnClickListener(v -> closeNow());
        closeWrap = new FrameLayout(a);
        closeWrap.addView(closeBtn, new FrameLayout.LayoutParams(touch, touch));
        closeBtn.setVisibility(View.GONE);
        layer.addView(closeWrap, new FrameLayout.LayoutParams(touch, touch, Gravity.TOP | Gravity.LEFT));
        layer.setOnApplyWindowInsetsListener((v, ins) -> {
            // safe area: system bars and the camera cutout
            int l = ins.getSystemWindowInsetLeft(), t = ins.getSystemWindowInsetTop(), r = ins.getSystemWindowInsetRight();
            int b = ins.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28 && ins.getDisplayCutout() != null) {
                l = Math.max(l, ins.getDisplayCutout().getSafeInsetLeft());
                t = Math.max(t, ins.getDisplayCutout().getSafeInsetTop());
                r = Math.max(r, ins.getDisplayCutout().getSafeInsetRight());
                b = Math.max(b, ins.getDisplayCutout().getSafeInsetBottom());
            }
            if (l != safeLeft || t != safeTop || r != safeRight || b != safeBottom) {
                safeLeft = l; safeTop = t; safeRight = r; safeBottom = b;
                layoutTexture();
                retarget(false);
            }
            return ins;
        });

        progressSpring = new SpringAnimation(this, PROGRESS);
        progressSpring.setSpring(new SpringForce().setStiffness(STIFFNESS).setDampingRatio(DAMPING));
        progressSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        progressSpring.addEndListener((anim, canceled, value, velocity) -> {
            if (!canceled && closing && value <= 0.001f) finishClose();
        });
        dragSpring = new SpringAnimation(this, DRAG);
        dragSpring.setSpring(new SpringForce(0f).setStiffness(STIFFNESS).setDampingRatio(DAMPING));
        for (int i = 0; i < followSprings.length; i++) {
            final int k = i;
            SpringAnimation s = new SpringAnimation(this, new FloatPropertyCompat<YugiPlayer>("place" + k) {
                @Override public float getValue(YugiPlayer p) { return p.fv[k]; }
                @Override public void setValue(YugiPlayer p, float v) { p.fv[k] = v; p.applyGeometry(); }
            });
            s.setSpring(new SpringForce().setStiffness(FOLLOW_STIFFNESS).setDampingRatio(1f));
            s.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS);
            followSprings[k] = s;
        }

        layer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                // fold / rotate: the player stays, only its geometry follows the new window
                layoutTexture();
                retarget(false);
                if (req != null && !hasSrc && !full && !closing) setFull(true); // no place in the page for it
            }
        });
    }

    /** Adds the (hidden) player layer above everything on the stream screen. */
    public static YugiPlayer attach(Activity a, Host h) {
        YugiPlayer p = new YugiPlayer(a, h);
        a.addContentView(p.layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // the stream view moving or zooming (no layout pass) moves the video with it
        p.layer.getViewTreeObserver().addOnPreDrawListener(() -> {
            p.checkStreamMoved();
            return true;
        });
        current = new WeakReference<>(p);
        return p;
    }

    public boolean isOpen() { return req != null; }

    // ---------------------------------------------------------------- open

    /** A new video from the desktop (UI thread). Replaces the current one if open. */
    public void open(Request r) {
        if (req != null && r.id.equals(req.id)) return; // the same redirect again: already playing
        if (req != null) {
            // already showing something: swap the content in place, in the same mode
            YugiEvents.postMediaState(req.id, "closed", currentTime(), null);
            releaseContent();
            req = r;
            lastT = r.t;
            firstFrame = false;
            ytFallback = false;
            content.setAlpha(0f);
            closing = false;
            takeDesk(r);
            if (r.hasRect()) aspect = r.w / r.h;
            layoutTexture();
            retarget(true);
            if (!hasSrc) full = true;
            progressSpring.animateToFinalPosition(full ? 1f : 0f);
            startContent();
            YugiEvents.postMediaState(r.id, "opened", r.t, null);
            return;
        }
        req = r;
        lastT = r.t;
        closing = false;
        firstFrame = false;
        ytFallback = false;
        // the shape the user sees on the stream, so the player lands exactly on it
        aspect = r.hasRect() ? r.w / r.h : 16f / 9f;
        takeDesk(r);
        mini = false;
        hasSrc = false;
        lastStream = null;
        full = false;
        progress = 0f;
        dragY = 0f;
        fsBtn.setFull(false);
        layer.bringToFront();
        YugiTheme.raiseOverlays((ViewGroup) layer.getParent(), layer); // toolbar, chat and Yugi stay above the video
        layer.setVisibility(View.VISIBLE);
        retarget(false); // no size yet on the very first open: the layout listener places it
        content.setAlpha(0f);
        hero.setImageBitmap(null);
        hero.setAlpha(1f);
        controls.hide(false);
        setDuck(true);
        startContent(); // load at once: the first frame comes sooner
        YugiEvents.postMediaState(r.id, "opened", r.t, null);

        final Request opening = r;
        host.capture(bmp -> {
            if (req != opening || closing) return;
            Bitmap crop = cropHero(bmp, opening);
            if (crop != null) hero.setImageBitmap(crop);
            retarget(false);
            // no place in the page: full screen, as a flight from the middle (before the first layout, the
            // layout listener decides)
            if (!hasSrc && (deskVideo == null || layer.getWidth() > 0)) setFull(true);
            applyGeometry();
        });
        applyGeometry();
    }

    private void takeDesk(Request r) {
        deskVideo = r.hasRect() ? new float[]{r.x, r.y, r.w, r.h, r.sw, r.sh} : null;
        deskView = r.view;
        deskHidden = false;
    }

    /** "media.rect": the video moved on the desktop; the player follows it (or docks / comes back). */
    void updateRect(RectUpdate u) {
        if (req == null || closing || !u.id.equals(req.id)) return;
        if (u.hidden) {
            deskHidden = true;
        } else {
            deskHidden = false;
            deskVideo = u.rect;
            if (u.view != null) deskView = u.view;
            // YouTube's box is what the embed fills: a new shape (theatre mode, resize) is the new aspect
            if (web != null && !ytFallback && deskVideo != null) {
                float a = deskVideo[2] / deskVideo[3];
                if (Math.abs(a - aspect) > aspect * 0.01f) { aspect = a; layoutTexture(); }
            }
        }
        retarget(true);
    }

    private Bitmap cropHero(Bitmap frame, Request r) {
        if (frame == null || !r.hasRect()) return null;
        int fx = Math.round(r.x / r.sw * frame.getWidth()), fy = Math.round(r.y / r.sh * frame.getHeight());
        int fw = Math.round(r.w / r.sw * frame.getWidth()), fh = Math.round(r.h / r.sh * frame.getHeight());
        Rect c = new Rect(fx, fy, fx + fw, fy + fh);
        if (!c.intersect(0, 0, frame.getWidth(), frame.getHeight()) || c.width() < 4 || c.height() < 4) return null;
        try {
            return Bitmap.createBitmap(frame, c.left, c.top, c.width(), c.height());
        } catch (Throwable t) {
            return null;
        }
    }

    /** The stream view's bounds on the layer, or null. */
    private RectF streamOnLayer() {
        View sv = host.streamView();
        if (sv == null || sv.getWidth() <= 0) return null;
        int[] a = new int[2], b = new int[2];
        sv.getLocationInWindow(a);
        layer.getLocationInWindow(b);
        float sx = sv.getScaleX(), sy = sv.getScaleY();
        return new RectF(a[0] - b[0], a[1] - b[1], a[0] - b[0] + sv.getWidth() * sx, a[1] - b[1] + sv.getHeight() * sy);
    }

    private void checkStreamMoved() {
        if (req == null || closing) return;
        RectF s = streamOnLayer();
        if (s == null || s.equals(lastStream)) return;
        boolean first = lastStream == null;
        lastStream = s;
        if (!first) retarget(false);
    }

    /**
     * Where the player sits when not full screen (layer px, see {@link #fv}). False when there is no place
     * in the page for it (no rect from the desktop, no stream yet).
     */
    private boolean computeTarget(float[] out) {
        RectF stream = streamOnLayer();
        if (req == null || stream == null || layer.getWidth() <= 0) return false;
        RectF src = mapDesk(deskVideo, stream);
        if (src == null) return false;
        RectF clip = mapDesk(deskView, stream);
        if (clip == null) clip = new RectF(stream);
        else if (!clip.intersect(stream)) clip.set(src.left, src.top, src.left, src.top);
        float vis = deskHidden ? 0f : visibleFraction(src, clip);
        mini = mini ? vis < MINI_EXIT : vis < MINI_ENTER;
        if (mini) {
            float w = layer.getWidth(), h = layer.getHeight();
            boolean left = layer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL; // the "end" corner
            src = miniRect(aspect, w, h, safeLeft, safeRight, safeBottom, YugiTheme.dp(activity, MINI_MARGIN_DP),
                    YugiTheme.dp(activity, MINI_MAX_DP), left);
            clip = new RectF(0, 0, w, h);
        }
        out[0] = src.left; out[1] = src.top; out[2] = src.right; out[3] = src.bottom;
        out[4] = clip.left; out[5] = clip.top; out[6] = clip.right; out[7] = clip.bottom;
        out[8] = mini ? YugiTheme.dp(activity, MINI_RADIUS_DP) : 0f;
        return true;
    }

    /** Moves the in-page place to the latest target: at once, or on the follow spring. */
    private void retarget(boolean animate) {
        float[] t = new float[9];
        boolean wasMini = mini;
        if (!computeTarget(t)) { applyGeometry(); return; }
        if (!hasSrc || !animate) {
            for (SpringAnimation s : followSprings) s.cancel();
            System.arraycopy(t, 0, fv, 0, 9);
            hasSrc = true;
        } else {
            // following the page: stiff, no bounce; docking / undocking: the same spring as the modes
            boolean modeChange = wasMini != mini;
            for (int i = 0; i < 9; i++) {
                followSprings[i].getSpring().setStiffness(modeChange ? STIFFNESS : FOLLOW_STIFFNESS)
                        .setDampingRatio(modeChange ? DAMPING : 1f);
                followSprings[i].animateToFinalPosition(t[i]);
            }
        }
        if (mini && !full) controls.hide(true);
        applyGeometry();
    }

    // ---------------------------------------------------------------- modes

    /** Full screen on / off, on the spring (interruptible). Without a place in the page, off means close. */
    private void setFull(boolean on) {
        if (req == null || closing) return;
        if (!on && !hasSrc) { closeNow(); return; }
        full = on;
        fsBtn.setFull(on);
        if (on) {
            focusBeforeFull = activity.getCurrentFocus();
            layer.requestFocus();
        } else {
            restoreFocus();
            dragSpring.animateToFinalPosition(0f);
        }
        if (firstFrame) setChromeShown(true);
        progressSpring.animateToFinalPosition(on ? 1f : 0f);
    }

    /** In the page the stream keeps the keyboard focus; full screen takes it while it lasts. */
    private void restoreFocus() {
        View f = focusBeforeFull;
        focusBeforeFull = null;
        if (layer.hasFocus()) layer.clearFocus();
        if (f != null && f.isAttachedToWindow()) f.requestFocus();
    }

    // ---------------------------------------------------------------- geometry

    private RectF fullVideo() {
        float w = Math.max(1, layer.getWidth()), h = Math.max(1, layer.getHeight());
        // the YouTube embed draws its title and controls over its own box: keep the button corners outside it
        if (req != null && "youtube".equals(req.kind) && !ytFallback) return fitClearOfCorner(aspect, w, h, closeBand());
        return fit(aspect, w, h);
    }

    /** Space a corner button needs from the top / side edge (margin + 48dp + gap, plus the safe inset). */
    private float closeBand() {
        return YugiTheme.dp(activity, 12 + YugiTheme.TOUCH + 8) + Math.max(safeTop, Math.max(safeLeft, safeRight));
    }

    /**
     * Like {@link #fit}, but when the video would cover the top corners, shrinks it so a band of {@code band}
     * stays free at the sides (landscape) or above and below it (portrait).
     */
    static RectF fitClearOfCorner(float aspect, float w, float h, float band) {
        RectF r = fit(aspect, w, h);
        if (r.left >= band || r.top >= band) return r;
        if (w >= h) { r = fit(aspect, Math.max(1, w - 2 * band), h); r.offset(band, 0); }
        else { r = fit(aspect, w, Math.max(1, h - 2 * band)); r.offset(0, band); }
        return r;
    }

    /** The buttons show with the controls and fade after a few seconds; any touch brings them back. */
    private void setChromeShown(boolean on) {
        ui.removeCallbacks(hideChrome);
        if (on) ui.postDelayed(hideChrome, 3500);
        if (on == chromeShown) return;
        chromeShown = on;
        for (View b : new View[]{closeBtn, fsBtn}) {
            b.animate().cancel();
            if (on) {
                if (b.getVisibility() != View.VISIBLE) { b.setAlpha(0f); b.setVisibility(View.VISIBLE); }
                b.animate().alpha(1f).setDuration(180).start();
            } else {
                b.animate().alpha(0f).setDuration(220)
                        .withEndAction(() -> { if (!chromeShown) b.setVisibility(View.GONE); }).start();
            }
        }
    }

    /** The visible part of the card, in the card's own (unscaled) coordinates. */
    private RectF clipRect() {
        RectF full = new RectF(0, 0, Math.max(1, layer.getWidth()), Math.max(1, layer.getHeight()));
        RectF base = fullVideo();
        if (hasSrc && scaleNow > 0.0001f) {
            // the browser viewport (layer px) seen from inside the card: the player never covers the tab strip
            RectF c = new RectF((fv[4] - txNow) / scaleNow, (fv[5] - tyNow) / scaleNow,
                    (fv[6] - txNow) / scaleNow, (fv[7] - tyNow) / scaleNow);
            if (!base.intersect(c)) base.set(base.left, base.top, base.left, base.top);
        }
        return lerp(base, full, Math.max(0f, Math.min(1f, progress)));
    }

    /** What the user sees of the player, in layer px. */
    private RectF visibleOnLayer() {
        RectF c = clipRect();
        return new RectF(c.left * scaleNow + txNow, c.top * scaleNow + tyNow, c.right * scaleNow + txNow, c.bottom * scaleNow + tyNow);
    }

    /** Places the card for the current progress / drag: uniform scale from the place in the page to full screen. */
    private void applyGeometry() {
        if (layer.getWidth() <= 0) return;
        RectF video = fullVideo();
        // the video keeps its shape inside its box in the page (like object-fit: contain on the desktop)
        RectF src = hasSrc ? fitInto(aspect, new RectF(fv[0], fv[1], fv[2], fv[3])) : shrunkCenter(video);
        float s0 = src.width() / video.width();
        float scale = s0 + (1f - s0) * progress;
        // video rect origin moves from src to its full-screen place
        float tx = src.left + (video.left - src.left) * progress - video.left * scale;
        float ty = src.top + (video.top - src.top) * progress - video.top * scale;
        // dragging: follow the finger and shrink a little (Photos-style)
        float h = layer.getHeight();
        float d = Math.max(0f, dragY);
        float dragScale = 1f - Math.min(0.3f, d / h * 0.6f);
        float cx = layer.getWidth() / 2f, cy = h / 2f;
        tx = cx + (tx - cx) * dragScale;
        ty = cy + (ty - cy) * dragScale + d;
        scale *= dragScale;
        scaleNow = scale; txNow = tx; tyNow = ty;
        card.setScaleX(scale);
        card.setScaleY(scale);
        card.setTranslationX(tx);
        card.setTranslationY(ty);
        card.invalidateOutline();

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hero.getLayoutParams();
        int hw = Math.round(video.width()), hh = Math.round(video.height());
        if (lp.width != hw || lp.height != hh || lp.leftMargin != Math.round(video.left) || lp.topMargin != Math.round(video.top)) {
            lp.width = hw; lp.height = hh;
            lp.leftMargin = Math.round(video.left); lp.topMargin = Math.round(video.top);
            hero.setLayoutParams(lp);
        }
        float dragFade = 1f - Math.min(1f, d / (h * 0.6f));
        scrim.setAlpha(Math.max(0f, Math.min(1f, progress)) * dragFade);
        placeChrome(dragFade);
    }

    /** Buttons in the top corners of what is shown (in the page) or of the screen (full), on the same spring. */
    private void placeChrome(float alpha) {
        RectF vis = visibleOnLayer();
        float w = layer.getWidth(), p = Math.max(0f, Math.min(1f, progress));
        int touch = YugiTheme.dp(activity, YugiTheme.TOUCH), m = YugiTheme.dp(activity, 6), fm = YugiTheme.dp(activity, 12);
        boolean rtl = layer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        // close at the start corner (as before), full screen at the end corner
        float inStart = rtl ? vis.right - m - touch : vis.left + m, inEnd = rtl ? vis.left + m : vis.right - m - touch;
        float fStart = rtl ? w - fm - safeRight - touch : fm + safeLeft, fEnd = rtl ? fm + safeLeft : w - fm - safeRight - touch;
        float y = vis.top + m + (fm + safeTop - vis.top - m) * p;
        closeWrap.setTranslationX(inStart + (fStart - inStart) * p);
        fsWrap.setTranslationX(inEnd + (fEnd - inEnd) * p);
        closeWrap.setTranslationY(y);
        fsWrap.setTranslationY(y);
        // a video too small for two buttons side by side shows none (a tap there opens full screen when mini)
        boolean fits = p > 0.5f || (vis.width() >= 2 * touch + 3 * m && vis.height() >= touch + 2 * m);
        int vv = fits && alpha > 0.02f ? View.VISIBLE : View.INVISIBLE;
        for (View wrap : new View[]{closeWrap, fsWrap}) {
            wrap.setAlpha(alpha);
            if (wrap.getVisibility() != vv) wrap.setVisibility(vv);
        }
        if (exo != null) controls.place(vis, p);
    }

    /** Without a place in the page: start from a small centered copy (still a spring, never a cut). */
    private static RectF shrunkCenter(RectF video) {
        float s = 0.86f;
        float w = video.width() * s, h = video.height() * s;
        float cx = video.centerX(), cy = video.centerY();
        return new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
    }

    private static final FloatPropertyCompat<YugiPlayer> PROGRESS = new FloatPropertyCompat<YugiPlayer>("progress") {
        @Override public float getValue(YugiPlayer p) { return p.progress; }
        @Override public void setValue(YugiPlayer p, float v) { p.progress = v; p.applyGeometry(); }
    };
    private static final FloatPropertyCompat<YugiPlayer> DRAG = new FloatPropertyCompat<YugiPlayer>("drag") {
        @Override public float getValue(YugiPlayer p) { return p.dragY; }
        @Override public void setValue(YugiPlayer p, float v) { p.dragY = v; p.applyGeometry(); }
    };

    // ---------------------------------------------------------------- content

    private void startContent() {
        if ("youtube".equals(req.kind)) startYouTube(false);
        else startExo();
        final Request r = req;
        ui.postDelayed(() -> {
            if (req != r || firstFrame || closing) return;
            if ("youtube".equals(r.kind) && !ytFallback) startYouTube(true);
            else fail("no first frame");
        }, FIRST_FRAME_TIMEOUT_MS);
    }

    private void onFirstFrame() {
        if (firstFrame || req == null) return;
        firstFrame = true;
        setChromeShown(true);
        content.animate().alpha(1f).setDuration(CROSSFADE_MS).start();
        hero.animate().alpha(0f).setDuration(CROSSFADE_MS).setStartDelay(40).start();
        YugiEvents.postMediaState(req.id, "playing", currentTime(), null);
    }

    private void startExo() {
        Context c = activity;
        Map<String, String> headers = new HashMap<>();
        if (req.pageUrl != null && req.pageUrl.startsWith("http")) headers.put("Referer", req.pageUrl);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers);
        exo = new ExoPlayer.Builder(c)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(c).setDataSourceFactory(http))
                .build();
        exo.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        exo.setHandleAudioBecomingNoisy(true);
        texture = new TextureView(c);
        content.addView(texture, match());
        exo.setVideoTextureView(texture);
        exo.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() { onFirstFrame(); }
            @Override public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width > 0 && vs.height > 0) {
                    aspect = vs.width * vs.pixelWidthHeightRatio / vs.height;
                    layoutTexture();
                    applyGeometry();
                }
            }
            @Override public void onIsPlayingChanged(boolean playing) { controls.sync(); }
            @Override public void onPlayerError(PlaybackException e) {
                LimeLog.warning("YugiPlayer: " + e.getErrorCodeName() + " " + e.getMessage());
                fail(e.getErrorCodeName());
            }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(req.url));
        if ("hls".equals(req.kind)) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        exo.setMediaItem(item.build(), (long) (req.t * 1000));
        exo.prepare();
        exo.play();
        layoutTexture();
        controls.attach(exo);
    }

    private void layoutTexture() {
        if (web != null) {
            // the embed gets exactly the video box (its own title / controls stay inside it); the watch page is full
            RectF v = fullVideo();
            FrameLayout.LayoutParams wl = ytFallback ? match()
                    : new FrameLayout.LayoutParams(Math.round(v.width()), Math.round(v.height()), Gravity.TOP | Gravity.LEFT);
            if (!ytFallback) { wl.leftMargin = Math.round(v.left); wl.topMargin = Math.round(v.top); }
            web.setLayoutParams(wl);
        }
        if (texture == null) return;
        RectF v = fullVideo();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(v.width()), Math.round(v.height()),
                Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = Math.round(v.left);
        lp.topMargin = Math.round(v.top);
        texture.setLayoutParams(lp);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void startYouTube(boolean fallback) {
        ytFallback = fallback;
        if (web == null) {
            web = new WebView(activity);
            web.setBackgroundColor(0xFF000000);
            WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            web.setWebChromeClient(new WebChromeClient() {
                // YouTube's own full-screen button switches our mode; the page itself never goes full screen
                @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                    ui.post(() -> {
                        try { cb.onCustomViewHidden(); } catch (Throwable ignored) {}
                        setFull(!full);
                    });
                }
                @Override public void onHideCustomView() {}
            });
            web.addJavascriptInterface(new YtBridge(), "Yugi");
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView v, String url) {
                    // the watch page has no IFrame API callbacks: show it once it has drawn
                    if (ytFallback) ui.postDelayed(YugiPlayer.this::onFirstFrame, 350);
                }
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    String host = r.getUrl().getHost();
                    return host == null || !(host.endsWith("youtube.com") || host.endsWith("google.com")
                            || host.endsWith("youtube-nocookie.com"));
                }
            });
            content.addView(web, match());
        }
        if (fallback) {
            LimeLog.info("YugiPlayer: youtube embed failed, watch page fallback");
            web.loadUrl(youtubeWatchUrl(req.vid, lastT));
            setFull(true); // a whole watch page does not fit the video's box in the page
        } else {
            web.loadDataWithBaseURL(YT_BASE, youtubeHtml(req.vid, req.t), "text/html", "utf-8", null);
        }
        layoutTexture();
    }

    /** Calls from the YouTube page (JS thread -> UI thread). */
    private final class YtBridge {
        @JavascriptInterface public void onState(int state, double t) {
            ui.post(() -> {
                if (!Double.isNaN(t) && t > 0) lastT = t;
                if (state == 1) onFirstFrame(); // PLAYING
            });
        }
        @JavascriptInterface public void onTime(double t) {
            ui.post(() -> { if (!Double.isNaN(t) && t > 0) lastT = t; });
        }
        @JavascriptInterface public void onError(int code) {
            ui.post(() -> {
                LimeLog.info("YugiPlayer: youtube embed error " + code);
                if (req != null && !ytFallback && !closing) startYouTube(true);
            });
        }
    }

    private double currentTime() {
        if (exo != null) {
            long p = exo.getCurrentPosition();
            if (p > 0) lastT = p / 1000.0;
        } else if (web != null && ytFallback) {
            web.evaluateJavascript("(function(){var v=document.querySelector('video');return v?v.currentTime:-1})()",
                    s -> { try { double d = Double.parseDouble(s); if (d > 0) lastT = d; } catch (Exception ignored) {} });
        }
        return lastT;
    }

    private void releaseContent() {
        controls.attach(null);
        if (exo != null) {
            exo.release();
            exo = null;
        }
        if (texture != null) {
            content.removeView(texture);
            texture = null;
        }
        if (web != null) {
            content.removeView(web);
            web.stopLoading();
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
    }

    private void fail(String reason) {
        if (req == null || closing) return;
        YugiEvents.postMediaState(req.id, "error", currentTime(), reason);
        closeInternal(false);
    }

    // ---------------------------------------------------------------- close

    /**
     * Back (Game): full screen collapses back into the page; in the page (or mini) the video goes back to
     * the desktop, as with the close button.
     */
    public void close() {
        if (req == null || closing) return;
        if (full && hasSrc) { setFull(false); return; }
        closeNow();
    }

    /** Ends it: back to the video's place on the desktop, then the position goes back to the server. */
    private void closeNow() {
        if (req == null || closing) return;
        double t = currentTime();
        if (web != null && ytFallback) {
            // the watch page answers asynchronously: give it a moment for an exact position
            final Request r = req;
            ui.postDelayed(() -> { if (req == r) YugiEvents.postMediaState(r.id, "closed", lastT, null); }, 250);
        } else {
            YugiEvents.postMediaState(req.id, "closed", t, null);
        }
        closeInternal(true);
    }

    private void closeInternal(boolean animate) {
        closing = true;
        if (exo != null) exo.pause();
        controls.hide(true);
        setChromeShown(false);
        restoreFocus();
        hero.animate().cancel();
        content.animate().cancel();
        // the player itself flies back (the frame cut at the start would be stale by now)
        if (firstFrame) { hero.setAlpha(0f); content.setAlpha(1f); }
        if (!animate || layer.getWidth() <= 0 || progress <= 0.001f) {
            progressSpring.cancel();
            finishClose();
            return;
        }
        dragSpring.animateToFinalPosition(0f);
        progressSpring.animateToFinalPosition(0f);
    }

    private void finishClose() {
        layer.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            layer.setVisibility(View.GONE);
            layer.setAlpha(1f);
            releaseContent();
            hero.setImageBitmap(null);
            for (SpringAnimation s : followSprings) s.cancel();
            req = null;
            closing = false;
            full = false;
            hasSrc = false;
            setDuck(false);
            host.onPlayerClosed();
        }).start();
    }

    /** Activity going away: close at once, the position still goes back to the server. */
    public void release() {
        progressSpring.cancel();
        dragSpring.cancel();
        for (SpringAnimation s : followSprings) s.cancel();
        if (req != null) {
            YugiEvents.postMediaState(req.id, "closed", currentTime(), null);
            req = null;
        }
        full = false;
        hasSrc = false;
        releaseContent();
        layer.setVisibility(View.GONE);
        setDuck(false);
    }

    private static void setDuck(boolean on) {
        com.limelight.binding.audio.AndroidAudioRenderer.yugiVolume = on ? DUCK_VOLUME : 1f;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---------------------------------------------------------------- touch

    /** True when a touch at (x, y) is not for the player: in the page, outside what it shows. */
    private boolean passesThrough(float x, float y) {
        if (req == null) return true;
        if (closing) return true;
        if (full || progress > 0.5f) return false; // full screen owns the screen
        if (visibleOnLayer().contains(x, y)) return false;
        return !(hits(closeWrap, closeBtn, x, y) || hits(fsWrap, fsBtn, x, y));
    }

    private static boolean hits(View wrap, View btn, float x, float y) {
        if (wrap.getVisibility() != View.VISIBLE || btn.getVisibility() != View.VISIBLE) return false;
        float l = wrap.getLeft() + wrap.getTranslationX(), t = wrap.getTop() + wrap.getTranslationY();
        return x >= l && x < l + wrap.getWidth() && y >= t && y < t + wrap.getHeight();
    }

    private final class Layer extends FrameLayout {
        private final int slop;
        private float downX, downY, lastY;
        private boolean dragging, passing, miniTap;
        private VelocityTracker vt;

        Layer(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        private boolean contentScrolledDown() {
            return web != null && ytFallback && web.canScrollVertically(-1);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            // in the page, a touch outside the player goes to the stream (the whole gesture: we refuse its down)
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) passing = passesThrough(e.getX(), e.getY());
            if (passing) return false;
            return super.dispatchTouchEvent(e);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (req == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!closing) setChromeShown(true); // any touch brings the buttons back
                    downX = e.getX(); downY = lastY = e.getY();
                    dragging = false;
                    miniTap = false;
                    if (closing) return false;
                    if (!full && mini && hasSrc && !hits(closeWrap, closeBtn, downX, downY) && !hits(fsWrap, fsBtn, downX, downY)) {
                        miniTap = true; // the docked mini player: a tap opens full screen
                        return true;
                    }
                    if (full && (progressSpring.isRunning() || dragSpring.isRunning())) {
                        // grab mid-flight: the spring stops where it is and the finger takes over
                        progressSpring.cancel(); dragSpring.cancel(); dragging = true; startVt(e); return true;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getY() - downY, dx = e.getX() - downX;
                    // dragging down only in full screen; in the page the video stays a plain part of it
                    if (full && !closing && dy > slop && dy > Math.abs(dx) * 1.5f && !contentScrolledDown()) {
                        dragging = true;
                        lastY = e.getY();
                        startVt(e);
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        private void startVt(MotionEvent e) {
            if (vt != null) vt.recycle();
            vt = VelocityTracker.obtain();
            vt.addMovement(e);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (req == null) return false;
            if (miniTap) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        && Math.hypot(e.getX() - downX, e.getY() - downY) < slop * 2) setFull(true);
                return true;
            }
            if (!dragging) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP && exo != null) controls.toggle();
                return true; // nothing under the player gets touches inside it
            }
            if (vt != null) vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    dragY = Math.max(0f, dragY + (e.getY() - lastY));
                    lastY = e.getY();
                    applyGeometry();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    float vy = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                    float dp = getResources().getDisplayMetrics().density;
                    boolean collapse = dragY > CLOSE_DRAG_DP * dp || vy > CLOSE_FLING_DP_S * dp;
                    dragSpring.setStartVelocity(vy);
                    if (collapse) {
                        setFull(false); // back into the page (closes when there is no place for it there)
                        dragSpring.animateToFinalPosition(0f);
                    } else {
                        dragSpring.animateToFinalPosition(0f);
                        progressSpring.animateToFinalPosition(1f);
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    // ---------------------------------------------------------------- small views

    /** Glass circle with a chevron pointing down: "put the video back". */
    private static final class CloseButton extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CloseButton(Context c) {
            super(c);
            fill.setColor(0x73000000);
            stroke.setColor(YugiTheme.TEXT);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(YugiTheme.dp(c, 2.2f));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setFocusable(true);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f * 0.8f;
            c.drawCircle(w / 2f, h / 2f, r, fill);
            float a = r * 0.42f;
            path.reset();
            path.moveTo(w / 2f - a, h / 2f - a * 0.35f);
            path.lineTo(w / 2f, h / 2f + a * 0.45f);
            path.lineTo(w / 2f + a, h / 2f - a * 0.35f);
            c.drawPath(path, stroke);
        }

        @Override public void setPressed(boolean p) {
            super.setPressed(p);
            animate().scaleX(p ? 0.9f : 1f).scaleY(p ? 0.9f : 1f).setDuration(p ? 60 : 160).start();
        }
    }

    /** Glass circle with four corners: out = full screen, in = back into the page. */
    private static final class FullButton extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean isFull;

        FullButton(Context c) {
            super(c);
            fill.setColor(0x73000000);
            stroke.setColor(YugiTheme.TEXT);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(YugiTheme.dp(c, 2.2f));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setFocusable(true);
            setFull(false);
        }

        void setFull(boolean on) {
            isFull = on;
            setContentDescription(on ? "יציאה ממסך מלא" : "מסך מלא");
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f * 0.8f;
            c.drawCircle(w / 2f, h / 2f, r, fill);
            float a = r * 0.44f, len = r * 0.26f;
            path.reset();
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    if (!isFull) {
                        // corners at the edge of the square, arms pointing back in
                        float x = w / 2f + sx * a, y = h / 2f + sy * a;
                        path.moveTo(x - sx * len, y); path.lineTo(x, y); path.lineTo(x, y - sy * len);
                    } else {
                        // corners near the middle, arms pointing out
                        float x = w / 2f + sx * a * 0.4f, y = h / 2f + sy * a * 0.4f;
                        path.moveTo(x + sx * len, y); path.lineTo(x, y); path.lineTo(x, y + sy * len);
                    }
                }
            }
            c.drawPath(path, stroke);
        }

        @Override public void setPressed(boolean p) {
            super.setPressed(p);
            animate().scaleX(p ? 0.9f : 1f).scaleY(p ? 0.9f : 1f).setDuration(p ? 60 : 160).start();
        }
    }

    /** Minimal ExoPlayer controls: play / pause and a scrubber with times, on a glass bar over the video. */
    private final class Controls {
        final FrameLayout root;
        private final FrameLayout bar;
        private final TextView playBtn, time;
        private final SeekBar seek;
        private ExoPlayer p;
        private boolean shown, scrubbing;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                update();
                if (shown) ui.postDelayed(this, 500);
            }
        };
        private final Runnable autoHide = () -> hide(true);

        Controls(Context c) {
            root = new FrameLayout(c);
            root.setClickable(false);
            bar = new FrameLayout(c);
            bar.setBackground(YugiTheme.rounded(c, YugiTheme.GLASS, YugiTheme.RADIUS_L));
            int pad = YugiTheme.dp(c, 12);
            bar.setPadding(pad, YugiTheme.dp(c, 4), pad, YugiTheme.dp(c, 4));
            int touch = YugiTheme.dp(c, YugiTheme.TOUCH);

            playBtn = YugiTheme.text(c, "❚❚", 16, 600, YugiTheme.TEXT);
            playBtn.setGravity(Gravity.CENTER);
            playBtn.setMinWidth(touch);
            playBtn.setContentDescription("הפעלה / השהיה");
            playBtn.setOnClickListener(v -> {
                if (p == null) return;
                if (p.isPlaying()) p.pause(); else p.play();
                sync();
                kick();
            });
            bar.addView(playBtn, new FrameLayout.LayoutParams(touch, touch, Gravity.START | Gravity.CENTER_VERTICAL));

            time = YugiTheme.text(c, "0:00", 13, 500, YugiTheme.TEXT_DIM);
            time.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            time.setTextDirection(View.TEXT_DIRECTION_LTR);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(YugiTheme.dp(c, 96), touch, Gravity.END | Gravity.CENTER_VERTICAL);
            bar.addView(time, tlp);

            seek = new SeekBar(c);
            YugiTheme.styleSlider(seek);
            seek.setMax(1000);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                    if (user && p != null && p.getDuration() > 0) {
                        p.seekTo(p.getDuration() * v / 1000);
                        update();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar s) { scrubbing = true; ui.removeCallbacks(autoHide); }
                @Override public void onStopTrackingTouch(SeekBar s) { scrubbing = false; kick(); }
            });
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, touch, Gravity.CENTER_VERTICAL);
            // between the play button and the time label, never on top of either
            slp.leftMargin = touch + YugiTheme.dp(c, 4);
            slp.rightMargin = YugiTheme.dp(c, 96) + YugiTheme.dp(c, 4);
            if (Build.VERSION.SDK_INT >= 17) { slp.setMarginStart(slp.leftMargin); slp.setMarginEnd(slp.rightMargin); }
            bar.addView(seek, slp);

            // placed over the shown video by place() (in the page or full screen)
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
            root.addView(bar, blp);
            bar.setAlpha(0f);
            bar.setVisibility(View.GONE);
            bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // a timeline runs left to right
        }

        /** Bottom of the shown video {@code vis} (layer px); margins grow towards full screen ({@code p} = 1). */
        void place(RectF vis, float p) {
            Context c = root.getContext();
            int side = YugiTheme.dp(c, 8 + 8 * p), bottom = YugiTheme.dp(c, 8 + 12 * p);
            int touch = YugiTheme.dp(c, YugiTheme.TOUCH);
            int w = Math.max(touch * 3, Math.round(vis.width() - 2 * side));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            if (lp.width != w) { lp.width = w; bar.setLayoutParams(lp); }
            int bh = bar.getHeight() > 0 ? bar.getHeight() : touch + YugiTheme.dp(c, 8);
            bar.setTranslationX(vis.left + side);
            bar.setTranslationY(vis.bottom - bottom - bh);
        }

        void attach(ExoPlayer player) {
            p = player;
            if (p == null) hide(false);
        }

        void toggle() { if (shown) hide(true); else show(); }

        void show() {
            if (p == null) return;
            shown = true;
            bar.setVisibility(View.VISIBLE);
            applyGeometry(); // places the bar over the video as it is shown now
            bar.animate().alpha(1f).setDuration(180).start();
            ui.removeCallbacks(tick);
            ui.post(tick);
            kick();
        }

        void hide(boolean animate) {
            shown = false;
            ui.removeCallbacks(tick);
            ui.removeCallbacks(autoHide);
            if (!animate) { bar.setAlpha(0f); bar.setVisibility(View.GONE); return; }
            bar.animate().alpha(0f).setDuration(160).withEndAction(() -> { if (!shown) bar.setVisibility(View.GONE); }).start();
        }

        private void kick() {
            ui.removeCallbacks(autoHide);
            ui.postDelayed(autoHide, 3500);
        }

        void sync() {
            if (p != null) playBtn.setText(p.isPlaying() ? "❚❚" : "▶");
        }

        private void update() {
            if (p == null) return;
            long d = p.getDuration(), pos = p.getCurrentPosition();
            if (!scrubbing && d > 0) seek.setProgress((int) (pos * 1000 / d));
            time.setText(fmt(pos) + (d > 0 ? " / " + fmt(d) : ""));
            sync();
        }
    }

    static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    // ---------------------------------------------------------------- test hooks

    View layerForTest() { return layer; }

    void setProgressForTest(float p) { progress = p; applyGeometry(); }

    boolean isFullForTest() { return full; }

    boolean isMiniForTest() { return mini; }

    void toggleFullForTest() { setFull(!full); }

    boolean passesThroughForTest(float x, float y) { return passesThrough(x, y); }

    RectF shownForTest() { return visibleOnLayer(); }
}

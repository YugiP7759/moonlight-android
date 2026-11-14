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
 * player follows it, clipped to the browser viewport (a video scrolled off the desktop is simply not seen,
 * like in the browser; it never leaves the page by itself). The moves are replayed on the page's own clock
 * ({@link RectTrack}), once per display frame, a little behind, so they stay smooth and in step with the
 * stream. Touches outside the player go to the stream as usual.
 * <p>
 * A floating window only by the explicit "חלון צף" button: a small window in a corner, dragged with the
 * finger and snapped to the nearest corner on a spring, with two 48dp buttons under it: "פתח בדפדפן" (the
 * video goes on playing in the desktop browser from the same point) and "סגור" (ends it, the desktop stays
 * paused there). A tap on it opens full screen.
 * <p>
 * Full screen is a mode on a spring (Apple-like, interruptible): the 48dp button opposite the close
 * button, YouTube's own full-screen button, or a tap on the floating window. Back or dragging down in full
 * screen collapses it back (into the page, or the floating window); Back in the page, or the close button,
 * ends it and the video goes on in the desktop browser from the same point
 * ({@code POST /media/state closed, reason browser}). Whenever the player ends some other way (the app
 * stops, a playback error) the desktop gets the position too, so the desktop page is never left with a
 * state that needs a click.
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
        /** The page clock when the rect was read (ms, 0 = unknown: older extension) and its counter. */
        final double ts;
        final long seq;

        RectUpdate(String id, boolean hidden, float[] rect, float[] view, double ts, long seq) {
            this.id = id; this.hidden = hidden; this.rect = rect; this.view = view; this.ts = ts; this.seq = seq;
        }

        static RectUpdate parse(JSONObject o) {
            String id = o.optString("id");
            if (id.isEmpty()) return null;
            float[] r = deskRect(o.optJSONObject("rect"));
            boolean hidden = o.optBoolean("hidden", false) || r == null;
            double ts = o.optDouble("ts", 0);
            if (Double.isNaN(ts) || Double.isInfinite(ts) || ts < 0) ts = 0;
            return new RectUpdate(id, hidden, r, deskRect(o.optJSONObject("view")), ts, o.optLong("seq", 0));
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<YugiPlayer> current = new WeakReference<>(null);

    /** YugiEvents: event "media.rect" (any thread). The open player follows the video. */
    public static void onRectEvent(String json) {
        final long arrived = android.os.SystemClock.elapsedRealtimeNanos(); // off the wire, before the main thread queue
        final RectUpdate u;
        try {
            u = RectUpdate.parse(new JSONObject(json));
        } catch (Exception e) {
            return;
        }
        if (u == null) return;
        MAIN.post(() -> {
            YugiPlayer p = current.get();
            if (p != null) p.updateRect(u, arrived / 1e6);
        });
    }

    /**
     * The video's moves on the desktop, replayed on the page's own clock. Each report carries the page time
     * it was read at; the phone plays them back at (arrival clock - the smallest delay seen lately - DELAY),
     * interpolating between reports. That removes the network's jitter and the reports' uneven spacing (a
     * player that steps at the rate the requests happen to arrive is what looked jerky), and holds the player
     * back about as much as the stream is behind the page, so both move together.
     */
    static final class RectTrack {
        static final int MAX = 64;
        static final double WINDOW_MS = 3000;
        /** On top of the fastest report: the encode + decode the stream adds over a small HTTP request. */
        final double delayMs;
        private final double[] ts = new double[MAX], arr = new double[MAX];
        private final boolean[] hid = new boolean[MAX];
        private final float[][] rect = new float[MAX][], view = new float[MAX][];
        private int n;
        private long lastSeq;
        // recent (arrival - ts) offsets for the baseline
        private final double[] offArr = new double[256], offVal = new double[256];
        private int offN, offHead;

        RectTrack(double delayMs) { this.delayMs = delayMs; }

        void reset() { n = 0; lastSeq = 0; offN = 0; offHead = 0; }

        boolean isEmpty() { return n == 0; }

        /** Adds a report; false when it is older than one already here (out of order). */
        boolean add(RectUpdate u, double arrivedMs) {
            if (u.seq > 0) {
                if (u.seq <= lastSeq) return false;
                lastSeq = u.seq;
            }
            double t = u.ts > 0 ? u.ts : arrivedMs; // older extension: its arrival is the best clock there is
            if (n > 0 && t <= ts[n - 1]) t = ts[n - 1] + 0.01;
            if (n == MAX) shift(1);
            ts[n] = t; arr[n] = arrivedMs; hid[n] = u.hidden; rect[n] = u.rect; view[n] = u.view;
            n++;
            int i = (offHead + offN) % offArr.length;
            if (offN == offArr.length) { offHead = (offHead + 1) % offArr.length; offN--; }
            offArr[i] = arrivedMs; offVal[i] = arrivedMs - t; offN++;
            return true;
        }

        private void shift(int k) {
            System.arraycopy(ts, k, ts, 0, n - k);
            System.arraycopy(arr, k, arr, 0, n - k);
            System.arraycopy(hid, k, hid, 0, n - k);
            System.arraycopy(rect, k, rect, 0, n - k);
            System.arraycopy(view, k, view, 0, n - k);
            n -= k;
        }

        /** Smallest arrival - ts over the last few seconds: the fastest a report made it here. */
        private double baseline(double nowMs) {
            double b = Double.MAX_VALUE;
            for (int k = 0; k < offN; k++) {
                int i = (offHead + k) % offArr.length;
                if (nowMs - offArr[i] <= WINDOW_MS || k == offN - 1) b = Math.min(b, offVal[i]);
            }
            return b == Double.MAX_VALUE ? 0 : b;
        }

        /** The page time shown at {@code nowMs} (phone clock). */
        double playhead(double nowMs) { return nowMs - baseline(nowMs) - delayMs; }

        /**
         * The place at {@code nowMs} into {@code outRect} / {@code outView} (4 or more floats, deskRect layout);
         * returns 0 = nothing yet, 1 = shown, 2 = hidden. {@link #pending} tells whether later reports wait.
         */
        int at(double nowMs, float[] outRect, float[] outView) {
            if (n == 0) return 0;
            double t = playhead(nowMs);
            int i = 0;
            while (i + 1 < n && ts[i + 1] <= t) i++;
            if (i > 0) shift(i); // what is behind the playhead is never needed again
            if (t < ts[0]) return 0; // not yet: the place shown now is still the right one
            if (n == 1 || hid[0] || hid[1] || rect[0] == null || rect[1] == null) {
                return copy(0, outRect, outView);
            }
            float p = (float) ((t - ts[0]) / (ts[1] - ts[0]));
            p = Math.max(0f, Math.min(1f, p));
            for (int k = 0; k < 6; k++) outRect[k] = rect[0][k] + (rect[1][k] - rect[0][k]) * p;
            float[] v0 = view[0], v1 = view[1] != null ? view[1] : view[0];
            if (v0 != null) {
                for (int k = 0; k < 6; k++) outView[k] = v0[k] + (v1[k] - v0[k]) * p;
                return 1;
            }
            outView[2] = 0; // no viewport known
            return 1;
        }

        private int copy(int i, float[] outRect, float[] outView) {
            if (hid[i] || rect[i] == null) return 2;
            System.arraycopy(rect[i], 0, outRect, 0, 6);
            if (view[i] != null) System.arraycopy(view[i], 0, outView, 0, 6); else outView[2] = 0;
            return 1;
        }

        /** True while a report is still ahead of the playhead (keep running frames). */
        boolean pending(double nowMs) { return n > 1 || (n == 1 && ts[0] > playhead(nowMs)); }
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

    /**
     * The floating window's video in a corner of a w x h layer, clear of the system bars ({@code inset} l, t,
     * r, b), with a band of {@code below} under it for its buttons. Corner bit 0: left, bit 1: top.
     */
    static RectF floatRect(float aspect, float w, float h, float[] inset, float margin, float minW, float maxW,
                           float below, int corner) {
        if (aspect <= 0) aspect = 16f / 9f;
        float room = Math.max(1, w - inset[0] - inset[2] - 2 * margin);
        float mw = Math.min(room, Math.max(minW, Math.min(maxW, w * (w > h ? 0.32f : 0.6f)))), mh = mw / aspect;
        float maxH = Math.max(1, (h - inset[1] - inset[3] - 2 * margin) * 0.6f - below);
        if (mh > maxH) { mh = maxH; mw = mh * aspect; }
        boolean left = (corner & 1) != 0, top = (corner & 2) != 0;
        float l = left ? inset[0] + margin : w - inset[2] - margin - mw;
        float t = top ? inset[1] + margin : h - inset[3] - margin - below - mh;
        return new RectF(l, t, l + mw, t + mh);
    }

    /** The corner a floating window flung to (cx, cy) lands in: the quadrant of that point. */
    static int cornerFor(float cx, float cy, float w, float h) {
        return (cx < w / 2f ? 1 : 0) | (cy < h / 2f ? 2 : 0);
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
    /** The page's moves are shown this much after the fastest report (see {@link RectTrack}). */
    static final double RECT_DELAY_MS = 40;
    static final float FLOAT_MIN_DP = 240f, FLOAT_MAX_DP = 360f, FLOAT_MARGIN_DP = 16f, FLOAT_RADIUS_DP = 12f;
    /** Gap between the floating video and its button row. */
    static final float FLOAT_GAP_DP = 8f;
    /** How far a fling carries the floating window (s of its release speed, like iOS PiP). */
    static final float FLING_PROJECT_S = 0.25f;
    /** While playing, the phone tells the desktop where it is every few seconds (its resume point). */
    static final long BEAT_MS = 5000;

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
    private final View floatBtn;
    // positioned on the spring (placeChrome); the buttons inside fade with the controls
    private final FrameLayout closeWrap, fsWrap, floatWrap;
    /** The floating window's button row: "פתח בדפדפן" and "סגור", under the video. */
    private final FrameLayout floatBar;
    private final View browserBtn, stopBtn;
    private boolean chromeShown;
    private int safeLeft, safeTop, safeRight, safeBottom;
    private final Runnable hideChrome = () -> setChromeShown(false);
    private final Controls controls;

    private Request req;
    private float aspect = 16f / 9f;
    private float progress;        // 0 = in the page (or the floating window), 1 = full screen
    private boolean full;          // the mode progress is heading to
    private float dragY;           // finger offset while dragging down (full screen)
    private boolean closing;
    private boolean firstFrame;
    private double lastT;          // last known position (s)
    private boolean ytFallback;
    private boolean videoSized;    // ExoPlayer reported the real video size
    private View focusBeforeFull;

    // where the video is on the desktop (updated by "media.rect"; req itself never changes while open)
    private float[] deskVideo, deskView;
    private boolean deskHidden;
    /** The floating window (only ever by its button) and the corner it sits in (see {@link #floatRect}). */
    private boolean floating;
    private int floatCorner;
    private final RectTrack track = new RectTrack(RECT_DELAY_MS);
    private final float[] tmpRect = new float[6], tmpView = new float[6];
    private boolean framePosted;
    private final android.view.Choreographer.FrameCallback trackFrame = ft -> {
        framePosted = false;
        stepTrack();
    };
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
    /** Moves the shown place {@link #fv} from {@link #moveFrom} to {@link #moveTo} (into / out of / around the floating window). */
    private final SpringAnimation moveSpring;
    private final float[] moveFrom = new float[9], moveTo = new float[9];
    private float moveP = 1f;
    private final Runnable beat = new Runnable() {
        @Override public void run() {
            if (req == null || closing) return;
            if (firstFrame) YugiEvents.postMediaState(req.id, isPaused() ? "paused" : "playing", currentTime(), "beat");
            ui.postDelayed(this, BEAT_MS);
        }
    };

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
        closeBtn.setOnClickListener(v -> closeNow(true));
        closeWrap = new FrameLayout(a);
        closeWrap.addView(closeBtn, new FrameLayout.LayoutParams(touch, touch));
        closeBtn.setVisibility(View.GONE);
        layer.addView(closeWrap, new FrameLayout.LayoutParams(touch, touch, Gravity.TOP | Gravity.LEFT));

        // "חלון צף": next to the full-screen button; the only way the player ever leaves the page
        floatBtn = new FloatButton(a);
        floatBtn.setContentDescription("חלון צף");
        floatBtn.setOnClickListener(v -> setFloating(true));
        floatWrap = new FrameLayout(a);
        floatWrap.addView(floatBtn, new FrameLayout.LayoutParams(touch, touch));
        floatBtn.setVisibility(View.GONE);
        // under the close button (it stays the layer's top child)
        layer.addView(floatWrap, layer.indexOfChild(closeWrap), new FrameLayout.LayoutParams(touch, touch, Gravity.TOP | Gravity.LEFT));

        // the floating window's buttons, in a row under its video (never over the video's own title)
        floatBar = new FrameLayout(a);
        floatBar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        browserBtn = pill(a, "פתח בדפדפן");
        browserBtn.setOnClickListener(v -> handBack());
        floatBar.addView(browserBtn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, touch,
                Gravity.START | Gravity.CENTER_VERTICAL));
        stopBtn = new StopButton(a);
        stopBtn.setContentDescription("סגור");
        stopBtn.setOnClickListener(v -> closeNow(false));
        floatBar.addView(stopBtn, new FrameLayout.LayoutParams(touch, touch, Gravity.END | Gravity.CENTER_VERTICAL));
        floatBar.setVisibility(View.GONE);
        floatBar.setAlpha(0f);
        layer.addView(floatBar, layer.indexOfChild(closeWrap), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, touch,
                Gravity.TOP | Gravity.LEFT));
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
        // one spring for the whole place (9 values), so the geometry is applied once per frame
        moveSpring = new SpringAnimation(this, MOVE);
        moveSpring.setSpring(new SpringForce(1f).setStiffness(STIFFNESS).setDampingRatio(DAMPING));
        moveSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);

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

    private boolean isPaused() { return exo != null && !exo.getPlayWhenReady(); }

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
            videoSized = false;
            content.setAlpha(0f);
            closing = false;
            track.reset();
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
        videoSized = false;
        // the shape the user sees on the stream, so the player lands exactly on it
        aspect = r.hasRect() ? r.w / r.h : 16f / 9f;
        track.reset();
        takeDesk(r);
        floating = false;
        // the bottom corner on the "end" side (left in Hebrew)
        floatCorner = layer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : 0;
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
        ui.removeCallbacks(beat);
        ui.postDelayed(beat, BEAT_MS);

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

    /** "media.rect": the video moved on the desktop; the player follows it on the page's timing ({@link RectTrack}). */
    void updateRect(RectUpdate u, double arrivedMs) {
        if (req == null || closing || !u.id.equals(req.id)) return;
        if (!track.add(u, arrivedMs)) return;
        stepTrack();
    }

    /** Once per display frame while moves are queued: the place the page had a moment ago. */
    private void stepTrack() {
        if (req == null || closing) return;
        double now = android.os.SystemClock.elapsedRealtimeNanos() / 1e6;
        int st = track.at(now, tmpRect, tmpView);
        if (st == 2) {
            deskHidden = true;
        } else if (st == 1) {
            deskHidden = false;
            if (deskVideo == null || deskVideo.length != 6) deskVideo = new float[6];
            System.arraycopy(tmpRect, 0, deskVideo, 0, 6);
            if (tmpView[2] >= 2) {
                if (deskView == null || deskView.length != 6) deskView = new float[6];
                System.arraycopy(tmpView, 0, deskView, 0, 6);
            }
        }
        if (st != 0) retarget(true);
        if (track.pending(now) && !framePosted) {
            framePosted = true;
            android.view.Choreographer.getInstance().postFrameCallback(trackFrame);
        }
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
        if (floating) {
            // the floating window needs no place on the desktop
            RectF f = floatTarget(floatCorner);
            out[0] = f.left; out[1] = f.top; out[2] = f.right; out[3] = f.bottom;
            out[4] = 0; out[5] = 0; out[6] = layer.getWidth(); out[7] = layer.getHeight();
            out[8] = YugiTheme.dp(activity, FLOAT_RADIUS_DP);
            return true;
        }
        RectF src = mapDesk(deskVideo, stream);
        if (src == null) return false;
        RectF clip = mapDesk(deskView, stream);
        if (clip == null) clip = new RectF(stream);
        else if (!clip.intersect(stream)) clip.set(src.left, src.top, src.left, src.top);
        // tab hidden / element gone: nothing to show in the page (the sound goes on, as in the browser)
        if (deskHidden) clip.set(src.left, src.top, src.left, src.top);
        out[0] = src.left; out[1] = src.top; out[2] = src.right; out[3] = src.bottom;
        out[4] = clip.left; out[5] = clip.top; out[6] = clip.right; out[7] = clip.bottom;
        out[8] = 0f;
        return true;
    }

    /** The floating window's video box in {@code corner} (layer px). */
    private RectF floatTarget(int corner) {
        float[] inset = {safeLeft, safeTop, safeRight, safeBottom};
        return floatRect(aspect, layer.getWidth(), layer.getHeight(), inset, YugiTheme.dp(activity, FLOAT_MARGIN_DP),
                YugiTheme.dp(activity, FLOAT_MIN_DP), YugiTheme.dp(activity, FLOAT_MAX_DP), floatBand(), corner);
    }

    /** Space under the floating video for its button row. */
    private float floatBand() {
        return YugiTheme.dp(activity, FLOAT_GAP_DP + YugiTheme.TOUCH);
    }

    /**
     * Moves the shown place to the latest target. Following the page is direct (the moves are already smooth,
     * see {@link RectTrack}); a change of mode (into / out of the floating window) goes on the spring.
     */
    private void retarget(boolean animate) {
        retarget(animate, false, 0f);
    }

    private void retarget(boolean animate, boolean modeChange, float velocity) {
        float[] t = new float[9];
        if (!computeTarget(t)) { applyGeometry(); return; }
        // the shape the user sees on the stream: YouTube's embed fills its box (theatre mode, resize change
        // it), and any video until its real size is known
        boolean pageShape = req != null && (("youtube".equals(req.kind) && !ytFallback) || !videoSized);
        float bw = t[2] - t[0], bh = t[3] - t[1];
        if (!floating && pageShape && bw > 1 && bh > 1 && Math.abs(bw / bh - aspect) > aspect * 0.01f) {
            aspect = bw / bh;
            layoutTexture();
        }
        if (!hasSrc) {
            moveSpring.cancel();
            moveP = 1f;
            System.arraycopy(t, 0, fv, 0, 9);
            hasSrc = true;
        } else if (modeChange && animate) {
            System.arraycopy(fv, 0, moveFrom, 0, 9);
            System.arraycopy(t, 0, moveTo, 0, 9);
            moveSpring.cancel();
            moveP = 0f;
            moveSpring.setStartValue(0f);
            moveSpring.setStartVelocity(velocity);
            moveSpring.animateToFinalPosition(1f);
        } else if (moveSpring.isRunning()) {
            System.arraycopy(t, 0, moveTo, 0, 9); // the flight lands on the newest place
        } else {
            System.arraycopy(t, 0, fv, 0, 9);
        }
        if (floating && !full) controls.hide(true);
        applyGeometry();
    }

    // ---------------------------------------------------------------- modes

    /** Full screen on / off, on the spring (interruptible). Without a place in the page, off means close. */
    private void setFull(boolean on) {
        if (req == null || closing) return;
        if (!on && !hasSrc) { closeNow(true); return; }
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

    /**
     * Into the floating window (only from its button) or back. From full screen it flies straight to its
     * corner. Not without a place on the desktop (there is nothing to go back to there).
     */
    private void setFloating(boolean on) {
        if (req == null || closing) return;
        if (on && full) {
            full = false;
            fsBtn.setFull(false);
            restoreFocus();
            dragSpring.animateToFinalPosition(0f);
            progressSpring.animateToFinalPosition(0f);
        }
        if (floating == on) return;
        floating = on;
        if (on) controls.hide(true);
        if (firstFrame) setChromeShown(true);
        retarget(true, true, 0f);
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
        for (View b : new View[]{closeBtn, fsBtn, floatBtn}) {
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
        // the floating button sits inward of the full-screen one; in full screen landscape under it, so it stays
        // in the free band beside the video (never over the YouTube title)
        float g = touch + m;
        boolean land = w >= layer.getHeight();
        float flIn = rtl ? inEnd + g : inEnd - g;
        float flFull = land ? fEnd : (rtl ? fEnd + g : fEnd - g);
        float flY = vis.top + m + (fm + safeTop + (land ? touch + m : 0) - vis.top - m) * p;
        closeWrap.setTranslationX(inStart + (fStart - inStart) * p);
        fsWrap.setTranslationX(inEnd + (fEnd - inEnd) * p);
        floatWrap.setTranslationX(flIn + (flFull - flIn) * p);
        closeWrap.setTranslationY(y);
        fsWrap.setTranslationY(y);
        floatWrap.setTranslationY(flY);
        // the floating window shows only its own row; a video too small for the three buttons shows none
        boolean inFloat = floating && p < 0.5f;
        boolean fits = !inFloat && (p > 0.5f || (vis.width() >= 3 * touch + 4 * m && vis.height() >= touch + 2 * m));
        int vv = fits && alpha > 0.02f ? View.VISIBLE : View.INVISIBLE;
        for (View wrap : new View[]{closeWrap, fsWrap, floatWrap}) {
            wrap.setAlpha(alpha);
            if (wrap.getVisibility() != vv) wrap.setVisibility(vv);
        }
        placeFloatBar(vis, inFloat ? alpha * (1f - 2f * p) : 0f);
        if (exo != null) controls.place(vis, p);
    }

    /** The floating window's button row, right under what it shows, as wide as it. */
    private void placeFloatBar(RectF vis, float alpha) {
        int touch = YugiTheme.dp(activity, YugiTheme.TOUCH);
        int w = Math.max(touch * 3, Math.round(vis.width()));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) floatBar.getLayoutParams();
        if (lp.width != w) { lp.width = w; floatBar.setLayoutParams(lp); }
        floatBar.setTranslationX(vis.left);
        floatBar.setTranslationY(vis.bottom + YugiTheme.dp(activity, FLOAT_GAP_DP));
        floatBar.setAlpha(Math.max(0f, Math.min(1f, alpha)));
        int vv = alpha > 0.02f ? View.VISIBLE : View.GONE;
        if (floatBar.getVisibility() != vv) floatBar.setVisibility(vv);
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
    private static final FloatPropertyCompat<YugiPlayer> MOVE = new FloatPropertyCompat<YugiPlayer>("move") {
        @Override public float getValue(YugiPlayer p) { return p.moveP; }
        @Override public void setValue(YugiPlayer p, float v) {
            p.moveP = v;
            for (int i = 0; i < 9; i++) p.fv[i] = p.moveFrom[i] + (p.moveTo[i] - p.moveFrom[i]) * v;
            p.applyGeometry();
        }
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
                    videoSized = true;
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
            // the visible video: its renderer is never the one Android lets slow down
            if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
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
     * Back (Game): full screen collapses back (into the page or the floating window); otherwise the video goes back to
     * the desktop, as with the close button.
     */
    public void close() {
        if (req == null || closing) return;
        if (full && hasSrc) { setFull(false); return; }
        closeNow(true);
    }

    /** "פתח בדפדפן": the phone lets go and the desktop browser plays on from the same point. */
    private void handBack() { closeNow(true); }

    /**
     * Ends it: back to the video's place on the desktop, then the position goes back to the server. With
     * {@code playOn} the desktop video plays on from there ("closed", reason "browser"), else it stays
     * paused there (the floating window's "סגור").
     */
    private void closeNow(boolean playOn) {
        if (req == null || closing) return;
        double t = currentTime();
        final String reason = playOn ? "browser" : null;
        if (web != null && ytFallback) {
            // the watch page answers asynchronously: give it a moment for an exact position
            final Request r = req;
            ui.postDelayed(() -> { if (req == r) YugiEvents.postMediaState(r.id, "closed", lastT, reason); }, 250);
        } else {
            YugiEvents.postMediaState(req.id, "closed", t, reason);
        }
        closeInternal(true);
    }

    private void closeInternal(boolean animate) {
        closing = true;
        ui.removeCallbacks(beat);
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
            moveSpring.cancel();
            track.reset();
            req = null;
            closing = false;
            full = false;
            floating = false;
            hasSrc = false;
            setDuck(false);
            host.onPlayerClosed();
        }).start();
    }

    /** Activity going away: close at once, the position still goes back to the server. */
    public void release() {
        progressSpring.cancel();
        dragSpring.cancel();
        moveSpring.cancel();
        ui.removeCallbacks(beat);
        track.reset();
        if (req != null) {
            YugiEvents.postMediaState(req.id, "closed", currentTime(), null);
            req = null;
        }
        full = false;
        floating = false;
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
        return !(hits(closeWrap, closeBtn, x, y) || hits(fsWrap, fsBtn, x, y) || hits(floatWrap, floatBtn, x, y)
                || hits(floatBar, floatBar, x, y));
    }

    private static boolean hits(View wrap, View btn, float x, float y) {
        if (wrap.getVisibility() != View.VISIBLE || btn.getVisibility() != View.VISIBLE) return false;
        float l = wrap.getLeft() + wrap.getTranslationX(), t = wrap.getTop() + wrap.getTranslationY();
        return x >= l && x < l + wrap.getWidth() && y >= t && y < t + wrap.getHeight();
    }

    private final class Layer extends FrameLayout {
        private final int slop;
        private float downX, downY, lastY;
        private boolean dragging, passing, floatTouch, floatDragging;
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
                    floatTouch = false;
                    floatDragging = false;
                    if (closing) return false;
                    if (!full && floating && hasSrc && progress < 0.5f && visibleOnLayer().contains(downX, downY)) {
                        // the floating window: dragged around, a tap opens full screen
                        floatTouch = true;
                        startVt(e);
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

        /** Dragging the floating window: it follows the finger, then lands in the corner it was flung to. */
        private boolean onFloatTouch(MotionEvent e) {
            if (vt != null) vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (!floatDragging && Math.hypot(dx, dy) > slop) {
                        floatDragging = true;
                        moveSpring.cancel();
                        moveP = 1f;
                        downX = e.getX(); downY = e.getY();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    if (floatDragging) {
                        fv[0] += dx; fv[2] += dx; fv[1] += dy; fv[3] += dy;
                        fv[4] = 0; fv[5] = 0; fv[6] = getWidth(); fv[7] = getHeight();
                        downX = e.getX(); downY = e.getY();
                        applyGeometry();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float vx = 0, vy = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                    if (!floatDragging) {
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) setFull(true);
                        return true;
                    }
                    floatDragging = false;
                    float cx = (fv[0] + fv[2]) / 2f + vx * FLING_PROJECT_S, cy = (fv[1] + fv[3]) / 2f + vy * FLING_PROJECT_S;
                    floatCorner = cornerFor(cx, cy, getWidth(), getHeight());
                    // the spring starts at the finger's speed along the way to the corner
                    RectF to = floatTarget(floatCorner);
                    float ddx = to.left - fv[0], ddy = to.top - fv[1], d2 = ddx * ddx + ddy * ddy;
                    float v = d2 > 1f ? (vx * ddx + vy * ddy) / d2 : 0f;
                    retarget(true, true, Math.max(-20f, Math.min(20f, v)));
                    return true;
                }
                default:
                    return true;
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
            if (floatTouch) return onFloatTouch(e);
            if (!dragging) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP && exo != null && !(floating && !full)) controls.toggle();
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

    /** Glass circle with a small window in a big one: "floating window". */
    private static final class FloatButton extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        FloatButton(Context c) {
            super(c);
            fill.setColor(0x73000000);
            stroke.setColor(YugiTheme.TEXT);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(YugiTheme.dp(c, 2f));
            stroke.setStrokeJoin(Paint.Join.ROUND);
            solid.setColor(YugiTheme.TEXT);
            setClickable(true);
            setFocusable(true);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f * 0.8f;
            c.drawCircle(w / 2f, h / 2f, r, fill);
            float bw = r * 0.95f, bh = r * 0.7f, k = YugiTheme.dp(getContext(), 2.5f);
            box.set(w / 2f - bw / 2f, h / 2f - bh / 2f, w / 2f + bw / 2f, h / 2f + bh / 2f);
            c.drawRoundRect(box, k, k, stroke);
            // the small window in the bottom "end" corner, as the floating window lands
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float sw = bw * 0.46f, sh = bh * 0.5f, in = YugiTheme.dp(getContext(), 2.5f);
            float l = rtl ? box.left + in : box.right - in - sw;
            c.drawRoundRect(new RectF(l, box.bottom - in - sh, l + sw, box.bottom - in), k * 0.6f, k * 0.6f, solid);
        }

        @Override public void setPressed(boolean p) {
            super.setPressed(p);
            animate().scaleX(p ? 0.9f : 1f).scaleY(p ? 0.9f : 1f).setDuration(p ? 60 : 160).start();
        }
    }

    /** Glass circle with an X: "close" (the floating window). */
    private static final class StopButton extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        StopButton(Context c) {
            super(c);
            fill.setColor(YugiTheme.GLASS);
            stroke.setColor(YugiTheme.TEXT);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(YugiTheme.dp(c, 2.2f));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
            setFocusable(true);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f * 0.84f, a = r * 0.36f;
            c.drawCircle(w / 2f, h / 2f, r, fill);
            c.drawLine(w / 2f - a, h / 2f - a, w / 2f + a, h / 2f + a, stroke);
            c.drawLine(w / 2f + a, h / 2f - a, w / 2f - a, h / 2f + a, stroke);
        }

        @Override public void setPressed(boolean p) {
            super.setPressed(p);
            animate().scaleX(p ? 0.9f : 1f).scaleY(p ? 0.9f : 1f).setDuration(p ? 60 : 160).start();
        }
    }

    /** A glass pill with a label, 48dp high. */
    private static View pill(Context c, String label) {
        TextView t = YugiTheme.text(c, label, 15, 600, YugiTheme.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setMinHeight(YugiTheme.dp(c, YugiTheme.TOUCH));
        int pad = YugiTheme.dp(c, 18);
        t.setPadding(pad, 0, pad, 0);
        t.setBackground(YugiTheme.rounded(c, YugiTheme.GLASS, YugiTheme.TOUCH / 2f));
        t.setClickable(true);
        t.setFocusable(true);
        t.setContentDescription(label);
        t.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(60).start();
            else if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            return false;
        });
        return t;
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

    boolean isFloatingForTest() { return floating; }

    /** As if the video drew its first frame (the embed's PLAYING), so no fallback starts during a long test. */
    void firstFrameForTest() { onFirstFrame(); }

    void setFloatingForTest(boolean on) { setFloating(on); }

    void openInBrowserForTest() { handBack(); }

    void stopForTest() { closeNow(false); }

    RectF floatBarForTest() {
        return new RectF(floatBar.getTranslationX(), floatBar.getTranslationY(),
                floatBar.getTranslationX() + floatBar.getWidth(), floatBar.getTranslationY() + floatBar.getHeight());
    }

    void updateRect(RectUpdate u) { updateRect(u, android.os.SystemClock.elapsedRealtimeNanos() / 1e6); }

    void toggleFullForTest() { setFull(!full); }

    boolean passesThroughForTest(float x, float y) { return passesThrough(x, y); }

    RectF shownForTest() { return visibleOnLayer(); }
}

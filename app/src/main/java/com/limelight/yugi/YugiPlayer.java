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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hybrid stage 2: a video that starts on the server desktop plays here, decoded by the phone.
 * <p>
 * The desktop extension reports the video (event "media"); this opens a native player above the stream
 * without touching the stream itself. Transition: a hero cut out of the current stream frame at the
 * video's place grows on a spring to full screen, and the real player crossfades in on its first frame,
 * so there is never an empty or black frame. Dragging down (or back) closes it on a spring back to its
 * place, and the position goes back to the server ({@code POST /media/state closed}), where the
 * desktop video seeks to it. The springs can be interrupted at any point (grab while opening, etc.).
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

        Request(String kind, String id, String vid, String url, String pageUrl, String title, double t,
                float x, float y, float w, float h, float sw, float sh) {
            this.kind = kind; this.id = id; this.vid = vid; this.url = url; this.pageUrl = pageUrl;
            this.title = title; this.t = t; this.x = x; this.y = y; this.w = w; this.h = h; this.sw = sw; this.sh = sh;
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
            return new Request(kind, id, vid, url, o.optString("pageUrl"), o.optString("title"), t, x, y, w, h, sw, sh);
        }

        boolean hasRect() { return w > 0 && h > 0; }
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

    /** The video rect when fitted (letterboxed) into a box of w x h. */
    static RectF fit(float aspect, float w, float h) {
        if (aspect <= 0 || w <= 0 || h <= 0) return new RectF(0, 0, w, h);
        float vw = w, vh = w / aspect;
        if (vh > h) { vh = h; vw = h * aspect; }
        float l = (w - vw) / 2f, t = (h - vh) / 2f;
        return new RectF(l, t, l + vw, t + vh);
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
                + "playerVars:{autoplay:1,playsinline:1,start:" + start + ",rel:0,modestbranding:1,fs:0,origin:'" + YT_ORIGIN + "'},"
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
    private final Controls controls;

    private Request req;
    private RectF source;          // video rect on the stream (layer coordinates), may be null
    private float aspect = 16f / 9f;
    private float progress;        // 0 = at the source rect, 1 = full screen
    private float dragY;           // finger offset while dragging down
    private boolean closing;
    private boolean firstFrame;
    private double lastT;          // last known position (s)
    private boolean ytFallback;

    private ExoPlayer exo;
    private TextureView texture;
    private WebView web;

    private final SpringAnimation progressSpring, dragSpring;

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
                float r = YugiTheme.dp(a, 14) * (1f - progress);
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

        closeBtn = new CloseButton(a);
        closeBtn.setContentDescription("סגירה");
        closeBtn.setOnClickListener(v -> close());
        int touch = YugiTheme.dp(a, YugiTheme.TOUCH);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(touch, touch, Gravity.TOP | Gravity.START);
        clp.topMargin = clp.leftMargin = clp.rightMargin = YugiTheme.dp(a, 12);
        layer.addView(closeBtn, clp);

        progressSpring = new SpringAnimation(this, PROGRESS);
        progressSpring.setSpring(new SpringForce().setStiffness(STIFFNESS).setDampingRatio(DAMPING));
        progressSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        progressSpring.addEndListener((anim, canceled, value, velocity) -> {
            if (!canceled && closing && value <= 0.001f) finishClose();
        });
        dragSpring = new SpringAnimation(this, DRAG);
        dragSpring.setSpring(new SpringForce(0f).setStiffness(STIFFNESS).setDampingRatio(DAMPING));

        layer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                // fold / rotate: the player stays, only its geometry follows the new window
                source = computeSource();
                applyGeometry();
            }
        });
    }

    /** Adds the (hidden) player layer above everything on the stream screen. */
    public static YugiPlayer attach(Activity a, Host h) {
        YugiPlayer p = new YugiPlayer(a, h);
        a.addContentView(p.layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return p;
    }

    public boolean isOpen() { return req != null; }

    // ---------------------------------------------------------------- open

    /** A new video from the desktop (UI thread). Replaces the current one if open. */
    public void open(Request r) {
        if (req != null) {
            // already showing something: swap the content in place (no flight from the desktop)
            YugiEvents.postMediaState(req.id, "closed", currentTime(), null);
            releaseContent();
            req = r;
            lastT = r.t;
            firstFrame = false;
            ytFallback = false;
            content.setAlpha(0f);
            closing = false;
            progressSpring.animateToFinalPosition(1f);
            startContent();
            YugiEvents.postMediaState(r.id, "opened", r.t, null);
            return;
        }
        req = r;
        lastT = r.t;
        closing = false;
        firstFrame = false;
        ytFallback = false;
        aspect = r.hasRect() ? r.w / r.h : 16f / 9f;
        layer.bringToFront();
        layer.setVisibility(View.VISIBLE);
        layer.requestFocus();
        source = computeSource();
        progress = 0f;
        dragY = 0f;
        content.setAlpha(0f);
        hero.setImageBitmap(null);
        hero.setAlpha(1f);
        controls.hide(false);
        setDuck(true);
        startContent(); // load in parallel with the flight: the first frame comes sooner
        YugiEvents.postMediaState(r.id, "opened", r.t, null);

        final Request opening = r;
        host.capture(bmp -> {
            if (req != opening || closing) return;
            Bitmap crop = cropHero(bmp, opening);
            if (crop != null) hero.setImageBitmap(crop);
            applyGeometry();
            progressSpring.animateToFinalPosition(1f);
        });
        applyGeometry();
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

    private RectF computeSource() {
        View sv = host.streamView();
        if (req == null || sv == null || sv.getWidth() <= 0) return null;
        int[] a = new int[2], b = new int[2];
        sv.getLocationInWindow(a);
        layer.getLocationInWindow(b);
        float sx = sv.getScaleX(), sy = sv.getScaleY();
        RectF stream = new RectF(a[0] - b[0], a[1] - b[1],
                a[0] - b[0] + sv.getWidth() * sx, a[1] - b[1] + sv.getHeight() * sy);
        return mapRect(req, stream);
    }

    // ---------------------------------------------------------------- geometry

    private RectF fullVideo() {
        return fit(aspect, Math.max(1, layer.getWidth()), Math.max(1, layer.getHeight()));
    }

    /** The visible part of the card, in the card's own (unscaled) coordinates. */
    private RectF clipRect() {
        RectF full = new RectF(0, 0, Math.max(1, layer.getWidth()), Math.max(1, layer.getHeight()));
        return lerp(fullVideo(), full, progress);
    }

    /** Places the card for the current progress / drag: uniform scale from the source to full screen. */
    private void applyGeometry() {
        if (layer.getWidth() <= 0) return;
        RectF video = fullVideo();
        RectF src = source != null ? source : shrunkCenter(video);
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
        float chrome = Math.max(0f, (progress - 0.85f) / 0.15f) * dragFade;
        closeBtn.setAlpha(chrome);
        closeBtn.setVisibility(chrome > 0.02f ? View.VISIBLE : View.INVISIBLE);
    }

    /** Without a source rect: start from a small centered copy (still a spring, never a cut). */
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
            web.setWebChromeClient(new WebChromeClient());
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
        } else {
            web.loadDataWithBaseURL(YT_BASE, youtubeHtml(req.vid, req.t), "text/html", "utf-8", null);
        }
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

    /** Spring back to the video's place on the desktop, then hand the position back to the server. */
    public void close() {
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
        // the hero goes back over the player so the flight ends on the desktop frame
        hero.animate().cancel();
        hero.setAlpha(hero.getDrawable() != null ? 1f : 0f);
        content.animate().cancel();
        content.animate().alpha(hero.getDrawable() != null ? 0f : 1f).setDuration(160).start();
        source = computeSource();
        if (!animate || layer.getWidth() <= 0) {
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
            req = null;
            closing = false;
            setDuck(false);
            host.onPlayerClosed();
        }).start();
    }

    /** Activity going away: close at once, the position still goes back to the server. */
    public void release() {
        progressSpring.cancel();
        dragSpring.cancel();
        if (req != null) {
            YugiEvents.postMediaState(req.id, "closed", currentTime(), null);
            req = null;
        }
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

    // ---------------------------------------------------------------- touch: drag down to close

    private final class Layer extends FrameLayout {
        private final int slop;
        private float downX, downY, lastY;
        private boolean dragging;
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

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (req == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = lastY = e.getY();
                    dragging = false;
                    if (progressSpring.isRunning() || dragSpring.isRunning()) {
                        // grab mid-flight: the spring stops where it is and the finger takes over
                        if (!closing) { progressSpring.cancel(); dragSpring.cancel(); dragging = true; startVt(e); return true; }
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getY() - downY, dx = e.getX() - downX;
                    if (!closing && dy > slop && dy > Math.abs(dx) * 1.5f && !contentScrolledDown()) {
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
            if (!dragging) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP && exo != null) controls.toggle();
                return true; // nothing under the player gets touches (the stream stays as it was)
            }
            if (vt != null) vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    dragY = Math.max(0f, dragY + (e.getY() - lastY));
                    lastY = e.getY();
                    if (progress < 1f) progress = Math.min(1f, progress); // keep a grabbed flight where it is
                    applyGeometry();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    float vy = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                    float dp = getResources().getDisplayMetrics().density;
                    boolean shouldClose = dragY > CLOSE_DRAG_DP * dp || vy > CLOSE_FLING_DP_S * dp;
                    if (shouldClose) {
                        close();
                    } else {
                        dragSpring.setStartVelocity(vy);
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

    /** Minimal ExoPlayer controls: play / pause and a scrubber with times, on a glass bar. */
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

            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            int m = YugiTheme.dp(c, 16);
            blp.leftMargin = blp.rightMargin = m;
            blp.bottomMargin = YugiTheme.dp(c, 20);
            root.addView(bar, blp);
            bar.setAlpha(0f);
            bar.setVisibility(View.GONE);
            bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // a timeline runs left to right
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
            bar.animate().alpha(1f).translationY(0).setDuration(180).start();
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
}

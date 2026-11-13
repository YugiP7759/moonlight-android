package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

import static org.robolectric.Shadows.shadowOf;

/** Media redirect player: request parsing, geometry, and a render of the flight on the Fold screens. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class YugiPlayerTest {
    private static final File OUT = new File("build/yugi-screens");

    @Test
    public void parsesYoutubeAndRejectsBadInput() throws Exception {
        YugiPlayer.Request r = YugiPlayer.Request.parse(new JSONObject(
                "{\"kind\":\"youtube\",\"id\":\"a1\",\"vid\":\"dQw4w9WgXcQ\",\"t\":12.5,"
                        + "\"rect\":{\"x\":100,\"y\":50,\"w\":960,\"h\":540,\"sw\":1920,\"sh\":1080}}"));
        assertNotNull(r);
        assertEquals(12.5, r.t, 1e-9);
        assertTrue(r.hasRect());
        assertNull(YugiPlayer.Request.parse(new JSONObject("{\"kind\":\"youtube\",\"id\":\"a\",\"vid\":\"x');alert(1)//\"}")));
        assertNull(YugiPlayer.Request.parse(new JSONObject("{\"kind\":\"mp4\",\"id\":\"a\",\"url\":\"file:///sdcard/x\"}")));
        assertNull(YugiPlayer.Request.parse(new JSONObject("{\"kind\":\"mp4\",\"url\":\"https://x/y.mp4\"}")));
        YugiPlayer.Request h = YugiPlayer.Request.parse(new JSONObject("{\"kind\":\"hls\",\"id\":\"b\",\"url\":\"https://c/x.m3u8\",\"t\":-4}"));
        assertNotNull(h);
        assertEquals(0, h.t, 0);
        assertFalse(h.hasRect());
    }

    @Test
    public void mapsDesktopRectOntoTheStream() throws Exception {
        YugiPlayer.Request r = YugiPlayer.Request.parse(new JSONObject(
                "{\"kind\":\"mp4\",\"id\":\"a\",\"url\":\"https://x/y.mp4\","
                        + "\"rect\":{\"x\":480,\"y\":270,\"w\":960,\"h\":540,\"sw\":1920,\"sh\":1080}}"));
        RectF m = YugiPlayer.mapRect(r, new RectF(0, 100, 2520, 1180));
        assertEquals(630, m.left, 0.01);
        assertEquals(100 + 270, m.top, 0.01);
        assertEquals(1260, m.width(), 0.01);
        assertEquals(540, m.height(), 0.01);
        RectF f = YugiPlayer.fit(16f / 9f, 1080, 2400);
        assertEquals(1080, f.width(), 0.01);
        assertEquals(607.5, f.height(), 0.01);
        assertEquals((2400 - 607.5) / 2, f.top, 0.01);
    }

    @Test
    public void youtubePageAndTimes() {
        String html = YugiPlayer.youtubeHtml("dQw4w9WgXcQ", 61.7);
        assertTrue(html.contains("videoId:'dQw4w9WgXcQ'"));
        assertTrue(html.contains("start:61"));
        assertTrue(html.contains("playsinline:1"));
        assertTrue(html.contains("autoplay:1"));
        assertTrue("YouTube's own full-screen button is shown (it switches our mode)", html.contains("fs:1"));
        assertEquals("https://m.youtube.com/watch?v=abcdefghijk&t=61s", YugiPlayer.youtubeWatchUrl("abcdefghijk", 61.7));
        assertEquals("1:01:05", YugiPlayer.fmt(3665_000));
        assertEquals("0:07", YugiPlayer.fmt(7_400));
    }

    @Test @Config(qualifiers = "w960dp-h411dp-land-420dpi")
    public void flightCover() throws Exception { flight("player-cover-landscape"); }

    @Test @Config(qualifiers = "w750dp-h832dp-port-420dpi")
    public void flightInner() throws Exception { flight("player-inner-portrait"); }

    /** Renders the hero flight (start, middle, full) over a fake desktop frame and checks the geometry. */
    private void flight(String name) throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        View stream = new View(a) {
            @Override protected void onDraw(Canvas c) { c.drawBitmap(desktop(getWidth(), getHeight()), 0, 0, null); }
        };
        root.addView(stream, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        YugiPlayer p = YugiPlayer.attach(a, new YugiPlayer.Host() {
            @Override public void capture(YugiChat.BitmapCallback cb) { cb.onBitmap(desktop(stream.getWidth(), stream.getHeight())); }
            @Override public View streamView() { return stream; }
            @Override public void onPlayerClosed() {}
        });
        View decor = a.getWindow().getDecorView();
        int w = a.getResources().getDisplayMetrics().widthPixels, h = a.getResources().getDisplayMetrics().heightPixels;
        layout(decor, w, h);
        p.open(YugiPlayer.Request.parse(new JSONObject(
                "{\"kind\":\"mp4\",\"id\":\"q\",\"url\":\"https://example.com/a.mp4\","
                        + "\"rect\":{\"x\":300,\"y\":200,\"w\":800,\"h\":450,\"sw\":1920,\"sh\":1080}}")));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(p.isOpen());
        OUT.mkdirs();
        for (float pr : new float[]{0f, 0.5f, 1f}) {
            p.setProgressForTest(pr);
            layout(decor, w, h);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(b));
            try (FileOutputStream o = new FileOutputStream(new File(OUT, name + "-" + (int) (pr * 100) + ".png"))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
        }
        // full screen: the close button is on screen and inside its 48dp target
        View layer = p.layerForTest();
        ViewGroup lg = (ViewGroup) layer;
        View close = lg.getChildAt(lg.getChildCount() - 1);
        Rect cr = new Rect();
        close.getHitRect(cr);
        assertTrue("close button on screen " + cr, cr.left >= 0 && cr.top >= 0 && cr.right <= w && cr.bottom <= h);
        assertTrue(cr.width() >= Math.round(48 * a.getResources().getDisplayMetrics().density) - 1);
        p.release();
        assertFalse(p.isOpen());
    }

    private static void layout(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
    }

    /** Fake desktop: grey with a "video" (gradient bars) at the rect posted above. */
    private static Bitmap desktop(int w, int h) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF2B2F38);
        Paint p = new Paint();
        float sx = w / 1920f, sy = h / 1080f;
        int[] cols = {Color.RED, 0xFFFF9500, Color.YELLOW, Color.GREEN, Color.BLUE, 0xFFAF52DE};
        for (int i = 0; i < cols.length; i++) {
            p.setColor(cols[i]);
            c.drawRect((300 + i * 800f / cols.length) * sx, 200 * sy, (300 + (i + 1) * 800f / cols.length) * sx, 650 * sy, p);
        }
        return b;
    }

    @Test
    public void pageGeometry() throws Exception {
        RectF stream = new RectF(0, 0, 1920, 1080);
        // unclamped: a video scrolled half above the screen keeps its size
        RectF m = YugiPlayer.mapDesk(new float[]{100, -270, 960, 540, 1920, 1080}, stream);
        assertEquals(-270, m.top, 0.01);
        assertEquals(540, m.height(), 0.01);
        assertEquals(0.5f, YugiPlayer.visibleFraction(m, stream), 0.001f);
        assertEquals(0f, YugiPlayer.visibleFraction(new RectF(0, -600, 100, -10), stream), 0f);
        RectF mini = YugiPlayer.miniRect(16f / 9f, 1080, 2400, 0, 0, 120, 40, 840, false);
        assertEquals(2400 - 120 - 40, mini.bottom, 0.01);
        assertEquals(1080 - 40, mini.right, 0.01);
        assertEquals(16f / 9f, mini.width() / mini.height(), 0.01);
        RectF left = YugiPlayer.miniRect(16f / 9f, 1080, 2400, 30, 0, 0, 40, 840, true);
        assertEquals(70, left.left, 0.01);
        RectF in = YugiPlayer.fitInto(16f / 9f, new RectF(100, 100, 500, 500));
        assertEquals(300, in.centerY(), 0.01);
        assertEquals(400, in.width(), 0.01);
        YugiPlayer.RectUpdate u = YugiPlayer.RectUpdate.parse(new JSONObject("{\"id\":\"q\",\"hidden\":true}"));
        assertTrue(u.hidden);
        assertNull(YugiPlayer.RectUpdate.parse(new JSONObject("{\"rect\":{}}")));
    }

    /**
     * Opens in the page (never full screen by itself), follows the page, docks as mini off screen,
     * toggles full screen, Back collapses, and touches outside the player go to the stream.
     */
    @Test @Config(qualifiers = "w750dp-h832dp-port-420dpi")
    public void inPageMode() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        View stream = new View(a) {
            @Override protected void onDraw(Canvas c) { c.drawBitmap(desktop(getWidth(), getHeight()), 0, 0, null); }
        };
        root.addView(stream, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        YugiPlayer p = YugiPlayer.attach(a, new YugiPlayer.Host() {
            @Override public void capture(YugiChat.BitmapCallback cb) { cb.onBitmap(desktop(stream.getWidth(), stream.getHeight())); }
            @Override public View streamView() { return stream; }
            @Override public void onPlayerClosed() {}
        });
        View decor = a.getWindow().getDecorView();
        int w = a.getResources().getDisplayMetrics().widthPixels, h = a.getResources().getDisplayMetrics().heightPixels;
        layout(decor, w, h);
        p.open(YugiPlayer.Request.parse(new JSONObject(
                // youtube: a WebView, so no network error (an mp4 that fails to load would close the player)
                "{\"kind\":\"youtube\",\"id\":\"q\",\"vid\":\"dQw4w9WgXcQ\","
                        + "\"rect\":{\"x\":300,\"y\":200,\"w\":800,\"h\":450,\"sw\":1920,\"sh\":1080},"
                        + "\"view\":{\"x\":0,\"y\":100,\"w\":1920,\"h\":980,\"sw\":1920,\"sh\":1080}}")));
        layout(decor, w, h);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(p.isOpen());
        assertFalse("opens in the page", p.isFullForTest());
        float sx = stream.getWidth() / 1920f, sy = stream.getHeight() / 1080f;
        RectF shown = p.shownForTest();
        assertEquals(300 * sx, shown.left, 2);
        assertEquals(200 * sy, shown.top, 2);
        assertEquals(800 * sx, shown.width(), 2);
        // outside the player: the stream gets it; inside: the player
        assertTrue(p.passesThroughForTest(shown.left - 20, shown.bottom + 40));
        assertFalse(p.passesThroughForTest(shown.centerX(), shown.centerY()));
        snap(decor, w, h, "player-inpage");

        // the page scrolls 150 desktop px: the player follows (springs settle when the looper runs)
        p.updateRect(YugiPlayer.RectUpdate.parse(new JSONObject(
                "{\"id\":\"q\",\"rect\":{\"x\":300,\"y\":50,\"w\":800,\"h\":450,\"sw\":1920,\"sh\":1080},"
                        + "\"view\":{\"x\":0,\"y\":100,\"w\":1920,\"h\":980,\"sw\":1920,\"sh\":1080}}")));
        settle();
        shown = p.shownForTest();
        assertEquals("clipped to the browser viewport, not over the tab strip", 100 * sy, shown.top, 2);
        assertEquals(500 * sy, shown.bottom, 2);
        snap(decor, w, h, "player-inpage-scrolled");
        // another redirect's rect is ignored
        p.updateRect(YugiPlayer.RectUpdate.parse(new JSONObject("{\"id\":\"other\",\"hidden\":true}")));
        settle();
        assertFalse(p.isMiniForTest());

        // scrolled off the screen: docked mini, still playing
        p.updateRect(YugiPlayer.RectUpdate.parse(new JSONObject(
                "{\"id\":\"q\",\"rect\":{\"x\":300,\"y\":-500,\"w\":800,\"h\":450,\"sw\":1920,\"sh\":1080}}")));
        settle();
        assertTrue(p.isMiniForTest());
        assertTrue(p.isOpen());
        shown = p.shownForTest();
        assertTrue("mini at the bottom " + shown, shown.bottom > h * 0.8f && shown.bottom <= h);
        snap(decor, w, h, "player-mini");

        // full screen and Back
        p.toggleFullForTest();
        settle();
        assertTrue(p.isFullForTest());
        shown = p.shownForTest();
        assertEquals(0, shown.left, 1);
        assertEquals(w, shown.right, 1);
        assertFalse("full screen owns the screen", p.passesThroughForTest(5, 5));
        snap(decor, w, h, "player-full");
        p.close(); // Back
        settle();
        assertTrue("Back collapses, the video keeps playing", p.isOpen());
        assertFalse(p.isFullForTest());
        p.close(); // Back again: back to the desktop
        settle();
        assertFalse(p.isOpen());
    }

    private static void settle() {
        for (int i = 0; i < 120; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
        }
    }

    private static void snap(View decor, int w, int h, String name) throws Exception {
        layout(decor, w, h);
        OUT.mkdirs();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    @Test
    public void youtubeBoxLeavesTheCloseCornerFree() {
        float band = 180;
        // Fold cover landscape (21:9): the pillarbox already frees the corner, nothing shrinks
        RectF cover = YugiPlayer.fitClearOfCorner(16f / 9f, 2520, 1080, band);
        assertEquals(YugiPlayer.fit(16f / 9f, 2520, 1080), cover);
        // a 16:9 screen: the video shrinks so the side bands hold the button, and stays centred
        RectF exact = YugiPlayer.fitClearOfCorner(16f / 9f, 1920, 1080, band);
        assertTrue(exact.left >= band - 0.5f && exact.right <= 1920 - band + 0.5f);
        assertEquals(960, exact.centerX(), 0.5f);
        // portrait: the band above the video is free
        RectF port = YugiPlayer.fitClearOfCorner(16f / 9f, 1080, 2400, band);
        assertTrue(port.top >= band);
    }
}

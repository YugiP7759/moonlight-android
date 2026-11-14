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
import android.view.MotionEvent;
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
        // the floating window: bottom right (corner 0), its button band of 150 under it, clear of the bars
        float[] inset = {0, 80, 0, 120};
        RectF fl = YugiPlayer.floatRect(16f / 9f, 1080, 2400, inset, 40, 600, 900, 150, 0);
        assertEquals(2400 - 120 - 40 - 150, fl.bottom, 0.01);
        assertEquals(1080 - 40, fl.right, 0.01);
        assertEquals(16f / 9f, fl.width() / fl.height(), 0.01);
        assertTrue("never narrower than its minimum", fl.width() >= 600 - 0.01);
        RectF tl = YugiPlayer.floatRect(16f / 9f, 1080, 2400, new float[]{30, 80, 0, 0}, 40, 600, 900, 150, 3);
        assertEquals(70, tl.left, 0.01);
        assertEquals(120, tl.top, 0.01);
        // a narrow screen: the minimum gives way to the room there is
        RectF narrow = YugiPlayer.floatRect(16f / 9f, 500, 1000, new float[]{0, 0, 0, 0}, 20, 600, 900, 100, 0);
        assertEquals(460, narrow.width(), 0.01);
        assertEquals(0, YugiPlayer.cornerFor(900, 2000, 1080, 2400));
        assertEquals(1, YugiPlayer.cornerFor(100, 2000, 1080, 2400));
        assertEquals(3, YugiPlayer.cornerFor(100, 200, 1080, 2400));
        assertEquals(2, YugiPlayer.cornerFor(900, 200, 1080, 2400));
        RectF in = YugiPlayer.fitInto(16f / 9f, new RectF(100, 100, 500, 500));
        assertEquals(300, in.centerY(), 0.01);
        assertEquals(400, in.width(), 0.01);
        YugiPlayer.RectUpdate u = YugiPlayer.RectUpdate.parse(new JSONObject("{\"id\":\"q\",\"hidden\":true}"));
        assertTrue(u.hidden);
        assertNull(YugiPlayer.RectUpdate.parse(new JSONObject("{\"rect\":{}}")));
    }

    private static YugiPlayer.RectUpdate rect(float y, double ts, long seq) throws Exception {
        return YugiPlayer.RectUpdate.parse(new JSONObject("{\"id\":\"q\",\"rect\":{\"x\":100,\"y\":" + y
                + ",\"w\":800,\"h\":450,\"sw\":1920,\"sh\":1080},\"ts\":" + ts + ",\"seq\":" + seq + "}"));
    }

    /**
     * The page's moves replayed on its own clock: reports that arrive in bursts, late or out of order still
     * give an even motion, a fixed delay behind the fastest report.
     */
    @Test
    public void rectTrackReplaysThePageTiming() throws Exception {
        YugiPlayer.RectTrack tr = new YugiPlayer.RectTrack(40);
        float[] r = new float[6], v = new float[6];
        assertEquals(0, tr.at(0, r, v));
        // the page scrolls 10 px every 16 ms; the phone gets the reports in uneven bursts, 100..160 ms later
        double[] arrive = {1100, 1116, 1150, 1150, 1170, 1210, 1210};
        for (int i = 0; i < arrive.length; i++) {
            assertTrue(tr.add(rect(500 - 10 * i, 1000 + 16 * i, i + 1), arrive[i]));
        }
        // baseline = 100 ms (the fastest), so the playhead is now - 140
        assertEquals(1000 + 24, tr.playhead(1164), 1e-6);
        assertEquals(1, tr.at(1164, r, v));
        assertEquals("halfway between two reports", 500 - 15, r[1], 0.01);
        assertTrue(tr.pending(1164));
        // a late duplicate / older report is dropped
        assertFalse(tr.add(rect(999, 1010, 3), 1215));
        // the motion is even even though the reports came in bursts
        float prev = Float.NaN;
        for (double now = 1164; now <= 1212; now += 16) {
            tr.at(now, r, v);
            if (!Float.isNaN(prev)) assertEquals(-10, r[1] - prev, 0.01);
            prev = r[1];
        }
        // past the last report: it stays there, nothing pending
        assertEquals(1, tr.at(5000, r, v));
        assertEquals(500 - 60, r[1], 0.01);
        assertFalse(tr.pending(5000));
        // hidden is a step, at its time
        tr.add(YugiPlayer.RectUpdate.parse(new JSONObject("{\"id\":\"q\",\"hidden\":true,\"ts\":1200,\"seq\":9}")), 5001);
        assertEquals(2, tr.at(5001 + 200, r, v));
        // an older extension (no ts / seq): its arrival is the clock
        YugiPlayer.RectTrack old = new YugiPlayer.RectTrack(40);
        old.add(YugiPlayer.RectUpdate.parse(new JSONObject("{\"id\":\"q\",\"rect\":{\"x\":1,\"y\":2,\"w\":30,\"h\":40,\"sw\":1920,\"sh\":1080}}")), 100);
        assertEquals(1, old.at(100 + 40, r, v));
        assertEquals(2, r[1], 0.01);
        assertEquals("no viewport in the report", 0, v[2], 0.01);
    }

    /**
     * Opens in the page (never full screen by itself), follows the page, stays in the page when the video
     * scrolls off (never floats by itself), floats only by its button (two buttons under the window),
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
        p.firstFrameForTest(); // the embed is playing (no watch-page fallback after 12 s of test time)
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
        assertFalse(p.isFloatingForTest());

        // scrolled off the screen: it stays in the page (not seen there), still playing, never floats by itself
        p.updateRect(YugiPlayer.RectUpdate.parse(new JSONObject(
                "{\"id\":\"q\",\"rect\":{\"x\":300,\"y\":-500,\"w\":800,\"h\":450,\"sw\":1920,\"sh\":1080}}")));
        settle();
        assertFalse("never floats by itself", p.isFloatingForTest());
        assertTrue(p.isOpen());
        shown = p.shownForTest();
        assertTrue("nothing shown off the page " + shown, shown.height() < 1 || shown.bottom <= 100 * sy + 1);
        assertTrue("the stream gets the touches there", p.passesThroughForTest(w / 2f, h / 2f));
        snap(decor, w, h, "player-offpage");

        // back in view, then the floating window by its button: bottom corner, its two buttons under it
        p.updateRect(YugiPlayer.RectUpdate.parse(new JSONObject(
                "{\"id\":\"q\",\"rect\":{\"x\":300,\"y\":200,\"w\":800,\"h\":450,\"sw\":1920,\"sh\":1080},"
                        + "\"view\":{\"x\":0,\"y\":100,\"w\":1920,\"h\":980,\"sw\":1920,\"sh\":1080}}")));
        settle();
        p.setFloatingForTest(true);
        settle();
        assertTrue(p.isFloatingForTest());
        shown = p.shownForTest();
        layout(decor, w, h);
        RectF bar = p.floatBarForTest();
        assertTrue("floating at the bottom " + shown, shown.bottom > h * 0.6f && bar.bottom <= h);
        assertTrue("its buttons are under the video, not on it " + bar, bar.top >= shown.bottom);
        assertTrue("48dp row", bar.height() >= 48 * a.getResources().getDisplayMetrics().density - 1);
        assertFalse(p.passesThroughForTest(shown.centerX(), shown.centerY()));
        assertFalse(p.passesThroughForTest(bar.centerX(), bar.centerY()));
        assertTrue(p.passesThroughForTest(w / 2f, 40));
        snap(decor, w, h, "player-float");
        // page moves don't move the floating window
        RectF before = new RectF(shown);
        p.updateRect(YugiPlayer.RectUpdate.parse(new JSONObject(
                "{\"id\":\"q\",\"rect\":{\"x\":300,\"y\":-500,\"w\":800,\"h\":450,\"sw\":1920,\"sh\":1080}}")));
        settle();
        assertEquals(before, p.shownForTest());
        // dragged up and flung to the top: lands in a top corner
        drag(p.layerForTest(), shown.centerX(), shown.centerY(), shown.centerX() - w * 0.3f, h * 0.25f);
        settle();
        shown = p.shownForTest();
        assertTrue("snapped to the top " + shown, shown.top < h * 0.3f);
        snap(decor, w, h, "player-float-top");

        // full screen and Back
        p.setFloatingForTest(false);
        settle();
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

    private static void drag(View v, float x0, float y0, float x1, float y1) {
        long t = android.os.SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y0, 0));
        for (int i = 1; i <= 8; i++) {
            float f = i / 8f;
            v.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 12, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 0));
        }
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 8 * 12 + 4, MotionEvent.ACTION_UP, x1, y1, 0));
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

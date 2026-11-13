package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** The floating toolbar: drag past the touch slop moves it, release springs to the nearest side, taps still work. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w960dp-h411dp-land-420dpi")
public class YugiChromeDragTest {
    private Activity a;
    private FrameLayout root;
    private YugiChrome chrome;
    private int clicks;
    private long t;
    private int w, h;

    private int dp(float v) { return YugiTheme.dp(a, v); }

    @Before
    public void setUp() {
        a = Robolectric.buildActivity(Activity.class).setup().get();
        a.getSharedPreferences("yugi_chrome", Context.MODE_PRIVATE).edit().clear().commit();
        root = new FrameLayout(a);
        chrome = new YugiChrome(a, Arrays.asList(
                new YugiChrome.Button(R.drawable.yugi_ic_keyboard, "מקלדת", () -> clicks++),
                new YugiChrome.Button(R.drawable.yugi_ic_menu, "תפריט", () -> {})));
        chrome.setAutoHide(false);
        root.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
        a.setContentView(root); // attached, so clicks (posted) and springs run like on the phone
        shadowOf(Looper.getMainLooper()).idle();
        w = root.getWidth();
        h = root.getHeight();
        assertTrue(w > 0 && h > 0);
        t = SystemClock.uptimeMillis();
    }

    private void layout(int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
    }

    private void send(int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(t, t, action, x, y, 0);
        root.dispatchTouchEvent(e);
        e.recycle();
    }

    /** A slow drag (no throw) from (x0,y0) to (x1,y1), lifted after a pause. */
    private void drag(float x0, float y0, float x1, float y1) {
        send(MotionEvent.ACTION_DOWN, x0, y0);
        for (int i = 1; i <= 10; i++) {
            t += 16;
            send(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 10f, y0 + (y1 - y0) * i / 10f);
        }
        t += 300;
        send(MotionEvent.ACTION_MOVE, x1, y1);
        t += 16;
        send(MotionEvent.ACTION_UP, x1, y1);
    }

    private Rect firstButton() { return chrome.visibleRects().get(0); }

    @Test
    public void defaultSpotIsTopRight() {
        Rect bar = chrome.barRect();
        assertEquals(w - dp(12), bar.right);
        assertEquals(dp(12), bar.top);
        assertTrue(chrome.isDockedRight());
    }

    @Test
    public void tapOnAButtonStillClicks() {
        Rect b = firstButton();
        send(MotionEvent.ACTION_DOWN, b.centerX(), b.centerY());
        t += 60;
        send(MotionEvent.ACTION_UP, b.centerX() + 2, b.centerY() + 2); // jitter under the slop
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, clicks);
        assertEquals(dp(12), chrome.barRect().top); // did not move
    }

    @Test
    public void dragFromAButtonSnapsToTheNearestEdgeAndIsRemembered() {
        Rect b = firstButton();
        drag(b.centerX(), b.centerY(), dp(150), h / 2f);
        settle();
        assertEquals(0, clicks); // the drag is not a tap
        assertFalse(chrome.isDockedRight());
        Rect bar = chrome.barRect();
        assertEquals(dp(12), bar.left, 2);
        assertTrue("height kept", Math.abs(bar.centerY() - h / 2) < dp(40));
        assertFalse(a.getSharedPreferences("yugi_chrome", Context.MODE_PRIVATE).getBoolean("right", true));

        // A new chrome (other screen / next launch) comes back on the left at the same height
        YugiChrome again = new YugiChrome(a, Arrays.asList(
                new YugiChrome.Button(R.drawable.yugi_ic_menu, "תפריט", () -> {})));
        FrameLayout r2 = new FrameLayout(a);
        r2.addView(again, new FrameLayout.LayoutParams(-1, -1));
        r2.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        r2.layout(0, 0, w, h);
        assertFalse(again.isDockedRight());
        assertEquals(dp(12), again.barRect().left, 2);
    }

    @Test
    public void collapsedHandleDragsAndTapOpens() {
        chrome.setCollapsed(true);
        shadowOf(Looper.getMainLooper()).idle();
        List<Rect> rs = chrome.visibleRects();
        assertEquals(1, rs.size());
        Rect hd = rs.get(0);
        assertTrue(hd.width() >= dp(48) && hd.height() >= dp(48));
        drag(hd.centerX(), hd.centerY(), dp(80), h - dp(60));
        settle();
        assertTrue(chrome.isCollapsed());
        assertFalse(chrome.isDockedRight());
        Rect moved = chrome.visibleRects().get(0);
        assertEquals(dp(12), moved.left, 2);
        assertTrue(moved.bottom <= h - dp(12) + 2);

        send(MotionEvent.ACTION_DOWN, moved.centerX(), moved.centerY());
        t += 60;
        send(MotionEvent.ACTION_UP, moved.centerX(), moved.centerY());
        settle();
        assertFalse(chrome.isCollapsed());
        Rect bar = chrome.barRect();
        assertTrue(bar.left >= 0 && bar.right <= w && bar.top >= 0 && bar.bottom <= h);
    }

    @Test
    public void emptyAreaOfTheBarDragsToo() {
        Rect bar = chrome.barRect();
        drag(bar.left + dp(2), bar.top + dp(2), w - dp(100), h - dp(40)); // inside the 4dp padding
        settle();
        assertTrue(chrome.isDockedRight());
        assertTrue(chrome.barRect().top > h / 2);
    }

    @Test
    public void foldOrRotateKeepsItOnScreen() {
        Rect b = firstButton();
        drag(b.centerX(), b.centerY(), w - dp(60), h - dp(10)); // bottom-right
        settle();
        // unfold / rotate to a portrait screen
        layout(h, w);
        Rect bar = chrome.barRect();
        assertTrue(chrome.isDockedRight());
        assertEquals(h - dp(12), bar.right, 2);
        assertTrue(bar.top >= 0 && bar.bottom <= w);
    }

    @Test
    public void throwProjectionPicksTheFarSide() {
        // a short hop to the left of centre but thrown right still lands right (iOS PiP)
        assertTrue(YugiChrome.snapRight(400 + YugiChrome.project(1500), 1000));
        assertFalse(YugiChrome.snapRight(400, 1000));
    }

    @Test
    public void overlaysStayAboveAnOpeningPlayer() {
        FrameLayout content = new FrameLayout(a);
        View stream = new View(a), keyboard = new View(a), player = new View(a);
        YugiChatBubble bubble = new YugiChatBubble(a, () -> {});
        content.addView(stream);
        YugiChrome bar = new YugiChrome(a, Arrays.asList(
                new YugiChrome.Button(R.drawable.yugi_ic_menu, "תפריט", () -> {})));
        content.addView(bar);
        content.addView(bubble);
        content.addView(keyboard);
        content.addView(player);
        // what YugiPlayer.open does
        player.bringToFront();
        YugiTheme.raiseOverlays(content, player);
        assertEquals(stream, content.getChildAt(0));
        assertEquals(player, content.getChildAt(1));
        assertEquals(bar, content.getChildAt(2));
        assertEquals(bubble, content.getChildAt(3));
        assertEquals(keyboard, content.getChildAt(4));
    }
}

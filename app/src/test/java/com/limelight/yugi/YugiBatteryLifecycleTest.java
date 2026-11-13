package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSensor;

/** Battery: nothing streams, polls or listens once a YugiPC screen is in the background or gone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class YugiBatteryLifecycleTest {

    @After
    public void tearDown() {
        // let every spring finish: androidx's AnimationHandler is static, and a spring left mid-flight
        // would keep the next test's springs from ever getting a frame
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5));
        YugiSessions.offline = false;
    }

    private static YugiChat newChat(Activity a) {
        return new YugiChat(a, new YugiChat.Host() {
            @Override public void captureScreen(YugiChat.BitmapCallback cb) { cb.onBitmap(null); }
            @Override public void pickFile() {}
            @Override public int reservedTop() { return 0; }
        });
    }

    @Test
    public void releaseClosesChatAndSessionsPanel() {
        YugiSessions.offline = true; // no server in tests
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        YugiChat chat = newChat(a);
        root.addView(chat, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        chat.setOpen(true);
        chat.showSessions(true);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(chat.sessionsForTest().isShownForTest());

        chat.release();
        assertFalse("chat still open after release", chat.isOpen());
        assertFalse("sessions panel still live after release", chat.sessionsForTest().isShownForTest());
    }

    @Test
    public void hiddenWindowStopsSessionsAndBringsThemBack() {
        YugiSessions.offline = true;
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        YugiChat chat = newChat(a);
        root.addView(chat, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        chat.setOpen(true);
        chat.showSessions(true);
        root.dispatchWindowVisibilityChanged(View.GONE);
        assertFalse(chat.sessionsForTest().isShownForTest());
        root.dispatchWindowVisibilityChanged(View.VISIBLE);
        assertTrue(chat.sessionsForTest().isShownForTest());
        chat.release();
    }

    @Test
    public void gamesScreenBlanksBoosteroidOnStopAndReloadsOnStart() {
        ActivityController<GamesActivity> c = Robolectric.buildActivity(GamesActivity.class).setup();
        GamesActivity a = c.get();
        WebView web = find(a.getWindow().getDecorView(), WebView.class);
        assertEquals(GamesActivity.URL, web.getUrl());

        c.pause().stop();
        assertEquals("Boosteroid keeps streaming in the background", "about:blank", web.getUrl());

        c.restart().start().resume();
        assertEquals(GamesActivity.URL, web.getUrl());
        c.pause().stop().destroy();
    }

    @Test
    public void filePickerTripKeepsTheGame() throws Exception {
        ActivityController<GamesActivity> c = Robolectric.buildActivity(GamesActivity.class).setup();
        GamesActivity a = c.get();
        WebView web = find(a.getWindow().getDecorView(), WebView.class);
        java.lang.reflect.Field f = GamesActivity.class.getDeclaredField("pickingFile");
        f.setAccessible(true);
        f.setBoolean(a, true); // as the chat's attach button does before opening the picker
        c.pause().stop();
        assertEquals("the file picker must not kill the game", GamesActivity.URL, web.getUrl());
        c.restart().start().resume();
        assertFalse(f.getBoolean(a));
        c.pause().stop();
        assertEquals("about:blank", web.getUrl());
        c.destroy();
    }

    @Test
    public void gyroOffWhileHostPaused() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        SensorManager sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE));
        VirtualPadView pad = new VirtualPadView(a);
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        root.addView(pad);
        pad.setPadEnabled(true);
        PadSettings s = new PadSettings();
        s.gyro = true;
        pad.applySettings(s);
        assertTrue(pad.isGyroListening());

        pad.setHostPaused(true);
        assertFalse("gyroscope still on while the screen is paused", pad.isGyroListening());
        pad.setHostPaused(false);
        assertTrue(pad.isGyroListening());
        root.removeView(pad);
        assertFalse(pad.isGyroListening());
    }

    private static <T extends View> T find(View v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                T t = find(g.getChildAt(i), type);
                if (t != null) return t;
            }
        }
        return null;
    }
}

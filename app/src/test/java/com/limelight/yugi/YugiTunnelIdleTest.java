package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;

import com.yugipc.tsbridge.Tsbridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;

/** Battery: the embedded Tailscale node stops after a minute with no screen and comes back with one. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class YugiTunnelIdleTest {

    @Before
    public void setUp() {
        Tsbridge.reset();
        YugiTunnel.resetForTest();
        YugiTunnel.bg = Runnable::run;
    }

    @After
    public void tearDown() {
        YugiTunnel.resetForTest();
        YugiTunnel.bg = r -> new Thread(r, "yugi-tunnel").start();
    }

    @Test
    public void stopsAfterAMinuteInTheBackgroundAndReconnectsOnReturn() {
        Application app = (Application) androidx.test.core.app.ApplicationProvider.getApplicationContext();
        // ArtemisApplication.onCreate already registered the watcher (it's the test app too)
        ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(YugiTunnel.ensureReady(app));
        assertEquals(1, Tsbridge.starts);

        c.pause().stop();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        assertEquals("stopped too early", 0, Tsbridge.stops);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
        assertEquals(1, Tsbridge.stops);
        assertFalse(YugiTunnel.isStartedForTest());

        c.restart().start().resume();
        assertEquals("not reconnected when a screen came back", 2, Tsbridge.starts);
        assertTrue(YugiTunnel.isStartedForTest());
        assertEquals("chat forward added once", 1, Tsbridge.forwards);
        c.pause().stop().destroy();
    }

    @Test
    public void quickTripAwayKeepsTheNode() {
        Application app = (Application) androidx.test.core.app.ApplicationProvider.getApplicationContext();
        ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(YugiTunnel.ensureReady(app));
        c.pause().stop();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        c.restart().start().resume();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(90));
        assertEquals(0, Tsbridge.stops);
        assertEquals(1, Tsbridge.starts);
        c.pause().stop().destroy();
    }
}

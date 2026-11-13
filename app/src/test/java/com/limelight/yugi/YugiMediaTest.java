package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** On-demand mic and camera: they follow the desktop's demand, the toolbar overrides, and stop turns all off. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class YugiMediaTest {
    private static final class FakeBridge implements YugiMedia.Bridge {
        boolean running, mic, cam;
        int starts;
        @Override public void start(Activity activity, String host, int port) { running = true; starts++; }
        @Override public void stop() { running = false; }
        @Override public void setMicEnabled(boolean enabled) { mic = enabled; }
        @Override public void setCameraEnabled(boolean enabled) { cam = enabled; }
        @Override public void switchCamera() {}
    }

    private FakeBridge b;
    private Activity a;

    @Before
    public void setUp() {
        a = Robolectric.buildActivity(Activity.class).setup().get();
        b = new FakeBridge();
        YugiMedia.register(b);
        YugiMedia.stop();
        YugiMedia.setMic(true); // nothing lent
    }

    @After
    public void tearDown() { YugiMedia.stop(); }

    @Test
    public void nothingRunsUntilAnAppAsks() {
        YugiMedia.start(a);
        assertFalse(b.running);
        YugiMedia.onDemand(true, true);
        assertTrue(b.running);
        assertTrue(b.cam);
        assertFalse(b.mic);
        YugiMedia.onDemand(false, true);
        assertTrue(b.mic);
        assertEquals("one session for both", 1, b.starts);
        YugiMedia.onDemand(true, false);
        assertFalse(b.cam);
        assertTrue(b.running);
        YugiMedia.onDemand(false, false);
        assertFalse("no session when nothing is used", b.running);
    }

    @Test
    public void demandBeforeTheStreamWaitsForIt() {
        YugiMedia.onDemand(true, true);
        assertFalse(b.running);
        YugiMedia.start(a);
        assertTrue(b.running);
        assertTrue(YugiMedia.isCamOn());
    }

    @Test
    public void toolbarOverridesBothWays() {
        YugiMedia.start(a);
        YugiMedia.toggleCam(); // nothing asks: on by hand
        assertTrue(b.cam);
        YugiMedia.toggleCam();
        assertFalse(b.running);
        YugiMedia.onDemand(true, true);
        assertTrue(b.cam);
        YugiMedia.toggleCam(); // off although the app uses it
        assertFalse(b.cam);
        YugiMedia.onDemand(true, false);
        YugiMedia.onDemand(true, true); // the next use gets it again
        assertTrue(b.cam);
    }

    @Test
    public void dictationBorrowsTheMic() {
        YugiMedia.start(a);
        YugiMedia.onDemand(false, true);
        assertTrue(YugiMedia.isMicOn());
        YugiMedia.setMic(false);
        assertFalse(b.mic);
        YugiMedia.setMic(true);
        assertTrue(b.mic);
    }

    @Test
    public void stopAndLostLinkTurnEverythingOff() {
        YugiMedia.start(a);
        YugiMedia.onDemand(true, true);
        YugiMedia.onDemand(false, true);
        YugiMedia.onDemandLost();
        assertFalse(b.running);
        YugiMedia.toggleMic();
        assertTrue(b.mic);
        YugiMedia.stop();
        assertFalse(b.running);
        assertFalse(YugiMedia.isMicOn());
        YugiMedia.start(a);
        assertFalse("a manual override does not outlive the stream", b.running);
    }
}

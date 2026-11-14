package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.PowerManager;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.List;

/** Battery first: the stream profile, the low-battery layer, the smooth switch and the per-visit log. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class YugiPowerTest {
    private Context app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        YugiPower.smooth = false;
        YugiPower.current = null;
        YugiPowerLog.resetForTest(app);
    }

    @After
    public void tearDown() {
        YugiPower.smooth = false;
        YugiPower.current = null;
    }

    private void battery(int pct, boolean plugged) {
        Intent i = new Intent(Intent.ACTION_BATTERY_CHANGED);
        i.putExtra(BatteryManager.EXTRA_LEVEL, pct);
        i.putExtra(BatteryManager.EXTRA_SCALE, 100);
        i.putExtra(BatteryManager.EXTRA_PLUGGED, plugged ? BatteryManager.BATTERY_PLUGGED_AC : 0);
        app.sendStickyBroadcast(i);
    }

    @Test public void desktopDefaultsTo30FpsAt6Mbps() {
        YugiPower.Profile p = YugiPower.choose(false, false, 1080, 120);
        assertEquals(30, p.fps);
        assertEquals(6000, p.kbps);
        assertEquals(1f, p.scale, 0f);
        assertTrue(p.lowLatencyWifi);
    }

    @Test public void smoothIs60At10Mbps() {
        YugiPower.Profile p = YugiPower.choose(false, true, 1080, 120);
        assertEquals(60, p.fps);
        assertEquals(10000, p.kbps);
    }

    @Test public void lowBatteryIs720p30At4MbpsWithoutLowLatencyWifiAndBeatsSmooth() {
        YugiPower.Profile p = YugiPower.choose(true, true, 1080, 120);
        assertEquals(30, p.fps);
        assertEquals(4000, p.kbps);
        assertEquals(720f / 1080f, p.scale, 1e-4f);
        assertFalse(p.lowLatencyWifi);
        // the cover screen's stream is already under 720 lines: no further shrink
        assertEquals(1f, YugiPower.choose(true, false, 712, 120).scale, 0f);
    }

    @Test public void lowBatteryFollowsTheBatteryAndPowerSaver() {
        battery(80, false);
        assertFalse(YugiPower.isLowBattery(app));
        battery(29, false);
        assertTrue(YugiPower.isLowBattery(app));
        battery(29, true);
        assertFalse("charging is not low", YugiPower.isLowBattery(app));
        battery(80, false);
        shadowOf((PowerManager) app.getSystemService(Context.POWER_SERVICE)).setIsPowerSaveMode(true);
        assertTrue(YugiPower.isLowBattery(app));
        shadowOf((PowerManager) app.getSystemService(Context.POWER_SERVICE)).setIsPowerSaveMode(false);
    }

    @Test public void menuSwitchFlipsSmoothAndRestreams() {
        int[] restreams = {0};
        List<YugiNav.Item> items = YugiPcOverlay.menuItems(() -> restreams[0]++);
        assertEquals(2, items.size());
        for (YugiNav.Item it : items) assertFalse("no games entry", it.label.contains("משחקים"));
        items.get(1).action.run();
        assertTrue(YugiPower.smooth);
        assertEquals(1, restreams[0]);
        YugiPcOverlay.menuItems(() -> restreams[0]++).get(1).action.run();
        assertFalse(YugiPower.smooth);
        assertEquals(2, restreams[0]);
        assertEquals("no restream hook: no switch", 1, YugiPcOverlay.menuItems(null).size());
    }

    @Test public void aVisitSpansFoldRestreamsAndIsLoggedOnce() throws Exception {
        battery(80, false);
        YugiPowerLog.streamStarted(app, 1200, 1080, 30, 6000);
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5 * 60_000));
        YugiPowerLog.streamRestarting();
        YugiPowerLog.streamStarted(app, 1664, 712, 30, 6000);
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5 * 60_000));
        battery(78, false);
        YugiPowerLog.streamStopped(app, "background");

        JSONArray log = YugiPowerLog.log(app);
        assertEquals(1, log.length());
        JSONObject v = log.getJSONObject(0);
        assertEquals(80, v.getInt("pct_start"));
        assertEquals(78, v.getInt("pct_end"));
        assertEquals(2, v.getInt("pct_drop"));
        assertEquals(10.0, v.getDouble("minutes"), 0.2);
        assertEquals(12.0, v.getDouble("pct_per_hour"), 0.5);
        assertEquals(1, v.getInt("restreams"));
        assertEquals("cover", v.getString("screen"));
        assertFalse(v.getBoolean("charging"));
        assertEquals(1, YugiPowerLog.read(app, YugiPowerLog.KEY_UNSENT).length());
        YugiPowerLog.dropSent(app, 1);
        assertEquals(0, YugiPowerLog.read(app, YugiPowerLog.KEY_UNSENT).length());
    }

    @Test public void shortVisitsAreNotLoggedAndChargingIsMarked() throws Exception {
        battery(50, false);
        YugiPowerLog.streamStarted(app, 1200, 1080, 30, 6000);
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(20_000));
        YugiPowerLog.streamStopped(app, "background");
        assertEquals(0, YugiPowerLog.log(app).length());

        YugiPowerLog.streamStarted(app, 1200, 1080, 30, 6000);
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(3 * 60_000));
        battery(52, true);
        YugiPowerLog.streamStopped(app, "closed");
        assertTrue(YugiPowerLog.log(app).getJSONObject(0).getBoolean("charging"));
    }
}

package com.limelight.yugi;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

/**
 * Battery first: the stream profile for the desktop. Frugal always, never asks, never pops up.
 *
 * <ul>
 *   <li><b>Default:</b> 30 FPS at 6 Mbps. A desktop is mostly still (Sunshine sends a frame only when
 *       something changes), so the cap only bites while something moves; then it halves the decoder
 *       work and the bytes on the radio, and the server's x264 CPU too.</li>
 *   <li><b>Smooth:</b> 60 FPS at 10 Mbps, only from the menu ("תנועה חלקה"), for this app run only.
 *       The Moonlight protocol can't change FPS inside a stream, so a switch is a re-stream.</li>
 *   <li><b>Low battery</b> (below 30% and not charging, or the system's power saving): 30 FPS, at most
 *       720 lines, 4 Mbps, and Wi-Fi keeps its power saving (no LOW_LATENCY lock). Wins over smooth.
 *       Evaluated at each connect (folds re-stream anyway); silent.</li>
 * </ul>
 * Why 30 and not 45: the panel runs at 60 Hz during the stream, and 45 frames on 60 refreshes land
 * unevenly (judder while scrolling), which looks worse than an even 30.
 */
public final class YugiPower {
    private YugiPower() {}

    public static final int DESKTOP_FPS = 30;
    public static final int DESKTOP_KBPS = 6000;
    public static final int SMOOTH_FPS = 60;
    public static final int SMOOTH_KBPS = 10000;
    public static final int LOW_FPS = 30;
    public static final int LOW_KBPS = 4000;
    /** Low battery: the stream's short side is at most this (720p class). */
    public static final int LOW_MAX_LINES = 720;
    /** On a metered network (cellular): the same 2.5 Mbps Moonlight used before (bitrate / 4), never more. */
    public static final int METERED_KBPS = 2500;
    public static final int LOW_BATTERY_PCT = 30;

    /** Chosen from the menu: 60 FPS until the app process ends. */
    public static volatile boolean smooth;

    /** The profile of the stream being started (the Game screen reads it for the Wi-Fi lock). */
    public static volatile Profile current;

    public static final class Profile {
        public final int fps, kbps;
        /** Multiplier on the per-screen scale (1 = as measured). */
        public final float scale;
        public final boolean low;
        /** WIFI_MODE_FULL_LOW_LATENCY (turns the Wi-Fi power saving off while streaming). */
        public final boolean lowLatencyWifi;

        Profile(int fps, int kbps, float scale, boolean low) {
            this.fps = fps;
            this.kbps = kbps;
            this.scale = scale;
            this.low = low;
            this.lowLatencyWifi = !low;
        }
    }

    /**
     * @param shortSide the stream's short side at the normal per-screen scale (px)
     * @param maxFps    what the screen can show
     */
    static Profile choose(boolean low, boolean smoothWanted, int shortSide, int maxFps) {
        if (low) {
            float s = shortSide > LOW_MAX_LINES ? (float) LOW_MAX_LINES / shortSide : 1f;
            return new Profile(Math.min(LOW_FPS, maxFps), LOW_KBPS, s, true);
        }
        if (smoothWanted && maxFps >= SMOOTH_FPS) return new Profile(SMOOTH_FPS, SMOOTH_KBPS, 1f, false);
        return new Profile(Math.min(DESKTOP_FPS, maxFps), DESKTOP_KBPS, 1f, false);
    }

    public static Profile choose(Context ctx, int shortSide, int maxFps) {
        Profile p = choose(isLowBattery(ctx), smooth, shortSide, maxFps);
        current = p;
        return p;
    }

    /** Below 30% and not charging, or the system's battery saver. A sticky broadcast read: no listener. */
    public static boolean isLowBattery(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) return true;
            Intent b = ctx.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return false;
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (level < 0 || scale <= 0 || plugged != 0) return false;
            return level * 100f / scale < LOW_BATTERY_PCT;
        } catch (Exception e) {
            return false;
        }
    }
}

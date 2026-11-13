package com.limelight.yugi;

/**
 * YugiPC constants. Values the server agent may change live here, in one place.
 */
public final class YugiConfig {
    private YugiConfig() {}

    /** Sunshine host. Direct Tailscale IP for now; the embedded tunnel (TsBridge) may override it to 127.0.0.1. */
    public static final String DEFAULT_HOST = "100.104.166.48";
    public static final int HTTP_PORT = 47989;
    public static final int API_PORT = 47990;

    /** Sunshine app to stream. */
    public static final String APP_NAME = "Desktop";

    /** Requested frame rate, and the stable minimum. */
    public static final int FPS_PREFERRED = 120;
    public static final int FPS_FALLBACK = 60;

    /**
     * Per-screen stream profiles, measured on the server (x264 on CPU).
     * The stream keeps the active screen's aspect ratio at a fraction of its size and is
     * stretched back to full screen with bilinear filtering (no bars).
     *  - Cover screen (wide, e.g. 2520x1080): 66% -> ~1664x712 holds 120 FPS (~111 real).
     *  - Main / inner screen (near-square, e.g. 2184x1968): 55% -> ~1200x1080 at 60 FPS.
     * A screen whose long/short side ratio is at least WIDE_ASPECT uses the cover profile.
     */
    public static final float WIDE_ASPECT = 1.6f;
    public static final float COVER_SCALE = 0.66f;
    public static final int COVER_FPS = 120;
    public static final float INNER_SCALE = 0.55f;
    public static final int INNER_FPS = 60;

    /**
     * Automatic 120 -> 60 drop when the received rate stays low. Off by default: the profiles above
     * are already tuned, and an idle desktop may legitimately send fewer frames.
     */
    public static final boolean FPS_AUTO_FALLBACK = false;

    /** Fixed bitrates (kbps): not Moonlight's automatic high default. */
    public static final int BITRATE_60_KBPS = 10000;
    public static final int BITRATE_120_KBPS = 15000;

    /**
     * Moonlight mouse mode "1" (normal mouse / direct touch): tap = click where touched, drag = drag,
     * second finger = scroll, long press = right click.
     */
    public static final String MOUSE_MODE = "1";

    /** Extra global multiplier on top of the per-screen scale (1.0 = as measured). */
    public static final float RES_SCALE = 1.0f;

    /** Stream dimensions are rounded to this multiple (encoder friendly). */
    public static final int SIZE_ALIGN = 8;

    /** Client name shown in Sunshine's paired-devices list. */
    public static final String CLIENT_NAME = "YugiPC";

    /** Side channel for mic + camera (WHIP). Used once the WHIP module is merged. */
    public static final String WHIP_URL = "http://100.104.166.48:8890/whip";

    /**
     * Known screens (physical px, landscape-first) for reference and sanity checks.
     * The stream always uses the live window size; this table documents the Galaxy Z Fold 7.
     */
    public static final int[][] KNOWN_SCREENS = {
            {2520, 1080}, // Z Fold 7 cover screen
            {2184, 1968}, // Z Fold 7 main (inner) screen
    };

    /** Current host (may be switched to the embedded tunnel's local forwarder). */
    public static volatile String host = DEFAULT_HOST;

    /** Round a dimension to the nearest multiple of SIZE_ALIGN. */
    public static int align(float v) {
        int a = Math.round(v / SIZE_ALIGN) * SIZE_ALIGN;
        return Math.max(SIZE_ALIGN, a);
    }

    public static boolean isWide(int w, int h) {
        return (float) Math.max(w, h) / Math.max(1, Math.min(w, h)) >= WIDE_ASPECT;
    }
}

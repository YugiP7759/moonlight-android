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

    /** Requested frame rate, and the stable minimum to fall back to. */
    public static final int FPS_PREFERRED = 120;
    public static final int FPS_FALLBACK = 60;

    /**
     * Stream resolution as a fraction of the active screen (same aspect ratio).
     * 1.0 = native. Lower it if the server can only hold 60 FPS at a reduced size;
     * the picture is then stretched to the full screen with bilinear filtering (no bars).
     */
    public static final float RES_SCALE = 1.0f;

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

    /** Round a dimension down to an even number (encoders need even sizes). */
    public static int even(int v) {
        return Math.max(2, v & ~1);
    }
}

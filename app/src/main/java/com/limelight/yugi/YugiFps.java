package com.limelight.yugi;

/**
 * Watches received FPS. If 120 was requested but the server can't hold it,
 * asks for a restart at the stable 60 FPS.
 */
public final class YugiFps {
    /** Below this average (at a 120 request) a window counts as "not holding". */
    public static final float HOLD_THRESHOLD = 100f;
    /** Stats windows to ignore at stream start (encoder warm-up). */
    public static final int WARMUP_WINDOWS = 3;
    /** Consecutive bad windows before falling back. */
    public static final int BAD_WINDOWS = 5;

    private static int windows;
    private static int bad;
    /** Sticky for this app process: once 120 failed, stay at 60. */
    public static volatile boolean fallback;

    public interface Listener { void onFallbackNeeded(); }
    private static volatile Listener listener;

    private YugiFps() {}

    public static void reset(Listener l) {
        windows = 0;
        bad = 0;
        listener = l;
    }

    /** Called from the decoder's stats window (once per window). */
    public static void report(float receivedFps, float requestedFps) {
        Listener l = listener;
        if (l == null || fallback || requestedFps <= YugiConfig.FPS_FALLBACK) return;
        if (++windows <= WARMUP_WINDOWS) return;
        bad = receivedFps < HOLD_THRESHOLD ? bad + 1 : 0;
        if (bad >= BAD_WINDOWS) {
            fallback = true;
            listener = null;
            l.onFallbackNeeded();
        }
    }
}

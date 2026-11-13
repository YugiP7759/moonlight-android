package com.limelight.yugi;

import android.app.Activity;

/**
 * Integration point for the mic + camera side channel (WHIP, built in the `whip/` module).
 * Until that module is merged this is a no-op holder; the WHIP module registers a {@link Bridge}.
 */
public final class YugiMedia {
    public interface Bridge {
        void start(Activity activity, String host, int port);
        void stop();
        void setMicEnabled(boolean enabled);
        void setCameraEnabled(boolean enabled);
        void switchCamera();
    }

    private static volatile Bridge bridge;
    private static boolean micOn = true;
    /**
     * Camera starts off: a 720p30 capture + H.264 encode + ~2.5 Mbps uplink for the whole session was the
     * biggest battery cost. The toolbar's camera button turns it on when a desktop app needs it.
     */
    private static boolean camOn = false;

    private YugiMedia() {}

    /** Registers the built-in WHIP sender (module `whip/`). */
    public static void registerDefault() {
        if (bridge == null) register(new WhipBridge());
    }

    /** Adapter from the WHIP module's WhipStreamer to this bridge. */
    private static final class WhipBridge implements Bridge {
        private final com.yugipc.whip.WhipStreamer whip = new com.yugipc.whip.WhipStreamer();

        WhipBridge() {
            whip.setStateListener((state, message, fatal) ->
                    com.limelight.LimeLog.info("YugiPC WHIP: " + state + (message != null ? " " + message : "")
                            + (fatal ? " (fatal)" : "")));
        }

        @Override public void start(Activity activity, String host, int port) { whip.start(activity, host, port); }
        @Override public void stop() { whip.stop(); }
        @Override public void setMicEnabled(boolean enabled) { whip.setMicEnabled(enabled); }
        @Override public void setCameraEnabled(boolean enabled) { whip.setCameraEnabled(enabled); }
        @Override public void switchCamera() { whip.switchCamera(); }
    }

    public static void register(Bridge b) { bridge = b; }
    public static boolean isAvailable() { return bridge != null; }

    public static void start(Activity a) {
        Bridge b = bridge;
        if (b != null) {
            b.start(a, YugiConfig.host, YugiConfig.WHIP_PORT);
            b.setMicEnabled(micOn);
            b.setCameraEnabled(camOn);
        }
    }

    public static void stop() {
        Bridge b = bridge;
        if (b != null) b.stop();
    }

    public static boolean isMicOn() { return micOn; }
    public static boolean isCamOn() { return camOn; }

    public static void setMic(boolean on) {
        micOn = on;
        Bridge b = bridge;
        if (b != null) b.setMicEnabled(on);
    }

    public static void setCam(boolean on) {
        camOn = on;
        Bridge b = bridge;
        if (b != null) b.setCameraEnabled(on);
    }

    public static void switchCamera() {
        Bridge b = bridge;
        if (b != null) b.switchCamera();
    }
}

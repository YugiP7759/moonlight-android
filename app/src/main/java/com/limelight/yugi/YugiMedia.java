package com.limelight.yugi;

import android.app.Activity;

/**
 * Integration point for the mic + camera side channel (WHIP, built in the `whip/` module).
 * Until that module is merged this is a no-op holder; the WHIP module registers a {@link Bridge}.
 */
public final class YugiMedia {
    public interface Bridge {
        void start(Activity activity, String whipUrl);
        void stop();
        void setMicEnabled(boolean enabled);
        void setCameraEnabled(boolean enabled);
        void switchCamera();
    }

    private static volatile Bridge bridge;
    private static boolean micOn = true;
    private static boolean camOn = true;

    private YugiMedia() {}

    public static void register(Bridge b) { bridge = b; }
    public static boolean isAvailable() { return bridge != null; }

    public static void start(Activity a) {
        Bridge b = bridge;
        if (b != null) {
            b.start(a, YugiConfig.WHIP_URL);
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

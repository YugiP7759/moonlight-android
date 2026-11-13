package com.limelight.yugi;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The phone mic + camera for the desktop (WHIP to yugipc-bridge, module `whip/`), on demand.
 *
 * <p>The bridge reports whether a desktop app uses them (events {@code media.cam} / {@code media.mic}
 * through {@link YugiEvents}): Zoom, Meet in Brave or a camera app opens the virtual camera or records
 * from "YugiPC Mic". Only then does the phone capture: Camera2 opens and AudioRecord starts, and when
 * the app stops they are closed and released, and the WHIP session itself ends when neither is needed.
 * The toolbar buttons override: on when nothing asks for it, or off while an app uses it (until that
 * app stops). When the stream ends, the event stream drops or the app goes to the background,
 * everything turns off.
 */
public final class YugiMedia {
    public interface Bridge {
        void start(Activity activity, String host, int port);
        void stop();
        void setMicEnabled(boolean enabled);
        void setCameraEnabled(boolean enabled);
        void switchCamera();
    }

    /** Main thread. Called whenever what the phone really captures changes. */
    public interface Listener {
        void onMediaState(boolean micOn, boolean camOn);
    }

    private static volatile Bridge bridge;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    // All below guarded by YugiMedia.class
    private static Activity activity;     // the stream screen while connected, else null
    private static boolean micDemand, camDemand;   // a desktop app uses it (server)
    private static boolean micManual, camManual;   // toolbar: on although nothing asks
    private static boolean micBlocked, camBlocked; // toolbar: off although an app asks (until it stops)
    private static boolean micLent;                // local dictation (YugiVoice, YugiChat) has the mic
    private static boolean running;                // the WHIP session is up
    private static boolean micOn, camOn;           // what was last applied

    private YugiMedia() {}

    /** Registers the built-in WHIP sender (module `whip/`). */
    public static void registerDefault() {
        if (bridge == null) register(new WhipBridge());
    }

    /** Adapter from the WHIP module's WhipStreamer to this bridge. */
    private static final class WhipBridge implements Bridge {
        private final com.yugipc.whip.WhipStreamer whip = new com.yugipc.whip.WhipStreamer(
                new com.yugipc.whip.WhipConfig.Builder().micInitiallyEnabled(false).cameraInitiallyEnabled(false).build());

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

    public static void addListener(Listener l) { listeners.addIfAbsent(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    /** The desktop stream is up: from now on the mic and camera follow the desktop's demand. */
    public static synchronized void start(Activity a) {
        activity = a;
        apply();
    }

    /** The stream ended or the app left the foreground: everything off and released. */
    public static synchronized void stop() {
        activity = null;
        micDemand = camDemand = false;
        micManual = camManual = false;
        micBlocked = camBlocked = false;
        apply();
    }

    /** From the bridge (event media.cam / media.mic); {@code cam} false means the mic. */
    static synchronized void onDemand(boolean cam, boolean on) {
        if (cam) {
            camDemand = on;
            if (!on) camBlocked = false; // the next app that opens the camera gets it again
        } else {
            micDemand = on;
            if (!on) micBlocked = false;
        }
        apply();
    }

    /** The event stream is gone (stopped, or no reconnect in time): no app can be told to us now. */
    static synchronized void onDemandLost() {
        micDemand = camDemand = false;
        micBlocked = camBlocked = false;
        apply();
    }

    /** True while the phone mic really records for the desktop. */
    public static synchronized boolean isMicOn() { return micOn; }

    /** True while the phone camera really captures for the desktop. */
    public static synchronized boolean isCamOn() { return camOn; }

    /**
     * Lends the mic to local dictation and gives it back: {@code setMic(false)} while it is on, then
     * {@code setMic(true)} when done (YugiVoice, YugiChat). Not the toolbar override: see {@link #toggleMic()}.
     */
    public static synchronized void setMic(boolean on) {
        micLent = !on;
        apply();
    }

    /** Toolbar: on -> off (even while an app uses it, until that app stops); off -> on. */
    public static synchronized void toggleMic() {
        if (micOn) {
            micManual = false;
            micBlocked = micDemand;
        } else {
            micManual = true;
            micBlocked = false;
        }
        apply();
    }

    /** Toolbar: on -> off (even while an app uses it, until that app stops); off -> on. */
    public static synchronized void toggleCam() {
        if (camOn) {
            camManual = false;
            camBlocked = camDemand;
        } else {
            camManual = true;
            camBlocked = false;
        }
        apply();
    }

    /** Toolbar override for the camera: true forces it on, false forces it off while an app uses it. */
    public static synchronized void setCam(boolean on) {
        camManual = on;
        camBlocked = !on && camDemand;
        apply();
    }

    public static void switchCamera() {
        Bridge b = bridge;
        if (b != null) b.switchCamera();
    }

    private static void apply() {
        boolean connected = activity != null;
        boolean mic = connected && !micLent && (micManual || (micDemand && !micBlocked));
        boolean cam = connected && (camManual || (camDemand && !camBlocked));
        Bridge b = bridge;
        if (b != null) {
            if (mic || cam) {
                b.setMicEnabled(mic); // set first: a session that starts now opens with exactly these
                b.setCameraEnabled(cam);
                if (!running) {
                    b.start(activity, YugiConfig.host, YugiConfig.WHIP_PORT);
                    running = true;
                }
            } else if (running) {
                b.setMicEnabled(false);
                b.setCameraEnabled(false);
                b.stop(); // nothing is needed: no session, no capture, nothing in the background
                running = false;
            }
        } else {
            mic = cam = false;
        }
        if (mic != micOn || cam != camOn) {
            micOn = mic;
            camOn = cam;
            com.limelight.LimeLog.info("YugiPC media: mic=" + mic + " cam=" + cam);
            final boolean m = mic, c = cam;
            ui.post(() -> { for (Listener l : listeners) l.onMediaState(m, c); });
        }
    }
}

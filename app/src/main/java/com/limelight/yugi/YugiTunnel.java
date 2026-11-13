package com.limelight.yugi;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * Embedded Tailscale (tsnet) bridge from the private AAR `tsbridge.aar` (repo yugipc-tailscale).
 * The AAR is only present in private builds (it carries the auth key), so it is called by
 * reflection: without it the app falls back to the phone's Tailscale app and the direct IP.
 * With it, every Sunshine/WHIP port is forwarded on 127.0.0.1.
 * Battery: the node stops after a minute with no YugiPC screen showing and comes back when one does.
 */
public final class YugiTunnel {
    private static final String CLASS = "com.yugipc.tsbridge.Tsbridge";
    public static final String LOCAL_HOST = "127.0.0.1";
    public static final long READY_TIMEOUT_MS = 25000;

    /** No screen of ours showing for this long: the tailnet node stops (it keeps the radio busy). */
    public static final long IDLE_STOP_MS = 60_000;

    private static Class<?> cls;
    private static boolean started;
    private static boolean chatForwardAdded;
    /** Stopped by the idle watcher: start again as soon as a screen comes back. */
    private static volatile boolean idleStopped;
    private static volatile int startedActivities;
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Start/stop block (network, up to seconds): off the main thread. Tests run them inline. */
    static Executor bg = r -> new Thread(r, "yugi-tunnel").start();
    private static Context appContext;
    private static final Runnable idleStop = () -> bg.execute(YugiTunnel::stopIfIdle);

    private YugiTunnel() {}

    public static synchronized boolean isAvailable() {
        if (cls == null) {
            try {
                cls = Class.forName(CLASS);
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = cls.getMethod(name, types);
        return m.invoke(null, args);
    }

    /**
     * Starts the node once per process and waits until it is connected. Must run off the main thread.
     * @return true when the tailnet is up (YugiConfig.host is then 127.0.0.1).
     */
    public static synchronized boolean ensureReady(Context ctx) {
        if (!isAvailable()) return false;
        try {
            if (!started) {
                File dir = new File(ctx.getNoBackupFilesDir(), "tailscale");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                if (!chatForwardAdded) {
                    // Yugi chat bridge (not in the module's default forwards); kept by the module across Stop/Start
                    try {
                        call("addTCPForward", new Class<?>[]{long.class, long.class}, (long) YugiChat.PORT, (long) YugiChat.PORT);
                    } catch (NoSuchMethodException e) {
                        call("addTCPForward", new Class<?>[]{int.class, int.class}, YugiChat.PORT, YugiChat.PORT);
                    } catch (Throwable t) {
                        LimeLog.warning("YugiPC tunnel: chat forward: " + t);
                    }
                    chatForwardAdded = true;
                }
                call("start", new Class<?>[]{String.class, String.class, String.class},
                        dir.getAbsolutePath(), "", "");
                started = true;
                idleStopped = false;
            }
            boolean ok = (Boolean) call("waitReady", new Class<?>[]{long.class}, READY_TIMEOUT_MS);
            if (ok) {
                YugiConfig.host = LOCAL_HOST;
            } else {
                LimeLog.warning("YugiPC tunnel not ready: " + call("state", new Class<?>[0])
                        + " " + call("lastError", new Class<?>[0]));
            }
            return ok;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            LimeLog.warning("YugiPC tunnel start failed: " + c);
            return false;
        }
    }

    /**
     * From Application.onCreate: counts our visible screens. None for {@link #IDLE_STOP_MS} stops the node
     * (Tsbridge.stop); the next screen to show starts it again in the background.
     */
    public static void watchApp(Application app) {
        appContext = app;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { onScreenStarted(); }
            @Override public void onActivityStopped(Activity a) { onScreenStopped(); }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    static void onScreenStarted() {
        if (startedActivities++ > 0) return;
        main.removeCallbacks(idleStop);
        if (idleStopped && appContext != null) {
            final Context ctx = appContext;
            bg.execute(() -> { if (startedActivities > 0) ensureReady(ctx); });
        }
    }

    static void onScreenStopped() {
        if (startedActivities > 0) startedActivities--;
        if (startedActivities == 0) {
            main.removeCallbacks(idleStop);
            main.postDelayed(idleStop, IDLE_STOP_MS);
        }
    }

    private static synchronized void stopIfIdle() {
        if (startedActivities > 0 || !started || !isAvailable()) return;
        try {
            call("stop", new Class<?>[0]);
            LimeLog.info("YugiPC tunnel: stopped, no screen for " + IDLE_STOP_MS / 1000 + " s");
        } catch (Throwable t) {
            LimeLog.warning("YugiPC tunnel stop failed: " + t);
        }
        started = false;
        idleStopped = true;
    }

    static synchronized boolean isStartedForTest() { return started; }

    static synchronized void resetForTest() {
        started = false;
        chatForwardAdded = false;
        idleStopped = false;
        startedActivities = 0;
        main.removeCallbacks(idleStop);
    }

    /** True when the build has a Tailscale key baked in. */
    public static boolean hasBuiltinKey() {
        if (!isAvailable()) return false;
        try {
            return (Boolean) call("hasBuiltinKey", new Class<?>[0]);
        } catch (Throwable t) {
            return false;
        }
    }
}

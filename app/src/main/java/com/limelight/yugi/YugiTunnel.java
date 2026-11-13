package com.limelight.yugi;

import android.content.Context;

import com.limelight.LimeLog;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Embedded Tailscale (tsnet) bridge from the private AAR `tsbridge.aar` (repo yugipc-tailscale).
 * The AAR is only present in private builds (it carries the auth key), so it is called by
 * reflection: without it the app falls back to the phone's Tailscale app and the direct IP.
 * With it, every Sunshine/WHIP port is forwarded on 127.0.0.1.
 */
public final class YugiTunnel {
    private static final String CLASS = "com.yugipc.tsbridge.Tsbridge";
    public static final String LOCAL_HOST = "127.0.0.1";
    public static final long READY_TIMEOUT_MS = 25000;

    private static Class<?> cls;
    private static boolean started;

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
                try {
                    // Yugi chat bridge (not in the module's default forwards)
                    call("addTCPForward", new Class<?>[]{long.class, long.class}, (long) YugiChat.PORT, (long) YugiChat.PORT);
                } catch (NoSuchMethodException e) {
                    call("addTCPForward", new Class<?>[]{int.class, int.class}, YugiChat.PORT, YugiChat.PORT);
                } catch (Throwable t) {
                    LimeLog.warning("YugiPC tunnel: chat forward: " + t);
                }
                call("start", new Class<?>[]{String.class, String.class, String.class},
                        dir.getAbsolutePath(), "", "");
                started = true;
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

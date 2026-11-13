package com.limelight.yugi;

import android.content.Context;
import android.content.SharedPreferences;

/** Tunables of the on-screen pad, saved per device. */
public final class PadSettings {
    public float lookSensitivity = 0.6f;   // look zone: deflection per (dp/ms)
    public float lookCurve = 1.0f;         // 1 = linear; >1 = slow precise aim, fast flicks
    public float lookAntiDeadzone = 0.12f; // jump past the game's inner deadzone
    public float lookDecay = 0.45f;        // per 8 ms tick once the finger stops
    public float stickDeadzone = 0.08f;    // left floating stick
    public boolean gyro = false;
    public float gyroSensitivity = 0.35f;
    public boolean invertY = false;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("yugipc_pad_settings", Context.MODE_PRIVATE);
    }

    public static PadSettings load(Context c) {
        SharedPreferences p = prefs(c);
        PadSettings s = new PadSettings();
        s.lookSensitivity = p.getFloat("look_sens", s.lookSensitivity);
        s.lookCurve = p.getFloat("look_curve", s.lookCurve);
        s.lookAntiDeadzone = p.getFloat("look_anti_dz", s.lookAntiDeadzone);
        s.stickDeadzone = p.getFloat("stick_dz", s.stickDeadzone);
        s.gyro = p.getBoolean("gyro", s.gyro);
        s.gyroSensitivity = p.getFloat("gyro_sens", s.gyroSensitivity);
        s.invertY = p.getBoolean("invert_y", s.invertY);
        return s;
    }

    public void save(Context c) {
        prefs(c).edit()
                .putFloat("look_sens", lookSensitivity)
                .putFloat("look_curve", lookCurve)
                .putFloat("look_anti_dz", lookAntiDeadzone)
                .putFloat("stick_dz", stickDeadzone)
                .putBoolean("gyro", gyro)
                .putFloat("gyro_sens", gyroSensitivity)
                .putBoolean("invert_y", invertY)
                .apply();
    }
}

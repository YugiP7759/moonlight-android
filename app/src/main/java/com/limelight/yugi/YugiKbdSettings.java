package com.limelight.yugi;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Keyboard settings (size, font, docked / split / floating, one-handed, haptics, sound), saved in
 * SharedPreferences and shared by every YugiPC keyboard. A change is pushed to all live keyboards at
 * once, so the settings sheet is its own live preview.
 */
public final class YugiKbdSettings {
    public static final String PREFS = "yugi_keyboard";

    public static final int DOCKED = 0, SPLIT = 1, FLOATING = 2;
    public static final int HAND_NONE = 0, HAND_LEFT = 1, HAND_RIGHT = 2;

    public static final float HEIGHT_MIN = 0.8f, HEIGHT_MAX = 1.3f;
    public static final float FONT_MIN = 0.85f, FONT_MAX = 1.3f;

    public float height = 1f;     // row height factor
    public float font = 1f;       // label size factor
    public int mode = DOCKED;
    public int hand = HAND_NONE;  // docked only
    public boolean haptics = true;
    public boolean sound = false;
    public float floatX = 0.5f;   // floating panel center, fraction of the window
    public float floatY = 0.62f;

    public interface Listener { void onKbdSettings(YugiKbdSettings s); }

    private static final List<Listener> listeners = new ArrayList<>();
    private static YugiKbdSettings cached;

    /** The current settings (loaded once, then kept in memory). */
    public static YugiKbdSettings get(Context c) {
        if (cached == null) cached = load(c);
        return cached;
    }

    static YugiKbdSettings load(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        YugiKbdSettings s = new YugiKbdSettings();
        s.height = clamp(p.getFloat("height", 1f), HEIGHT_MIN, HEIGHT_MAX);
        s.font = clamp(p.getFloat("font", 1f), FONT_MIN, FONT_MAX);
        s.mode = p.getInt("mode", DOCKED);
        if (s.mode < DOCKED || s.mode > FLOATING) s.mode = DOCKED;
        s.hand = p.getInt("hand", HAND_NONE);
        if (s.hand < HAND_NONE || s.hand > HAND_RIGHT) s.hand = HAND_NONE;
        s.haptics = p.getBoolean("haptics", true);
        s.sound = p.getBoolean("sound", false);
        s.floatX = clamp(p.getFloat("floatX", 0.5f), 0f, 1f);
        s.floatY = clamp(p.getFloat("floatY", 0.62f), 0f, 1f);
        return s;
    }

    /** Saves and pushes the change to every live keyboard. */
    public void apply(Context c) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("height", height)
                .putFloat("font", font)
                .putInt("mode", mode)
                .putInt("hand", hand)
                .putBoolean("haptics", haptics)
                .putBoolean("sound", sound)
                .putFloat("floatX", floatX)
                .putFloat("floatY", floatY)
                .apply();
        cached = this;
        for (Listener l : new ArrayList<>(listeners)) l.onKbdSettings(this);
    }

    public static void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    /** Tests: forget the in-memory copy. */
    static void resetForTest() { cached = null; listeners.clear(); }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}

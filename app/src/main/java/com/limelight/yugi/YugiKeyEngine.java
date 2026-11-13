package com.limelight.yugi;

import static com.limelight.yugi.YugiKeyLayout.M_ALT;
import static com.limelight.yugi.YugiKeyLayout.M_CAPS;
import static com.limelight.yugi.YugiKeyLayout.M_CTRL;
import static com.limelight.yugi.YugiKeyLayout.M_FN;
import static com.limelight.yugi.YugiKeyLayout.M_SHIFT;
import static com.limelight.yugi.YugiKeyLayout.M_WIN;

/**
 * What the YugiPC keyboard sends, Windows touch keyboard style:
 * <ul>
 *   <li>Plain characters go out as UTF-8 text (any language, no host layout involved).</li>
 *   <li>With Ctrl, Alt or Win the physical US key goes out as a key code with those modifiers
 *       (Ctrl+ש = Ctrl+A), and so do Esc, Enter, arrows, Del and F-keys (with Shift too: Shift+arrow selects).</li>
 *   <li>Shift, Ctrl and Alt are sticky: a tap arms them for the next key, a second tap clears them,
 *       a quick double tap on Shift is Caps Lock. Holding a modifier while tapping keys chords them.</li>
 *   <li>Win: a tap opens the start menu (Meta press), holding it chords (Win+E).</li>
 *   <li>Fn turns the number row into F1..F12 and Backspace into Del until tapped again.</li>
 * </ul>
 * Pure Java, unit tested in YugiKeyEngineTest. All calls happen on the UI thread, in order, so
 * a Backspace never overtakes the letter before it.
 */
public final class YugiKeyEngine {
    public interface Sender {
        void text(String s);
        /** @param androidKeyCode KeyEvent.KEYCODE_*, @param mods KeyboardPacket.MODIFIER_* bits */
        void key(int androidKeyCode, boolean down, int mods);
    }

    public static final long DOUBLE_TAP_MS = 350;

    private final Sender out;
    public boolean hebrew;
    private int held;      // modifiers whose key is physically down
    private int chorded;   // held modifiers already used with another key
    private int latched;   // one-shot modifiers
    private int locked;    // Caps Lock, Fn
    private long lastShiftTapAt = -10_000;

    public YugiKeyEngine(Sender out) { this.out = out; }

    // ---------------------------------------------------------------- state for drawing

    public boolean isActive(int bit) { return ((held | latched | locked) & bit) != 0; }
    public boolean isLocked(int bit) { return (locked & bit) != 0; }
    public boolean shift() { return isActive(M_SHIFT); }
    public boolean caps() { return (locked & M_CAPS) != 0; }
    public boolean fn() { return (locked & M_FN) != 0; }

    /** Modifier bits that go with a key sent right now (Shift, Ctrl, Alt, Win). */
    public int currentMods() { return (held | latched | (locked & M_SHIFT)) & (M_SHIFT | M_CTRL | M_ALT | M_WIN); }

    // ---------------------------------------------------------------- modifiers

    public void modDown(int bit) {
        if (bit == M_FN || bit == M_CAPS) return;
        held |= bit;
        chorded &= ~bit;
    }

    public void modUp(int bit, long now) {
        if (bit == M_FN || bit == M_CAPS) {
            locked ^= bit;
            return;
        }
        boolean used = (chorded & bit) != 0;
        held &= ~bit;
        chorded &= ~bit;
        if (used) return;
        if (bit == M_WIN) {
            // tap = the Windows key itself (start menu / KDE launcher)
            tap(YugiKeyLayout.modKeyCode(M_WIN), latched & ~M_WIN);
            latched = 0;
            return;
        }
        if (bit == M_SHIFT) {
            if ((locked & M_CAPS) != 0 && (latched & M_SHIFT) == 0) {
                locked &= ~M_CAPS;               // tap Shift ends Caps Lock
            } else if ((latched & M_SHIFT) != 0) {
                latched &= ~M_SHIFT;
                if (now - lastShiftTapAt < DOUBLE_TAP_MS) locked |= M_CAPS; // double tap
            } else {
                latched |= M_SHIFT;
            }
            lastShiftTapAt = now;
            return;
        }
        latched ^= bit; // Ctrl / Alt: arm, or disarm
    }

    // ---------------------------------------------------------------- keys

    /**
     * A character key (or one of its long-press alternates).
     * @param alt the alternate chosen with a long press, or null
     */
    public void charKey(YugiKeyLayout.Key k, String alt) {
        int mods = currentMods();
        if (alt == null && fn() && k.fnCode != 0) {
            tap(k.fnCode, mods);
        } else if (alt == null && (mods & (M_CTRL | M_ALT | M_WIN)) != 0) {
            int code = YugiKeyLayout.usKeyCode(k.us);
            if (code != 0) tap(code, mods);
        } else {
            String s = alt != null ? alt : YugiKeyLayout.output(k, hebrew, (mods & M_SHIFT) != 0, caps());
            if (s != null && !s.isEmpty()) out.text(s);
        }
        consumed();
    }

    /** Esc, Tab, Enter, Backspace, Del, arrows. Returns the modifiers used (kept for auto-repeat). */
    public int specialKey(YugiKeyLayout.Key k) {
        int mods = currentMods();
        tap(specialCode(k), mods);
        consumed();
        return mods;
    }

    /** Auto-repeat of a held special key, with the modifiers of its first press. */
    public void repeat(YugiKeyLayout.Key k, int mods) {
        tap(specialCode(k), mods);
    }

    /** The code a special key sends now: with Fn locked, Backspace is Del (forward delete). */
    public int specialCode(YugiKeyLayout.Key k) {
        return fn() && k.fnCode != 0 ? k.fnCode : k.code;
    }

    /** An emoji from the picker: always text (an armed Ctrl must not turn it into a shortcut). */
    public void emoji(String s) {
        if (s != null && !s.isEmpty()) out.text(s);
        consumed();
    }

    /** Backspace from the emoji picker (no modifiers). */
    public void backspace() {
        tap(android.view.KeyEvent.KEYCODE_DEL, 0);
    }

    /** One-shot modifiers used up by a key that typed into the keyboard itself (emoji search). */
    public void consume() { consumed(); }

    public void toggleLanguage() { hebrew = !hebrew; }

    /** Clears one-shot modifiers (keyboard hidden). */
    public void reset() {
        latched = 0;
        held = 0;
        chorded = 0;
        locked &= ~M_FN;
    }

    private void consumed() {
        chorded |= held;
        latched = 0;
    }

    /** Modifiers down, key down + up, modifiers up (reverse order). */
    private void tap(int code, int mods) {
        int m = 0;
        for (int bit = 1; bit <= M_WIN; bit <<= 1) {
            if ((mods & bit) != 0) {
                out.key(YugiKeyLayout.modKeyCode(bit), true, m);
                m |= bit;
            }
        }
        out.key(code, true, m);
        out.key(code, false, m);
        for (int bit = M_WIN; bit >= 1; bit >>= 1) {
            if ((mods & bit) != 0) {
                m &= ~bit;
                out.key(YugiKeyLayout.modKeyCode(bit), false, m);
            }
        }
    }
}

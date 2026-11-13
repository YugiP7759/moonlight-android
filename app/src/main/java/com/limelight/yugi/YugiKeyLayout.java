package com.limelight.yugi;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Key model of the YugiPC keyboard, modeled on the Windows touch keyboard: English and Hebrew
 * (Israeli SI-1452 positions), a row of PC keys (Esc, Tab, Ctrl, Alt, Win, arrows, Del, Fn -> F1..F12),
 * sticky modifiers and long-press alternates.
 * <p>
 * Two layouts: {@link #full()} (15 units wide, for screens >= 600dp) and {@link #compact()} (11 units,
 * for the narrow cover screen in portrait, with the PC keys on a strip of their own).
 * Pure Java (only KeyEvent constants, inlined at compile time), unit tested in YugiKeyLayoutTest.
 * Everything a frame needs is precomputed here, so drawing never allocates.
 */
public final class YugiKeyLayout {

    // ---- key kinds
    public static final int CHAR = 0;     // types text (US position in {@link Key#us})
    public static final int SPECIAL = 1;  // sends a key code (Esc, Enter, arrows...)
    public static final int MOD = 2;      // sticky modifier
    public static final int ACTION = 3;   // handled by the keyboard itself (language, hide)

    // ---- modifier bits (match KeyboardPacket.MODIFIER_*)
    public static final int M_SHIFT = 0x01, M_CTRL = 0x02, M_ALT = 0x04, M_WIN = 0x08;
    // keyboard-only "modifiers"
    public static final int M_FN = 0x100, M_CAPS = 0x200;

    public static final int A_LANG = 1, A_HIDE = 2;

    private static final String US_PLAIN = "`1234567890-=[]\\;',./";
    private static final String US_SHIFT = "~!@#$%^&*()_+{}|:\"<>?";

    /** Israeli standard layout: US key -> Hebrew character. */
    private static final Map<Character, String> HE = new HashMap<>();
    static {
        String us = "qwertyuiopasdfghjkl;'zxcvbnm,./";
        String[] he = {"/", "'", "ק", "ר", "א", "ט", "ו", "ן", "ם", "פ",
                "ש", "ד", "ג", "כ", "ע", "י", "ח", "ל", "ך", "ף", ",",
                "ז", "ס", "ב", "ה", "נ", "מ", "צ", "ת", "ץ", "."};
        for (int i = 0; i < us.length(); i++) HE.put(us.charAt(i), he[i]);
    }

    /** Long-press alternates, keyed by the character the key types right now. */
    private static final Map<String, String[]> ALTS = new HashMap<>();
    static {
        alt("a", "à", "á", "â", "ä", "ã", "å", "æ");
        alt("e", "è", "é", "ê", "ë", "€");
        alt("i", "ì", "í", "î", "ï");
        alt("o", "ò", "ó", "ô", "ö", "õ", "ø", "œ");
        alt("u", "ù", "ú", "û", "ü");
        alt("c", "ç", "©");
        alt("n", "ñ");
        alt("s", "ß", "§");
        alt("y", "ý", "ÿ");
        alt("A", "À", "Á", "Â", "Ä", "Ã", "Å", "Æ");
        alt("E", "È", "É", "Ê", "Ë");
        alt("O", "Ò", "Ó", "Ô", "Ö", "Õ", "Ø", "Œ");
        alt("U", "Ù", "Ú", "Û", "Ü");
        alt("C", "Ç");
        alt("N", "Ñ");
        // Hebrew: the final form, and the geresh/gershayim
        alt("כ", "ך");
        alt("מ", "ם");
        alt("נ", "ן");
        alt("פ", "ף");
        alt("צ", "ץ");
        alt("י", "ײ");
        alt("ו", "װ", "ױ");
        alt("'", "\"", "`", "~", "׳", "״");
        alt(",", ";", "<", "„");
        alt(".", "…", ">", ":", "·");
        alt("/", "?", "\\", "|");
        alt("-", "_", "=", "+", "–", "—");
        alt("9", "(", "[", "{");
        alt("0", ")", "]", "}");
        alt("4", "$", "₪", "€", "£");
        alt("1", "!", "¹", "½");
        alt("2", "@", "²");
        alt("3", "#", "³");
        alt("5", "%", "‰");
        alt("8", "*", "•", "°");
        alt("=", "+", "≠", "±");
    }

    private static void alt(String k, String... v) { ALTS.put(k, v); }

    public static final class Key {
        public final int kind;
        public final char us;          // CHAR: the key's US-layout character
        public final int code;         // SPECIAL: Android key code; MOD: modifier bit; ACTION: action id
        public final float units;      // width in key units
        public int row;
        public float x;                // left edge in units
        public boolean repeat;         // auto-repeat while held (Backspace, Del, arrows)
        // precomputed labels (no allocation while drawing)
        public final String label;     // SPECIAL / MOD / ACTION label
        public String enLower, enUpper, he, heShift, fnLabel;
        public int fnCode;             // F-key code in Fn mode (0 = none)
        public String hint;            // small corner hint (shifted symbol on digits/punctuation)

        Key(int kind, char us, int code, float units, String label) {
            this.kind = kind;
            this.us = us;
            this.code = code;
            this.units = units;
            this.label = label;
        }
    }

    public final List<Key> keys = new ArrayList<>();
    public final float widthUnits;
    public final int rows;
    /** Relative height of each row (the compact PC-key strip is a bit lower). */
    public final float[] rowHeights;
    public final boolean compact;

    private YugiKeyLayout(boolean compact, float widthUnits, float[] rowHeights) {
        this.compact = compact;
        this.widthUnits = widthUnits;
        this.rows = rowHeights.length;
        this.rowHeights = rowHeights;
    }

    // ------------------------------------------------------------------ layouts

    public static YugiKeyLayout full() {
        YugiKeyLayout l = new YugiKeyLayout(false, 15f, new float[]{1f, 1f, 1f, 1f, 1f});
        int r = 0;
        l.special(r, "Esc", KeyEvent.KEYCODE_ESCAPE, 1f, false);
        l.chars(r, "1234567890-=");
        l.special(r, "⌫", KeyEvent.KEYCODE_DEL, 2f, true);
        r++;
        l.special(r, "Tab", KeyEvent.KEYCODE_TAB, 1.5f, false);
        l.chars(r, "qwertyuiop[]");
        l.chr(r, '\\', 1.5f);
        r++;
        l.mod(r, "Caps", M_CAPS, 1.75f);
        l.chars(r, "asdfghjkl;'");
        l.special(r, "Enter", KeyEvent.KEYCODE_ENTER, 2.25f, false);
        r++;
        l.mod(r, "⇧", M_SHIFT, 2f);
        l.chars(r, "zxcvbnm,./");
        l.special(r, "↑", KeyEvent.KEYCODE_DPAD_UP, 1f, true);
        l.mod(r, "⇧", M_SHIFT, 2f);
        r++;
        l.mod(r, "Fn", M_FN, 1f);
        l.mod(r, "Ctrl", M_CTRL, 1.25f);
        l.mod(r, "Win", M_WIN, 1f);
        l.mod(r, "Alt", M_ALT, 1.25f);
        l.action(r, "EN", A_LANG, 1.25f);
        l.chr(r, ' ', 4.25f);
        l.special(r, "Del", KeyEvent.KEYCODE_FORWARD_DEL, 1f, true);
        l.special(r, "←", KeyEvent.KEYCODE_DPAD_LEFT, 1f, true);
        l.special(r, "↓", KeyEvent.KEYCODE_DPAD_DOWN, 1f, true);
        l.special(r, "→", KeyEvent.KEYCODE_DPAD_RIGHT, 1f, true);
        l.action(r, "⌨", A_HIDE, 1f);
        l.finish();
        return l;
    }

    public static YugiKeyLayout compact() {
        YugiKeyLayout l = new YugiKeyLayout(true, 11f, new float[]{0.8f, 1f, 1f, 1f, 1f, 1f});
        int r = 0;
        l.special(r, "Esc", KeyEvent.KEYCODE_ESCAPE, 1f, false);
        l.special(r, "Tab", KeyEvent.KEYCODE_TAB, 1f, false);
        l.mod(r, "Ctrl", M_CTRL, 1f);
        l.mod(r, "Alt", M_ALT, 1f);
        l.mod(r, "Win", M_WIN, 1f);
        l.mod(r, "Fn", M_FN, 1f);
        l.special(r, "←", KeyEvent.KEYCODE_DPAD_LEFT, 1f, true);
        l.special(r, "↑", KeyEvent.KEYCODE_DPAD_UP, 1f, true);
        l.special(r, "↓", KeyEvent.KEYCODE_DPAD_DOWN, 1f, true);
        l.special(r, "→", KeyEvent.KEYCODE_DPAD_RIGHT, 1f, true);
        l.special(r, "Del", KeyEvent.KEYCODE_FORWARD_DEL, 1f, true);
        r++;
        l.chars(r, "1234567890");
        l.special(r, "⌫", KeyEvent.KEYCODE_DEL, 1f, true);
        r++;
        l.chars(r, "qwertyuiop-");
        r++;
        l.chars(r, "asdfghjkl;'");
        r++;
        l.mod(r, "⇧", M_SHIFT, 1f);
        l.chars(r, "zxcvbnm,./");
        r++;
        l.action(r, "EN", A_LANG, 1.5f);
        l.chr(r, ' ', 5.5f);
        l.special(r, "Enter", KeyEvent.KEYCODE_ENTER, 2.5f, false);
        l.action(r, "⌨", A_HIDE, 1.5f);
        l.finish();
        return l;
    }

    private void chars(int row, String s) {
        for (int i = 0; i < s.length(); i++) chr(row, s.charAt(i), 1f);
    }

    private void chr(int row, char us, float units) {
        Key k = new Key(CHAR, us, 0, units, null);
        k.row = row;
        k.enLower = String.valueOf(us);
        k.enUpper = shifted(us);
        String h = HE.get(us);
        k.he = h != null ? h : k.enLower;
        k.heShift = k.enUpper; // Windows Hebrew layout: Shift types the Latin capital / US symbol
        int ui = US_PLAIN.indexOf(us);
        if (ui >= 0) k.hint = String.valueOf(US_SHIFT.charAt(ui));
        if (us >= '1' && us <= '9') { k.fnCode = KeyEvent.KEYCODE_F1 + (us - '1'); k.fnLabel = "F" + (us - '0'); }
        else if (us == '0') { k.fnCode = KeyEvent.KEYCODE_F10; k.fnLabel = "F10"; }
        else if (us == '-' && !compact) { k.fnCode = KeyEvent.KEYCODE_F11; k.fnLabel = "F11"; }
        else if (us == '=' && !compact) { k.fnCode = KeyEvent.KEYCODE_F12; k.fnLabel = "F12"; }
        keys.add(k);
    }

    private void special(int row, String label, int code, float units, boolean repeat) {
        Key k = new Key(SPECIAL, (char) 0, code, units, label);
        k.row = row;
        k.repeat = repeat;
        keys.add(k);
    }

    private void mod(int row, String label, int bit, float units) {
        Key k = new Key(MOD, (char) 0, bit, units, label);
        k.row = row;
        keys.add(k);
    }

    private void action(int row, String label, int id, float units) {
        Key k = new Key(ACTION, (char) 0, id, units, label);
        k.row = row;
        keys.add(k);
    }

    private void finish() {
        float[] x = new float[rows];
        for (Key k : keys) {
            k.x = x[k.row];
            x[k.row] += k.units;
        }
    }

    /** Sum of key widths per row (all rows must equal {@link #widthUnits}). */
    public float rowWidth(int row) {
        float w = 0;
        for (Key k : keys) if (k.row == row) w += k.units;
        return w;
    }

    // ------------------------------------------------------------------ behavior

    public static String shifted(char us) {
        if (us >= 'a' && us <= 'z') return String.valueOf((char) (us - 32));
        int i = US_PLAIN.indexOf(us);
        return i >= 0 ? String.valueOf(US_SHIFT.charAt(i)) : String.valueOf(us);
    }

    /**
     * What a character key types. Caps Lock affects letters only (like Windows); Shift and Caps
     * cancel each other on letters.
     */
    public static String output(Key k, boolean hebrew, boolean shift, boolean caps) {
        if (k.kind != CHAR) return null;
        boolean letter = k.us >= 'a' && k.us <= 'z';
        boolean upper = letter ? (shift ^ caps) : shift;
        if (hebrew) {
            if (upper) return k.heShift;
            return k.he;
        }
        return upper ? k.enUpper : k.enLower;
    }

    /** Label drawn on a key in the current state. */
    public static String label(Key k, boolean hebrew, boolean shift, boolean caps, boolean fn) {
        switch (k.kind) {
            case CHAR:
                if (fn && k.fnLabel != null) return k.fnLabel;
                if (k.us == ' ') return hebrew ? "עברית" : "English";
                return output(k, hebrew, shift, caps);
            case ACTION:
                if (k.code == A_LANG) return hebrew ? "עב" : "EN";
                return k.label;
            default:
                return k.label;
        }
    }

    public static String[] alternates(String out) {
        return out == null ? null : ALTS.get(out);
    }

    /**
     * Android key code of the key at this US position, for shortcuts: with Ctrl, Alt or Win held the
     * keyboard sends the physical key (Ctrl+ש is Ctrl+A), the way Windows does.
     */
    public static int usKeyCode(char us) {
        if (us >= 'a' && us <= 'z') return KeyEvent.KEYCODE_A + (us - 'a');
        if (us >= '0' && us <= '9') return KeyEvent.KEYCODE_0 + (us - '0');
        switch (us) {
            case '`': return KeyEvent.KEYCODE_GRAVE;
            case '-': return KeyEvent.KEYCODE_MINUS;
            case '=': return KeyEvent.KEYCODE_EQUALS;
            case '[': return KeyEvent.KEYCODE_LEFT_BRACKET;
            case ']': return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case '\\': return KeyEvent.KEYCODE_BACKSLASH;
            case ';': return KeyEvent.KEYCODE_SEMICOLON;
            case '\'': return KeyEvent.KEYCODE_APOSTROPHE;
            case ',': return KeyEvent.KEYCODE_COMMA;
            case '.': return KeyEvent.KEYCODE_PERIOD;
            case '/': return KeyEvent.KEYCODE_SLASH;
            case ' ': return KeyEvent.KEYCODE_SPACE;
            default: return 0;
        }
    }

    /** Android key code of a modifier bit (sent down before and up after a shortcut). */
    public static int modKeyCode(int bit) {
        switch (bit) {
            case M_SHIFT: return KeyEvent.KEYCODE_SHIFT_LEFT;
            case M_CTRL: return KeyEvent.KEYCODE_CTRL_LEFT;
            case M_ALT: return KeyEvent.KEYCODE_ALT_LEFT;
            case M_WIN: return KeyEvent.KEYCODE_META_LEFT;
            default: return 0;
        }
    }
}

package com.limelight.yugi;

import android.text.Editable;
import android.text.Layout;
import android.text.Selection;
import android.view.KeyEvent;
import android.widget.EditText;

/**
 * Sends the YugiPC keyboard into an EditText inside the app (no Android IME involved).
 * Text replaces the selection; Backspace/Del delete the selection or one character; arrows move the
 * caret (Shift+arrow selects, visually in Hebrew when the field is laid out); Ctrl+A/C/X/V/Z work;
 * Enter calls {@link Actions#onEnter()} (Shift+Enter is a new line); Esc hides the keyboard.
 * Modifier key events themselves are ignored: the engine already folds them into {@code mods}.
 */
public final class YugiEditTextSender implements YugiKeyEngine.Sender {
    public interface Actions {
        /** Enter without Shift (send). */
        void onEnter();
        default void onEscape() {}
    }

    private final EditText field;
    private final Actions actions;

    public YugiEditTextSender(EditText field, Actions actions) {
        this.field = field;
        this.actions = actions;
    }

    @Override
    public void text(String s) {
        replaceSelection(s);
    }

    /** Replaces the selection (or inserts at the caret) and leaves the caret after the new text. */
    public void replaceSelection(String s) {
        Editable e = field.getText();
        int a = Math.max(0, Selection.getSelectionStart(e)), b = Math.max(0, Selection.getSelectionEnd(e));
        int st = Math.min(a, b), en = Math.max(a, b);
        e.replace(st, en, s);
        Selection.setSelection(e, st + s.length());
    }

    @Override
    public void key(int code, boolean down, int mods) {
        if (!down) return;
        switch (code) {
            case KeyEvent.KEYCODE_SHIFT_LEFT: case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT: case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT: case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT: case KeyEvent.KEYCODE_META_RIGHT:
                return;
            default:
                break;
        }
        boolean shift = (mods & YugiKeyLayout.M_SHIFT) != 0;
        boolean ctrl = (mods & YugiKeyLayout.M_CTRL) != 0;
        Editable e = field.getText();
        if (ctrl) {
            int id = 0;
            switch (code) {
                case KeyEvent.KEYCODE_A: id = android.R.id.selectAll; break;
                case KeyEvent.KEYCODE_C: id = android.R.id.copy; break;
                case KeyEvent.KEYCODE_X: id = android.R.id.cut; break;
                case KeyEvent.KEYCODE_V: id = android.R.id.paste; break;
                case KeyEvent.KEYCODE_Z: id = android.R.id.undo; break;
                case KeyEvent.KEYCODE_DEL: deleteWordBefore(e); return;
                default: break;
            }
            if (id != 0) field.onTextContextMenuItem(id);
            return;
        }
        Layout l = field.getLayout();
        switch (code) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (shift) replaceSelection("\n");
                else actions.onEnter();
                return;
            case KeyEvent.KEYCODE_ESCAPE:
                actions.onEscape();
                return;
            case KeyEvent.KEYCODE_TAB:
                return;
            case KeyEvent.KEYCODE_DEL:
                delete(e, false);
                return;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                delete(e, true);
                return;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (l != null) { if (shift) Selection.extendLeft(e, l); else Selection.moveLeft(e, l); }
                else logical(e, rtl(e) ? 1 : -1, shift);
                return;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (l != null) { if (shift) Selection.extendRight(e, l); else Selection.moveRight(e, l); }
                else logical(e, rtl(e) ? -1 : 1, shift);
                return;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (l != null) { if (shift) Selection.extendUp(e, l); else Selection.moveUp(e, l); }
                else logical(e, -e.length(), shift);
                return;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (l != null) { if (shift) Selection.extendDown(e, l); else Selection.moveDown(e, l); }
                else logical(e, e.length(), shift);
                return;
            default:
                break;
        }
    }

    private static boolean rtl(Editable e) {
        for (int i = 0; i < e.length(); i++) {
            byte d = Character.getDirectionality(e.charAt(i));
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
        }
        return false;
    }

    /** Without a layout (not yet drawn): move by characters in logical order. */
    private static void logical(Editable e, int by, boolean extend) {
        int start = Selection.getSelectionStart(e), end = Selection.getSelectionEnd(e);
        if (!extend && start != end) {
            Selection.setSelection(e, by < 0 ? Math.min(start, end) : Math.max(start, end));
            return;
        }
        int to = Math.max(0, Math.min(e.length(), end + by));
        if (extend) Selection.setSelection(e, start, to);
        else Selection.setSelection(e, to);
    }

    private static void delete(Editable e, boolean forward) {
        int a = Selection.getSelectionStart(e), b = Selection.getSelectionEnd(e);
        if (a < 0) return;
        int st = Math.min(a, b), en = Math.max(a, b);
        if (st != en) {
            e.delete(st, en);
            return;
        }
        if (forward) {
            if (st >= e.length()) return;
            e.delete(st, graphemeAfter(e, st));
        } else {
            if (st == 0) return;
            e.delete(graphemeBefore(e, st), st);
        }
    }

    /**
     * Start of the character before {@code at} as the user sees it: a whole emoji (👍🏽, 👨‍👩‍👧, 🇮🇱, #️⃣),
     * not one code point of it. Falls back to one code point.
     */
    static int graphemeBefore(CharSequence s, int at) {
        int cp = Character.offsetByCodePoints(s, at, -1);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.icu.text.BreakIterator it = android.icu.text.BreakIterator.getCharacterInstance();
                it.setText(s.toString());
                int p = it.preceding(at);
                return p >= 0 && p < at ? p : cp;
            }
            java.text.BreakIterator it = java.text.BreakIterator.getCharacterInstance();
            it.setText(s.toString());
            int p = it.preceding(at);
            return p >= 0 && p < at ? Math.min(p, cp) : cp;
        } catch (RuntimeException ex) {
            return cp;
        }
    }

    /** End of the character after {@code at} (see {@link #graphemeBefore}). */
    static int graphemeAfter(CharSequence s, int at) {
        int cp = Character.offsetByCodePoints(s, at, 1);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.icu.text.BreakIterator it = android.icu.text.BreakIterator.getCharacterInstance();
                it.setText(s.toString());
                int f = it.following(at);
                return f > at && f <= s.length() ? f : cp;
            }
            java.text.BreakIterator it = java.text.BreakIterator.getCharacterInstance();
            it.setText(s.toString());
            int f = it.following(at);
            return f > at && f <= s.length() ? Math.max(f, cp) : cp;
        } catch (RuntimeException ex) {
            return cp;
        }
    }

    private static void deleteWordBefore(Editable e) {
        int a = Selection.getSelectionStart(e), b = Selection.getSelectionEnd(e);
        if (a < 0) return;
        if (a != b) { delete(e, false); return; }
        int i = a;
        while (i > 0 && Character.isWhitespace(e.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(e.charAt(i - 1))) i--;
        e.delete(i, a);
    }
}

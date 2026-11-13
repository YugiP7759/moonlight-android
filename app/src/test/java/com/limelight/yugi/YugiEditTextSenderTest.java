package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.text.Selection;
import android.view.KeyEvent;
import android.widget.EditText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The YugiPC keyboard writing into an in-app EditText (chat): text, selection, delete, arrows, Enter. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class YugiEditTextSenderTest {
    private EditText et;
    private int enters;

    private YugiEditTextSender sender() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        et = new EditText(a);
        return new YugiEditTextSender(et, () -> enters++);
    }

    private void tap(YugiEditTextSender s, int code, int mods) {
        s.key(code, true, mods);
        s.key(code, false, mods);
    }

    @Test public void typesReplacesAndDeletes() {
        YugiEditTextSender s = sender();
        s.text("שלום");
        s.text(" ");
        s.text("abc");
        assertEquals("שלום abc", et.getText().toString());
        assertEquals(8, et.getSelectionStart());
        tap(s, KeyEvent.KEYCODE_DEL, 0);
        assertEquals("שלום ab", et.getText().toString());
        // selection is replaced by typing, deleted by Backspace
        Selection.setSelection(et.getText(), 0, 4);
        s.text("היי");
        assertEquals("היי ab", et.getText().toString());
        assertEquals(3, et.getSelectionStart());
        Selection.setSelection(et.getText(), 3, 6);
        tap(s, KeyEvent.KEYCODE_DEL, 0);
        assertEquals("היי", et.getText().toString());
        // forward delete at the caret
        Selection.setSelection(et.getText(), 0);
        tap(s, KeyEvent.KEYCODE_FORWARD_DEL, 0);
        assertEquals("יי", et.getText().toString());
        // Backspace at the start does nothing
        tap(s, KeyEvent.KEYCODE_DEL, 0);
        assertEquals("יי", et.getText().toString());
    }

    @Test public void backspaceDeletesAWholeEmoji() {
        YugiEditTextSender s = sender();
        String[] emoji = {"👍🏽", "👨‍👩‍👧", "🇮🇱", "#️⃣", "❤️", "😀"};
        s.text("a");
        for (String e : emoji) s.text(e);
        for (int i = emoji.length - 1; i >= 0; i--) {
            tap(s, KeyEvent.KEYCODE_DEL, 0);
            StringBuilder want = new StringBuilder("a");
            for (int j = 0; j < i; j++) want.append(emoji[j]);
            assertEquals("after deleting " + emoji[i], want.toString(), et.getText().toString());
        }
        // forward delete too
        s.text("🇮🇱👍🏽");
        Selection.setSelection(et.getText(), 1);
        tap(s, KeyEvent.KEYCODE_FORWARD_DEL, 0);
        assertEquals("a👍🏽", et.getText().toString());
    }

    @Test public void arrowsMoveAndShiftSelects() {
        YugiEditTextSender s = sender();
        s.text("hello");
        tap(s, KeyEvent.KEYCODE_DPAD_LEFT, 0);
        tap(s, KeyEvent.KEYCODE_DPAD_LEFT, 0);
        assertEquals(3, et.getSelectionStart());
        tap(s, KeyEvent.KEYCODE_DPAD_RIGHT, 0);
        assertEquals(4, et.getSelectionStart());
        tap(s, KeyEvent.KEYCODE_DPAD_LEFT, YugiKeyLayout.M_SHIFT);
        tap(s, KeyEvent.KEYCODE_DPAD_LEFT, YugiKeyLayout.M_SHIFT);
        assertEquals(2, Math.min(et.getSelectionStart(), et.getSelectionEnd()));
        assertEquals(4, Math.max(et.getSelectionStart(), et.getSelectionEnd()));
        s.text("Y");
        assertEquals("heYo", et.getText().toString());
        tap(s, KeyEvent.KEYCODE_DPAD_UP, 0);
        assertEquals(0, et.getSelectionStart());
        tap(s, KeyEvent.KEYCODE_DPAD_DOWN, 0);
        assertEquals(4, et.getSelectionStart());
    }

    @Test public void enterSendsShiftEnterIsNewLineCtrlASelectsAll() {
        YugiEditTextSender s = sender();
        s.text("a");
        tap(s, KeyEvent.KEYCODE_ENTER, 0);
        assertEquals(1, enters);
        assertEquals("a", et.getText().toString());
        tap(s, KeyEvent.KEYCODE_ENTER, YugiKeyLayout.M_SHIFT);
        assertEquals("a\n", et.getText().toString());
        assertEquals(1, enters);
        // modifier key events alone never type anything
        tap(s, KeyEvent.KEYCODE_SHIFT_LEFT, 0);
        tap(s, KeyEvent.KEYCODE_CTRL_LEFT, 0);
        assertEquals("a\n", et.getText().toString());
        tap(s, KeyEvent.KEYCODE_A, YugiKeyLayout.M_CTRL);
        assertEquals(0, Math.min(et.getSelectionStart(), et.getSelectionEnd()));
        assertEquals(2, Math.max(et.getSelectionStart(), et.getSelectionEnd()));
    }

    @Test public void engineToFieldHebrewWithSticky() {
        YugiEditTextSender s = sender();
        YugiKeyEngine engine = new YugiKeyEngine(s);
        YugiKeyLayout l = YugiKeyLayout.compact();
        engine.hebrew = true;
        engine.charKey(key(l, 'a'), null);   // ש
        engine.charKey(key(l, ','), null);   // ת
        engine.charKey(key(l, ' '), null);
        assertEquals("שת ", et.getText().toString());
        // Ctrl+ש = Ctrl+A selects all, and nothing is typed
        engine.modDown(YugiKeyLayout.M_CTRL);
        engine.modUp(YugiKeyLayout.M_CTRL, 0);
        engine.charKey(key(l, 'a'), null);
        assertEquals("שת ", et.getText().toString());
        assertEquals(3, Math.abs(et.getSelectionEnd() - et.getSelectionStart()));
        engine.specialKey(special(l, KeyEvent.KEYCODE_DEL));
        assertTrue(et.getText().toString().isEmpty());
    }

    static YugiKeyLayout.Key key(YugiKeyLayout l, char us) {
        for (YugiKeyLayout.Key k : l.keys) if (k.kind == YugiKeyLayout.CHAR && k.us == us) return k;
        throw new AssertionError("no key " + us);
    }

    static YugiKeyLayout.Key special(YugiKeyLayout l, int code) {
        for (YugiKeyLayout.Key k : l.keys) if (k.kind == YugiKeyLayout.SPECIAL && k.code == code) return k;
        throw new AssertionError("no key " + code);
    }
}

package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Keyboard logic without Android: layouts, what each key sends, and the desktop scroll math. */
public class YugiKeyboardLogicTest {

    // ------------------------------------------------------------ recording sender

    static final class Rec implements YugiKeyEngine.Sender {
        final List<String> log = new ArrayList<>();
        @Override public void text(String s) { log.add("T:" + s); }
        @Override public void key(int code, boolean down, int mods) { log.add((down ? "D:" : "U:") + code + "/" + mods); }
        String all() { return String.join(" ", log); }
    }

    static YugiKeyLayout.Key key(YugiKeyLayout l, char us) {
        for (YugiKeyLayout.Key k : l.keys) if (k.kind == YugiKeyLayout.CHAR && k.us == us) return k;
        throw new AssertionError("no key " + us);
    }

    static YugiKeyLayout.Key special(YugiKeyLayout l, int code) {
        for (YugiKeyLayout.Key k : l.keys) if (k.kind == YugiKeyLayout.SPECIAL && k.code == code) return k;
        throw new AssertionError("no special " + code);
    }

    // ------------------------------------------------------------ layouts

    @Test public void rowsAreFullWidth() {
        for (YugiKeyLayout l : new YugiKeyLayout[]{YugiKeyLayout.full(), YugiKeyLayout.compact()}) {
            for (int r = 0; r < l.rows; r++) {
                assertEquals("row " + r + (l.compact ? " compact" : " full"), l.widthUnits, l.rowWidth(r), 1e-4);
            }
        }
    }

    @Test public void pcKeysPresentInBothLayouts() {
        for (YugiKeyLayout l : new YugiKeyLayout[]{YugiKeyLayout.full(), YugiKeyLayout.compact()}) {
            int[] codes = {KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL};
            for (int c : codes) special(l, c);
            int mods = 0;
            for (YugiKeyLayout.Key k : l.keys) if (k.kind == YugiKeyLayout.MOD) mods |= k.code;
            int want = YugiKeyLayout.M_SHIFT | YugiKeyLayout.M_CTRL | YugiKeyLayout.M_ALT | YugiKeyLayout.M_WIN | YugiKeyLayout.M_FN;
            assertEquals(want, mods & want);
        }
    }

    @Test public void hebrewHasAll27Letters() {
        for (YugiKeyLayout l : new YugiKeyLayout[]{YugiKeyLayout.full(), YugiKeyLayout.compact()}) {
            StringBuilder sb = new StringBuilder();
            for (YugiKeyLayout.Key k : l.keys) if (k.kind == YugiKeyLayout.CHAR) sb.append(YugiKeyLayout.output(k, true, false, false));
            for (char c : "אבגדהוזחטיכךלמםנןסעפףצץקרשת".toCharArray()) {
                assertTrue("missing " + c, sb.indexOf(String.valueOf(c)) >= 0);
            }
        }
    }

    @Test public void arrowsStackedInFullLayout() {
        YugiKeyLayout l = YugiKeyLayout.full();
        assertEquals(special(l, KeyEvent.KEYCODE_DPAD_UP).x, special(l, KeyEvent.KEYCODE_DPAD_DOWN).x, 1e-4);
    }

    // ------------------------------------------------------------ engine

    @Test public void plainTextAndHebrew() {
        Rec r = new Rec();
        YugiKeyEngine e = new YugiKeyEngine(r);
        YugiKeyLayout l = YugiKeyLayout.full();
        e.charKey(key(l, 'a'), null);
        e.toggleLanguage();
        e.charKey(key(l, 'a'), null);
        e.charKey(key(l, ','), null);
        e.charKey(key(l, ' '), null);
        assertEquals("T:a T:ש T:ת T: ", r.all());
    }

    @Test public void stickyShiftIsOneShotAndDoubleTapIsCaps() {
        Rec r = new Rec();
        YugiKeyEngine e = new YugiKeyEngine(r);
        YugiKeyLayout l = YugiKeyLayout.full();
        e.modDown(YugiKeyLayout.M_SHIFT); e.modUp(YugiKeyLayout.M_SHIFT, 1000);
        e.charKey(key(l, 'h'), null);
        e.charKey(key(l, 'i'), null);
        e.charKey(key(l, '1'), null);
        assertEquals("T:H T:i T:1", r.all());
        r.log.clear();
        e.modDown(YugiKeyLayout.M_SHIFT); e.modUp(YugiKeyLayout.M_SHIFT, 5000);
        e.modDown(YugiKeyLayout.M_SHIFT); e.modUp(YugiKeyLayout.M_SHIFT, 5200);
        assertTrue(e.caps());
        e.charKey(key(l, 'a'), null);
        e.charKey(key(l, 'b'), null);
        e.charKey(key(l, '1'), null); // caps does not shift digits (Windows)
        assertEquals("T:A T:B T:1", r.all());
        e.modDown(YugiKeyLayout.M_SHIFT); e.modUp(YugiKeyLayout.M_SHIFT, 9000);
        assertFalse(e.caps());
    }

    @Test public void ctrlUsesUsKeyPositionEvenInHebrew() {
        Rec r = new Rec();
        YugiKeyEngine e = new YugiKeyEngine(r);
        YugiKeyLayout l = YugiKeyLayout.full();
        e.toggleLanguage();
        e.modDown(YugiKeyLayout.M_CTRL); e.modUp(YugiKeyLayout.M_CTRL, 0);
        e.charKey(key(l, 'c'), null);               // ב position = C
        int ctrl = KeyEvent.KEYCODE_CTRL_LEFT, c = KeyEvent.KEYCODE_C;
        assertEquals("D:" + ctrl + "/0 D:" + c + "/2 U:" + c + "/2 U:" + ctrl + "/0", r.all());
        r.log.clear();
        e.charKey(key(l, 'c'), null);               // one-shot: back to text
        assertEquals("T:ב", r.all());
    }

    @Test public void heldModifierChordsAndDoesNotLatch() {
        Rec r = new Rec();
        YugiKeyEngine e = new YugiKeyEngine(r);
        YugiKeyLayout l = YugiKeyLayout.full();
        e.modDown(YugiKeyLayout.M_WIN);
        e.charKey(key(l, 'e'), null);
        e.modUp(YugiKeyLayout.M_WIN, 0);
        int meta = KeyEvent.KEYCODE_META_LEFT, k = KeyEvent.KEYCODE_E;
        assertEquals("D:" + meta + "/0 D:" + k + "/8 U:" + k + "/8 U:" + meta + "/0", r.all());
        r.log.clear();
        e.modDown(YugiKeyLayout.M_WIN); e.modUp(YugiKeyLayout.M_WIN, 0);   // tap alone: start menu
        assertEquals("D:" + meta + "/0 U:" + meta + "/0", r.all());
    }

    @Test public void shiftArrowSelectsAndRepeatKeepsMods() {
        Rec r = new Rec();
        YugiKeyEngine e = new YugiKeyEngine(r);
        YugiKeyLayout l = YugiKeyLayout.full();
        e.modDown(YugiKeyLayout.M_SHIFT); e.modUp(YugiKeyLayout.M_SHIFT, 0);
        int mods = e.specialKey(special(l, KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(YugiKeyLayout.M_SHIFT, mods);
        r.log.clear();
        e.repeat(special(l, KeyEvent.KEYCODE_DPAD_LEFT), mods);
        int sh = KeyEvent.KEYCODE_SHIFT_LEFT, left = KeyEvent.KEYCODE_DPAD_LEFT;
        assertEquals("D:" + sh + "/0 D:" + left + "/1 U:" + left + "/1 U:" + sh + "/0", r.all());
    }

    @Test public void fnTurnsDigitsIntoFKeys() {
        Rec r = new Rec();
        YugiKeyEngine e = new YugiKeyEngine(r);
        YugiKeyLayout l = YugiKeyLayout.full();
        e.modDown(YugiKeyLayout.M_FN); e.modUp(YugiKeyLayout.M_FN, 0);
        assertTrue(e.fn());
        e.charKey(key(l, '5'), null);
        e.charKey(key(l, '='), null);
        int f5 = KeyEvent.KEYCODE_F5, f12 = KeyEvent.KEYCODE_F12;
        assertEquals("D:" + f5 + "/0 U:" + f5 + "/0 D:" + f12 + "/0 U:" + f12 + "/0", r.all());
        assertEquals("F5", YugiKeyLayout.label(key(l, '5'), false, false, false, true));
    }

    @Test public void emojiKeyTakesDelsPlace() {
        for (YugiKeyLayout l : new YugiKeyLayout[]{YugiKeyLayout.full(), YugiKeyLayout.compact()}) {
            int emoji = 0;
            for (YugiKeyLayout.Key k : l.keys) {
                if (k.kind == YugiKeyLayout.ACTION && k.code == YugiKeyLayout.A_EMOJI) emoji++;
                assertFalse("no Del key (it is Fn + Backspace)", k.kind == YugiKeyLayout.SPECIAL && k.code == KeyEvent.KEYCODE_FORWARD_DEL);
            }
            assertEquals(1, emoji);
        }
    }

    @Test public void fnBackspaceIsDelete() {
        Rec r = new Rec();
        YugiKeyEngine e = new YugiKeyEngine(r);
        YugiKeyLayout l = YugiKeyLayout.full();
        YugiKeyLayout.Key bs = special(l, KeyEvent.KEYCODE_DEL);
        int del = KeyEvent.KEYCODE_DEL, fwd = KeyEvent.KEYCODE_FORWARD_DEL;
        e.specialKey(bs);
        assertEquals("D:" + del + "/0 U:" + del + "/0", r.all());
        assertEquals("⌫", YugiKeyLayout.label(bs, false, false, false, false));
        e.modDown(YugiKeyLayout.M_FN); e.modUp(YugiKeyLayout.M_FN, 0);
        r.log.clear();
        int mods = e.specialKey(bs);
        e.repeat(bs, mods); // held: auto-repeat deletes forward too
        assertEquals("D:" + fwd + "/0 U:" + fwd + "/0 D:" + fwd + "/0 U:" + fwd + "/0", r.all());
        assertEquals("Del", YugiKeyLayout.label(bs, false, false, false, true));
        assertEquals("Del", YugiKeyLayout.label(special(YugiKeyLayout.compact(), KeyEvent.KEYCODE_DEL), true, false, false, true));
        // Fn off again: Backspace
        e.modDown(YugiKeyLayout.M_FN); e.modUp(YugiKeyLayout.M_FN, 0);
        r.log.clear();
        e.specialKey(bs);
        assertEquals("D:" + del + "/0 U:" + del + "/0", r.all());
    }

    @Test public void emojiIsTextEvenWithCtrlArmed() {
        Rec r = new Rec();
        YugiKeyEngine e = new YugiKeyEngine(r);
        e.modDown(YugiKeyLayout.M_CTRL); e.modUp(YugiKeyLayout.M_CTRL, 0);
        e.emoji("👨‍👩‍👧");
        assertEquals("T:👨‍👩‍👧", r.all());
        assertFalse("the one-shot Ctrl is used up", e.isActive(YugiKeyLayout.M_CTRL));
        r.log.clear();
        e.backspace();
        assertEquals("D:" + KeyEvent.KEYCODE_DEL + "/0 U:" + KeyEvent.KEYCODE_DEL + "/0", r.all());
    }

    @Test public void longPressAlternates() {
        YugiKeyLayout l = YugiKeyLayout.full();
        assertNotNull(YugiKeyLayout.alternates(YugiKeyLayout.output(key(l, 'e'), false, false, false)));
        assertEquals("ך", YugiKeyLayout.alternates(YugiKeyLayout.output(key(l, 'f'), true, false, false))[0]);
        Rec r = new Rec();
        YugiKeyEngine e = new YugiKeyEngine(r);
        e.charKey(key(l, 'e'), "é");
        assertEquals("T:é", r.all());
    }

    // ------------------------------------------------------------ desktop scroll

    @Test public void shiftMovesCaretJustAboveKeyboard() {
        // inner screen portrait: view 2000px tall, desktop 1080 high, keyboard top at 1400 (600 tall)
        float bottom = YugiShift.caretBottomInView(900, 20, 1080, 0, 2000); // 920/1080*2000
        assertEquals(1703.7f, bottom, 0.1f);
        float s = YugiShift.shiftFor(bottom, 63, 1400, 600);
        assertEquals(1703.7f + 63 - 1400, s, 0.1f);
        assertEquals(1400 - 63, bottom - s, 0.1f); // caret ends 24dp above the keyboard
    }

    @Test public void noShiftWhenCaretAlreadyVisible() {
        assertEquals(0f, YugiShift.shiftFor(500, 63, 1400, 600), 0f);
    }

    @Test public void shiftNeverExceedsKeyboardHeight() {
        float b = YugiShift.caretBottomInView(1075, 20, 1080, 0, 2000);
        assertEquals(600f, YugiShift.shiftFor(b, 63, 1400, 600), 0f);
    }

    @Test public void unknownCaretFallsBackToTouch() {
        assertTrue(Float.isNaN(YugiShift.caretBottomInView(10, 10, 0, 0, 2000)));
        assertEquals(0f, YugiShift.shiftFor(Float.NaN, 63, 1400, 600), 0f);
        float fb = YugiShift.fallbackCaretBottom(1500, 60);
        assertEquals(1530f, fb, 0f);
        assertEquals(1530 + 63 - 1400, YugiShift.shiftFor(fb, 63, 1400, 600), 0.01f);
    }
}

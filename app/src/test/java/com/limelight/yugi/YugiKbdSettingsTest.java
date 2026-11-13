package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keyboard settings: saved and reloaded, pushed live to the keyboard; split mode on the Fold inner
 * screen keeps every key out of the middle gap; floating and one-handed panels; the chat field keyboard.
 * Renders kbd2-*.png into app/build/yugi-screens for a visual check.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class YugiKbdSettingsTest {
    private static final File OUT = new File("build/yugi-screens");

    @Before public void reset() { YugiKbdSettings.resetForTest(); }
    @After public void restore() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        new YugiKbdSettings().apply(a); // defaults back for the other tests
        YugiKbdSettings.resetForTest();
    }

    @Test public void savedAndReloaded() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        YugiKbdSettings s = YugiKbdSettings.get(a);
        assertEquals(YugiKbdSettings.DOCKED, s.mode);
        assertTrue(s.haptics);
        AtomicInteger pushed = new AtomicInteger();
        YugiKbdSettings.Listener l = x -> pushed.incrementAndGet();
        YugiKbdSettings.addListener(l);
        s.height = 1.2f; s.font = 1.1f; s.mode = YugiKbdSettings.FLOATING; s.hand = YugiKbdSettings.HAND_LEFT;
        s.haptics = false; s.sound = true; s.floatX = 0.3f; s.floatY = 0.4f;
        s.apply(a);
        assertEquals(1, pushed.get());
        YugiKbdSettings.removeListener(l);
        YugiKbdSettings r = YugiKbdSettings.load(a);
        assertEquals(1.2f, r.height, 1e-4);
        assertEquals(1.1f, r.font, 1e-4);
        assertEquals(YugiKbdSettings.FLOATING, r.mode);
        assertEquals(YugiKbdSettings.HAND_LEFT, r.hand);
        assertFalse(r.haptics);
        assertTrue(r.sound);
        assertEquals(0.3f, r.floatX, 1e-4);
        assertEquals(0.4f, r.floatY, 1e-4);
    }

    @Test @Config(qualifiers = "w960dp-h411dp-land-420dpi")
    public void heightAndFontAreLive() {
        Rig g = new Rig();
        float h0 = g.kb.panelHeight();
        float t0 = g.kb.textSizeOf(charKey(g.kb, 'q'));
        YugiKbdSettings s = YugiKbdSettings.get(g.a);
        s.height = YugiKbdSettings.HEIGHT_MAX;
        s.font = YugiKbdSettings.FONT_MAX;
        s.apply(g.a); // the keyboard listens: no re-creation needed
        g.layout();
        assertTrue("taller", g.kb.panelHeight() > h0 * 1.15f);
        assertTrue("bigger labels", g.kb.textSizeOf(charKey(g.kb, 'q')) > t0 * 1.2f);
    }

    @Test @Config(qualifiers = "w832dp-h750dp-land-420dpi")
    public void splitOnInnerScreen() throws Exception {
        YugiKbdSettings.get(Robolectric.buildActivity(Activity.class).setup().get()).mode = YugiKbdSettings.SPLIT;
        Rig g = new Rig();
        assertEquals(YugiKbdSettings.SPLIT, g.kb.effectiveMode());
        float[] gap = g.kb.splitGap();
        assertNotNull(gap);
        assertTrue("a real gap in the middle", gap[1] - gap[0] > g.w * 0.15f);
        int left = 0, right = 0;
        for (int i = 0; i < g.kb.keyCount(); i++) {
            float[] b = g.kb.keyBounds(i);
            boolean inLeft = b[2] <= gap[0] + 0.5f, inRight = b[0] >= gap[1] - 0.5f;
            assertTrue("key " + i + " crosses the middle gap", inLeft || inRight);
            assertTrue("key " + i + " inside the screen", b[0] >= 0 && b[2] <= g.w);
            if (inLeft) left++; else right++;
            for (int j = i + 1; j < g.kb.keyCount(); j++) {
                float[] o = g.kb.keyBounds(j);
                boolean overlap = b[0] < o[2] - 0.5f && o[0] < b[2] - 0.5f && b[1] < o[3] - 0.5f && o[1] < b[3] - 0.5f;
                assertFalse("keys " + i + " and " + j + " overlap", overlap);
            }
        }
        assertTrue(left > 20 && right > 20);
        // the space bar is in one half, whole
        int space = charKey(g.kb, ' ');
        float[] sb = g.kb.keyBounds(space);
        assertTrue(sb[2] - sb[0] > g.kb.keyBounds(charKey(g.kb, 'q'))[2] - g.kb.keyBounds(charKey(g.kb, 'q'))[0]);
        save(g.root, "kbd2-split-inner");
    }

    @Test @Config(qualifiers = "w411dp-h960dp-port-420dpi")
    public void splitFallsBackToDockedOnCover() {
        YugiKbdSettings.get(Robolectric.buildActivity(Activity.class).setup().get()).mode = YugiKbdSettings.SPLIT;
        Rig g = new Rig();
        assertEquals(YugiKbdSettings.DOCKED, g.kb.effectiveMode());
        assertNull(g.kb.splitGap());
    }

    @Test @Config(qualifiers = "w960dp-h411dp-land-420dpi")
    public void floatingIsSmallAndLetsTouchesThrough() throws Exception {
        YugiKbdSettings.get(Robolectric.buildActivity(Activity.class).setup().get()).mode = YugiKbdSettings.FLOATING;
        Rig g = new Rig();
        float[] p = g.kb.panelBounds();
        assertTrue("narrow", p[2] - p[0] <= YugiTheme.dp(g.a, 400) + 1);
        assertEquals(0f, g.kb.pushHeight(), 0f);
        assertTrue(g.kb.layout().compact);
        long t = SystemClock.uptimeMillis();
        MotionEvent outside = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 5, 5, 0);
        assertFalse("outside the panel goes to the screen below", g.kb.onTouchEvent(outside));
        // drag the grabber: the panel follows and the spot is saved
        float gx = (p[0] + p[2]) / 2, gy = p[1] + YugiTheme.dp(g.a, 8);
        assertTrue(g.kb.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, gx, gy, 0)));
        g.kb.onTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_MOVE, gx - 150, gy - 60, 0));
        g.kb.onTouchEvent(MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, gx - 150, gy - 60, 0));
        float[] q = g.kb.panelBounds();
        assertEquals(p[0] - 150, q[0], 2f);
        assertTrue(YugiKbdSettings.load(g.a).floatX < 0.5f);
        save(g.root, "kbd2-floating");
    }

    @Test @Config(qualifiers = "w411dp-h960dp-port-420dpi")
    public void oneHandedRight() throws Exception {
        YugiKbdSettings.get(Robolectric.buildActivity(Activity.class).setup().get()).hand = YugiKbdSettings.HAND_RIGHT;
        Rig g = new Rig();
        float minL = Float.MAX_VALUE;
        for (int i = 0; i < g.kb.keyCount(); i++) minL = Math.min(minL, g.kb.keyBounds(i)[0]);
        assertTrue("keys moved to the right", minL > YugiTheme.dp(g.a, 40));
        // a tap on the empty side brings the full keyboard back
        long t = SystemClock.uptimeMillis();
        float[] p = g.kb.panelBounds();
        g.kb.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10, (p[1] + p[3]) / 2, 0));
        save(g.root, "kbd2-onehand-before");
        assertEquals(YugiKbdSettings.HAND_NONE, YugiKbdSettings.get(g.a).hand);
    }

    @Test @Config(qualifiers = "w960dp-h411dp-land-420dpi")
    public void listeningAndSettingsSheet() throws Exception {
        Rig g = new Rig();
        g.kb.setListening(true);
        g.kb.setVoiceLevel(0.7f);
        g.kb.setVoicePreview("שלום יוגי מה נשמע");
        save(g.root, "kbd2-listening");
        g.kb.setListening(false);
        YugiKbdSettingsView v = YugiKbdSettingsView.show(g.a, g.kb, null);
        g.layout();
        idle();
        g.layout();
        android.graphics.Rect r = v.sheetRect();
        assertTrue("sheet above the keys", r.bottom <= g.kb.getTop() + g.kb.panelBounds()[1] + 1);
        save(g.root, "kbd2-settings");
    }

    @Test @Config(qualifiers = "w411dp-h960dp-port-420dpi")
    public void chatFieldUsesOurKeyboard() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        EditText et = new EditText(a);
        root.addView(et, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150, android.view.Gravity.BOTTOM));
        AtomicInteger sent = new AtomicInteger();
        int[] inset = {0};
        YugiFieldKeyboard fk = YugiFieldKeyboard.attach(a);
        fk.bind(et, sent::incrementAndGet);
        fk.setInsetListener(px -> inset[0] = px);
        assertFalse("the phone keyboard never opens", et.getShowSoftInputOnFocus());
        fk.show(et);
        View content = a.findViewById(android.R.id.content);
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        layout(content, dm.widthPixels, dm.heightPixels);
        fk.settleForTest();
        assertTrue(fk.isShown());
        assertEquals(Math.round(fk.view().pushHeight()), inset[0]);
        fk.engine().charKey(YugiEditTextSenderTest.key(fk.view().layout(), 'a'), null);
        assertEquals("ש", et.getText().toString());
        fk.engine().specialKey(YugiEditTextSenderTest.special(fk.view().layout(), android.view.KeyEvent.KEYCODE_ENTER));
        assertEquals(1, sent.get());
        fk.hide();
        fk.settleForTest();
        assertEquals(0, inset[0]);
    }

    // ---------------------------------------------------------------- helpers

    private static final class Rig {
        final Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root = new FrameLayout(a);
        final YugiKeyboard kb;
        final int w, h;

        Rig() {
            a.setContentView(root);
            View bg = new View(a);
            bg.setBackgroundColor(0xFF2A3348);
            root.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            kb = new YugiKeyboard(a, new YugiKeyEngine(new YugiKeyboardScreenshotTest.Rec()), () -> {});
            root.addView(kb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM));
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
            layout();
        }

        void layout() { YugiKbdSettingsTest.layout(a.findViewById(android.R.id.content), w, h); }
    }

    static int charKey(YugiKeyboard kb, char us) {
        for (int i = 0; i < kb.keyCount(); i++) {
            YugiKeyLayout.Key k = kb.layout().keys.get(i);
            if (k.kind == YugiKeyLayout.CHAR && k.us == us) return i;
        }
        throw new AssertionError("no key " + us);
    }

    static void layout(View v, int w, int h) {
        for (int i = 0; i < 2; i++) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            v.layout(0, 0, w, h);
            idle();
        }
    }

    static void idle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400)); }

    static void save(View v, String name) throws Exception {
        View c = ((Activity) v.getContext()).findViewById(android.R.id.content);
        Bitmap b = Bitmap.createBitmap(c.getWidth(), c.getHeight(), Bitmap.Config.ARGB_8888);
        c.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}

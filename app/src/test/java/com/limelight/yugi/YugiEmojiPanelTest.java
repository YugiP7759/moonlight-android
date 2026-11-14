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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The emoji picker inside the YugiPC keyboard: opens from the emoji key at the keyboard's height,
 * types emoji (with skin tones) and Backspace, keeps recents, scrolls, jumps by category, searches in
 * English and Hebrew with the keyboard's own keys, and ABC brings the keys back. Renders
 * app/build/yugi-screens/emoji-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class YugiEmojiPanelTest {
    private static final File OUT = new File("build/yugi-screens");

    static final class Rec implements YugiKeyEngine.Sender {
        final List<String> log = new ArrayList<>();
        @Override public void text(String s) { log.add("T:" + s); }
        @Override public void key(int code, boolean down, int mods) { if (down) log.add("K:" + code); }
    }

    private Activity a;
    private View content;
    private YugiKeyboardHost host;
    private YugiKeyboard kb;
    private Rec rec;
    private int w, h;

    private void setUp() {
        a = Robolectric.buildActivity(Activity.class).setup().get();
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        w = dm.widthPixels;
        h = dm.heightPixels;
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        FrameLayout desktop = new FrameLayout(a);
        View stream = new View(a);
        stream.setBackgroundColor(0xFF203550);
        desktop.addView(stream, match());
        root.addView(desktop, match());
        rec = new Rec();
        host = YugiKeyboardHost.attach(a, desktop, stream, rec);
        content = a.findViewById(android.R.id.content);
        layout();
        host.show();
        layout();
        host.settleForTest();
        layout();
        kb = host.view();
    }

    @Test @Config(qualifiers = "w750dp-h832dp-port-420dpi")
    public void innerPortrait() throws Exception { run("inner-portrait", true); }

    @Test @Config(qualifiers = "w411dp-h960dp-port-420dpi")
    public void coverPortrait() throws Exception { run("cover-portrait", false); }

    @Test @Config(qualifiers = "w832dp-h750dp-land-420dpi")
    public void innerLandscape() throws Exception { run("inner-landscape", false); }

    private void run(String name, boolean full) throws Exception {
        setUp();
        float keysHeight = kb.panelHeight();
        assertFalse(kb.isEmojiOpen());

        // the emoji key opens the picker at the keyboard's height
        tapKey(emojiKey());
        assertTrue(name + ": picker open", kb.isEmojiOpen());
        YugiEmojiPanel p = kb.emojiPanelForTest();
        assertNotNull(p);
        assertTrue(p.isLoaded());
        assertEquals(name + ": same height as the keys", keysHeight, kb.panelHeight(), 0.5f);
        assertTrue(name + ": no text typed by opening", rec.log.isEmpty());
        p.settleForTest();
        assertTrue(name + ": at least 6 columns", p.columnsForTest() >= 6);
        // no recents yet: it opens on the smileys
        assertEquals(name, 1, p.currentSection());
        save(name + "-grid");

        // tap 😀: typed, and remembered
        float[] c = p.cellCenterForTest(1, 0);
        tap(c[0], c[1]);
        assertEquals(name, "T:😀", last());
        assertEquals("😀", new YugiEmojiData.Prefs(a).recents().get(0));

        // Backspace in the picker
        float[] del = p.delCenterForTest();
        tap(del[0], del[1]);
        assertEquals(name, "K:" + KeyEvent.KEYCODE_DEL, last());

        // drag up: the grid scrolls
        float before = p.scrollYForTest();
        float[] g = p.gridForTest();
        drag(c[0], g[1] - 4, c[0], g[0] + 4);
        idle();
        p.settleForTest();
        assertTrue(name + ": scrolled " + before + " -> " + p.scrollYForTest(), p.scrollYForTest() > before + 100);

        // the "people" tab jumps there; 👋 long-pressed offers the skin tones
        float[] tab = p.tabCenterForTest(2);
        tap(tab[0], tab[1]);
        idleFor(800);
        p.settleForTest();
        assertEquals(name + ": people tab", 2, p.currentSection());
        YugiEmojiData d = YugiEmojiData.get(a);
        int wave = p.indexInSectionForTest(2, "👋");
        assertEquals("👋 opens the people", 0, wave);
        float[] tc = p.cellCenterForTest(2, wave);
        assertTrue(name + ": 👋 visible", tc[1] > g[0] && tc[1] < g[1]);
        long t0 = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, t0, tc[0], tc[1]);
        idleFor(500);
        String[] tones = p.toneOptionsForTest();
        assertNotNull(name + ": skin tones open on a long press", tones);
        assertEquals(6, tones.length);
        save(name + "-tones");
        // slide to the medium tone and lift
        float[] opt = p.toneCenterForTest(3);
        touch(MotionEvent.ACTION_MOVE, t0, opt[0], opt[1]);
        touch(MotionEvent.ACTION_UP, t0, opt[0], opt[1]);
        assertEquals(name, "T:👋🏽", last());
        assertNull(p.toneOptionsForTest());
        assertEquals(name + ": the tone is kept", "👋🏽", p.itemForTest(2, wave));
        assertEquals("👋🏽", new YugiEmojiData.Prefs(a).recents().get(0));

        // search: the field brings the keys back above which the results show
        float[] s = p.searchCenterForTest();
        tap(s[0], s[1]);
        assertTrue(name + ": search mode", kb.isEmojiSearch());
        layout();
        assertTrue(name + ": taller with the search field", kb.panelHeight() > keysHeight + 40);
        rec.log.clear();
        for (char ch : "heart".toCharArray()) tapKey(charKey(ch));
        assertTrue(name + ": letters went to the search, not the target: " + rec.log, rec.log.isEmpty());
        assertEquals("heart", p.query());
        assertTrue(p.results().length > 0);
        assertEquals("❤️", d.emoji[p.results()[0]]);
        save(name + "-search");
        float[] r0 = p.stripCenterForTest(0);
        tap(r0[0], r0[1]);
        assertEquals(name, "T:❤️", last());
        // Backspace edits the query
        tapKey(specialKey(KeyEvent.KEYCODE_DEL));
        assertEquals("hear", p.query());
        // Hebrew: "לב" (keys k, c)
        for (int i = 0; i < 4; i++) tapKey(specialKey(KeyEvent.KEYCODE_DEL));
        assertEquals("", p.query());
        host.engine().toggleLanguage();
        tapKey(charKey('k'));
        tapKey(charKey('c'));
        assertEquals("לב", p.query());
        boolean heart = false;
        for (int i : p.results()) if (d.emoji[i].equals("❤️")) heart = true;
        assertTrue(name + ": לב finds ❤️", heart);
        host.engine().toggleLanguage();
        save(name + "-search-he");

        // back to the grid: the height of the keys again
        float[] back = p.backCenterForTest();
        tap(back[0], back[1]);
        layout();
        assertFalse(kb.isEmojiSearch());
        assertTrue(kb.isEmojiOpen());
        assertEquals(name, keysHeight, kb.panelHeight(), 0.5f);

        // ABC: the keys
        p.settleForTest();
        float[] abc = p.abcCenterForTest();
        tap(abc[0], abc[1]);
        assertFalse(name + ": ABC closes the picker", kb.isEmojiOpen());
        rec.log.clear();
        tapKey(charKey('a'));
        assertEquals("T:a", last());

        // reopening shows the recents first
        tapKey(emojiKey());
        p.settleForTest();
        assertEquals(name + ": recents first", 0, p.currentSection());
        assertEquals("❤️", p.itemForTest(0, 0));
        assertEquals("👋🏽", p.itemForTest(0, 1));
        if (full) save(name + "-recents");

        // hiding the keyboard closes the picker
        host.hide();
        idle();
        host.settleForTest();
        kb.setVisibility(View.GONE);
        assertFalse(kb.isEmojiOpen());
    }

    // ---------------------------------------------------------------- helpers

    private String last() { return rec.log.isEmpty() ? "" : rec.log.get(rec.log.size() - 1); }

    private int emojiKey() {
        List<YugiKeyLayout.Key> keys = kb.layout().keys;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).kind == YugiKeyLayout.ACTION && keys.get(i).code == YugiKeyLayout.A_EMOJI) return i;
        }
        throw new AssertionError("no emoji key");
    }

    private int charKey(char us) {
        List<YugiKeyLayout.Key> keys = kb.layout().keys;
        for (int i = 0; i < keys.size(); i++) if (keys.get(i).kind == YugiKeyLayout.CHAR && keys.get(i).us == us) return i;
        throw new AssertionError("no key " + us);
    }

    private int specialKey(int code) {
        List<YugiKeyLayout.Key> keys = kb.layout().keys;
        for (int i = 0; i < keys.size(); i++) if (keys.get(i).kind == YugiKeyLayout.SPECIAL && keys.get(i).code == code) return i;
        throw new AssertionError("no special " + code);
    }

    private void tapKey(int i) {
        float[] b = kb.keyBounds(i);
        tap((b[0] + b[2]) / 2, (b[1] + b[3]) / 2);
    }

    private void tap(float x, float y) {
        long t = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, t, x, y);
        touch(MotionEvent.ACTION_UP, t, x, y);
        idle();
    }

    private void drag(float x0, float y0, float x1, float y1) {
        long t = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, t, x0, y0);
        for (int i = 1; i <= 10; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
            touch(MotionEvent.ACTION_MOVE, t, x0 + (x1 - x0) * i / 10f, y0 + (y1 - y0) * i / 10f);
        }
        touch(MotionEvent.ACTION_UP, t, x1, y1);
    }

    private void touch(int action, long downTime, float x, float y) {
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        kb.onTouchEvent(e);
        e.recycle();
    }

    private void layout() {
        for (int i = 0; i < 2; i++) {
            content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, w, h);
            idle();
        }
        if (host != null) host.settleForTest();
    }

    private static void idle() { idleFor(400); }

    private static void idleFor(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void save(String name) throws Exception {
        layout();
        Bitmap b = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        content.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(OUT, "emoji-" + name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}

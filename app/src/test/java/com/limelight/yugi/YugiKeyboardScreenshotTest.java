package com.limelight.yugi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.limelight.R;

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
import java.util.Arrays;
import java.util.List;

/**
 * Visual QA of the YugiPC keyboard on the Galaxy Z Fold 7 screens: renders PNGs to
 * app/build/yugi-screens (kb-*) and fails if a label does not fit its key, keys overlap or leave
 * the screen, the keyboard covers the toolbar, or the caret of the focused desktop field is not
 * visible above the keyboard after the desktop scrolled up with it.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class YugiKeyboardScreenshotTest {
    private static final File OUT = new File("build/yugi-screens");
    // the desktop as streamed (Fold 7 inner screen at 55%), and a text field near its bottom
    private static final float DESK_W = 1200, DESK_H = 1080, FIELD_Y = 930, CARET_H = 26;

    @Test @Config(qualifiers = "w960dp-h411dp-land-420dpi")
    public void coverLandscape() throws Exception { run("1-cover-landscape"); }

    @Test @Config(qualifiers = "w411dp-h960dp-port-420dpi")
    public void coverPortrait() throws Exception { run("2-cover-portrait"); }

    @Test @Config(qualifiers = "w832dp-h750dp-land-420dpi")
    public void innerLandscape() throws Exception { run("3-inner-landscape"); }

    @Test @Config(qualifiers = "w750dp-h832dp-port-420dpi")
    public void innerPortrait() throws Exception { run("4-inner-portrait"); }

    static final class Rec implements YugiKeyEngine.Sender {
        final List<String> log = new ArrayList<>();
        @Override public void text(String s) { log.add("T:" + s); }
        @Override public void key(int code, boolean down, int mods) { log.add((down ? "D:" : "U:") + code + "/" + mods); }
    }

    private void run(String name) throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        List<String> problems = new ArrayList<>();

        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        FrameLayout desktop = new FrameLayout(a);
        DesktopView stream = new DesktopView(a);
        desktop.addView(stream, match());
        root.addView(desktop, match());
        YugiChrome chrome = YugiChrome.attach(a, Arrays.asList(
                new YugiChrome.Button(R.drawable.yugi_ic_yugi, "יוגי", () -> {}),
                new YugiChrome.Button(R.drawable.yugi_ic_keyboard, "מקלדת", () -> {}),
                new YugiChrome.Button(R.drawable.yugi_ic_menu, "תפריט", () -> {})));
        Rec rec = new Rec();
        YugiKeyboardHost host = YugiKeyboardHost.attach(a, desktop, stream, rec);
        View content = a.findViewById(android.R.id.content);
        layout(content, w, h);

        // the server reports focus in the field near the bottom: the keyboard opens and the desktop scrolls
        host.caretForTest(true, FIELD_Y, CARET_H, DESK_W, DESK_H);
        assertTrue(name + ": keyboard opens on focus", host.isShown());
        layout(content, w, h);
        host.settleForTest();
        layout(content, w, h);
        YugiKeyboard kb = host.view();
        float panelTop = kb.getTop() + kb.getTranslationY() + kb.headroom();

        // caret visible above the keyboard, with the 24dp margin
        float caretBottom = YugiShift.caretBottomInView(FIELD_Y, CARET_H, DESK_H, stream.getY(), stream.getHeight())
                + desktop.getTranslationY();
        float caretTop = caretBottom - CARET_H * stream.getHeight() / DESK_H;
        if (caretBottom > panelTop - YugiTheme.dp(a, 24) + 1) problems.add(name + ": caret hidden by keyboard " + caretBottom + " > " + panelTop);
        if (caretTop < 0) problems.add(name + ": caret scrolled off the top");
        if (desktop.getTranslationY() >= 0) problems.add(name + ": desktop did not scroll");
        if (panelTop <= chrome.getReservedTop()) problems.add(name + ": keyboard covers the toolbar");
        checkKeys(name, a, kb, w, problems);
        save(content, "kb-" + name + "-en");

        // pressed key with its bubble (top letter row)
        kb.pressForTest(firstChar(kb, 'w'), true);
        save(content, "kb-" + name + "-bubble");
        kb.releaseAll();

        // Hebrew, Shift armed, Ctrl armed
        host.engine().toggleLanguage();
        host.engine().modDown(YugiKeyLayout.M_CTRL);
        host.engine().modUp(YugiKeyLayout.M_CTRL, 0);
        kb.invalidate();
        checkKeys(name + "-he", a, kb, w, problems);
        save(content, "kb-" + name + "-he-ctrl");
        host.engine().reset();
        host.engine().toggleLanguage();

        // long-press alternates on "e"
        kb.showAlternatesForTest(firstChar(kb, 'e'));
        save(content, "kb-" + name + "-alts");
        kb.releaseAll();

        // real touches: "h", "i" then Backspace go out in order; the headroom is not ours
        rec.log.clear();
        tap(kb, firstChar(kb, 'h'));
        tap(kb, firstChar(kb, 'i'));
        tap(kb, special(kb, android.view.KeyEvent.KEYCODE_DEL));
        int del = android.view.KeyEvent.KEYCODE_DEL;
        assertEquals(name, Arrays.asList("T:h", "T:i", "D:" + del + "/0", "U:" + del + "/0"), rec.log);
        long t = SystemClock.uptimeMillis();
        MotionEvent above = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, kb.headroom() / 2, 0);
        assertFalse(name + ": headroom passes touches through", kb.onTouchEvent(above));
        above.recycle();

        // focus leaves text: the keyboard closes and the desktop comes back down
        host.caretForTest(false, 0, 0, 0, 0);
        idle();
        host.settleForTest();
        assertFalse(name + ": closes when focus leaves text", host.isShown());
        assertEquals(name + ": desktop back in place", 0f, desktop.getTranslationY(), 0.5f);

        // a field already above the keyboard: no scroll at all
        host.caretForTest(true, 200, CARET_H, DESK_W, DESK_H);
        layout(content, w, h);
        host.settleForTest();
        assertEquals(name + ": no scroll when the field is visible", 0f, desktop.getTranslationY(), 0.5f);

        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    // ---------------------------------------------------------------- checks

    private static void checkKeys(String name, Activity a, YugiKeyboard kb, int w, List<String> problems) {
        YugiKeyLayout l = kb.layout();
        Paint p = new Paint(kb.textPaintForTest());
        float pad = YugiTheme.dp(a, 3);
        List<RectF> rects = new ArrayList<>();
        for (int i = 0; i < kb.keyCount(); i++) {
            float[] b = kb.keyBounds(i);
            RectF r = new RectF(b[0], b[1], b[2], b[3]);
            if (r.left < 0 || r.right > w + 0.5f) problems.add(name + ": key " + i + " leaves the screen");
            if (r.height() < YugiTheme.dp(a, 30)) problems.add(name + ": key " + i + " lower than 30dp");
            for (RectF o : rects) if (RectF.intersects(o, r)) problems.add(name + ": keys overlap at " + i);
            rects.add(r);
            YugiKeyLayout.Key k = l.keys.get(i);
            for (boolean heb : new boolean[]{false, true}) for (boolean sh : new boolean[]{false, true}) for (boolean fn : new boolean[]{false, true}) {
                String s = YugiKeyLayout.label(k, heb, sh, false, fn);
                if (s == null) continue;
                p.setTextSize(fn && k.fnLabel != null ? kb.fnSizeOf(i) : kb.textSizeOf(i));
                float tw = p.measureText(s);
                if (tw > r.width() - 2 * pad) problems.add(name + ": label '" + s + "' (" + tw + "px) wider than key " + r.width());
                if (-p.ascent() + p.descent() > r.height()) problems.add(name + ": label '" + s + "' taller than key");
            }
        }
    }

    private static int firstChar(YugiKeyboard kb, char us) {
        List<YugiKeyLayout.Key> keys = kb.layout().keys;
        for (int i = 0; i < keys.size(); i++) if (keys.get(i).kind == YugiKeyLayout.CHAR && keys.get(i).us == us) return i;
        throw new AssertionError("no key " + us);
    }

    private static int special(YugiKeyboard kb, int code) {
        List<YugiKeyLayout.Key> keys = kb.layout().keys;
        for (int i = 0; i < keys.size(); i++) if (keys.get(i).kind == YugiKeyLayout.SPECIAL && keys.get(i).code == code) return i;
        throw new AssertionError("no special " + code);
    }

    private static void tap(YugiKeyboard kb, int i) {
        float[] b = kb.keyBounds(i);
        float x = (b[0] + b[2]) / 2, y = (b[1] + b[3]) / 2;
        long t = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        kb.onTouchEvent(d);
        kb.onTouchEvent(u);
        d.recycle();
        u.recycle();
    }

    // ---------------------------------------------------------------- scene

    /** Stand-in for the stream: a desktop with a text field near the bottom and its caret. */
    static final class DesktopView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        DesktopView(Activity a) { super(a); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float sy = h / DESK_H;
            p.setShader(new LinearGradient(0, 0, w, h, 0xFF1F3B5C, 0xFF3A2A4E, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            p.setColor(0x22FFFFFF);
            for (int y = 0; y < h; y += h / 8) c.drawLine(0, y, w, y, p);
            // the field: white box around the caret line, "Hello" and a caret
            float top = (FIELD_Y - 10) * sy, bottom = (FIELD_Y + CARET_H + 10) * sy;
            p.setColor(0xFFF5F5F5);
            c.drawRoundRect(w * 0.1f, top, w * 0.9f, bottom, 8, 8, p);
            p.setColor(0xFF202020);
            p.setTextSize((CARET_H * 0.8f) * sy);
            c.drawText("שלום Hello", w * 0.12f, (FIELD_Y + CARET_H * 0.8f) * sy, p);
            float cx = w * 0.12f + p.measureText("שלום Hello") + 4;
            p.setColor(0xFF1A73E8);
            c.drawRect(cx, FIELD_Y * sy, cx + 3, (FIELD_Y + CARET_H) * sy, p);
        }
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static void layout(View v, int w, int h) {
        for (int i = 0; i < 2; i++) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            v.layout(0, 0, w, h);
            idle();
        }
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }

    private static void save(View v, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}

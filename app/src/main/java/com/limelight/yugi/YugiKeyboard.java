package com.limelight.yugi;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The YugiPC keyboard: one custom View (no Android IME), drawn directly on the canvas.
 * <p>
 * Behaves like the Windows touch keyboard (see {@link YugiKeyEngine}); looks and feels like Apple's:
 * pressed state in the same frame as the touch, a key bubble above the finger, long-press
 * alternates you slide to, a light haptic tick, a quiet fade-out on release.
 * <p>
 * Performance: every Paint, rect, label and text size is prepared on layout. {@link #onDraw} and the
 * touch path never allocate, and nothing animates while the keyboard is idle.
 * The top {@link #headroom()} pixels are transparent room for bubbles; touches there fall through
 * to the desktop below.
 */
@SuppressLint("ViewConstructor")
public class YugiKeyboard extends View {
    public interface Host {
        void onHideRequested();
        /** Any key touched (used to keep the toolbar quiet, etc.). */
        default void onKeyTouched() {}
    }

    private static final int MAXP = 10;               // tracked pointers
    private static final long LONG_PRESS_MS = 380;
    private static final long REPEAT_DELAY_MS = 420;
    private static final long REPEAT_RATE_MS = 45;
    private static final long FADE_MS = 140;

    // palette (YugiTheme family)
    private static final int PANEL = 0xFF0F131A; // opaque: the desktop's edge never shows through
    private static final int KEY = 0xFF2B313C;
    private static final int KEY_DARK = 0xFF1C212A;
    private static final int KEY_PRESSED = 0xFF4B5466;
    private static final int KEY_SHADOW = 0x66000000;
    private static final int MOD_ON = 0xFF3B4A78;
    private static final int BUBBLE = 0xFF3A4150;

    private final YugiKeyEngine engine;
    private final Host host;
    private final YugiKeyLayout full = YugiKeyLayout.full();
    private final YugiKeyLayout compact = YugiKeyLayout.compact();
    private YugiKeyLayout layout = full;

    // geometry (px)
    private final float dp;
    private float rowH, gap, padX, padTop, padBottom, radius, headroom;
    private float[] kl = new float[0], kt = new float[0], kr = new float[0], kb = new float[0];
    private float[] textSize = new float[0];
    private float[] fnSize = new float[0];
    private float hintSize, bubbleTextSize;
    private int screenH;

    // per-key visual state
    private float[] pressT = new float[0];      // 1 while held, fades after release
    private long[] releasedAt = new long[0];
    private int[] holdCount = new int[0];

    // per-pointer state (indexed by pointer id, ids < MAXP)
    private final int[] pKey = new int[MAXP];
    private final boolean[] pCommitted = new boolean[MAXP];
    private final int[] pMods = new int[MAXP];
    private int altPointer = -1, altKey = -1, altSel = -1;
    private String[] alts;
    private int bubblePointer = -1;
    private int repeatPointer = -1;
    private int longPressPointer = -1;

    // drawing objects, created once
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairPaint = new Paint();
    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private final Runnable longPress = this::onLongPress;
    private final Runnable repeatTick = this::onRepeat;

    public YugiKeyboard(Context c, YugiKeyEngine engine, Host host) {
        super(c);
        this.engine = engine;
        this.host = host;
        dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, c.getResources().getDisplayMetrics());
        Typeface medium = YugiTheme.font(c, 500);
        Typeface regular = YugiTheme.font(c, 400);
        panelPaint.setColor(PANEL);
        hairPaint.setColor(YugiTheme.STROKE);
        shadowPaint.setColor(KEY_SHADOW);
        textPaint.setColor(YugiTheme.TEXT);
        textPaint.setTypeface(medium);
        textPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setColor(YugiTheme.TEXT_FAINT);
        hintPaint.setTypeface(regular);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        bubblePaint.setColor(BUBBLE);
        if (Build.VERSION.SDK_INT >= 28) bubblePaint.setShadowLayer(10 * dp, 0, 3 * dp, 0x80000000);
        for (int i = 0; i < MAXP; i++) pKey[i] = -1;
        setHapticFeedbackEnabled(true);
        setClickable(true);
        setContentDescription("מקלדת");
        // the panel is opaque enough that the stream does not need to be blurred behind it
        // (RenderEffect cannot blur the SurfaceView layer underneath anyway)
    }

    // ------------------------------------------------------------------ sizing

    /** Total height (headroom + keys) for a window of this size. */
    public int preferredHeight(int windowW, int windowH) {
        configure(windowW, windowH);
        return Math.round(headroom + panelHeight());
    }

    /** Height of the visible keyboard panel (without the transparent headroom). */
    public float panelHeight() {
        float h = padTop + padBottom;
        for (int i = 0; i < layout.rows; i++) h += rowH * layout.rowHeights[i] + (i > 0 ? gap : 0);
        return h;
    }

    public float headroom() { return headroom; }

    private void configure(int w, int h) {
        screenH = h;
        YugiKeyLayout want = w / dp < 600 ? compact : full;
        if (want != layout) {
            layout = want;
            engine.reset();
        }
        rowH = clamp(h * 0.082f, 36 * dp, 52 * dp);
        gap = Math.max(4 * dp, rowH * 0.13f);
        padX = 6 * dp;
        padTop = 8 * dp;
        padBottom = 10 * dp;
        radius = Math.min(9 * dp, rowH * 0.2f);
        headroom = rowH * 1.45f + 8 * dp;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = getRootView() != null && getRootView().getHeight() > 0 ? getRootView().getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        setMeasuredDimension(w, preferredHeight(w, h));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        layoutKeys(r - l); // also when only the layout (full/compact) or the row height changed
    }

    private void layoutKeys(int w) {
        int n = layout.keys.size();
        if (kl.length != n) {
            kl = new float[n]; kt = new float[n]; kr = new float[n]; kb = new float[n];
            textSize = new float[n];
            fnSize = new float[n];
            pressT = new float[n];
            releasedAt = new long[n];
            holdCount = new int[n];
        }
        float unit = (w - 2 * padX + gap) / layout.widthUnits;
        float[] rowTop = new float[layout.rows];
        float y = headroom + padTop;
        for (int i = 0; i < layout.rows; i++) {
            rowTop[i] = y;
            y += rowH * layout.rowHeights[i] + gap;
        }
        float base = rowH * 0.42f;
        hintSize = rowH * 0.22f;
        bubbleTextSize = rowH * 0.7f;
        for (int i = 0; i < n; i++) {
            YugiKeyLayout.Key k = layout.keys.get(i);
            kl[i] = padX + k.x * unit;
            kr[i] = padX + (k.x + k.units) * unit - gap;
            kt[i] = rowTop[k.row];
            kb[i] = kt[i] + rowH * layout.rowHeights[k.row];
            boolean glyph = k.label != null && k.label.codePointCount(0, k.label.length()) == 1; // ⇧ ⌫ ← ⌨
            float size = (k.kind == YugiKeyLayout.CHAR && k.us != ' ') || glyph ? base : rowH * 0.3f;
            if (layout.rowHeights[k.row] < 1f) size *= 0.92f;
            // fit the widest label this key can show (no text ever touches the key edge)
            float room = (kr[i] - kl[i]) - 8 * dp;
            textPaint.setTextSize(size);
            float widest = Math.max(width(k.label), Math.max(width(k.enLower), width(k.enUpper)));
            widest = Math.max(widest, width(k.he));
            if (k.kind == YugiKeyLayout.CHAR && k.us == ' ') widest = Math.max(width("English"), width("עברית"));
            if (k.kind == YugiKeyLayout.ACTION) widest = Math.max(widest, Math.max(width("EN"), width("עב")));
            if (widest > room && widest > 0) size *= room / widest;
            textSize[i] = size;
            // F-keys fit on their own, so "0" keeps the size of the other digits
            float fs = rowH * 0.3f;
            textPaint.setTextSize(fs);
            float fw = width(k.fnLabel);
            fnSize[i] = fw > room && fw > 0 ? fs * room / fw : fs;
        }
    }

    private float width(String s) { return s == null ? 0 : textPaint.measureText(s); }

    /** Key bounds, for tests: {left, top, right, bottom}. */
    public float[] keyBounds(int i) { return new float[]{kl[i], kt[i], kr[i], kb[i]}; }
    public int keyCount() { return layout.keys.size(); }
    public YugiKeyLayout layout() { return layout; }
    public float textSizeOf(int i) { return textSize[i]; }
    public float fnSizeOf(int i) { return fnSize[i]; }
    public Paint textPaintForTest() { return textPaint; }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        float top = headroom;
        c.drawRect(0, top, w, getHeight(), panelPaint);
        c.drawRect(0, top, w, top + Math.max(1, dp * 0.6f), hairPaint);

        boolean heb = engine.hebrew, shift = engine.shift(), caps = engine.caps(), fn = engine.fn();
        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        int n = layout.keys.size();
        for (int i = 0; i < n; i++) {
            YugiKeyLayout.Key k = layout.keys.get(i);
            float t = pressT[i];
            if (holdCount[i] == 0 && t > 0) {
                t = 1f - (now - releasedAt[i]) / (float) FADE_MS;
                if (t <= 0) t = 0; else animating = true;
                pressT[i] = t;
            }
            boolean modOn = k.kind == YugiKeyLayout.MOD && engine.isActive(k.code);
            int base = modOn ? MOD_ON : (k.kind == YugiKeyLayout.CHAR ? KEY : KEY_DARK);
            r.set(kl[i], kt[i] + dp, kr[i], kb[i] + dp);
            c.drawRoundRect(r, radius, radius, shadowPaint);
            keyPaint.setColor(t > 0 ? blend(base, KEY_PRESSED, t) : base);
            r.set(kl[i], kt[i], kr[i], kb[i]);
            c.drawRoundRect(r, radius, radius, keyPaint);

            String label = YugiKeyLayout.label(k, heb, shift, caps, fn);
            if (label == null) continue;
            textPaint.setTextSize(fn && k.fnLabel != null ? fnSize[i] : textSize[i]);
            textPaint.setColor(modOn ? YugiTheme.ACCENT : (k.kind == YugiKeyLayout.CHAR && k.us != ' ' ? YugiTheme.TEXT : YugiTheme.TEXT_DIM));
            float cx = (kl[i] + kr[i]) * 0.5f, cy = (kt[i] + kb[i]) * 0.5f;
            c.drawText(label, cx, cy - (textPaint.ascent() + textPaint.descent()) * 0.5f, textPaint);
            if (k.hint != null && !fn && !(heb && k.he != null && !k.he.equals(k.enLower)) && rowH > 30 * dp) {
                hintPaint.setTextSize(hintSize);
                c.drawText(k.hint, kr[i] - 4 * dp, kt[i] + 3 * dp - hintPaint.ascent(), hintPaint);
            }
            if (modOn && engine.isLocked(k.code)) {
                // locked (Caps, Fn): a small bar under the label
                float bw = Math.min(14 * dp, (kr[i] - kl[i]) * 0.3f);
                r.set(cx - bw / 2, kb[i] - 6 * dp, cx + bw / 2, kb[i] - 4 * dp);
                hintPaint.setColor(YugiTheme.ACCENT);
                c.drawRoundRect(r, dp, dp, hintPaint);
                hintPaint.setColor(YugiTheme.TEXT_FAINT);
            }
        }

        if (altPointer >= 0 && alts != null) drawAlternates(c);
        else if (bubblePointer >= 0 && pKey[bubblePointer] >= 0) drawBubble(c, pKey[bubblePointer], heb, shift, caps, fn);

        if (animating) postInvalidateOnAnimation();
    }

    private void drawBubble(Canvas c, int i, boolean heb, boolean shift, boolean caps, boolean fn) {
        YugiKeyLayout.Key k = layout.keys.get(i);
        if (k.kind != YugiKeyLayout.CHAR || k.us == ' ') return;
        String label = YugiKeyLayout.label(k, heb, shift, caps, fn);
        if (label == null) return;
        float kw = kr[i] - kl[i];
        float bw = Math.max(kw * 1.3f, rowH * 1.05f);
        float bh = rowH * 1.3f;
        float cx = clamp((kl[i] + kr[i]) * 0.5f, bw / 2 + 2 * dp, getWidth() - bw / 2 - 2 * dp);
        float bottom = kt[i] - 6 * dp;
        r.set(cx - bw / 2, bottom - bh, cx + bw / 2, bottom);
        c.drawRoundRect(r, radius * 1.4f, radius * 1.4f, bubblePaint);
        textPaint.setTextSize(fn && k.fnLabel != null ? bubbleTextSize * 0.6f : bubbleTextSize);
        textPaint.setColor(YugiTheme.TEXT);
        c.drawText(label, cx, r.centerY() - (textPaint.ascent() + textPaint.descent()) * 0.5f, textPaint);
    }

    private float altCell() { return Math.max(rowH * 0.95f, 40 * dp); }

    private float altLeft() {
        float cell = altCell();
        float total = cell * alts.length;
        float cx = (kl[altKey] + kr[altKey]) * 0.5f;
        return clamp(cx - cell / 2, 2 * dp, getWidth() - total - 2 * dp);
    }

    private void drawAlternates(Canvas c) {
        float cell = altCell(), left = altLeft();
        float bottom = kt[altKey] - 6 * dp, h = rowH * 1.15f;
        r.set(left, bottom - h, left + cell * alts.length, bottom);
        c.drawRoundRect(r, radius * 1.4f, radius * 1.4f, bubblePaint);
        textPaint.setTextSize(rowH * 0.48f);
        for (int j = 0; j < alts.length; j++) {
            float x0 = left + j * cell;
            if (j == altSel) {
                r.set(x0 + 3 * dp, bottom - h + 3 * dp, x0 + cell - 3 * dp, bottom - 3 * dp);
                keyPaint.setColor(YugiTheme.ACCENT);
                c.drawRoundRect(r, radius, radius, keyPaint);
            }
            textPaint.setColor(j == altSel ? YugiTheme.BRAND_BG : YugiTheme.TEXT);
            c.drawText(alts[j], x0 + cell / 2, bottom - h / 2 - (textPaint.ascent() + textPaint.descent()) * 0.5f, textPaint);
        }
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ------------------------------------------------------------------ touch

    private int hit(float x, float y) {
        if (y < headroom) return -1;
        int n = layout.keys.size();
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            // gaps belong to the nearest key: distance to the key rect
            float dx = x < kl[i] ? kl[i] - x : (x > kr[i] ? x - kr[i] : 0);
            float dy = y < kt[i] ? kt[i] - y : (y > kb[i] ? y - kb[i] : 0);
            float d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (e.getY() < headroom) return false; // the desktop above stays touchable
                // fall through
            case MotionEvent.ACTION_POINTER_DOWN:
                if (id < MAXP) down(id, e.getX(idx), e.getY(idx));
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int p = 0; p < e.getPointerCount(); p++) {
                    int pid = e.getPointerId(p);
                    if (pid < MAXP) move(pid, e.getX(p), e.getY(p));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (id < MAXP) up(id, e.getEventTime(), false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;
            default:
                return true;
        }
    }

    private void down(int id, float x, float y) {
        int i = hit(x, y);
        if (i < 0) return;
        // rolling typing: a new finger commits the character still under an earlier finger
        for (int p = 0; p < MAXP; p++) {
            if (p != id && pKey[p] >= 0 && !pCommitted[p] && layout.keys.get(pKey[p]).kind == YugiKeyLayout.CHAR && p != altPointer) {
                engine.charKey(layout.keys.get(pKey[p]), null);
                pCommitted[p] = true;
            }
        }
        cancelTimers();
        pKey[id] = i;
        pCommitted[id] = false;
        press(i);
        performHapticFeedback(Build.VERSION.SDK_INT >= 27 ? HapticFeedbackConstants.KEYBOARD_PRESS : HapticFeedbackConstants.KEYBOARD_TAP);
        host.onKeyTouched();
        YugiKeyLayout.Key k = layout.keys.get(i);
        switch (k.kind) {
            case YugiKeyLayout.CHAR:
                bubblePointer = id;
                String out = YugiKeyLayout.output(k, engine.hebrew, engine.shift(), engine.caps());
                if (!engine.fn() && YugiKeyLayout.alternates(out) != null) {
                    longPressPointer = id;
                    postDelayed(longPress, LONG_PRESS_MS);
                }
                break;
            case YugiKeyLayout.SPECIAL:
                pMods[id] = engine.specialKey(k);
                pCommitted[id] = true;
                if (k.repeat) {
                    repeatPointer = id;
                    postDelayed(repeatTick, REPEAT_DELAY_MS);
                }
                break;
            case YugiKeyLayout.MOD:
                engine.modDown(k.code);
                break;
            default:
                break;
        }
        invalidate();
    }

    private void move(int id, float x, float y) {
        int cur = pKey[id];
        if (cur < 0) return;
        if (id == altPointer) {
            float cell = altCell(), left = altLeft();
            int sel = (int) Math.floor((x - left) / cell);
            sel = Math.max(0, Math.min(alts.length - 1, sel));
            if (sel != altSel) {
                altSel = sel;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
            }
            return;
        }
        YugiKeyLayout.Key k = layout.keys.get(cur);
        if (k.kind != YugiKeyLayout.CHAR || pCommitted[id]) return;
        int i = hit(x, y);
        if (i >= 0 && i != cur && layout.keys.get(i).kind == YugiKeyLayout.CHAR) {
            // sliding over the keys moves the selection (commit happens on release)
            release(cur);
            pKey[id] = i;
            press(i);
            if (longPressPointer == id) {
                removeCallbacks(longPress);
                String out = YugiKeyLayout.output(layout.keys.get(i), engine.hebrew, engine.shift(), engine.caps());
                if (!engine.fn() && YugiKeyLayout.alternates(out) != null) postDelayed(longPress, LONG_PRESS_MS);
            }
            invalidate();
        }
    }

    private void up(int id, long time, boolean cancel) {
        int i = pKey[id];
        if (i < 0) return;
        pKey[id] = -1;
        YugiKeyLayout.Key k = layout.keys.get(i);
        if (id == repeatPointer) { removeCallbacks(repeatTick); repeatPointer = -1; }
        if (id == longPressPointer) { removeCallbacks(longPress); longPressPointer = -1; }
        switch (k.kind) {
            case YugiKeyLayout.CHAR:
                if (!cancel && !pCommitted[id]) {
                    String alt = (id == altPointer && altSel >= 0) ? alts[altSel] : null;
                    engine.charKey(k, alt);
                }
                break;
            case YugiKeyLayout.MOD:
                if (!cancel) engine.modUp(k.code, time);
                break;
            case YugiKeyLayout.ACTION:
                if (!cancel) {
                    if (k.code == YugiKeyLayout.A_LANG) engine.toggleLanguage();
                    else if (k.code == YugiKeyLayout.A_HIDE) host.onHideRequested();
                }
                break;
            default:
                break;
        }
        if (id == altPointer) { altPointer = -1; alts = null; altKey = -1; altSel = -1; }
        if (id == bubblePointer) bubblePointer = -1;
        release(i);
        invalidate();
    }

    private void press(int i) {
        holdCount[i]++;
        pressT[i] = 1f;
    }

    private void release(int i) {
        if (holdCount[i] > 0) holdCount[i]--;
        if (holdCount[i] == 0) releasedAt[i] = SystemClock.uptimeMillis();
    }

    private void onLongPress() {
        int id = longPressPointer;
        longPressPointer = -1;
        if (id < 0 || pKey[id] < 0 || pCommitted[id]) return;
        YugiKeyLayout.Key k = layout.keys.get(pKey[id]);
        String out = YugiKeyLayout.output(k, engine.hebrew, engine.shift(), engine.caps());
        String[] a = YugiKeyLayout.alternates(out);
        if (a == null) return;
        alts = a;
        altKey = pKey[id];
        altPointer = id;
        altSel = 0;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void onRepeat() {
        int id = repeatPointer;
        if (id < 0 || pKey[id] < 0) return;
        engine.repeat(layout.keys.get(pKey[id]), pMods[id]);
        postDelayed(repeatTick, REPEAT_RATE_MS);
    }

    private void cancelTimers() {
        removeCallbacks(longPress);
        longPressPointer = -1;
    }

    /** Drops all touches (keyboard hidden mid-press). */
    public void releaseAll() {
        removeCallbacks(longPress);
        removeCallbacks(repeatTick);
        repeatPointer = longPressPointer = -1;
        for (int p = 0; p < MAXP; p++) pKey[p] = -1;
        for (int i = 0; i < pressT.length; i++) { pressT[i] = 0; holdCount[i] = 0; }
        altPointer = bubblePointer = -1;
        alts = null;
        engine.reset();
        invalidate();
    }

    // ---------------------------------------------------------------- test hooks

    /** Simulates a finger held on key i (screenshots: pressed state and bubble). */
    public void pressForTest(int i, boolean bubble) {
        pKey[0] = i;
        press(i);
        if (bubble) bubblePointer = 0;
        invalidate();
    }

    public void showAlternatesForTest(int i) {
        pKey[0] = i;
        longPressPointer = 0;
        onLongPress();
    }
}

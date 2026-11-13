package com.limelight.yugi;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioManager;
import android.text.TextPaint;
import android.text.TextUtils;
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
        /** The mic key: start or stop dictation in the keyboard's language. */
        default void onMicRequested(boolean hebrew) {}
        /** Long press on the language key. */
        default void onSettingsRequested() {}
        /** Size, mode or position changed (settings, drag): re-place what rides on the keyboard. */
        default void onGeometryChanged() {}
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
    // settings-driven geometry: the panel rect and the key area inside it
    private YugiKbdSettings set;
    private int mode = YugiKbdSettings.DOCKED; // effective (split needs a wide screen)
    private int hand = YugiKbdSettings.HAND_NONE;
    private float panelL, panelT, panelR, panelB, keysL, keysR, handleH, stripL, stripR;
    private float splitGapL = -1, splitGapR = -1;

    // dictation
    private boolean listening;
    private float voiceLevel;
    private String voicePreview;
    private final TextPaint previewPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path micPath = new Path();

    // floating drag
    private int dragPointer = -1;
    private float dragDX, dragDY;
    private final AudioManager audio;
    private final YugiKbdSettings.Listener settingsListener;

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
        previewPaint.setColor(YugiTheme.TEXT);
        previewPaint.setTypeface(regular);
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        set = YugiKbdSettings.get(c);
        settingsListener = s -> {
            requestLayout();
            invalidate();
            host.onGeometryChanged();
        };
        for (int i = 0; i < MAXP; i++) pKey[i] = -1;
        setHapticFeedbackEnabled(true);
        setClickable(true);
        setContentDescription("מקלדת");
        // the panel is opaque enough that the stream does not need to be blurred behind it
        // (RenderEffect cannot blur the SurfaceView layer underneath anyway)
    }

    // ------------------------------------------------------------------ sizing

    /**
     * Total view height for a window of this size: headroom + panel when docked; the whole window when
     * floating (touches outside the panel fall through).
     */
    public int preferredHeight(int windowW, int windowH) {
        configure(windowW, windowH);
        return mode == YugiKbdSettings.FLOATING ? windowH : Math.round(headroom + panelHeight());
    }

    /** Height of the visible keyboard panel (without the transparent headroom). */
    public float panelHeight() {
        float h = padTop + padBottom + handleH;
        for (int i = 0; i < layout.rows; i++) h += rowH * layout.rowHeights[i] + (i > 0 ? gap : 0);
        return h;
    }

    /** How much of the window bottom the keyboard covers (0 when floating: nothing is pushed up). */
    public float pushHeight() { return mode == YugiKbdSettings.FLOATING ? 0f : panelHeight(); }

    public float headroom() { return headroom; }
    public int effectiveMode() { return mode; }
    /** Panel rect in view pixels: {left, top, right, bottom}. */
    public float[] panelBounds() { return new float[]{panelL, panelT, panelR, panelB}; }
    /** Split mode: the empty middle {left, right}, or null. */
    public float[] splitGap() { return splitGapL < 0 ? null : new float[]{splitGapL, splitGapR}; }

    private void configure(int w, int h) {
        screenH = h;
        set = YugiKbdSettings.get(getContext());
        float wdp = w / dp;
        mode = set.mode;
        if (mode == YugiKbdSettings.SPLIT && wdp < 600) mode = YugiKbdSettings.DOCKED;
        hand = mode == YugiKbdSettings.DOCKED ? set.hand : YugiKbdSettings.HAND_NONE;
        boolean floating = mode == YugiKbdSettings.FLOATING;
        rowH = clamp(clamp(h * 0.082f, 36 * dp, 52 * dp) * set.height, 30 * dp, 68 * dp);
        if (floating) rowH = Math.min(rowH, 44 * dp * set.height);
        gap = Math.max(4 * dp, rowH * 0.13f);
        padX = 6 * dp;
        padTop = 8 * dp;
        padBottom = 10 * dp;
        handleH = floating ? 20 * dp : 0;
        radius = Math.min(9 * dp, rowH * 0.2f);
        headroom = rowH * 1.45f + 8 * dp;
        // horizontal extent of the panel and of the keys
        float pw = floating ? Math.min(w - 16 * dp, 400 * dp) : w;
        float keysW = pw - 2 * padX;
        if (hand != YugiKbdSettings.HAND_NONE) keysW = Math.min(w - 52 * dp, Math.max(330 * dp, w * 0.5f)) - 2 * padX;
        YugiKeyLayout want = mode != YugiKbdSettings.SPLIT && keysW / dp < 590 ? compact : full;
        if (want != layout) {
            layout = want;
            engine.reset();
        }
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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        YugiKbdSettings.addListener(settingsListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        YugiKbdSettings.removeListener(settingsListener);
        super.onDetachedFromWindow();
    }

    /** Places the panel for the current mode (floating: at the saved spot, kept on screen). */
    private void placePanel(int w) {
        float ph = panelHeight();
        stripL = stripR = -1;
        if (mode == YugiKbdSettings.FLOATING) {
            int viewH = getHeight() > 0 ? getHeight() : screenH;
            float pw = Math.min(w - 16 * dp, 400 * dp);
            panelL = clamp(set.floatX * w - pw / 2, 4 * dp, w - pw - 4 * dp);
            panelR = panelL + pw;
            panelT = clamp(set.floatY * viewH - ph / 2, rowH * 1.45f, viewH - ph - 4 * dp);
            panelB = panelT + ph;
            keysL = panelL + padX;
            keysR = panelR - padX;
            return;
        }
        panelL = 0;
        panelR = w;
        panelT = headroom;
        panelB = headroom + ph;
        keysL = padX;
        keysR = w - padX;
        if (hand != YugiKbdSettings.HAND_NONE) {
            float kw = Math.min(w - 52 * dp, Math.max(330 * dp, w * 0.5f)) - 2 * padX;
            if (hand == YugiKbdSettings.HAND_RIGHT) {
                keysL = keysR - kw;
                stripL = 0;
                stripR = keysL - padX;
            } else {
                keysR = keysL + kw;
                stripL = keysR + padX;
                stripR = w;
            }
        }
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
        placePanel(w);
        float keysW = keysR - keysL;
        float unit = (keysW + gap) / layout.widthUnits;
        float[] rowTop = new float[layout.rows];
        float y = panelT + handleH + padTop;
        for (int i = 0; i < layout.rows; i++) {
            rowTop[i] = y;
            y += rowH * layout.rowHeights[i] + gap;
        }
        // split: each row breaks at the key edge nearest its middle; the halves keep the outer edges
        boolean split = mode == YugiKbdSettings.SPLIT;
        float halfW = split ? (keysW - keysW * 0.26f) / 2 : 0;
        float[] rowSplit = new float[layout.rows];
        if (split) {
            float mid = layout.widthUnits / 2;
            for (int r0 = 0; r0 < layout.rows; r0++) {
                float best = mid, bestD = Float.MAX_VALUE;
                for (YugiKeyLayout.Key k : layout.keys) {
                    if (k.row != r0) continue;
                    float edge = k.x + k.units;
                    if (edge >= layout.widthUnits - 0.01f) continue;
                    if (Math.abs(edge - mid) < bestD) { bestD = Math.abs(edge - mid); best = edge; }
                }
                rowSplit[r0] = best;
            }
            splitGapL = keysL + halfW;
            splitGapR = keysR - halfW;
        } else {
            splitGapL = splitGapR = -1;
        }
        float base = rowH * 0.42f * set.font;
        hintSize = rowH * 0.22f * set.font;
        bubbleTextSize = rowH * 0.7f * set.font;
        for (int i = 0; i < n; i++) {
            YugiKeyLayout.Key k = layout.keys.get(i);
            if (split) {
                float s0 = rowSplit[k.row];
                if (k.x + k.units / 2 <= s0) {
                    float u = (halfW + gap) / s0;
                    kl[i] = keysL + k.x * u;
                    kr[i] = keysL + (k.x + k.units) * u - gap;
                } else {
                    float u = (halfW + gap) / (layout.widthUnits - s0);
                    float b0 = keysR - halfW;
                    kl[i] = b0 + (k.x - s0) * u;
                    kr[i] = b0 + (k.x + k.units - s0) * u - gap;
                }
            } else {
                kl[i] = keysL + k.x * unit;
                kr[i] = keysL + (k.x + k.units) * unit - gap;
            }
            kt[i] = rowTop[k.row];
            kb[i] = kt[i] + rowH * layout.rowHeights[k.row];
            boolean glyph = k.label != null && k.label.codePointCount(0, k.label.length()) == 1; // ⇧ ⌫ ← ⌨
            float size = (k.kind == YugiKeyLayout.CHAR && k.us != ' ') || glyph ? base : rowH * 0.3f * set.font;
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
            float fs = rowH * 0.3f * set.font;
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
        float top = panelT;
        if (mode == YugiKbdSettings.FLOATING) {
            r.set(panelL, panelT, panelR, panelB);
            float pr = radius * 2f;
            r.offset(0, 4 * dp);
            c.drawRoundRect(r, pr, pr, shadowPaint);
            r.offset(0, -4 * dp);
            c.drawRoundRect(r, pr, pr, panelPaint);
            // grabber
            float gw = 36 * dp, cx0 = (panelL + panelR) / 2, gy = panelT + handleH * 0.55f;
            r.set(cx0 - gw / 2, gy - 2.5f * dp, cx0 + gw / 2, gy + 2.5f * dp);
            hintPaint.setColor(dragPointer >= 0 ? YugiTheme.TEXT_DIM : YugiTheme.TEXT_FAINT);
            c.drawRoundRect(r, 3 * dp, 3 * dp, hintPaint);
            hintPaint.setColor(YugiTheme.TEXT_FAINT);
        } else {
            c.drawRect(0, top, w, getHeight(), panelPaint);
            c.drawRect(0, top, w, top + Math.max(1, dp * 0.6f), hairPaint);
            if (stripL >= 0) drawStripChevron(c);
        }

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

            if (k.kind == YugiKeyLayout.ACTION && k.code == YugiKeyLayout.A_MIC) {
                if (drawMicKey(c, i, now)) animating = true;
                continue;
            }
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
            if (k.kind == YugiKeyLayout.ACTION && k.code == YugiKeyLayout.A_LANG && rowH > 30 * dp) {
                // long press opens the settings: a small ring in the corner says so
                float rr = Math.max(2.2f * dp, rowH * 0.06f);
                hintPaint.setStyle(Paint.Style.STROKE);
                hintPaint.setStrokeWidth(1.2f * dp);
                c.drawCircle(kr[i] - 4 * dp - rr, kt[i] + 4 * dp + rr, rr, hintPaint);
                hintPaint.setStyle(Paint.Style.FILL);
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

        if (listening && voicePreview != null && !voicePreview.isEmpty()) drawPreview(c);
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

    /** Mic key: a mic glyph; while listening an accent key with a live level meter. Returns true to keep animating. */
    private boolean drawMicKey(Canvas c, int i, long now) {
        float cx = (kl[i] + kr[i]) * 0.5f, cy = (kt[i] + kb[i]) * 0.5f;
        float s = Math.min(kr[i] - kl[i], kb[i] - kt[i]) * 0.5f * Math.min(1.15f, set.font);
        if (!listening) {
            textPaint.setColor(YugiTheme.TEXT_DIM);
            drawMicGlyph(c, cx, cy, s, textPaint);
            return false;
        }
        // soft halo that breathes with the voice, then the key in accent
        float grow = (2f + 4f * voiceLevel) * dp;
        r.set(kl[i] - grow, kt[i] - grow, kr[i] + grow, kb[i] + grow);
        keyPaint.setColor((int) (0x30 + 0x40 * voiceLevel) << 24 | (YugiTheme.ACCENT & 0xFFFFFF));
        c.drawRoundRect(r, radius + grow, radius + grow, keyPaint);
        r.set(kl[i], kt[i], kr[i], kb[i]);
        keyPaint.setColor(YugiTheme.ACCENT);
        c.drawRoundRect(r, radius, radius, keyPaint);
        // five bars, like iOS dictation
        float bw = Math.max(2 * dp, s * 0.14f), step = bw * 1.9f;
        float maxH = (kb[i] - kt[i]) * 0.56f;
        keyPaint.setColor(YugiTheme.BRAND_BG);
        for (int j = 0; j < 5; j++) {
            double wave = 0.5 + 0.5 * Math.sin(now / 110.0 + j * 1.3);
            float hh = maxH * (0.18f + 0.82f * voiceLevel * (float) (0.45 + 0.55 * wave));
            float x = cx + (j - 2) * step;
            r.set(x - bw / 2, cy - hh / 2, x + bw / 2, cy + hh / 2);
            c.drawRoundRect(r, bw / 2, bw / 2, keyPaint);
        }
        return true;
    }

    private void drawMicGlyph(Canvas c, float cx, float cy, float s, Paint p) {
        float bodyW = s * 0.36f, bodyH = s * 0.62f, top = cy - s * 0.52f;
        r.set(cx - bodyW / 2, top, cx + bodyW / 2, top + bodyH);
        c.drawRoundRect(r, bodyW / 2, bodyW / 2, p);
        Paint.Style old = p.getStyle();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.4f * dp, s * 0.07f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float cupW = s * 0.6f;
        r.set(cx - cupW / 2, top + bodyH * 0.35f, cx + cupW / 2, top + bodyH * 1.15f);
        micPath.rewind();
        micPath.addArc(r, 0, 180);
        c.drawPath(micPath, p);
        float stemTop = r.bottom, stemBot = stemTop + s * 0.18f;
        c.drawLine(cx, stemTop, cx, stemBot, p);
        c.drawLine(cx - s * 0.16f, stemBot, cx + s * 0.16f, stemBot, p);
        p.setStyle(old);
    }

    private void drawPreview(Canvas c) {
        float h = rowH * 0.9f, pad = 12 * dp;
        float tw = previewPaint.measureText(voicePreview);
        float pw = tw + 2 * pad;
        float cx = (panelL + panelR) / 2;
        float bottom = panelT - 6 * dp;
        r.set(cx - pw / 2, bottom - h, cx + pw / 2, bottom);
        c.drawRoundRect(r, h / 2, h / 2, bubblePaint);
        c.drawText(voicePreview, r.left + pad, r.centerY() - (previewPaint.ascent() + previewPaint.descent()) * 0.5f, previewPaint);
    }

    private void drawStripChevron(Canvas c) {
        float cx = (stripL + stripR) / 2, cy = (panelT + panelB) / 2, s = 7 * dp;
        hintPaint.setStyle(Paint.Style.STROKE);
        hintPaint.setStrokeWidth(2.2f * dp);
        hintPaint.setStrokeCap(Paint.Cap.ROUND);
        hintPaint.setColor(YugiTheme.TEXT_DIM);
        float dir = hand == YugiKbdSettings.HAND_RIGHT ? -1 : 1; // points to the side the keys come back to
        c.drawLine(cx - dir * s * 0.5f, cy - s, cx + dir * s * 0.5f, cy, hintPaint);
        c.drawLine(cx + dir * s * 0.5f, cy, cx - dir * s * 0.5f, cy + s, hintPaint);
        hintPaint.setStyle(Paint.Style.FILL);
        hintPaint.setColor(YugiTheme.TEXT_FAINT);
    }

    // ------------------------------------------------------------------ dictation state (from the host)

    public void setListening(boolean on) {
        listening = on;
        if (!on) { voiceLevel = 0; voicePreview = null; }
        invalidate();
    }

    public boolean isListening() { return listening; }

    public void setVoiceLevel(float level) {
        voiceLevel = Math.max(0f, Math.min(1f, level));
    }

    /** Live dictation text shown above the keys (desktop: nothing is typed until the end). */
    public void setVoicePreview(String s) {
        if (s == null || s.isEmpty()) { voicePreview = null; invalidate(); return; }
        previewPaint.setTextSize(rowH * 0.36f * set.font);
        float avail = Math.max(60 * dp, (panelR - panelL) - 48 * dp);
        voicePreview = TextUtils.ellipsize(s, previewPaint, avail, TextUtils.TruncateAt.START).toString();
        invalidate();
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ------------------------------------------------------------------ touch

    private boolean inPanel(float x, float y) {
        return x >= panelL && x <= panelR && y >= panelT && y <= panelB + 1;
    }

    private int hit(float x, float y) {
        if (!inPanel(x, y)) return -1;
        if (stripL >= 0 && x >= stripL && x <= stripR) return -1;
        if (handleH > 0 && y < panelT + handleH) return -1;
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
                if (!inPanel(e.getX(), e.getY())) return false; // the desktop around stays touchable
                // fall through
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx), y = e.getY(idx);
                if (stripL >= 0 && x >= stripL && x <= stripR && y >= panelT) {
                    // one-handed: the empty side brings the full keyboard back
                    feedback(HapticFeedbackConstants.VIRTUAL_KEY, AudioManager.FX_KEYPRESS_STANDARD);
                    set.hand = YugiKbdSettings.HAND_NONE;
                    set.apply(getContext());
                    return true;
                }
                if (handleH > 0 && dragPointer < 0 && inPanel(x, y) && y < panelT + handleH) {
                    dragPointer = id;
                    dragDX = x - panelL;
                    dragDY = y - panelT;
                    invalidate();
                    return true;
                }
                if (id < MAXP) down(id, x, y);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragPointer >= 0) {
                    int pi = e.findPointerIndex(dragPointer);
                    if (pi >= 0) dragTo(e.getX(pi) - dragDX, e.getY(pi) - dragDY);
                }
                for (int p = 0; p < e.getPointerCount(); p++) {
                    int pid = e.getPointerId(p);
                    if (pid < MAXP) move(pid, e.getX(p), e.getY(p));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (id == dragPointer) { endDrag(); return true; }
                if (id < MAXP) up(id, e.getEventTime(), false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragPointer >= 0) endDrag();
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
        YugiKeyLayout.Key k = layout.keys.get(i);
        int fx = k.kind == YugiKeyLayout.SPECIAL && k.code == android.view.KeyEvent.KEYCODE_DEL ? AudioManager.FX_KEYPRESS_DELETE
                : k.kind == YugiKeyLayout.SPECIAL && k.code == android.view.KeyEvent.KEYCODE_ENTER ? AudioManager.FX_KEYPRESS_RETURN
                : k.kind == YugiKeyLayout.CHAR && k.us == ' ' ? AudioManager.FX_KEYPRESS_SPACEBAR
                : AudioManager.FX_KEYPRESS_STANDARD;
        feedback(Build.VERSION.SDK_INT >= 27 ? HapticFeedbackConstants.KEYBOARD_PRESS : HapticFeedbackConstants.KEYBOARD_TAP, fx);
        host.onKeyTouched();
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
            case YugiKeyLayout.ACTION:
                if (k.code == YugiKeyLayout.A_LANG) {
                    longPressPointer = id;
                    postDelayed(longPress, LONG_PRESS_MS);
                }
                break;
            default:
                break;
        }
        invalidate();
    }

    /** Haptic tick and key click, as the settings allow. */
    private void feedback(int haptic, int sound) {
        if (set.haptics) performHapticFeedback(haptic);
        if (set.sound && audio != null) audio.playSoundEffect(sound, -1);
    }

    private void dragTo(float left, float top) {
        int w = getWidth(), h = getHeight();
        float pw = panelR - panelL, ph = panelB - panelT;
        if (w <= 0 || h <= 0) return;
        set.floatX = clamp((left + pw / 2) / w, 0f, 1f);
        set.floatY = clamp((top + ph / 2) / h, 0f, 1f);
        layoutKeys(w);
        invalidate();
    }

    private void endDrag() {
        dragPointer = -1;
        // keep where it actually landed (after the on-screen clamp)
        int w = getWidth(), h = getHeight();
        if (w > 0 && h > 0) {
            set.floatX = (panelL + panelR) / 2 / w;
            set.floatY = (panelT + panelB) / 2 / h;
        }
        set.apply(getContext());
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
                if (set.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
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
                if (!cancel && !pCommitted[id]) {
                    if (k.code == YugiKeyLayout.A_LANG) engine.toggleLanguage();
                    else if (k.code == YugiKeyLayout.A_HIDE) host.onHideRequested();
                    else if (k.code == YugiKeyLayout.A_MIC) host.onMicRequested(engine.hebrew);
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
        if (k.kind == YugiKeyLayout.ACTION) {
            if (k.code == YugiKeyLayout.A_LANG) {
                pCommitted[id] = true; // the release does not switch the language
                feedback(HapticFeedbackConstants.LONG_PRESS, AudioManager.FX_KEYPRESS_STANDARD);
                host.onSettingsRequested();
            }
            return;
        }
        String out = YugiKeyLayout.output(k, engine.hebrew, engine.shift(), engine.caps());
        String[] a = YugiKeyLayout.alternates(out);
        if (a == null) return;
        alts = a;
        altKey = pKey[id];
        altPointer = id;
        altSel = 0;
        if (set.haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
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
        dragPointer = -1;
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

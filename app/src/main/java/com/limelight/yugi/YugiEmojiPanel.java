package com.limelight.yugi;

import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;

/**
 * The emoji picker inside the YugiPC keyboard, drawn on the keyboard's own canvas (no Views, no system
 * keyboard), at the keyboard's height: a search field on top, the emoji grid (only the visible rows
 * are drawn, so thousands of emoji cost nothing; smooth drag and fling), and a bar with ABC, the
 * category tabs and Backspace. A long press opens the skin tones; recents and the chosen tones are
 * kept ({@link YugiEmojiData.Prefs}).
 * <p>
 * Searching: the keyboard brings its keys back and types into the query; this class then draws the
 * search field and a strip of results above the keys ({@link #drawSearch}).
 * Loads its data only when first opened.
 */
final class YugiEmojiPanel {
    interface Out {
        void emoji(String s);
        void backspace();
        void abc();
        void openSearch();
        void closeSearch();
        void clearQuery();
        /** Haptic tick (and click, if enabled). */
        void feedback(int haptic);
    }

    static final String[] TITLES = {"אחרונים", "סמיילים ורגשות", "אנשים", "בעלי חיים וטבע", "אוכל ושתייה",
            "פעילות", "מסעות ומקומות", "חפצים", "סמלים", "דגלים"};
    static final String[] ICONS = {"🕘", "😀", "👋", "🐻", "🍔", "⚽", "🚗", "💡", "🔣", "🏳️"};
    static final int SECTIONS = 10; // recents + the 9 categories

    private static final long LONG_PRESS_MS = 380;
    private static final long REPEAT_DELAY_MS = 420, REPEAT_RATE_MS = 60;

    // regions
    private static final int R_NONE = 0, R_GRID = 1, R_SEARCH = 2, R_ABC = 3, R_TAB = 4, R_DEL = 5,
            R_BACK = 6, R_CLEAR = 7, R_STRIP = 8, R_FIELD = 9;

    private final View host;
    private final Out out;
    private final float dp;
    private YugiEmojiData data;
    private YugiEmojiData.Prefs prefs;

    // geometry
    private float l, t, r, b;          // the picker (keyboard panel area)
    private float pad, searchH, barH, gridT, gridB, cell, cellH, headerH, emojiSize, keyW, tabW;
    private int cols;
    private float radius;

    // sections: 0 = recents (snapshot at open), 1..9 = categories
    private final List<String> recents = new ArrayList<>();
    private final float[] secY = new float[SECTIONS];
    private final int[] secRows = new int[SECTIONS];
    private float contentH;
    private float scrollY;
    private final OverScroller scroller;
    private VelocityTracker velocity;
    private final int slop, minFling, maxFling;

    // search (drawn above the keys)
    private String query = "";
    private int[] results = new int[0];
    private float stripX;              // horizontal scroll of the results strip
    private final OverScroller stripScroller;
    private float sL, sT, sR, sB;      // search bar rect, set by drawSearch

    // touch
    private int region = R_NONE, pointer = -1;
    private float downX, downY, downScroll;
    private boolean dragging;
    private int pressSec = -1, pressIdx = -1, pressTab = -1;
    private boolean pressInside;
    // skin tones
    private String[] toneOpts;
    private int toneBase = -1, toneSel = -1;
    private float toneCx, toneBottom, toneCell;

    // entrance spring
    private final FloatValueHolder enterValue = new FloatValueHolder(1f);
    private final SpringAnimation enter;

    // paints
    private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popup = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rr = new RectF();
    private final Path path = new Path();

    private final Runnable longPress = this::onLongPress;
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (region != R_DEL) return;
            out.backspace();
            host.postDelayed(this, REPEAT_RATE_MS);
        }
    };

    YugiEmojiPanel(View host, Out out) {
        this.host = host;
        this.out = out;
        dp = host.getResources().getDisplayMetrics().density;
        ViewConfiguration vc = ViewConfiguration.get(host.getContext());
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(host.getContext());
        stripScroller = new OverScroller(host.getContext());
        emojiPaint.setTextAlign(Paint.Align.CENTER);
        iconPaint.setTextAlign(Paint.Align.CENTER);
        ColorMatrix gray = new ColorMatrix();
        gray.setSaturation(0f);
        iconPaint.setColorFilter(new ColorMatrixColorFilter(gray));
        iconPaint.setAlpha(175);
        Typeface medium = YugiTheme.font(host.getContext(), 500);
        label.setTypeface(medium);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        popup.setColor(0xFF3A4150);
        if (Build.VERSION.SDK_INT >= 28) popup.setShadowLayer(10 * dp, 0, 3 * dp, 0x80000000);
        enter = new SpringAnimation(enterValue);
        enter.setSpring(new SpringForce(1f).setDampingRatio(0.72f).setStiffness(520f));
        enter.setMinimumVisibleChange(0.002f);
        enter.addUpdateListener((a, v, vel) -> host.invalidate());
    }

    // ---------------------------------------------------------------- open / layout

    /** Opens (loads the data the first time) with a spring; scrolls to recents, or smileys when there are none. */
    void open() {
        if (data == null) {
            data = YugiEmojiData.get(host.getContext());
            prefs = new YugiEmojiData.Prefs(host.getContext());
        }
        recents.clear();
        recents.addAll(prefs.recents());
        cancelTouch();
        if (b > t) layoutSections();
        scroller.forceFinished(true);
        scrollY = recents.isEmpty() && b > t ? secY[1] : 0;
        clampScroll();
        enter.cancel();
        enterValue.setValue(0f);
        enter.animateToFinalPosition(1f);
    }

    boolean isLoaded() { return data != null; }

    /** The picker's rect in the keyboard view, and the keyboard's row height (sizes follow it). */
    void setBounds(float left, float top, float right, float bottom, float rowH) {
        boolean changed = left != l || top != t || right != r || bottom != b;
        l = left; t = top; r = right; b = bottom;
        pad = 6 * dp;
        radius = Math.min(9 * dp, rowH * 0.2f);
        searchH = 44 * dp;
        barH = clamp(rowH * 0.95f, 40 * dp, 50 * dp);
        gridT = t + searchH;
        gridB = b - barH;
        float w = r - l - 2 * pad;
        cols = Math.max(6, Math.round(w / (46 * dp)));
        cell = w / cols;
        cellH = Math.min(cell, Math.max(40 * dp, (gridB - gridT - 28 * dp) / 3.6f));
        emojiSize = Math.min(cell, cellH) * 0.7f;
        headerH = 28 * dp;
        keyW = clamp(w * 0.13f, 44 * dp, 64 * dp);
        tabW = (w - 2 * keyW - 2 * pad) / SECTIONS;
        if (changed && data != null) {
            float frac = contentH > 0 ? scrollY / contentH : 0;
            layoutSections();
            scrollY = frac * contentH;
            clampScroll();
        }
    }

    private void layoutSections() {
        float y = 0;
        for (int s = 0; s < SECTIONS; s++) {
            secY[s] = y;
            int n = count(s);
            secRows[s] = s == 0 && n == 0 ? 1 : (n + cols - 1) / cols; // empty recents: one row for a hint
            y += headerH + secRows[s] * cellH;
        }
        contentH = y + 8 * dp;
    }

    private int count(int s) {
        if (s == 0) return recents.size();
        return data.catCount(s - 1);
    }

    /** The emoji shown in section s at position i (with its chosen skin tone). */
    private String item(int s, int i) {
        if (s == 0) return recents.get(i);
        int d = data.catItem(s - 1, i);
        return data.tones[d] != null ? prefs.variant(data.emoji[d]) : data.emoji[d];
    }

    private int baseOfItem(int s, int i) {
        if (s == 0) return data.baseOf(recents.get(i));
        return data.catItem(s - 1, i);
    }

    private float maxScroll() { return Math.max(0, contentH - (gridB - gridT)); }
    private void clampScroll() { scrollY = clamp(scrollY, 0, maxScroll()); }

    /** The section at the top of the grid (the active tab). */
    int currentSection() {
        if (scrollY >= maxScroll() - 1 && maxScroll() > 0) return SECTIONS - 1;
        int cur = 0;
        for (int s = 0; s < SECTIONS; s++) if (secY[s] <= scrollY + 1) cur = s;
        return cur;
    }

    /** Smoothly scrolls to a category (0 = recents). */
    void scrollToSection(int s) {
        float target = clamp(secY[s], 0, maxScroll());
        scroller.forceFinished(true);
        scroller.startScroll(0, Math.round(scrollY), 0, Math.round(target - scrollY), 320);
        host.postInvalidateOnAnimation();
    }

    // ---------------------------------------------------------------- drawing

    void draw(Canvas c) {
        if (data == null) return;
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.getCurrY();
            clampScroll();
            host.postInvalidateOnAnimation();
        }
        float e = enterValue.getValue();
        int save = c.save();
        if (e < 0.999f) {
            c.saveLayerAlpha(l, t - 20 * dp, r, b + 20 * dp, (int) (255 * clamp(e, 0f, 1f)));
            c.translate(0, (1f - e) * 22 * dp);
        }
        drawSearchPill(c, l + pad, t + 5 * dp, r - pad, t + searchH - 5 * dp, region == R_SEARCH && pressInside);
        drawGrid(c);
        drawBar(c);
        c.restoreToCount(save);
        if (toneOpts != null) drawTones(c);
    }

    private void drawGrid(Canvas c) {
        int save = c.save();
        c.clipRect(l, gridT, r, gridB);
        emojiPaint.setTextSize(emojiSize);
        float base = -(emojiPaint.ascent() + emojiPaint.descent()) * 0.5f;
        label.setTextSize(12.5f * dp);
        label.setColor(YugiTheme.TEXT_FAINT);
        label.setTextAlign(Paint.Align.RIGHT);
        float x0 = l + pad;
        for (int s = 0; s < SECTIONS; s++) {
            float sy = gridT + secY[s] - scrollY;
            float sh = headerH + secRows[s] * cellH;
            if (sy > gridB) break;
            if (sy + sh < gridT) continue;
            c.drawText(TITLES[s], r - pad - 6 * dp, sy + headerH * 0.68f, label);
            int n = count(s);
            if (s == 0 && n == 0) {
                label.setTextAlign(Paint.Align.CENTER);
                c.drawText("כאן יופיעו האימוג'י שבחרת לאחרונה", (l + r) / 2, sy + headerH + cellH * 0.55f, label);
                label.setTextAlign(Paint.Align.RIGHT);
                continue;
            }
            float rowsTop = sy + headerH;
            int first = Math.max(0, (int) Math.floor((gridT - rowsTop) / cellH));
            int last = Math.min(secRows[s] - 1, (int) Math.floor((gridB - rowsTop) / cellH));
            for (int row = first; row <= last; row++) {
                float cy = rowsTop + row * cellH + cellH / 2;
                for (int col = 0; col < cols; col++) {
                    int i = row * cols + col;
                    if (i >= n) break;
                    float cx = x0 + col * cell + cell / 2;
                    if (s == pressSec && i == pressIdx && toneOpts == null) {
                        float h = Math.min(cell, cellH) * 0.92f;
                        rr.set(cx - h / 2, cy - h / 2, cx + h / 2, cy + h / 2);
                        fill.setColor(YugiTheme.SURFACE_HI);
                        c.drawRoundRect(rr, radius, radius, fill);
                    }
                    c.drawText(item(s, i), cx, cy + base, emojiPaint);
                }
            }
        }
        c.restoreToCount(save);
    }

    private void drawBar(Canvas c) {
        float top = gridB, cy = top + barH / 2, kh = barH - 10 * dp;
        fill.setColor(YugiTheme.STROKE);
        c.drawRect(l, top, r, top + Math.max(1, 0.6f * dp), fill);
        // ABC
        float x = l + pad;
        drawKey(c, x, cy - kh / 2, x + keyW, cy + kh / 2, region == R_ABC && pressInside);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Math.min(15 * dp, kh * 0.42f));
        label.setColor(YugiTheme.TEXT);
        c.drawText("ABC", x + keyW / 2, cy - (label.ascent() + label.descent()) / 2, label);
        // tabs
        int cur = currentSection();
        float tx = x + keyW + pad;
        iconPaint.setTextSize(Math.min(tabW, kh) * 0.56f);
        emojiPaint.setTextSize(Math.min(tabW, kh) * 0.56f);
        float ib = -(iconPaint.ascent() + iconPaint.descent()) / 2;
        for (int s = 0; s < SECTIONS; s++) {
            float cx = tx + s * tabW + tabW / 2;
            if (s == cur || (region == R_TAB && pressTab == s && pressInside)) {
                float h = Math.min(tabW - 2 * dp, kh);
                rr.set(cx - h / 2, cy - h / 2, cx + h / 2, cy + h / 2);
                fill.setColor(s == cur ? YugiTheme.SURFACE_HI : 0xFF1F2530);
                c.drawRoundRect(rr, h / 2, h / 2, fill);
            }
            c.drawText(ICONS[s], cx, cy + ib, s == cur ? emojiPaint : iconPaint);
        }
        // Backspace
        float dr = r - pad;
        drawKey(c, dr - keyW, cy - kh / 2, dr, cy + kh / 2, region == R_DEL);
        drawBackspaceGlyph(c, dr - keyW / 2, cy, Math.min(keyW, kh) * 0.42f);
    }

    private void drawKey(Canvas c, float l0, float t0, float r0, float b0, boolean pressed) {
        rr.set(l0, t0 + dp, r0, b0 + dp);
        fill.setColor(0x66000000);
        c.drawRoundRect(rr, radius, radius, fill);
        rr.set(l0, t0, r0, b0);
        fill.setColor(pressed ? 0xFF4B5466 : 0xFF1C212A);
        c.drawRoundRect(rr, radius, radius, fill);
    }

    private void drawBackspaceGlyph(Canvas c, float cx, float cy, float s) {
        line.setColor(YugiTheme.TEXT_DIM);
        line.setStrokeWidth(Math.max(1.5f * dp, s * 0.1f));
        float w = s * 1.1f, h = s * 0.8f;
        float left = cx - w / 2, right = cx + w / 2, tip = left - s * 0.35f;
        path.rewind();
        path.moveTo(tip, cy);
        path.lineTo(left, cy - h / 2);
        path.lineTo(right, cy - h / 2);
        path.lineTo(right, cy + h / 2);
        path.lineTo(left, cy + h / 2);
        path.close();
        c.drawPath(path, line);
        float xc = (left + right) / 2 + s * 0.05f, d = h * 0.22f;
        c.drawLine(xc - d, cy - d, xc + d, cy + d, line);
        c.drawLine(xc - d, cy + d, xc + d, cy - d, line);
    }

    private void drawMagnifier(Canvas c, float cx, float cy, float s, int color) {
        line.setColor(color);
        line.setStrokeWidth(Math.max(1.5f * dp, s * 0.13f));
        float rad = s * 0.36f;
        c.drawCircle(cx - s * 0.08f, cy - s * 0.08f, rad, line);
        float k = (float) Math.sqrt(0.5);
        c.drawLine(cx - s * 0.08f + rad * k, cy - s * 0.08f + rad * k, cx + s * 0.42f, cy + s * 0.42f, line);
    }

    /** The search field: a pill with a magnifier and the placeholder (or the query with a caret). */
    private void drawSearchPill(Canvas c, float l0, float t0, float r0, float b0, boolean pressed) {
        float h = b0 - t0;
        rr.set(l0, t0, r0, b0);
        fill.setColor(pressed ? YugiTheme.SURFACE_HI : YugiTheme.SURFACE);
        c.drawRoundRect(rr, h / 2, h / 2, fill);
        float cy = (t0 + b0) / 2;
        drawMagnifier(c, r0 - h * 0.55f, cy, h * 0.42f, YugiTheme.TEXT_FAINT);
        label.setTextSize(Math.min(15 * dp, h * 0.44f));
        label.setTextAlign(Paint.Align.RIGHT);
        float textR = r0 - h * 1.0f;
        float base = cy - (label.ascent() + label.descent()) / 2;
        if (query.isEmpty()) {
            label.setColor(YugiTheme.TEXT_FAINT);
            c.drawText("חיפוש אימוג'י", textR, base, label);
        } else {
            label.setColor(YugiTheme.TEXT);
            float avail = textR - l0 - h * 1.1f;
            String q = TextUtils.ellipsize(query, label, avail, TextUtils.TruncateAt.START).toString();
            c.drawText(q, textR, base, label);
        }
    }

    /** Where the search field and strip are (search mode), set with the keyboard's layout. */
    void setSearchBounds(float left, float top, float right, float bottom) {
        sL = left; sT = top; sR = right; sB = bottom;
    }

    /** Results strip height and search field height, for the keyboard's layout in search mode. */
    float searchBarHeight(float rowH) { return 44 * dp + stripHeight(rowH); }

    private float stripHeight(float rowH) { return clamp(rowH * 1.05f, 44 * dp, 54 * dp); }

    /**
     * Search mode: the field (with back and clear) and a strip of results, in the rect above the keys.
     * With an empty query the strip shows the recents.
     */
    void drawSearch(Canvas c, float left, float top, float right, float bottom) {
        if (data == null) return;
        sL = left; sT = top; sR = right; sB = bottom;
        if (stripScroller.computeScrollOffset()) {
            stripX = clamp(stripScroller.getCurrX(), 0, stripMax());
            host.postInvalidateOnAnimation();
        }
        float e = enterValue.getValue();
        int save = c.save();
        if (e < 0.999f) {
            c.saveLayerAlpha(left, top - 20 * dp, right, bottom + 20 * dp, (int) (255 * clamp(e, 0f, 1f)));
            c.translate(0, (1f - e) * 16 * dp);
        }
        float fieldB = top + 44 * dp;
        float backW = 40 * dp;
        // back to the grid
        float bx = left + pad + backW / 2, by = (top + fieldB) / 2;
        if (region == R_BACK && pressInside) {
            fill.setColor(YugiTheme.SURFACE_HI);
            c.drawCircle(bx, by, 17 * dp, fill);
        }
        line.setColor(YugiTheme.TEXT);
        line.setStrokeWidth(2.2f * dp);
        float s = 6 * dp;
        c.drawLine(bx + s * 0.5f, by - s, bx - s * 0.5f, by, line);
        c.drawLine(bx - s * 0.5f, by, bx + s * 0.5f, by + s, line);
        float pl = left + pad + backW + 4 * dp, pt = top + 5 * dp, pr = right - pad, pb = fieldB - 5 * dp;
        drawSearchPill(c, pl, pt, pr, pb, false);
        // caret after the text (RTL: at its left end) or at the placeholder's right
        float ph = pb - pt;
        label.setTextSize(Math.min(15 * dp, ph * 0.44f));
        float textR = pr - ph;
        float qw = query.isEmpty() ? 0 : Math.min(label.measureText(query), textR - pl - ph * 1.1f);
        float caretX = query.isEmpty() ? textR + 2 * dp : (isRtl(query) ? textR - qw - 2 * dp : textR + 2 * dp);
        fill.setColor(YugiTheme.ACCENT);
        c.drawRect(caretX - dp, pt + ph * 0.25f, caretX + dp, pb - ph * 0.25f, fill);
        if (!query.isEmpty()) {
            // clear (x in a circle) at the left end
            float cx = pl + ph * 0.55f;
            fill.setColor(region == R_CLEAR && pressInside ? YugiTheme.TEXT_DIM : YugiTheme.TEXT_FAINT);
            c.drawCircle(cx, (pt + pb) / 2, ph * 0.26f, fill);
            line.setColor(YugiTheme.SURFACE);
            line.setStrokeWidth(1.6f * dp);
            float d = ph * 0.1f, cyy = (pt + pb) / 2;
            c.drawLine(cx - d, cyy - d, cx + d, cyy + d, line);
            c.drawLine(cx - d, cyy + d, cx + d, cyy - d, line);
        }
        // results
        int clip = c.save();
        c.clipRect(left, fieldB, right, bottom);
        float sh = bottom - fieldB, cw = stripCell(), cyS = fieldB + sh / 2;
        emojiPaint.setTextSize(Math.min(cw, sh) * 0.66f);
        float base = -(emojiPaint.ascent() + emojiPaint.descent()) / 2;
        int n = stripCount();
        if (n == 0) {
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(YugiTheme.TEXT_FAINT);
            label.setTextSize(13 * dp);
            c.drawText(query.isEmpty() ? "הקלידו כדי לחפש" : "לא נמצאו אימוג'י", (left + right) / 2,
                    cyS - (label.ascent() + label.descent()) / 2, label);
        } else {
            int first = Math.max(0, (int) ((stripX) / cw));
            for (int i = first; i < n; i++) {
                float cx = left + pad + i * cw + cw / 2 - stripX;
                if (cx - cw / 2 > right) break;
                if (pressSec == -2 && pressIdx == i && toneOpts == null) {
                    float h = Math.min(cw, sh) * 0.9f;
                    rr.set(cx - h / 2, cyS - h / 2, cx + h / 2, cyS + h / 2);
                    fill.setColor(YugiTheme.SURFACE_HI);
                    c.drawRoundRect(rr, radius, radius, fill);
                }
                c.drawText(stripItem(i), cx, cyS + base, emojiPaint);
            }
        }
        c.restoreToCount(clip);
        c.restoreToCount(save);
        if (toneOpts != null) drawTones(c);
    }

    private static boolean isRtl(String s) {
        for (int i = 0; i < s.length(); i++) {
            byte d = Character.getDirectionality(s.charAt(i));
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT) return true;
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
        }
        return true; // the UI is Hebrew
    }

    private float stripCell() { return 46 * dp; }
    private int stripCount() { return query.isEmpty() ? recents.size() : results.length; }
    private float stripMax() { return Math.max(0, pad * 2 + stripCount() * stripCell() - (sR - sL)); }

    private String stripItem(int i) {
        if (query.isEmpty()) return recents.get(i);
        int d = results[i];
        return data.tones[d] != null ? prefs.variant(data.emoji[d]) : data.emoji[d];
    }

    private int stripBase(int i) {
        return query.isEmpty() ? data.baseOf(recents.get(i)) : results[i];
    }

    /** The search query changed (typed with the keyboard's keys). */
    void setQuery(String q) {
        query = q == null ? "" : q;
        results = data == null || query.trim().isEmpty() ? new int[0] : data.search(query, 300);
        stripScroller.forceFinished(true);
        stripX = 0;
        host.invalidate();
    }

    String query() { return query; }
    int[] results() { return results; }

    /** Search opened: the entrance spring again, for the field and strip. */
    void openSearch() {
        cancelTouch();
        recents.clear();
        recents.addAll(prefs.recents());
        enter.cancel();
        enterValue.setValue(0f);
        enter.animateToFinalPosition(1f);
    }

    /** Back from search to the grid. */
    void backToGrid() {
        cancelTouch();
        enter.cancel();
        enterValue.setValue(0f);
        enter.animateToFinalPosition(1f);
    }

    private void drawTones(Canvas c) {
        float w = toneCell * toneOpts.length, h = toneCell * 1.1f;
        float left = clamp(toneCx - w / 2, l + 2 * dp, Math.max(l + 2 * dp, r - w - 2 * dp));
        float top = toneBottom - h;
        rr.set(left, top, left + w, toneBottom);
        c.drawRoundRect(rr, radius * 1.4f, radius * 1.4f, popup);
        emojiPaint.setTextSize(toneCell * 0.62f);
        float base = -(emojiPaint.ascent() + emojiPaint.descent()) / 2;
        for (int j = 0; j < toneOpts.length; j++) {
            float x0 = left + j * toneCell;
            if (j == toneSel) {
                rr.set(x0 + 3 * dp, top + 3 * dp, x0 + toneCell - 3 * dp, toneBottom - 3 * dp);
                fill.setColor(YugiTheme.ACCENT);
                c.drawRoundRect(rr, radius, radius, fill);
            }
            c.drawText(toneOpts[j], x0 + toneCell / 2, top + h / 2 + base, emojiPaint);
        }
    }

    private float toneLeft() {
        float w = toneCell * toneOpts.length;
        return clamp(toneCx - w / 2, l + 2 * dp, Math.max(l + 2 * dp, r - w - 2 * dp));
    }

    // ---------------------------------------------------------------- touch

    /** The grid, the field and the bar (picker open, not searching). */
    boolean onTouch(MotionEvent e) {
        return touch(e, false);
    }

    /** The search field and the results strip (search mode, touches above the keys). */
    boolean onSearchTouch(MotionEvent e) {
        return touch(e, true);
    }

    private boolean touch(MotionEvent e, boolean search) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pointer = e.getPointerId(0);
            downX = e.getX();
            downY = e.getY();
            region = search ? searchRegion(downX, downY) : gridRegion(downX, downY);
            dragging = false;
            pressInside = true;
            pressSec = pressIdx = pressTab = -1;
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
            velocity.addMovement(e);
            switch (region) {
                case R_GRID: {
                    boolean wasFlinging = !scroller.isFinished();
                    scroller.forceFinished(true);
                    downScroll = scrollY;
                    if (!wasFlinging) pressGridAt(downX, downY);
                    break;
                }
                case R_STRIP: {
                    boolean wasFlinging = !stripScroller.isFinished();
                    stripScroller.forceFinished(true);
                    downScroll = stripX;
                    if (!wasFlinging) pressStripAt(downX);
                    break;
                }
                case R_DEL:
                    out.feedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    out.backspace();
                    host.postDelayed(repeat, REPEAT_DELAY_MS);
                    break;
                case R_TAB:
                    pressTab = tabAt(downX);
                    out.feedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    break;
                case R_NONE:
                    break;
                default:
                    out.feedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    break;
            }
            host.invalidate();
            return true;
        }
        int pi = e.findPointerIndex(pointer);
        if (pi < 0) {
            if (action == MotionEvent.ACTION_CANCEL) cancelTouch();
            return true;
        }
        float x = e.getX(pi), y = e.getY(pi);
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(e);
                if (toneOpts != null) {
                    int sel = (int) Math.floor((x - toneLeft()) / toneCell);
                    sel = Math.max(0, Math.min(toneOpts.length - 1, sel));
                    if (sel != toneSel) {
                        toneSel = sel;
                        out.feedback(HapticFeedbackConstants.CLOCK_TICK);
                        host.invalidate();
                    }
                    return true;
                }
                if (region == R_GRID) {
                    if (!dragging && Math.abs(y - downY) > slop) startDrag();
                    if (dragging) {
                        scrollY = downScroll - (y - downY);
                        clampScroll();
                        host.invalidate();
                    }
                } else if (region == R_STRIP) {
                    if (!dragging && Math.abs(x - downX) > slop) startDrag();
                    if (dragging) {
                        stripX = clamp(downScroll - (x - downX), 0, stripMax());
                        host.invalidate();
                    }
                } else {
                    boolean in = (search ? searchRegion(x, y) : gridRegion(x, y)) == region
                            && (region != R_TAB || tabAt(x) == pressTab);
                    if (in != pressInside) { pressInside = in; host.invalidate(); }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (action == MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.getActionIndex()) != pointer) return true;
                if (velocity != null) velocity.addMovement(e);
                release(x, y);
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelTouch();
                return true;
            default:
                return true;
        }
    }

    private void startDrag() {
        dragging = true;
        pressSec = pressIdx = -1;
        host.removeCallbacks(longPress);
        host.invalidate();
    }

    private void release(float x, float y) {
        host.removeCallbacks(longPress);
        host.removeCallbacks(repeat);
        int reg = region;
        region = R_NONE;
        if (toneOpts != null) {
            String chosen = toneOpts[Math.max(0, toneSel)];
            prefs.setVariant(data.emoji[toneBase], chosen);
            emit(chosen);
            toneOpts = null;
        } else if (reg == R_GRID || reg == R_STRIP) {
            if (dragging) {
                velocity.computeCurrentVelocity(1000, maxFling);
                if (reg == R_GRID) {
                    float vy = velocity.getYVelocity(pointer);
                    if (Math.abs(vy) > minFling) {
                        scroller.fling(0, Math.round(scrollY), 0, Math.round(-vy), 0, 0, 0, Math.round(maxScroll()));
                    }
                } else {
                    float vx = velocity.getXVelocity(pointer);
                    if (Math.abs(vx) > minFling) {
                        stripScroller.fling(Math.round(stripX), 0, Math.round(-vx), 0, 0, Math.round(stripMax()), 0, 0);
                    }
                }
            } else if (pressIdx >= 0) {
                emit(pressSec == -2 ? stripItem(pressIdx) : item(pressSec, pressIdx));
            }
        } else if (pressInside) {
            switch (reg) {
                case R_ABC: out.abc(); break;
                case R_SEARCH: out.openSearch(); break;
                case R_TAB: if (pressTab >= 0) scrollToSection(pressTab); break;
                case R_BACK: out.closeSearch(); break;
                case R_CLEAR: out.clearQuery(); break;
                default: break;
            }
        }
        pressSec = pressIdx = pressTab = -1;
        dragging = false;
        if (velocity != null) { velocity.recycle(); velocity = null; }
        host.postInvalidateOnAnimation();
    }

    void cancelTouch() {
        host.removeCallbacks(longPress);
        host.removeCallbacks(repeat);
        region = R_NONE;
        toneOpts = null;
        pressSec = pressIdx = pressTab = -1;
        dragging = false;
        if (velocity != null) { velocity.recycle(); velocity = null; }
        host.invalidate();
    }

    private void emit(String s) {
        out.emoji(s);
        prefs.used(s);
        host.invalidate();
    }

    private int gridRegion(float x, float y) {
        if (x < l || x > r || y < t || y > b) return R_NONE;
        if (y < gridT) return R_SEARCH;
        if (y < gridB) return R_GRID;
        if (x < l + pad + keyW + pad / 2) return R_ABC;
        if (x > r - pad - keyW - pad / 2) return R_DEL;
        return R_TAB;
    }

    private int searchRegion(float x, float y) {
        if (x < sL || x > sR || y < sT || y > sB) return R_NONE;
        float fieldB = sT + 44 * dp;
        if (y >= fieldB) return R_STRIP;
        float backR = sL + pad + 40 * dp + 2 * dp;
        if (x <= backR) return R_BACK;
        float ph = 34 * dp;
        if (!query.isEmpty() && x <= backR + 2 * dp + ph * 1.1f) return R_CLEAR;
        return R_FIELD;
    }

    private int tabAt(float x) {
        float tx = l + pad + keyW + pad;
        int s = (int) Math.floor((x - tx) / tabW);
        return Math.max(0, Math.min(SECTIONS - 1, s));
    }

    private void pressGridAt(float x, float y) {
        float cy = y - gridT + scrollY;
        for (int s = 0; s < SECTIONS; s++) {
            float rowsTop = secY[s] + headerH;
            float end = rowsTop + secRows[s] * cellH;
            if (cy < rowsTop || cy >= end) continue;
            int row = (int) ((cy - rowsTop) / cellH);
            int col = (int) Math.floor((x - l - pad) / cell);
            if (col < 0 || col >= cols) return;
            int i = row * cols + col;
            if (i >= count(s)) return;
            pressSec = s;
            pressIdx = i;
            toneCx = l + pad + col * cell + cell / 2;
            toneBottom = gridT + rowsTop + row * cellH - scrollY + 2 * dp;
            host.postDelayed(longPress, LONG_PRESS_MS);
            return;
        }
    }

    private void pressStripAt(float x) {
        float cw = stripCell();
        int i = (int) Math.floor((x - sL - pad + stripX) / cw);
        if (i < 0 || i >= stripCount()) return;
        pressSec = -2;
        pressIdx = i;
        toneCx = sL + pad + i * cw + cw / 2 - stripX;
        toneBottom = sT + 44 * dp + 2 * dp;
        host.postDelayed(longPress, LONG_PRESS_MS);
    }

    private void onLongPress() {
        if (pressIdx < 0 || dragging) return;
        int base = pressSec == -2 ? stripBase(pressIdx) : baseOfItem(pressSec, pressIdx);
        if (base < 0 || data.tones[base] == null) return;
        String[] v = data.tones[base];
        toneOpts = new String[v.length + 1];
        toneOpts[0] = data.emoji[base];
        System.arraycopy(v, 0, toneOpts, 1, v.length);
        toneBase = base;
        String cur = pressSec == -2 ? stripItem(pressIdx) : item(pressSec, pressIdx);
        toneSel = 0;
        for (int j = 0; j < toneOpts.length; j++) if (toneOpts[j].equals(cur)) toneSel = j;
        toneCell = Math.max(Math.min(cell, cellH) * 0.95f, 42 * dp);
        out.feedback(HapticFeedbackConstants.LONG_PRESS);
        host.invalidate();
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---------------------------------------------------------------- tests

    float[] cellCenterForTest(int section, int i) {
        int row = i / cols, col = i % cols;
        return new float[]{l + pad + col * cell + cell / 2, gridT + secY[section] + headerH + row * cellH + cellH / 2 - scrollY};
    }

    float[] tabCenterForTest(int s) {
        return new float[]{l + pad + keyW + pad + s * tabW + tabW / 2, gridB + barH / 2};
    }

    float[] abcCenterForTest() { return new float[]{l + pad + keyW / 2, gridB + barH / 2}; }
    float[] delCenterForTest() { return new float[]{r - pad - keyW / 2, gridB + barH / 2}; }
    float[] searchCenterForTest() { return new float[]{(l + r) / 2, t + searchH / 2}; }
    float[] stripCenterForTest(int i) { return new float[]{sL + pad + i * stripCell() + stripCell() / 2 - stripX, sT + 44 * dp + (sB - sT - 44 * dp) / 2}; }
    float[] backCenterForTest() { return new float[]{sL + pad + 20 * dp, sT + 22 * dp}; }
    float scrollYForTest() { return scrollY; }
    float[] gridForTest() { return new float[]{gridT, gridB}; }
    float[] toneCenterForTest(int j) {
        float h = toneCell * 1.1f;
        return new float[]{toneLeft() + j * toneCell + toneCell / 2, toneBottom - h / 2};
    }
    void settleForTest() {
        enter.cancel();
        enterValue.setValue(1f);
        if (!scroller.isFinished()) { scroller.abortAnimation(); scrollY = scroller.getFinalY(); clampScroll(); }
    }
    String[] toneOptionsForTest() { return toneOpts; }
    void longPressForTest() { onLongPress(); }
    float sectionTopForTest(int s) { return secY[s]; }
    int columnsForTest() { return cols; }
    int indexInSectionForTest(int s, String e) {
        for (int i = 0; i < count(s); i++) if (item(s, i).equals(e)) return i;
        return -1;
    }
    String itemForTest(int s, int i) { return item(s, i); }
}

package com.limelight.yugi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import com.limelight.R;

/**
 * Round avatar for a session or area, like a contact photo in Messages: a soft gradient per area with a glyph
 * (Yugi gets its orb icon), and an optional state badge on the bottom corner. While the session works the
 * badge breathes (a ring that grows and fades); the animation runs only while the view is showing.
 */
class YugiAvatar extends View {
    static final String YUGI = "yugi";

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int c1, c2;
    private String glyph = "";
    private Drawable icon;
    private int stateColor; // 0 = no badge
    private boolean pulsing;
    private int ringColor = YugiTheme.CARD;
    private int lastW = -1;

    YugiAvatar(Context c) {
        super(c);
        glyphPaint.setColor(0xFFFFFFFF);
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setTypeface(YugiTheme.font(c, 700));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Area key from /sessions ("personal", "mamila", "nextpress", "workspace", "other") or {@link #YUGI}. */
    YugiAvatar set(String area, String name) {
        icon = null;
        if (YUGI.equals(area)) {
            c1 = YugiTheme.BRAND_HI; c2 = YugiTheme.BRAND_DEEP;
            icon = getContext().getDrawable(R.drawable.yugi_ic_yugi);
            if (icon != null) { icon = icon.mutate(); icon.setTint(0xFFFFFFFF); }
        } else if ("personal".equals(area)) {
            c1 = 0xFF3DDC97; c2 = 0xFF1E9E8A; glyph = "א";
        } else if ("mamila".equals(area)) {
            c1 = 0xFFFFB340; c2 = 0xFFF2673A; glyph = "מ";
        } else if ("nextpress".equals(area)) {
            c1 = 0xFFC77DFF; c2 = 0xFF5E5CE6; glyph = "נ";
        } else {
            c1 = 0xFF8E9AAF; c2 = 0xFF4A5368;
            String n = name == null ? "" : name.trim();
            glyph = n.isEmpty() ? "•" : n.substring(0, Character.charCount(n.codePointAt(0))).toUpperCase(java.util.Locale.ROOT);
        }
        lastW = -1;
        invalidate();
        return this;
    }

    /** Badge color (0 hides it); {@code pulse} makes it breathe (a session at work). */
    void setState(int color, boolean pulse) {
        stateColor = color;
        boolean was = pulsing;
        pulsing = pulse && color != 0;
        invalidate();
        if (pulsing && !was) postInvalidateOnAnimation();
    }

    /** The surface behind the avatar, used for the badge's cut-out ring. */
    void setRingColor(int c) { ringColor = c; invalidate(); }

    boolean isPulsing() { return pulsing; }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        float d = Math.min(w, h);
        float r = d / 2f, cx = w / 2f, cy = h / 2f;
        if (w != lastW) {
            lastW = w;
            fill.setShader(new LinearGradient(0, 0, w, h, c1, c2, Shader.TileMode.CLAMP));
            glyphPaint.setTextSize(d * 0.44f);
        }
        cv.drawCircle(cx, cy, r, fill);
        if (icon != null) {
            int s = Math.round(d * 0.56f);
            icon.setBounds(Math.round(cx - s / 2f), Math.round(cy - s / 2f), Math.round(cx + s / 2f), Math.round(cy + s / 2f));
            icon.draw(cv);
        } else {
            Paint.FontMetrics fm = glyphPaint.getFontMetrics();
            cv.drawText(glyph, cx, cy - (fm.ascent + fm.descent) / 2f, glyphPaint);
        }
        if (stateColor != 0) {
            float br = d * 0.15f;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            // bottom corner on the end side (left in RTL), on the circle's edge
            float ring = Math.max(2f, d * 0.05f);
            float off = Math.min(r * 0.72f, r - br - ring); // the badge stays inside the avatar's square
            float bx = cx + (rtl ? -1 : 1) * off, by = cy + off;
            if (pulsing) {
                float t = (SystemClock.uptimeMillis() % 1600) / 1600f;
                badge.setColor(stateColor);
                badge.setAlpha(Math.round(110 * (1f - t)));
                cv.drawCircle(bx, by, br * (1f + 1.1f * t), badge);
            }
            badge.setColor(ringColor);
            badge.setAlpha(255);
            cv.drawCircle(bx, by, br + ring, badge);
            badge.setColor(stateColor);
            cv.drawCircle(bx, by, br, badge);
            if (pulsing && isShown() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
        }
    }
}

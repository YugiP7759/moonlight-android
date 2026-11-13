package com.limelight.yugi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * "Thinking": three soft dots that rise and brighten in a wave, like Messages' typing bubble but quieter.
 * Frames are drawn only while the view is on screen, so nothing ticks in the background.
 */
class YugiTypingDots extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long t0 = SystemClock.uptimeMillis();

    YugiTypingDots(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("יוגי חושב");
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(YugiTheme.dp(getContext(), 52), YugiTheme.dp(getContext(), 28));
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = YugiTheme.dp(getContext(), 3.5f), gap = YugiTheme.dp(getContext(), 11);
        float cy = getHeight() / 2f;
        float x0 = getWidth() / 2f - gap;
        long t = SystemClock.uptimeMillis() - t0;
        for (int i = 0; i < 3; i++) {
            // phase-shifted smooth wave: 0..1..0 every 1.2 s
            double ph = ((t + (getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 2 - i : i) * 160) % 1200) / 1200.0;
            float k = (float) Math.max(0, Math.sin(ph * Math.PI * 2));
            p.setColor(YugiTheme.TEXT);
            p.setAlpha(Math.round(90 + 140 * k));
            c.drawCircle(x0 + i * gap, cy - k * YugiTheme.dp(getContext(), 3), r, p);
        }
        if (isShown() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}

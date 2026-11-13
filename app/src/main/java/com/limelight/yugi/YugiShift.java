package com.limelight.yugi;

/**
 * Pure math for "the desktop scrolls up with the keyboard": how far the stream must move so the
 * text caret sits just above the keyboard. No Android dependencies, unit tested in YugiShiftTest.
 */
public final class YugiShift {
    private YugiShift() {}

    /**
     * Bottom edge of the caret in stream-view pixels (the view's own coordinates, before any shift).
     * The stream is stretched over the whole view, so desktop pixels scale linearly.
     *
     * @param caretY      caret top in desktop pixels
     * @param caretH      caret height in desktop pixels
     * @param screenH     desktop height in pixels (from the watcher; <= 0 means unknown)
     * @param viewTop     top of the stream view inside the moving wrapper, px
     * @param viewHeight  stream view height, px
     * @return caret bottom in wrapper pixels, or NaN when the input is unusable
     */
    public static float caretBottomInView(float caretY, float caretH, float screenH, float viewTop, float viewHeight) {
        if (!(screenH > 0) || !(viewHeight > 0) || Float.isNaN(caretY)) return Float.NaN;
        float bottom = caretY + Math.max(0f, caretH);
        bottom = Math.max(0f, Math.min(screenH, bottom));
        return viewTop + bottom * (viewHeight / screenH);
    }

    /**
     * How far up (px, >= 0) the desktop moves: shift = max(0, caretBottom + margin - keyboardTop),
     * never more than the keyboard's own height (the desktop's bottom edge then meets the keyboard top).
     *
     * @param caretBottom caret bottom in unshifted window pixels (NaN = unknown: no shift)
     * @param margin      gap kept between caret and keyboard (24dp)
     * @param kbTop       top edge of the fully shown keyboard, window pixels
     * @param kbHeight    keyboard height, px
     */
    public static float shiftFor(float caretBottom, float margin, float kbTop, float kbHeight) {
        if (Float.isNaN(caretBottom) || !(kbHeight > 0)) return 0f;
        float s = caretBottom + margin - kbTop;
        if (s <= 0f) return 0f;
        return Math.min(s, kbHeight);
    }

    /**
     * Fallback when the server gave no caret: the place the user last touched, plus about one text
     * line, stands in for the caret bottom.
     */
    public static float fallbackCaretBottom(float lastTouchY, float lineHeight) {
        if (Float.isNaN(lastTouchY)) return Float.NaN;
        return lastTouchY + lineHeight * 0.5f;
    }
}

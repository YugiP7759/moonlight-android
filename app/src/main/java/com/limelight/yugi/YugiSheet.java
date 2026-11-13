package com.limelight.yugi;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;

import com.limelight.R;

/**
 * A bottom sheet over a panel (iOS style): dimmed backdrop, a rounded card that rises on a spring, a title, an
 * optional note and a scrollable monospace body. Used for what a session's command printed (/cost, /context)
 * or the panel it opened (/model, /status). Tap the backdrop or ✕ to close.
 */
class YugiSheet extends FrameLayout {
    private final LinearLayout card;
    private final TextView title, note, body;
    private boolean shown;

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    YugiSheet(Context c) {
        super(c);
        setVisibility(GONE);
        setClickable(true);
        setBackgroundColor(0x99000000);
        setOnClickListener(v -> setShown(false));
        setTag("sheet");
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        card.setBackground(YugiTheme.corners(c, 0xFF161B24, 24, 24, 16, 16));
        card.setClickable(true);
        card.setPadding(dp(16), dp(8), dp(8), dp(16));
        View grip = new View(c);
        grip.setBackground(YugiTheme.rounded(c, 0x40FFFFFF, 3));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(36), dp(5));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(4);
        card.addView(grip, glp);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = YugiTheme.text(c, "", 18, 700, YugiTheme.TEXT);
        title.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton close = YugiTheme.iconButton(c, R.drawable.yugi_ic_close, "סגור");
        close.setOnClickListener(v -> setShown(false));
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(head);
        note = YugiTheme.text(c, "", 14, 400, YugiTheme.TEXT_DIM);
        note.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        note.setLineSpacing(0, 1.25f);
        note.setPadding(0, 0, dp(8), dp(10));
        card.addView(note);
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        body = YugiTheme.text(c, "", 13.5f, 400, 0xFFE6E8EE);
        body.setTypeface(Typeface.MONOSPACE);
        body.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        body.setTextDirection(TEXT_DIRECTION_FIRST_STRONG_LTR);
        body.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        body.setLineSpacing(0, 1.2f);
        body.setTextIsSelectable(true);
        body.setPadding(dp(12), dp(12), dp(12), dp(12));
        body.setBackground(YugiTheme.rounded(c, 0xFF0E1218, 12));
        sv.addView(body);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        slp.setMarginEnd(dp(8));
        card.addView(sv, slp);
        addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        YugiSpring.of(this, DynamicAnimation.ALPHA).addEndListener((a, canceled, v, vel) -> {
            if (!canceled && !shown) setVisibility(GONE);
        });
    }

    @Override
    protected void onMeasure(int w, int h) {
        // the card takes what it needs, up to 82% of the panel
        int max = Math.round(MeasureSpec.getSize(h) * 0.82f);
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        card.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        if (card.getMeasuredHeight() >= max) lp.height = max; else lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        super.onMeasure(w, h);
    }

    void show(String t, String n, String text) {
        title.setText(t);
        note.setText(n == null ? "" : n);
        note.setVisibility(n == null || n.isEmpty() ? GONE : VISIBLE);
        body.setText(text == null || text.isEmpty() ? "(אין פלט)" : text);
        setShown(true);
    }

    boolean isShownNow() { return shown; }

    void setShown(boolean s) {
        shown = s;
        if (s) {
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
                setAlpha(0f);
                card.setTranslationY(dp(260));
            }
            YugiSpring.to(this, DynamicAnimation.ALPHA, 1f);
            YugiSpring.to(card, DynamicAnimation.TRANSLATION_Y, 0f);
        } else {
            YugiSpring.to(this, DynamicAnimation.ALPHA, 0f);
            YugiSpring.to(card, DynamicAnimation.TRANSLATION_Y, dp(200));
        }
    }

    void settleForTest() {
        for (View v : new View[]{this, card}) {
            for (DynamicAnimation.ViewProperty p : new DynamicAnimation.ViewProperty[]{DynamicAnimation.ALPHA, DynamicAnimation.TRANSLATION_Y}) {
                androidx.dynamicanimation.animation.SpringAnimation a = YugiSpring.of(v, p);
                if (a.isRunning()) a.skipToEnd();
            }
        }
        if (shown) { setAlpha(1f); card.setTranslationY(0); }
    }
}

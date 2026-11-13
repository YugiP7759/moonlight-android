package com.limelight.yugi;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Pad edit mode card: clear title, size / opacity sliders for the selected control and large
 * Save / Cancel / Reset buttons. It moves to the top or bottom so it never covers the selected control.
 */
public class YugiPadEditor extends FrameLayout {
    public interface Actions {
        void onSave();
        void onCancel();
        void onReset();
        void onSize(float dp);
        void onAlpha(float alpha);
    }

    private final LinearLayout card;
    private final TextView hint;
    private final SeekBar size, alpha;
    private final TextView sizeVal, alphaVal;
    private boolean atBottom;

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    public YugiPadEditor(Context ctx, Actions actions) {
        super(ctx);
        setClickable(false);
        setVisibility(GONE);

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        card.setBackground(YugiTheme.roundedStroke(ctx, YugiTheme.GLASS_STRONG, YugiTheme.RADIUS_L, YugiTheme.STROKE));
        card.setElevation(dp(12));
        card.setClickable(true);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        addView(card);

        card.addView(YugiTheme.text(ctx, "עריכת שלט", 18, 600, YugiTheme.TEXT));
        hint = YugiTheme.text(ctx, "גרור כפתור כדי להזיז אותו. בחר כפתור כדי לשנות גודל ושקיפות.", 13, 400, YugiTheme.TEXT_DIM);
        hint.setPadding(0, dp(6), 0, dp(4));
        card.addView(hint);

        sizeVal = YugiTheme.text(ctx, "", 13, 500, YugiTheme.TEXT_DIM);
        size = slider(ctx, "גודל", sizeVal, 30, 220, v -> { sizeVal.setText(v + "dp"); actions.onSize(v); });
        alphaVal = YugiTheme.text(ctx, "", 13, 500, YugiTheme.TEXT_DIM);
        alpha = slider(ctx, "שקיפות", alphaVal, 10, 100, v -> { alphaVal.setText(v + "%"); actions.onAlpha(v / 100f); });

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        row.addView(button(ctx, "שמור", true, v -> actions.onSave()), weight());
        row.addView(button(ctx, "ביטול", false, v -> actions.onCancel()), weight());
        row.addView(button(ctx, "איפוס", false, v -> actions.onReset()), weight());
        card.addView(row);
        setSelection(null, 0, 0);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        return lp;
    }

    private TextView button(Context c, String label, boolean primary, View.OnClickListener l) {
        TextView b = YugiTheme.text(c, label, 15, 600, primary ? 0xFF0B0E14 : YugiTheme.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? YugiTheme.rounded(c, YugiTheme.TEXT, 24)
                : YugiTheme.roundedStroke(c, YugiTheme.SURFACE, 24, YugiTheme.STROKE));
        b.setOnClickListener(l);
        return b;
    }

    private interface IntSetter { void set(int v); }

    private SeekBar slider(Context c, String label, TextView value, int min, int max, IntSetter setter) {
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(0, dp(10), 0, 0);
        TextView t = YugiTheme.text(c, label, 14, 500, YugiTheme.TEXT);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(value);
        card.addView(head);
        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgressTintList(ColorStateList.valueOf(YugiTheme.ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(YugiTheme.TEXT));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser) setter.set(p + min); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sb.setTag(min);
        card.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return sb;
    }

    /** Shows the selected control's values (or disables the sliders), and keeps the card away from it. */
    public void setSelection(VirtualPadView.Control c, float selectedCenterY, int screenH) {
        boolean has = c != null;
        size.setEnabled(has);
        alpha.setEnabled(has);
        size.setAlpha(has ? 1f : 0.4f);
        alpha.setAlpha(has ? 1f : 0.4f);
        if (has) {
            size.setProgress(Math.round(c.sizeDp) - 30);
            alpha.setProgress(Math.round(c.alpha * 100) - 10);
            sizeVal.setText(Math.round(c.sizeDp) + "dp");
            alphaVal.setText(Math.round(c.alpha * 100) + "%");
        } else {
            sizeVal.setText("");
            alphaVal.setText("");
        }
        boolean bottom = has && selectedCenterY >= 0 && selectedCenterY < screenH / 2f;
        if (bottom != atBottom) {
            atBottom = bottom;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int width = Math.min(dp(380), w - dp(24));
        card.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h - dp(24), MeasureSpec.AT_MOST));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        Rect c = cardRect(r - l, b - t);
        card.layout(c.left, c.top, c.right, c.bottom);
    }

    Rect cardRect(int w, int h) {
        int cw = card.getMeasuredWidth(), ch = card.getMeasuredHeight();
        int left = (w - cw) / 2;
        int top = atBottom ? h - dp(12) - ch : dp(12);
        return new Rect(left, top, left + cw, top + ch);
    }

    public Rect cardRect() {
        return new Rect(card.getLeft(), card.getTop(), card.getRight(), card.getBottom());
    }

    @SuppressWarnings("unused")
    static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }
}

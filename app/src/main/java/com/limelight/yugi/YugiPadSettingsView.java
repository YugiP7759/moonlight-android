package com.limelight.yugi;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/** Pad settings sheet: aim sensitivity, curve, deadzones, gyro and invert Y. Values are saved. */
public class YugiPadSettingsView extends FrameLayout {
    public interface Listener { void onChanged(PadSettings s); void onClose(); }

    private final LinearLayout card;
    private final PadSettings s;
    private final Listener listener;

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    public YugiPadSettingsView(Context ctx, PadSettings settings, Listener l) {
        super(ctx);
        s = settings;
        listener = l;
        setBackgroundColor(0x66000000);
        setClickable(true); // modal
        setOnClickListener(v -> close());

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setClickable(true);

        ScrollView sv = new ScrollView(ctx);
        sv.setBackground(YugiTheme.roundedStroke(ctx, YugiTheme.GLASS_STRONG, YugiTheme.RADIUS_L, YugiTheme.STROKE));
        sv.setElevation(dp(12));
        sv.addView(card);
        addView(sv);

        card.addView(YugiTheme.text(ctx, "הגדרות שלט", 18, 600, YugiTheme.TEXT));
        TextView sub = YugiTheme.text(ctx, "החצי השמאלי: סטיק צף. החצי הימני: גרירה לכיוון.", 13, 400, YugiTheme.TEXT_DIM);
        sub.setPadding(0, dp(6), 0, dp(6));
        card.addView(sub);

        slider("רגישות כיוון", 5, 200, Math.round(s.lookSensitivity * 100), v -> s.lookSensitivity = v / 100f,
                v -> String.format(Locale.US, "%.2f", v / 100f));
        slider("עקומת האצה", 50, 250, Math.round(s.lookCurve * 100), v -> s.lookCurve = v / 100f,
                v -> String.format(Locale.US, "%.2f", v / 100f));
        slider("מינימום תנועה בכיוון", 0, 30, Math.round(s.lookAntiDeadzone * 100), v -> s.lookAntiDeadzone = v / 100f,
                v -> v + "%");
        slider("שטח מת בסטיק", 0, 30, Math.round(s.stickDeadzone * 100), v -> s.stickDeadzone = v / 100f,
                v -> v + "%");
        toggle("כיוון בג'ירוסקופ", s.gyro, on -> s.gyro = on);
        slider("רגישות ג'ירוסקופ", 5, 150, Math.round(s.gyroSensitivity * 100), v -> s.gyroSensitivity = v / 100f,
                v -> String.format(Locale.US, "%.2f", v / 100f));
        toggle("היפוך ציר Y", s.invertY, on -> s.invertY = on);

        TextView done = YugiTheme.text(ctx, "סיום", 15, 600, 0xFF0B0E14);
        done.setGravity(Gravity.CENTER);
        done.setBackground(YugiTheme.rounded(ctx, YugiTheme.TEXT, 24));
        done.setOnClickListener(v -> close());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        dl.topMargin = dp(14);
        card.addView(done, dl);
    }

    private void close() {
        s.save(getContext());
        listener.onChanged(s);
        listener.onClose();
    }

    private interface IntSetter { void set(int v); }
    private interface Fmt { String f(int v); }
    private interface BoolSetter { void set(boolean v); }

    private void slider(String label, int min, int max, int value, IntSetter setter, Fmt fmt) {
        Context c = getContext();
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(0, dp(12), 0, 0);
        head.addView(YugiTheme.text(c, label, 14, 500, YugiTheme.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView val = YugiTheme.text(c, fmt.f(value), 13, 500, YugiTheme.TEXT_DIM);
        val.setTextDirection(TEXT_DIRECTION_LTR);
        head.addView(val);
        card.addView(head);
        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setProgressTintList(ColorStateList.valueOf(YugiTheme.ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(YugiTheme.TEXT));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean u) {
                setter.set(p + min);
                val.setText(fmt.f(p + min));
                listener.onChanged(s);
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        card.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
    }

    private void toggle(String label, boolean value, BoolSetter setter) {
        Context c = getContext();
        Switch sw = new Switch(c);
        sw.setText(label);
        sw.setTextColor(YugiTheme.TEXT);
        sw.setTypeface(YugiTheme.font(c, 500));
        sw.setTextSize(14);
        sw.setChecked(value);
        sw.setMinHeight(dp(48));
        sw.setThumbTintList(ColorStateList.valueOf(YugiTheme.TEXT));
        sw.setTrackTintList(ColorStateList.valueOf(YugiTheme.ACCENT));
        sw.setOnCheckedChangeListener((b, on) -> { setter.set(on); listener.onChanged(s); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(8);
        card.addView(sw, lp);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int cw = Math.min(dp(420), w - dp(24));
        getChildAt(0).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h - dp(24), MeasureSpec.AT_MOST));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        android.view.View c = getChildAt(0);
        int w = r - l, h = b - t;
        int left = (w - c.getMeasuredWidth()) / 2, top = (h - c.getMeasuredHeight()) / 2;
        c.layout(left, top, left + c.getMeasuredWidth(), top + c.getMeasuredHeight());
    }

    public Rect cardRect() {
        android.view.View c = getChildAt(0);
        return new Rect(c.getLeft(), c.getTop(), c.getRight(), c.getBottom());
    }
}

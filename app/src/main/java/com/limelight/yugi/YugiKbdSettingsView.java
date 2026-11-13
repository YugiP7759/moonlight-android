package com.limelight.yugi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keyboard settings sheet (long press on the language key): height, label size, docked / split /
 * floating, one-handed, haptics and sound. Grouped inset rows in the Apple settings style.
 * <p>
 * There is no scrim: the live keyboard below stays visible and every change is applied to it at once
 * (the preview is the keyboard itself). Touches on the keyboard pass through; a tap anywhere else,
 * or "סיום", closes the sheet.
 */
@SuppressLint("ViewConstructor")
public class YugiKbdSettingsView extends FrameLayout {
    private final View keyboard;
    private final YugiKbdSettings s;
    private final ScrollView sheet;
    private final LinearLayout body;
    private final List<Runnable> refreshers = new ArrayList<>();
    private final YugiKbdSettings.Listener onChange = x -> post(this::requestLayout); // after the keyboard re-lays out
    private Runnable onClosed;

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    /** Opens the sheet over the activity, above the given keyboard view. */
    public static YugiKbdSettingsView show(Activity a, View keyboard, Runnable onClosed) {
        YugiKbdSettingsView v = new YugiKbdSettingsView(a, keyboard);
        v.onClosed = onClosed;
        a.addContentView(v, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.appear();
        return v;
    }

    public YugiKbdSettingsView(Context ctx, View keyboard) {
        super(ctx);
        this.keyboard = keyboard;
        s = YugiKbdSettings.get(ctx);

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        body.setPadding(dp(16), dp(14), dp(16), dp(16));

        sheet = new ScrollView(ctx);
        sheet.setBackground(YugiTheme.roundedStroke(ctx, YugiTheme.GLASS_STRONG, YugiTheme.RADIUS_L, YugiTheme.STROKE));
        sheet.setElevation(dp(16));
        sheet.setClickable(true);
        sheet.setVerticalScrollBarEnabled(false);
        sheet.setClipToOutline(true);
        sheet.addView(body);
        addView(sheet);

        // ---- title row: "מקלדת" ... "סיום"
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(YugiTheme.text(ctx, "מקלדת", 20, 700, YugiTheme.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView done = YugiTheme.text(ctx, "סיום", 15, 600, YugiTheme.BRAND_BG);
        done.setGravity(Gravity.CENTER);
        done.setPadding(dp(18), 0, dp(18), 0);
        done.setBackground(YugiTheme.rounded(ctx, YugiTheme.ACCENT, 20));
        done.setOnClickListener(v -> close());
        head.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        body.addView(head);

        // ---- size
        LinearLayout size = group("גודל");
        slider(size, "גובה", Math.round(YugiKbdSettings.HEIGHT_MIN * 100), Math.round(YugiKbdSettings.HEIGHT_MAX * 100),
                Math.round(s.height * 100), v -> s.height = v / 100f);
        hair(size);
        slider(size, "גודל אותיות", Math.round(YugiKbdSettings.FONT_MIN * 100), Math.round(YugiKbdSettings.FONT_MAX * 100),
                Math.round(s.font * 100), v -> s.font = v / 100f);

        // ---- mode
        LinearLayout mode = group("מצב");
        boolean wide = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density >= 600;
        segmented(mode, new String[]{"מעוגן", "מפוצל", "צף"}, () -> s.mode, v -> {
            s.mode = v;
            if (v != YugiKbdSettings.DOCKED) s.hand = YugiKbdSettings.HAND_NONE;
        });
        TextView note = YugiTheme.text(ctx, wide ? "מפוצל: שני חצאים בצדדים, לשתי האגודלים. צף: מקלדת קטנה שגוררים מהפס העליון."
                : "מפוצל עובד במסך הפנימי. צף: מקלדת קטנה שגוררים מהפס העליון.", 12, 400, YugiTheme.TEXT_DIM);
        note.setPadding(dp(14), 0, dp(14), dp(12));
        mode.addView(note);

        // ---- one hand
        LinearLayout hand = group("יד אחת");
        segmented(hand, new String[]{"כבוי", "שמאל", "ימין"}, () -> s.hand, v -> {
            s.hand = v;
            if (v != YugiKbdSettings.HAND_NONE) s.mode = YugiKbdSettings.DOCKED;
        });

        // ---- feedback
        LinearLayout fb = group("משוב");
        toggle(fb, "רטט", s.haptics, on -> s.haptics = on);
        hair(fb);
        toggle(fb, "צליל הקשה", s.sound, on -> s.sound = on);

        TextView hint = YugiTheme.text(ctx, "השינויים חלים על המקלדת מיד. לחיצה ארוכה על מקש השפה פותחת את המסך הזה.", 12, 400, YugiTheme.TEXT_FAINT);
        hint.setPadding(dp(4), dp(12), dp(4), 0);
        body.addView(hint);
    }

    // ---------------------------------------------------------------- building blocks

    private interface IntSetter { void set(int v); }
    private interface IntGetter { int get(); }
    private interface BoolSetter { void set(boolean v); }

    private LinearLayout group(String title) {
        Context c = getContext();
        TextView t = YugiTheme.text(c, title, 13, 500, YugiTheme.TEXT_DIM);
        t.setPadding(dp(14), dp(18), dp(14), dp(6));
        body.addView(t);
        LinearLayout g = new LinearLayout(c);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(YugiTheme.rounded(c, YugiTheme.SURFACE, YugiTheme.RADIUS_M));
        body.addView(g, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return g;
    }

    private void hair(LinearLayout g) {
        View h = new View(getContext());
        h.setBackgroundColor(YugiTheme.STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.setMarginStart(dp(14));
        g.addView(h, lp);
    }

    private void slider(LinearLayout g, String label, int min, int max, int value, IntSetter setter) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(4));
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(YugiTheme.text(c, label, 15, 400, YugiTheme.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView val = YugiTheme.text(c, pct(value), 14, 500, YugiTheme.TEXT_DIM);
        val.setTextDirection(TEXT_DIRECTION_LTR);
        top.addView(val);
        row.addView(top);
        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        YugiTheme.styleSlider(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (!user) return;
                setter.set(p + min);
                val.setText(pct(p + min));
                s.apply(getContext()); // live: the keyboard below resizes as the thumb moves
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        row.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        g.addView(row);
    }

    private static String pct(int v) { return String.format(Locale.US, "%d%%", v); }

    /** iOS-style segmented control: a track with a lighter pill on the chosen segment. */
    private void segmented(LinearLayout g, String[] labels, IntGetter get, IntSetter setter) {
        Context c = getContext();
        LinearLayout track = new LinearLayout(c);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(YugiTheme.rounded(c, 0xFF11151C, 10));
        track.setPadding(dp(3), dp(3), dp(3), dp(3));
        TextView[] segs = new TextView[labels.length];
        Runnable refresh = () -> {
            for (int i = 0; i < segs.length; i++) {
                boolean on = get.get() == i;
                segs[i].setBackground(on ? YugiTheme.rounded(c, YugiTheme.SURFACE_HI, 8) : null);
                segs[i].setTextColor(on ? YugiTheme.TEXT : YugiTheme.TEXT_DIM);
                segs[i].setSelected(on);
            }
        };
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = YugiTheme.text(c, labels[i], 14, 500, YugiTheme.TEXT_DIM);
            t.setGravity(Gravity.CENTER);
            t.setContentDescription(labels[i]);
            t.setOnClickListener(v -> {
                if (get.get() == idx) return;
                setter.set(idx);
                for (Runnable r : refreshers) r.run();
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                s.apply(getContext());
            });
            segs[i] = t;
            track.addView(t, new LinearLayout.LayoutParams(0, dp(42), 1));
        }
        refreshers.add(refresh);
        refresh.run();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(12), dp(12), dp(12));
        g.addView(track, lp);
    }

    private void toggle(LinearLayout g, String label, boolean value, BoolSetter setter) {
        Context c = getContext();
        Switch sw = new Switch(c);
        sw.setText(label);
        sw.setTextColor(YugiTheme.TEXT);
        sw.setTypeface(YugiTheme.font(c, 400));
        sw.setTextSize(15);
        sw.setChecked(value);
        sw.setPadding(dp(14), 0, dp(14), 0);
        sw.setThumbTintList(ColorStateList.valueOf(YugiTheme.TEXT));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0xFF34C759, 0xFF3A3F4B})); // iOS switch green when on
        sw.setOnCheckedChangeListener((b, on) -> { setter.set(on); s.apply(getContext()); });
        g.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
    }

    // ---------------------------------------------------------------- open / close

    private void appear() {
        sheet.setAlpha(0f);
        sheet.setScaleX(0.94f);
        sheet.setScaleY(0.94f);
        spring(sheet, DynamicAnimation.SCALE_X, 1f);
        spring(sheet, DynamicAnimation.SCALE_Y, 1f);
        sheet.animate().alpha(1f).setDuration(140).start();
    }

    private static void spring(View v, DynamicAnimation.ViewProperty p, float to) {
        SpringAnimation a = new SpringAnimation(v, p, to);
        a.getSpring().setDampingRatio(0.82f).setStiffness(SpringForce.STIFFNESS_MEDIUM);
        a.start();
    }

    public void close() {
        if (getParent() == null) return;
        s.apply(getContext());
        sheet.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(120).withEndAction(() -> {
            ViewGroup p = (ViewGroup) getParent();
            if (p != null) p.removeView(this);
        }).start();
        if (onClosed != null) onClosed.run();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        YugiKbdSettings.addListener(onChange);
    }

    @Override
    protected void onDetachedFromWindow() {
        YugiKbdSettings.removeListener(onChange);
        super.onDetachedFromWindow();
    }

    /** Touches on the live keyboard pass through; anywhere else outside the sheet closes it. */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        if (keyboardPanel().contains((int) e.getX(), (int) e.getY())) return false;
        close();
        return true;
    }

    /** The keyboard's visible panel, in this view's coordinates. */
    private Rect keyboardPanel() {
        if (keyboard == null || keyboard.getVisibility() != VISIBLE || !(keyboard instanceof YugiKeyboard)) return new Rect();
        float[] b = ((YugiKeyboard) keyboard).panelBounds();
        int[] me = new int[2], kb = new int[2];
        getLocationInWindow(me);
        keyboard.getLocationInWindow(kb);
        float dx = kb[0] - me[0], dy = kb[1] - me[1];
        return new Rect((int) (b[0] + dx), (int) (b[1] + dy), (int) (b[2] + dx), (int) (b[3] + dy));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int cw = Math.min(dp(420), w - dp(24));
        int room = roomAbove(h);
        sheet.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST));
    }

    /** Height free above the keyboard (at least enough for a few rows; the sheet scrolls). */
    private int roomAbove(int h) {
        Rect kb = keyboardPanel();
        int bottom = kb.isEmpty() || kb.top < h / 3 ? h - dp(12) : kb.top - dp(12);
        return Math.max(dp(140), bottom - dp(12)); // the sheet scrolls when the room is short
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        Rect kb = keyboardPanel();
        int bottom = kb.isEmpty() || kb.top < h / 3 ? h - dp(12) : kb.top - dp(12);
        int left = (w - sheet.getMeasuredWidth()) / 2;
        int top = Math.max(dp(12), bottom - sheet.getMeasuredHeight());
        sheet.layout(left, top, left + sheet.getMeasuredWidth(), top + sheet.getMeasuredHeight());
        sheet.setPivotX(sheet.getMeasuredWidth() / 2f);
        sheet.setPivotY(sheet.getMeasuredHeight());
    }

    public Rect sheetRect() {
        return new Rect(sheet.getLeft(), sheet.getTop(), sheet.getRight(), sheet.getBottom());
    }
}

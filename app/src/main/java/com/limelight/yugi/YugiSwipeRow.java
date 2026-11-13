package com.limelight.yugi;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;

/**
 * A list row with iOS-style swipe actions: swiping the row to the left slides it over a strip of actions on the
 * right edge (it follows the finger, rubber-bands past the end, a throw is projected, release springs open or
 * shut). A destructive action asks inside the row ("לסיים ולמחוק?" + cancel / confirm), never a system dialog.
 * Removing collapses the row's height on a spring.
 */
@SuppressLint("ViewConstructor")
class YugiSwipeRow extends FrameLayout {
    static final class Action {
        final String label;
        final int color;
        final String confirm;     // null: runs at once; else the in-row question
        final String confirmYes;
        final Runnable run;
        Action(String label, int color, String confirm, String confirmYes, Runnable run) {
            this.label = label; this.color = color; this.confirm = confirm; this.confirmYes = confirmYes; this.run = run;
        }
    }

    interface Listener { void onOpened(YugiSwipeRow row); }

    static final int ACTION_DP = 92;

    final View content;
    private final LinearLayout actions;
    private final List<TextView> actionViews = new ArrayList<>();
    private LinearLayout confirmStrip;
    private final SpringAnimation tx;
    private final int slop;
    private float downX, downY, startTx;
    private boolean tracking, dragging;
    private VelocityTracker vt;
    private boolean open;
    private Listener listener;
    private float heightFraction = 1f;
    private int fullHeight = -1;

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    private static final FloatPropertyCompat<YugiSwipeRow> HEIGHT = new FloatPropertyCompat<YugiSwipeRow>("rowHeight") {
        @Override public float getValue(YugiSwipeRow r) { return r.heightFraction; }
        @Override public void setValue(YugiSwipeRow r, float v) { r.heightFraction = Math.max(0f, v); r.requestLayout(); }
    };

    YugiSwipeRow(Context c, View content, List<Action> list) {
        super(c);
        this.content = content;
        setLayoutDirection(LAYOUT_DIRECTION_LTR); // actions always on the right edge (revealed by a left swipe)
        setClipChildren(true);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        for (Action a : list) {
            TextView b = YugiTheme.text(c, a.label, 14, 600, 0xFFFFFFFF);
            b.setGravity(Gravity.CENTER);
            b.setBackgroundColor(a.color);
            b.setPadding(dp(6), 0, dp(6), 0);
            b.setMaxLines(2);
            b.setContentDescription(a.label);
            b.setTag("action");
            b.setOnClickListener(v -> onAction(a));
            actions.addView(b, new LinearLayout.LayoutParams(dp(ACTION_DP), ViewGroup.LayoutParams.MATCH_PARENT));
            actionViews.add(b);
        }
        addView(actions, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        actions.setVisibility(INVISIBLE);
        addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tx = new SpringAnimation(content, DynamicAnimation.TRANSLATION_X);
        tx.setSpring(new SpringForce(0).setStiffness(600f).setDampingRatio(0.86f));
        tx.addUpdateListener((a, v, vel) -> actions.setVisibility(v < -0.5f ? VISIBLE : INVISIBLE));
        tx.addEndListener((a, canceled, v, vel) -> actions.setVisibility(v < -0.5f ? VISIBLE : INVISIBLE));
    }

    void setListener(Listener l) { listener = l; }

    private int actionsWidth() { return actionViews.size() * dp(ACTION_DP); }

    boolean isOpen() { return open; }

    void setOpen(boolean o, boolean animate) {
        open = o;
        float target = o ? -actionsWidth() : 0;
        if (o) actions.setVisibility(VISIBLE);
        if (animate) tx.animateToFinalPosition(target);
        else { tx.cancel(); content.setTranslationX(target); actions.setVisibility(o ? VISIBLE : INVISIBLE); }
        if (o && listener != null) listener.onOpened(this);
    }

    private void onAction(Action a) {
        if (a.confirm == null) { setOpen(false, true); a.run.run(); return; }
        showConfirm(a);
    }

    /** The in-row question: the row's face is replaced by "question · ביטול · confirm" (no dialog). */
    void showConfirm(Action a) {
        Context c = getContext();
        if (confirmStrip != null) removeView(confirmStrip);
        confirmStrip = new LinearLayout(c);
        confirmStrip.setOrientation(LinearLayout.HORIZONTAL);
        confirmStrip.setGravity(Gravity.CENTER_VERTICAL);
        confirmStrip.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        confirmStrip.setBackgroundColor(YugiTheme.CARD);
        confirmStrip.setPadding(dp(16), dp(8), dp(12), dp(8));
        confirmStrip.setClickable(true);
        confirmStrip.setTag("confirm");
        TextView q = YugiTheme.text(c, a.confirm, 15, 600, YugiTheme.TEXT);
        q.setMaxLines(2);
        q.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        q.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        confirmStrip.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView cancel = YugiTheme.pill(c, "ביטול", 0x1FFFFFFF, YugiTheme.TEXT);
        cancel.setOnClickListener(v -> hideConfirm());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        clp.setMarginStart(dp(8));
        confirmStrip.addView(cancel, clp);
        TextView yes = YugiTheme.pill(c, a.confirmYes, a.color, 0xFFFFFFFF);
        yes.setOnClickListener(v -> { hideConfirm(); a.run.run(); });
        LinearLayout.LayoutParams ylp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        ylp.setMarginStart(dp(8));
        confirmStrip.addView(yes, ylp);
        addView(confirmStrip, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setOpen(false, true);
        confirmStrip.setAlpha(0f);
        confirmStrip.setTranslationX(dp(24));
        YugiSpring.to(confirmStrip, DynamicAnimation.ALPHA, 1f);
        YugiSpring.to(confirmStrip, DynamicAnimation.TRANSLATION_X, 0f);
    }

    void hideConfirm() {
        if (confirmStrip == null) return;
        View s = confirmStrip;
        confirmStrip = null;
        removeView(s);
    }

    boolean isConfirming() { return confirmStrip != null; }

    /** Collapses the row (height and opacity on a spring), then {@code done}. */
    void collapse(Runnable done) {
        fullHeight = getHeight();
        SpringAnimation h = new SpringAnimation(this, HEIGHT);
        h.setSpring(new SpringForce(0f).setStiffness(500f).setDampingRatio(1f));
        h.setMinimumVisibleChange(0.002f);
        h.addEndListener((a, canceled, v, vel) -> { if (done != null) done.run(); });
        YugiSpring.to(this, DynamicAnimation.ALPHA, 0f);
        h.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        if (fullHeight >= 0) setMeasuredDimension(getMeasuredWidth(), Math.round(fullHeight * heightFraction));
    }

    // ---------------------------------------------------------------- gestures

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (confirmStrip != null || actionViews.isEmpty()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(e);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                if (vt != null) vt.addMovement(e);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f && (dx < 0 || open || content.getTranslationX() < 0)) {
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
                // a tap on an open row's face shuts it (and doesn't open the session)
                if (open && e.getX() < getWidth() - actionsWidth()) { setOpen(false, true); end(); return true; }
                end();
                return false;
            case MotionEvent.ACTION_CANCEL:
                end();
                return false;
            default:
                return false;
        }
    }

    private void begin(MotionEvent e) {
        tx.cancel();
        tracking = true;
        dragging = false;
        downX = e.getX();
        downY = e.getY();
        startTx = content.getTranslationX();
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain();
        vt.addMovement(e);
    }

    private void end() {
        tracking = false;
        dragging = false;
        if (vt != null) { vt.recycle(); vt = null; }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !actionViews.isEmpty() && confirmStrip == null) { begin(e); return true; }
            if (tracking && e.getActionMasked() == MotionEvent.ACTION_MOVE) return onInterceptTouchEvent(e) || true;
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) end();
            return tracking;
        }
        if (vt != null) vt.addMovement(e);
        float w = actionsWidth();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float t = startTx + e.getX() - downX;
                if (t > 0) t = t * 0.2f;                        // rubber band to the right
                else if (t < -w) t = -w + (t + w) * 0.35f;      // and past the actions
                content.setTranslationX(t);
                actions.setVisibility(t < -0.5f ? VISIBLE : INVISIBLE);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float v = 0;
                if (vt != null) { vt.computeCurrentVelocity(1000); v = vt.getXVelocity(); }
                float projected = content.getTranslationX() + YugiChrome.project(v) * 0.5f;
                boolean o = e.getActionMasked() == MotionEvent.ACTION_UP && projected < -w / 2f;
                tx.setStartVelocity(v);
                setOpen(o, true);
                end();
                return true;
            }
            default:
                return true;
        }
    }

    // ---------------------------------------------------------------- checks

    /** Action buttons in this row's coordinates (only while revealed). */
    List<Rect> actionRects() {
        List<Rect> out = new ArrayList<>();
        if (actions.getVisibility() != VISIBLE) return out;
        for (TextView b : actionViews) {
            out.add(new Rect(actions.getLeft() + b.getLeft(), actions.getTop() + b.getTop(),
                    actions.getLeft() + b.getRight(), actions.getTop() + b.getBottom()));
        }
        return out;
    }

    /** Where the row's face currently is (with its slide). */
    Rect contentRect() {
        int x = Math.round(content.getLeft() + content.getTranslationX());
        return new Rect(x, content.getTop(), x + content.getWidth(), content.getBottom());
    }

    void settleForTest() { if (tx.isRunning()) tx.skipToEnd(); }
}

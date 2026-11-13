package com.limelight.yugi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.limelight.R;

/**
 * The floating "יוגי" button: tap opens the chat, drag moves it anywhere, and on release it springs to the
 * nearest side edge (the throw velocity is projected, like iOS PiP). The spot is remembered (side + height
 * fraction), so it comes back in the same place on both screens and after rotation.
 */
@SuppressLint("ViewConstructor")
public class YugiChatBubble extends ImageView {
    private static final String PREFS = "yugi_chat_bubble";
    private static final float SIZE_DP = 56, MARGIN_DP = 12;

    private final Runnable onTap;
    private final SharedPreferences prefs;
    private final int touchSlop;
    private final SpringAnimation springX, springY, springScale, springScaleY;
    private VelocityTracker velocity;
    private float downRawX, downRawY, startX, startY;
    private boolean dragging, placed;
    private boolean right;       // which edge it rests on
    private float yFraction;     // 0..1 of the free height

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    YugiChatBubble(Context ctx, Runnable onTap) {
        super(ctx);
        this.onTap = onTap;
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        right = prefs.getBoolean("right", true);
        yFraction = prefs.getFloat("y", 0.55f);
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();

        setImageResource(R.drawable.yugi_ic_yugi);
        setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.ACCENT));
        setScaleType(ScaleType.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(YugiTheme.GLASS);
        bg.setStroke(Math.max(1, dp(1)), YugiTheme.STROKE);
        setBackground(bg);
        setElevation(dp(8));
        setContentDescription("יוגי");
        setClickable(true);
        setFocusable(true);
        setVisibility(INVISIBLE); // until the first layout places it

        // Apple-like: critically-damped-ish with a hint of overshoot, interruptible at any time
        springX = new SpringAnimation(this, DynamicAnimation.X);
        springX.setSpring(new SpringForce().setStiffness(500f).setDampingRatio(0.78f));
        springY = new SpringAnimation(this, DynamicAnimation.Y);
        springY.setSpring(new SpringForce().setStiffness(500f).setDampingRatio(0.78f));
        springScale = new SpringAnimation(this, DynamicAnimation.SCALE_X);
        springScale.setSpring(new SpringForce(1f).setStiffness(1500f).setDampingRatio(0.6f));
        springScaleY = new SpringAnimation(this, DynamicAnimation.SCALE_Y);
        springScaleY.setSpring(new SpringForce(1f).setStiffness(1500f).setDampingRatio(0.6f));

        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!placed) { placed = true; snapToSaved(false); setVisibility(VISIBLE); }
        });
    }

    /** Adds the bubble over an activity's content (on top of everything added before it). */
    static YugiChatBubble attach(Activity a, Runnable onTap) {
        YugiChatBubble b = new YugiChatBubble(a, onTap);
        int s = b.dp(SIZE_DP);
        a.addContentView(b, new FrameLayout.LayoutParams(s, s));
        View parent = (View) b.getParent();
        parent.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (b.placed && (r - l != or - ol || bt - t != ob - ot)) b.snapToSaved(false); // rotation / fold
        });
        return b;
    }

    /** Shown while the chat is closed. */
    void setShown(boolean shown) {
        animate().cancel();
        if (shown) {
            if (getVisibility() != VISIBLE && placed) { setAlpha(0f); setVisibility(VISIBLE); }
            animate().alpha(1f).setDuration(160).start();
        } else {
            animate().alpha(0f).setDuration(120).withEndAction(() -> setVisibility(GONE)).start();
        }
    }

    private int parentW() { return ((View) getParent()).getWidth(); }
    private int parentH() { return ((View) getParent()).getHeight(); }
    private float minX() { return dp(MARGIN_DP); }
    private float maxX() { return parentW() - getWidth() - dp(MARGIN_DP); }
    private float minY() { return dp(MARGIN_DP); }
    private float maxY() { return parentH() - getHeight() - dp(MARGIN_DP); }

    private void snapToSaved(boolean animated) {
        if (parentW() <= 0 || getWidth() <= 0) return;
        float tx = right ? maxX() : minX();
        float ty = minY() + yFraction * Math.max(0, maxY() - minY());
        if (animated) { springX.animateToFinalPosition(tx); springY.animateToFinalPosition(ty); }
        else { springX.cancel(); springY.cancel(); setX(tx); setY(ty); }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                springX.cancel();
                springY.cancel();
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startX = getX();
                startY = getY();
                dragging = false;
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(e);
                press(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocity != null) velocity.addMovement(e);
                float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) dragging = true;
                if (dragging) {
                    setX(clamp(startX + dx, 0, parentW() - getWidth()));
                    setY(clamp(startY + dy, 0, parentH() - getHeight()));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                press(false);
                float vx = 0, vy = 0;
                if (velocity != null) {
                    velocity.addMovement(e);
                    velocity.computeCurrentVelocity(1000);
                    vx = velocity.getXVelocity();
                    vy = velocity.getYVelocity();
                    velocity.recycle();
                    velocity = null;
                }
                if (!dragging) {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) { performClick(); }
                    return true;
                }
                // Project where a throw would coast to (UIScrollView "normal" deceleration), then pick the edge
                float projX = getX() + project(vx), projY = getY() + project(vy);
                right = projX + getWidth() / 2f > parentW() / 2f;
                float ty = clamp(projY, minY(), maxY());
                yFraction = maxY() > minY() ? (ty - minY()) / (maxY() - minY()) : 0.5f;
                prefs.edit().putBoolean("right", right).putFloat("y", yFraction).apply();
                springX.setStartVelocity(vx).animateToFinalPosition(right ? maxX() : minX());
                springY.setStartVelocity(vy).animateToFinalPosition(ty);
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (onTap != null) onTap.run();
        return true;
    }

    private void press(boolean down) {
        springScale.animateToFinalPosition(down ? 0.9f : 1f);
        springScaleY.animateToFinalPosition(down ? 0.9f : 1f);
    }

    /** Distance a throw at v px/s would travel with deceleration rate 0.998 per ms (iOS normal). */
    private static float project(float v) {
        float rate = 0.998f;
        return (v / 1000f) * rate / (1f - rate);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override
    protected void onDetachedFromWindow() {
        springX.cancel();
        springY.cancel();
        if (velocity != null) { velocity.recycle(); velocity = null; }
        super.onDetachedFromWindow();
    }
}

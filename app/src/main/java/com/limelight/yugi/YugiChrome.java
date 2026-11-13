package com.limelight.yugi;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one YugiPC overlay system for both screens ("המחשב שלי" and "משחקים"):
 * a single floating toolbar (48dp Material buttons on a dark translucent pill), top-right by default,
 * which shrinks to a small handle after 3 s without touch, and a small LTR stats pill in the opposite top corner.
 * The toolbar and the handle can be dragged (from the handle, or from the bar's empty area / any of its buttons
 * once the finger passes the touch slop); on release they spring to the nearest side edge like the Yugi bubble,
 * and the spot (side + height fraction) is remembered and kept on screen after rotation / fold.
 * Everything else (pad, panels) stays below {@link #getReservedTop()} so nothing overlaps the default spot.
 * <p>
 * With a primary button (Yugi) the chrome is the app's one floating control: the Yugi orb sits at the bar's
 * outer end (the docked side), after a hairline, and stays when the bar collapses (the collapsed bar is
 * [more | Yugi], so the chat is always one tap away). While the chat is open the whole chrome folds away into
 * the orb ({@link #setChatOpen}) and the chat card grows out of {@link #primaryRectInWindow()}.
 */
public class YugiChrome extends FrameLayout {
    public static final long AUTO_HIDE_MS = 3000;

    public static final class Button {
        int icon;
        final String label;
        final Runnable onClick;
        Runnable onLongClick;
        boolean active;
        ImageButton view;
        int liveColor;      // 0 = no live dot
        View dot;           // small dot on the button's corner (in the chrome, follows the bar)
        YugiChrome owner;

        public Button(int icon, String label, Runnable onClick) {
            this.icon = icon;
            this.label = label;
            this.onClick = onClick;
        }

        public Button longClick(Runnable r) { onLongClick = r; return this; }

        public void setIcon(int icon) {
            this.icon = icon;
            if (view != null) view.setImageResource(icon);
        }

        public void setActive(boolean on) {
            active = on;
            if (view != null) YugiTheme.setActive(view, on);
        }

        /**
         * A small dot on the button's corner while something runs live (the phone camera or mic for the
         * desktop); {@code color} 0 hides it. Comes and goes on a spring, also shown on the collapsed handle.
         */
        public void setLive(int color) {
            liveColor = color;
            if (owner != null) owner.updateDots();
        }
    }

    /** iOS privacy-indicator colors. */
    public static final int LIVE_CAMERA = 0xFF30D158;
    public static final int LIVE_MIC = 0xFFFF9F0A;

    public interface ReservedTopListener { void onReservedTop(int px); }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout bar;
    private final ImageButton handle;
    private final TextView hud;
    private final View handleDot;
    private final List<Button> buttons = new ArrayList<>();
    private boolean collapsed;
    private boolean autoHide = true;
    private int reservedTop;
    private ReservedTopListener reservedTopListener;
    private final Runnable collapseRunnable = () -> setCollapsed(true);
    // the primary (Yugi) orb, its hairline and the "more" button of the collapsed bar; null without a primary
    private final Button primary;
    private ImageButton primaryView, moreBtn;
    private View divider;
    private boolean wantVisible = true, chatOpen, interactive = true;

    // ---- drag state: gx/gy = the bar's top-left (the handle hangs off the docked side of the same box)
    private static final String PREFS = "yugi_chrome";
    private final SharedPreferences prefs;
    private final int touchSlop;
    private final SpringAnimation springX, springY;
    private boolean dockRight;   // which side edge it rests on
    private float yFraction;     // 0..1 of the free height
    private float gx, gy;
    private boolean placed, tracking, dragging;
    private float downX, downY, startGx, startGy;
    private VelocityTracker velocity;
    private int lastW, lastH, lastBarW, lastBarH;

    private static final FloatPropertyCompat<YugiChrome> GX = new FloatPropertyCompat<YugiChrome>("gx") {
        @Override public float getValue(YugiChrome c) { return c.gx; }
        @Override public void setValue(YugiChrome c, float v) { c.gx = v; c.applyPosition(); }
    };
    private static final FloatPropertyCompat<YugiChrome> GY = new FloatPropertyCompat<YugiChrome>("gy") {
        @Override public float getValue(YugiChrome c) { return c.gy; }
        @Override public void setValue(YugiChrome c, float v) { c.gy = v; c.applyPosition(); }
    };

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    public YugiChrome(Context ctx, List<Button> items) { this(ctx, items, null); }

    public YugiChrome(Context ctx, List<Button> items, Button primaryButton) {
        super(ctx);
        primary = primaryButton;
        setClipChildren(false);
        // Fixed placement in every locale: toolbar top-right, stats top-left
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        // The chrome itself never takes touches; only its children do.
        setClickable(false);
        setFocusable(false);
        YugiTheme.keepOnTop(this);
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dockRight = prefs.getBoolean("right", true);
        yFraction = prefs.getFloat("y", 0f);
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        // Same Apple-like spring as the Yugi bubble: a hint of overshoot, interruptible at any time
        springX = new SpringAnimation(this, GX);
        springX.setSpring(new SpringForce().setStiffness(500f).setDampingRatio(0.78f));
        springY = new SpringAnimation(this, GY);
        springY.setSpring(new SpringForce().setStiffness(500f).setDampingRatio(0.78f));

        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(LAYOUT_DIRECTION_LTR); // fixed order: menu is always outermost
        bar.setBackground(YugiTheme.roundedStroke(ctx, YugiTheme.GLASS, 28, YugiTheme.STROKE));
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setElevation(dp(6));
        for (int i = 0; i < items.size(); i++) {
            Button b = items.get(i);
            ImageButton v = YugiTheme.iconButton(ctx, b.icon, b.label);
            b.view = v;
            YugiTheme.setActive(v, b.active);
            v.setOnClickListener(x -> { b.onClick.run(); poke(); });
            if (b.onLongClick != null) {
                v.setOnLongClickListener(x -> { b.onLongClick.run(); poke(); return true; });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
            if (i > 0) lp.leftMargin = dp(4);
            bar.addView(v, lp);
            buttons.add(b);
            b.owner = this;
        }
        if (primary != null) {
            primaryView = new ImageButton(ctx);
            primaryView.setImageResource(primary.icon);
            primaryView.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            primaryView.setScaleType(ImageView.ScaleType.CENTER);
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(Color.WHITE);
            primaryView.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), YugiTheme.orb(), mask));
            primaryView.setContentDescription(primary.label);
            primaryView.setFocusable(false);
            primaryView.setOnClickListener(x -> { primary.onClick.run(); if (!chatOpen) poke(); });
            primary.view = primaryView;
            divider = new View(ctx);
            divider.setBackgroundColor(0x33FFFFFF);
            moreBtn = YugiTheme.iconButton(ctx, com.limelight.R.drawable.yugi_ic_more, "כל הכפתורים");
            moreBtn.setOnClickListener(x -> { setCollapsed(false); poke(); });
            moreBtn.setVisibility(GONE);
            orderBar();
        }
        addView(bar, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (Button b : buttons) {
            b.dot = makeDot(ctx);
            addView(b.dot, new LayoutParams(dp(DOT), dp(DOT)));
        }

        handle = YugiTheme.iconButton(ctx, com.limelight.R.drawable.yugi_ic_more, "סרגל YugiPC");
        GradientDrawable hbg = new GradientDrawable();
        hbg.setShape(GradientDrawable.OVAL);
        hbg.setColor(YugiTheme.GLASS);
        hbg.setStroke(dp(1), YugiTheme.STROKE);
        handle.setBackground(hbg);
        handle.setAlpha(0.8f);
        handle.setOnClickListener(v -> { setCollapsed(false); poke(); });
        handle.setVisibility(GONE);
        addView(handle, new LayoutParams(dp(48), dp(48)));

        hud = new TextView(ctx);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hud.setTypeface(YugiTheme.font(ctx, 500));
        hud.setTextDirection(TEXT_DIRECTION_LTR);
        hud.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        hud.setGravity(Gravity.CENTER);
        hud.setMinHeight(dp(32));
        hud.setPadding(dp(12), dp(6), dp(12), dp(6));
        hud.setBackground(YugiTheme.roundedStroke(ctx, YugiTheme.GLASS, 16, YugiTheme.STROKE));
        hud.setVisibility(GONE);
        addView(hud, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        handleDot = makeDot(ctx);
        addView(handleDot, new LayoutParams(dp(DOT), dp(DOT)));

        // show / hide on a spring; a hidden chrome leaves the layout once faded
        YugiSpring.of(this, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA).addEndListener((a, canceled, v, vel) -> {
            if (!canceled && !interactive) setVisibility(GONE);
        });
    }

    /** Bar order: [more] buttons… | Yugi, mirrored when docked left, so the orb is always at the outer end. */
    private void orderBar() {
        if (primaryView == null) return;
        List<View> seq = new ArrayList<>();
        seq.add(moreBtn);
        for (Button b : buttons) seq.add(b.view);
        seq.add(divider);
        seq.add(primaryView);
        if (!dockRight) java.util.Collections.reverse(seq);
        bar.removeAllViews();
        for (View v : seq) {
            LinearLayout.LayoutParams lp = v == divider
                    ? new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(24))
                    : new LinearLayout.LayoutParams(dp(48), dp(48));
            lp.gravity = Gravity.CENTER_VERTICAL;
            int m = v == divider ? dp(6) : dp(2);
            lp.leftMargin = m;
            lp.rightMargin = m;
            bar.addView(v, lp);
        }
    }

    private static void popIn(View v) {
        v.setAlpha(0f);
        v.setScaleX(0.6f);
        v.setScaleY(0.6f);
        YugiSpring.to(v, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 1f);
        YugiSpring.to(v, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1f);
        YugiSpring.to(v, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1f);
    }

    private static final int DOT = 8; // dp

    private View makeDot(Context ctx) {
        View d = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(LIVE_CAMERA);
        g.setStroke(dp(1.5f), YugiTheme.BRAND_BG); // reads on the light icon and on the dark glass
        d.setBackground(g);
        d.setClickable(false);
        d.setFocusable(false);
        d.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        d.setElevation(dp(7)); // above the bar
        d.setAlpha(0f);
        d.setScaleX(0f);
        d.setScaleY(0f);
        return d;
    }

    private static void springDot(View d, boolean on, int color) {
        if (on && d.getBackground() instanceof GradientDrawable) ((GradientDrawable) d.getBackground()).setColor(color);
        YugiSpring.to(d, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, on ? 1f : 0f);
        YugiSpring.to(d, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, on ? 1f : 0f);
        YugiSpring.to(d, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, on ? 1f : 0f);
    }

    /** Live dots: on the buttons while the bar is open, on the handle while it is collapsed. */
    void updateDots() {
        int handleColor = 0;
        for (Button b : buttons) {
            if (b.liveColor == LIVE_CAMERA || (handleColor == 0 && b.liveColor != 0)) handleColor = b.liveColor;
            springDot(b.dot, b.liveColor != 0 && !collapsed, b.liveColor);
        }
        springDot(handleDot, handleColor != 0 && collapsed, handleColor);
        positionDots();
    }

    private void positionDots() {
        float inset = dp(7);
        for (Button b : buttons) {
            if (b.view == null) continue;
            b.dot.setTranslationX(bar.getTranslationX() + b.view.getRight() - inset - b.dot.getWidth() / 2f);
            b.dot.setTranslationY(bar.getTranslationY() + b.view.getTop() + inset - b.dot.getHeight() / 2f);
        }
        View hv = primaryView != null ? moreBtn : handle;
        float hx = primaryView != null ? bar.getTranslationX() + moreBtn.getLeft() : handle.getTranslationX();
        float hy = primaryView != null ? bar.getTranslationY() + moreBtn.getTop() : handle.getTranslationY();
        handleDot.setTranslationX(hx + hv.getWidth() - inset - handleDot.getWidth() / 2f);
        handleDot.setTranslationY(hy + inset - handleDot.getHeight() / 2f);
    }

    /** Adds the chrome over an activity's content. */
    public static YugiChrome attach(Activity a, List<Button> items) { return attach(a, items, null); }

    /** Adds the chrome with a primary orb (Yugi) at the bar's outer end. */
    public static YugiChrome attach(Activity a, List<Button> items, Button primary) {
        YugiChrome c = new YugiChrome(a, items, primary);
        a.addContentView(c, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        c.poke();
        return c;
    }

    // ---------------------------------------------------------------- public API

    public void setReservedTopListener(ReservedTopListener l) {
        reservedTopListener = l;
        if (l != null && reservedTop > 0) l.onReservedTop(reservedTop);
    }

    /** Pixels from the top that belong to the chrome; the pad and panels stay below this line. */
    public int getReservedTop() { return reservedTop; }

    public void setAutoHide(boolean on) {
        autoHide = on;
        poke();
    }

    /** Keeps the toolbar expanded for another AUTO_HIDE_MS. */
    public void poke() {
        ui.removeCallbacks(collapseRunnable);
        if (collapsed) setCollapsed(false);
        if (autoHide) ui.postDelayed(collapseRunnable, AUTO_HIDE_MS);
    }

    public void setCollapsed(boolean c) {
        boolean was = collapsed;
        collapsed = c;
        if (primaryView != null) {
            // one bar: collapsed it keeps only [more | Yugi]
            for (Button b : buttons) b.view.setVisibility(c ? GONE : VISIBLE);
            divider.setVisibility(c ? GONE : VISIBLE);
            moreBtn.setVisibility(c ? VISIBLE : GONE);
            if (was && !c) for (Button b : buttons) popIn(b.view);
            if (!was && c) popIn(moreBtn);
        } else {
            bar.setVisibility(c ? GONE : VISIBLE);
            handle.setVisibility(c ? VISIBLE : GONE);
        }
        updateDots();
        if (placed && !dragging) {
            // a tap during a snap: finish at the resting spot so the opened bar is fully on screen
            springX.animateToFinalPosition(restX(dockRight));
            springY.animateToFinalPosition(clamp(gy, minGy(), maxGy()));
        }
        if (!c && autoHide) {
            ui.removeCallbacks(collapseRunnable);
            ui.postDelayed(collapseRunnable, AUTO_HIDE_MS);
        }
    }

    /** Hides the whole chrome (e.g. while editing the pad). */
    public void setChromeVisible(boolean visible) {
        wantVisible = visible;
        applyShown();
        if (visible && !chatOpen) poke();
    }

    /**
     * The chat opened from the Yugi orb: the chrome folds into the orb (fades and shrinks toward it on a
     * spring) and comes back the same way when the chat closes.
     */
    public void setChatOpen(boolean open) {
        if (chatOpen == open) return;
        chatOpen = open;
        applyShown();
        if (!open && wantVisible) poke();
    }

    public boolean isChatOpen() { return chatOpen; }

    private void applyShown() {
        boolean show = wantVisible && !chatOpen;
        interactive = show;
        if (primaryView != null && primaryView.getWidth() > 0) {
            // shrink toward the orb
            bar.setPivotX(primaryView.getLeft() + primaryView.getWidth() / 2f);
            bar.setPivotY(primaryView.getTop() + primaryView.getHeight() / 2f);
        }
        if (show) {
            if (getVisibility() != VISIBLE) {
                setAlpha(0f);
                setVisibility(VISIBLE);
            }
            YugiSpring.to(this, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 1f);
            YugiSpring.to(bar, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1f);
            YugiSpring.to(bar, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1f);
        } else if (getVisibility() == VISIBLE) {
            if (getAlpha() <= 0f || getWidth() == 0) { setAlpha(0f); setVisibility(GONE); return; }
            YugiSpring.to(this, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 0f);
            if (chatOpen) {
                YugiSpring.to(bar, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 0.6f);
                YugiSpring.to(bar, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 0.6f);
            }
        }
    }

    /** True while the chrome takes touches (shown, not folded into the chat). */
    public boolean isInteractive() { return interactive && getVisibility() == VISIBLE; }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!interactive) return false; // fading out: touches go through to the stream
        return super.dispatchTouchEvent(ev);
    }

    /** The Yugi orb in window coordinates (where the chat grows from), or null without a primary. */
    public Rect primaryRectInWindow() {
        if (primaryView == null || primaryView.getWidth() == 0) return null;
        int[] loc = new int[2];
        getLocationInWindow(loc);
        int x = loc[0] + Math.round(bar.getTranslationX()) + primaryView.getLeft();
        int y = loc[1] + Math.round(bar.getTranslationY()) + primaryView.getTop();
        return new Rect(x, y, x + primaryView.getWidth(), y + primaryView.getHeight());
    }

    /** The orb in this view's coordinates (checks). */
    public Rect primaryRect() {
        if (primaryView == null) return null;
        int x = Math.round(bar.getX()) + primaryView.getLeft(), y = Math.round(bar.getY()) + primaryView.getTop();
        return new Rect(x, y, x + primaryView.getWidth(), y + primaryView.getHeight());
    }

    public void setHudVisible(boolean on) {
        hud.setVisibility(on ? VISIBLE : GONE);
        requestLayout();
    }

    public boolean isHudVisible() { return hud.getVisibility() == VISIBLE; }

    /** "60 FPS · 98ms · 1280×1024", always left-to-right (bidi isolated). */
    public void setStats(Integer fps, Integer pingMs, int w, int h) {
        StringBuilder sb = new StringBuilder();
        sb.append(fps == null ? "–" : fps).append(" FPS · ");
        sb.append(pingMs == null ? "–" : pingMs).append("ms");
        if (w > 0 && h > 0) sb.append(" · ").append(w).append('×').append(h);
        hud.setText("⁦" + sb + "⁩");
    }

    public void setStatsText(String text) {
        hud.setText("⁦" + text + "⁩");
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int m = dp(12);
        int barW = bar.getMeasuredWidth(), barH = bar.getMeasuredHeight();
        // Children sit at the origin; their place on screen is a translation (so springs never relayout)
        bar.layout(0, 0, barW, barH);
        handle.layout(0, 0, handle.getMeasuredWidth(), handle.getMeasuredHeight());
        for (Button bt : buttons) bt.dot.layout(0, 0, bt.dot.getMeasuredWidth(), bt.dot.getMeasuredHeight());
        handleDot.layout(0, 0, handleDot.getMeasuredWidth(), handleDot.getMeasuredHeight());
        if (!placed || w != lastW || h != lastH || barW != lastBarW || barH != lastBarH) {
            // first layout, rotation, fold/unfold: back to the remembered side and height, inside the screen
            placed = true;
            lastW = w; lastH = h; lastBarW = barW; lastBarH = barH;
            springX.cancel();
            springY.cancel();
            endDrag();
            gx = restX(dockRight);
            gy = yFromFraction(yFraction);
        }
        applyPosition();

        // Reserved band and stats pill follow the default top row, so they never jump while dragging
        int top = m;
        int bottom = top + barH;
        if (hud.getVisibility() != GONE) {
            int hudW = hud.getMeasuredWidth(), hudH = hud.getMeasuredHeight();
            boolean fitsBeside = hudW + barW + 3 * m <= w;
            int hudTop = fitsBeside ? top + (barH - hudH) / 2 : top + barH + dp(8);
            // stats on the side away from the toolbar; under it (same side) when the screen is too narrow
            int hudLeft = dockRight ? m : w - m - hudW;
            if (!fitsBeside) hudLeft = dockRight ? w - m - hudW : m;
            hud.layout(hudLeft, hudTop, hudLeft + hudW, hudTop + hudH);
            bottom = Math.max(bottom, hudTop + hudH);
        }
        int reserved = bottom + dp(8);
        if (reserved != reservedTop) {
            reservedTop = reserved;
            if (reservedTopListener != null) post(() -> reservedTopListener.onReservedTop(reservedTop));
        }
    }

    // ---------------------------------------------------------------- drag

    private float margin() { return dp(12); }
    private float minGx() { return margin(); }
    private float maxGx() { return Math.max(margin(), getWidth() - margin() - bar.getWidth()); }
    private float minGy() { return margin(); }
    private float maxGy() { return Math.max(margin(), getHeight() - margin() - bar.getHeight()); }
    private float restX(boolean right) { return right ? maxGx() : minGx(); }
    private float yFromFraction(float f) { return minGy() + clamp(f, 0f, 1f) * (maxGy() - minGy()); }

    /** What the finger moves: the bar (always, with a primary) or the separate handle while collapsed. */
    private boolean handleMode() { return collapsed && primaryView == null; }

    /** The handle hangs off the docked side of the bar's box, vertically centred on it. */
    private float handleOffsetX() { return dockRight ? bar.getWidth() - handle.getWidth() : 0; }

    private void applyPosition() {
        bar.setTranslationX(gx);
        bar.setTranslationY(gy);
        handle.setTranslationX(gx + handleOffsetX());
        handle.setTranslationY(gy + (bar.getHeight() - handle.getHeight()) / 2f);
        positionDots();
    }

    /** True when (x, y) is on the visible part (bar or handle), in chrome coordinates. */
    private boolean hitsGroup(float x, float y) {
        View v = handleMode() ? handle : bar;
        if (v.getVisibility() != VISIBLE || getVisibility() != VISIBLE || !interactive) return false;
        return x >= v.getX() && x < v.getX() + v.getWidth() && y >= v.getY() && y < v.getY() + v.getHeight();
    }

    private void beginTrack(MotionEvent ev) {
        springX.cancel(); // interrupt a running snap: the bar stays exactly under the finger
        springY.cancel();
        tracking = true;
        dragging = false;
        downX = ev.getX();
        downY = ev.getY();
        startGx = gx;
        startGy = gy;
        ui.removeCallbacks(collapseRunnable); // no collapse under the finger
        if (velocity != null) velocity.recycle();
        velocity = VelocityTracker.obtain();
        velocity.addMovement(ev);
    }

    private boolean passedSlop(MotionEvent ev) {
        return Math.hypot(ev.getX() - downX, ev.getY() - downY) > touchSlop;
    }

    private void moveTo(MotionEvent ev) {
        View v = handleMode() ? handle : bar;
        float off = handleMode() ? handleOffsetX() : 0;
        gx = clamp(startGx + ev.getX() - downX, -off, Math.max(-off, getWidth() - v.getWidth() - off));
        gy = clamp(startGy + ev.getY() - downY, 0, Math.max(0, getHeight() - bar.getHeight()));
        applyPosition();
    }

    private void release(boolean fling) {
        float vx = 0, vy = 0;
        if (velocity != null && fling) {
            velocity.computeCurrentVelocity(1000);
            vx = velocity.getXVelocity();
            vy = velocity.getYVelocity();
        }
        // Project where the throw would coast to, then pick the nearest side edge (like iOS PiP)
        View v = handleMode() ? handle : bar;
        boolean right = snapRight(v.getX() + v.getWidth() / 2f + project(vx), getWidth());
        if (right != dockRight) {
            float hx = handle.getX();
            dockRight = right;
            if (handleMode()) gx = hx - handleOffsetX(); // the handle stays exactly under the finger
            orderBar(); // the orb moves to the new outer end
            applyPosition();
            requestLayout(); // the stats pill moves to the other side
        }
        float ty = clamp(gy + project(vy), minGy(), maxGy());
        yFraction = maxGy() > minGy() ? (ty - minGy()) / (maxGy() - minGy()) : 0f;
        prefs.edit().putBoolean("right", dockRight).putFloat("y", yFraction).apply();
        springX.setStartVelocity(vx).animateToFinalPosition(restX(dockRight));
        springY.setStartVelocity(vy).animateToFinalPosition(ty);
    }

    private void endDrag() {
        tracking = false;
        dragging = false;
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }

    private void scheduleCollapse() {
        ui.removeCallbacks(collapseRunnable);
        if (autoHide && !collapsed) ui.postDelayed(collapseRunnable, AUTO_HIDE_MS);
    }

    /** Nearest side edge for a box whose centre is at {@code centreX}. */
    static boolean snapRight(float centreX, int width) { return centreX > width / 2f; }

    /** Distance a throw at v px/s travels with deceleration rate 0.998 per ms (iOS normal). */
    static float project(float v) {
        float rate = 0.998f;
        return (v / 1000f) * rate / (1f - rate);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** True while the toolbar rests on the right edge. */
    public boolean isDockedRight() { return dockRight; }

    public boolean isCollapsed() { return collapsed; }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Buttons keep their taps and long presses; once the finger passes the touch slop the chrome drags
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (hitsGroup(ev.getX(), ev.getY())) beginTrack(ev); else endDrag();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                if (velocity != null) velocity.addMovement(ev);
                if (passedSlop(ev)) {
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (tracking) { endDrag(); scheduleCollapse(); }
                return false;
            default:
                return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Only reached when no button took the touch: the bar's empty area (padding, gaps)
                if (!hitsGroup(ev.getX(), ev.getY())) return false;
                beginTrack(ev);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                if (velocity != null) velocity.addMovement(ev);
                if (!dragging && passedSlop(ev)) {
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) moveTo(ev);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!tracking) return false;
                if (velocity != null) velocity.addMovement(ev);
                if (dragging) release(ev.getActionMasked() == MotionEvent.ACTION_UP);
                endDrag();
                scheduleCollapse();
                return true;
            default:
                return tracking;
        }
    }

    /** Bounding boxes of everything the chrome shows (for overlap checks). */
    public List<Rect> visibleRects() {
        List<Rect> out = new ArrayList<>();
        if (getVisibility() != VISIBLE || !interactive) return out;
        if (bar.getVisibility() == VISIBLE) {
            int bx = Math.round(bar.getX()), by = Math.round(bar.getY());
            for (int i = 0; i < bar.getChildCount(); i++) {
                View v = bar.getChildAt(i);
                if (v.getVisibility() != VISIBLE || v == divider) continue;
                out.add(new Rect(bx + v.getLeft(), by + v.getTop(), bx + v.getRight(), by + v.getBottom()));
            }
        }
        if (handle.getVisibility() == VISIBLE) {
            int hx = Math.round(handle.getX()), hy = Math.round(handle.getY());
            out.add(new Rect(hx, hy, hx + handle.getWidth(), hy + handle.getHeight()));
        }
        if (hud.getVisibility() == VISIBLE) {
            out.add(new Rect(hud.getLeft(), hud.getTop(), hud.getRight(), hud.getBottom()));
        }
        return out;
    }

    public Rect barRect() {
        int bx = Math.round(bar.getX()), by = Math.round(bar.getY());
        return new Rect(bx, by, bx + bar.getWidth(), by + bar.getHeight());
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        springX.cancel();
        springY.cancel();
        endDrag();
        super.onDetachedFromWindow();
    }

    @SuppressWarnings("unused")
    static String debug(Rect r) {
        return String.format(Locale.US, "[%d,%d %dx%d]", r.left, r.top, r.width(), r.height());
    }
}

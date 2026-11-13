package com.limelight.yugi;

import android.app.Activity;
import android.content.Context;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one YugiPC overlay system for both screens ("המחשב שלי" and "משחקים"):
 * a single floating toolbar in the top-end corner (48dp Material buttons on a dark translucent pill),
 * which shrinks to a small handle after 3 s without touch, and a small LTR stats pill in the top-start corner.
 * Everything else (pad, panels) stays below {@link #getReservedTop()} so nothing ever overlaps.
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
    }

    public interface ReservedTopListener { void onReservedTop(int px); }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout bar;
    private final ImageButton handle;
    private final TextView hud;
    private final List<Button> buttons = new ArrayList<>();
    private boolean collapsed;
    private boolean autoHide = true;
    private int reservedTop;
    private ReservedTopListener reservedTopListener;
    private final Runnable collapseRunnable = () -> setCollapsed(true);

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    public YugiChrome(Context ctx, List<Button> items) {
        super(ctx);
        setClipChildren(false);
        // Fixed placement in every locale: toolbar top-right, stats top-left
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        // The chrome itself never takes touches; only its children do.
        setClickable(false);
        setFocusable(false);

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
        }
        addView(bar, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
    }

    /** Adds the chrome over an activity's content. */
    public static YugiChrome attach(Activity a, List<Button> items) {
        YugiChrome c = new YugiChrome(a, items);
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
        collapsed = c;
        bar.setVisibility(c ? GONE : VISIBLE);
        handle.setVisibility(c ? VISIBLE : GONE);
        if (!c && autoHide) {
            ui.removeCallbacks(collapseRunnable);
            ui.postDelayed(collapseRunnable, AUTO_HIDE_MS);
        }
    }

    /** Hides the whole chrome (e.g. while editing the pad). */
    public void setChromeVisible(boolean visible) {
        setVisibility(visible ? VISIBLE : GONE);
        if (visible) poke();
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
        int w = r - l;
        int m = dp(12);
        int top = m;
        // Toolbar (or its handle) in the top-end corner
        int barW = bar.getMeasuredWidth(), barH = bar.getMeasuredHeight();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int barLeft = rtl ? m : w - m - barW;
        bar.layout(barLeft, top, barLeft + barW, top + barH);
        int hw = handle.getMeasuredWidth();
        int handleLeft = rtl ? m : w - m - hw;
        handle.layout(handleLeft, top + (barH - handle.getMeasuredHeight()) / 2,
                handleLeft + hw, top + (barH - handle.getMeasuredHeight()) / 2 + handle.getMeasuredHeight());

        int bottom = top + barH;
        if (hud.getVisibility() != GONE) {
            int hudW = hud.getMeasuredWidth(), hudH = hud.getMeasuredHeight();
            boolean fitsBeside = hudW + barW + 3 * m <= w;
            int hudTop = fitsBeside ? top + (barH - hudH) / 2 : top + barH + dp(8);
            int hudLeft = rtl ? w - m - hudW : m;
            if (!fitsBeside) hudLeft = rtl ? m : w - m - hudW; // under the toolbar, same side
            hud.layout(hudLeft, hudTop, hudLeft + hudW, hudTop + hudH);
            bottom = Math.max(bottom, hudTop + hudH);
        }
        int reserved = bottom + dp(8);
        if (reserved != reservedTop) {
            reservedTop = reserved;
            if (reservedTopListener != null) post(() -> reservedTopListener.onReservedTop(reservedTop));
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    /** Bounding boxes of everything the chrome shows (for overlap checks). */
    public List<Rect> visibleRects() {
        List<Rect> out = new ArrayList<>();
        if (getVisibility() != VISIBLE) return out;
        if (bar.getVisibility() == VISIBLE) {
            for (Button b : buttons) {
                View v = b.view;
                out.add(new Rect(bar.getLeft() + v.getLeft(), bar.getTop() + v.getTop(),
                        bar.getLeft() + v.getRight(), bar.getTop() + v.getBottom()));
            }
        }
        if (handle.getVisibility() == VISIBLE) {
            out.add(new Rect(handle.getLeft(), handle.getTop(), handle.getRight(), handle.getBottom()));
        }
        if (hud.getVisibility() == VISIBLE) {
            out.add(new Rect(hud.getLeft(), hud.getTop(), hud.getRight(), hud.getBottom()));
        }
        return out;
    }

    public Rect barRect() {
        return new Rect(bar.getLeft(), bar.getTop(), bar.getRight(), bar.getBottom());
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @SuppressWarnings("unused")
    static String debug(Rect r) {
        return String.format(Locale.US, "[%d,%d %dx%d]", r.left, r.top, r.width(), r.height());
    }
}

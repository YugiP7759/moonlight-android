package com.limelight.yugi;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * YugiPC design system: one dark, quiet palette for every overlay (toolbar, HUD, chat, menus, pad editor).
 */
public final class YugiTheme {
    private YugiTheme() {}

    // Palette
    public static final int GLASS = 0xE60B0E14;        // panels: ~90% dark
    public static final int GLASS_STRONG = 0xF20B0E14;
    public static final int SURFACE = 0xFF1A1F29;      // inputs, user bubbles
    public static final int SURFACE_HI = 0xFF262C38;   // active / pressed states
    public static final int STROKE = 0x1FFFFFFF;       // hairlines
    public static final int TEXT = 0xFFF2F3F5;
    public static final int TEXT_DIM = 0xFF9AA1AD;
    public static final int TEXT_FAINT = 0xFF6B7280;
    public static final int ACCENT = 0xFFC9D4FF;       // muted periwinkle, used sparingly
    public static final int DANGER = 0xFFE5484D;
    public static final int RIPPLE = 0x33FFFFFF;

    public static final int RADIUS_L = 20; // dp
    public static final int RADIUS_M = 14;
    public static final int RADIUS_S = 10;
    public static final int TOUCH = 48;    // dp, minimum touch target
    public static final int GAP = 8;       // dp, base spacing

    private static Typeface regular, medium, bold;

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** Heebo (variable, OFL) at the given weight; falls back to the system sans. */
    public static Typeface font(Context c, int weight) {
        try {
            if (weight >= 700) {
                if (bold == null) bold = load(c, 700);
                return bold;
            } else if (weight >= 500) {
                if (medium == null) medium = load(c, 500);
                return medium;
            } else {
                if (regular == null) regular = load(c, 400);
                return regular;
            }
        } catch (Throwable t) {
            return weight >= 500 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        }
    }

    private static Typeface load(Context c, int weight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Typeface t = new Typeface.Builder(c.getAssets(), "fonts/Heebo.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            if (t != null) return t;
        }
        Typeface t = Typeface.createFromAsset(c.getAssets(), "fonts/Heebo.ttf");
        return weight >= 600 ? Typeface.create(t, Typeface.BOLD) : t;
    }

    public static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable roundedStroke(Context c, int color, float radiusDp, int stroke) {
        GradientDrawable g = rounded(c, color, radiusDp);
        g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    public static Drawable circleRipple(int fill) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(fill);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, mask);
    }

    /** 48dp round icon button, white Material icon. */
    public static ImageButton iconButton(Context c, int icon, String label) {
        ImageButton v = new ImageButton(c);
        v.setImageResource(icon);
        v.setContentDescription(label);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setImageTintList(ColorStateList.valueOf(TEXT));
        v.setBackground(circleRipple(0x00000000));
        v.setFocusable(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.setTooltipText(label);
        return v;
    }

    public static void setActive(ImageButton v, boolean active) {
        v.setBackground(circleRipple(active ? SURFACE_HI : 0x00000000));
        v.setImageTintList(ColorStateList.valueOf(active ? ACCENT : TEXT));
    }

    public static TextView text(Context c, String s, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font(c, weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }
}

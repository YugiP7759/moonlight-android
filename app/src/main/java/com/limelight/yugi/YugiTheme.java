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
    public static final int BRAND_BG = 0xFF0B0E14;     // window / splash background
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

    // Overlays that always stay above any video surface (YugiPlayer, WebView fullscreen): toolbar, chat, Yugi bubble
    private static final java.util.Set<android.view.View> ON_TOP =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Marks an overlay as "always on top" of players and other full-screen surfaces. */
    public static void keepOnTop(android.view.View v) { ON_TOP.add(v); }

    /**
     * Call right after {@code surface.bringToFront()}: puts the marked overlays (and whatever already sat above
     * them, like the YugiPC keyboard) back above the surface, keeping their order. Only reorders, never detaches.
     */
    public static void raiseOverlays(android.view.ViewGroup parent, android.view.View surface) {
        if (parent == null) return;
        java.util.List<android.view.View> above = new java.util.ArrayList<>();
        boolean found = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            android.view.View c = parent.getChildAt(i);
            if (!found && ON_TOP.contains(c)) found = true;
            if (found && c != surface) above.add(c);
        }
        for (android.view.View v : above) v.bringToFront();
    }

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

    /** High-contrast slider: 6dp rounded track, accent fill, 24dp white thumb. */
    public static void styleSlider(android.widget.SeekBar sb) {
        Context c = sb.getContext();
        float r = dp(c, 3);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x4DFFFFFF);
        bg.setCornerRadius(r);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(ACCENT);
        fg.setCornerRadius(r);
        android.graphics.drawable.ClipDrawable clip =
                new android.graphics.drawable.ClipDrawable(fg, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(new Drawable[]{bg, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        int h = dp(c, 6);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            layers.setLayerHeight(0, h);
            layers.setLayerHeight(1, h);
            layers.setLayerGravity(0, android.view.Gravity.CENTER_VERTICAL);
            layers.setLayerGravity(1, android.view.Gravity.CENTER_VERTICAL);
        }
        sb.setProgressDrawable(layers);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(TEXT);
        thumb.setStroke(dp(c, 2), ACCENT);
        thumb.setSize(dp(c, 24), dp(c, 24));
        sb.setThumb(thumb);
        sb.setThumbOffset(dp(c, 12));
        sb.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        sb.setSplitTrack(false);
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

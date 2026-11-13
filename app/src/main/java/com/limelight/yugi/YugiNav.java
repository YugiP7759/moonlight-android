package com.limelight.yugi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Switching between "המחשב שלי" (desktop stream) and "משחקים" (Boosteroid), through a small
 * semi-transparent floating button that opens a side panel.
 */
public final class YugiNav {
    /** Set when the user picks "המחשב שלי" from the games screen: the launcher connects right away. */
    public static volatile boolean connectNow;

    private YugiNav() {}

    public static final class Item {
        final String label;
        final boolean current;
        final Runnable action;

        public Item(String label, boolean current, Runnable action) {
            this.label = label;
            this.current = current;
            this.action = action;
        }
    }

    public interface Items { List<Item> get(); }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    public static void openGames(Activity from) {
        Intent i = new Intent(from, GamesActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        from.startActivity(i);
        from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    public static void openPc(Activity from) {
        connectNow = true;
        Intent i = new Intent(from, YugiLauncher.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        from.startActivity(i);
        from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** Adds the floating menu button (top end corner). Returns it. */
    public static View attachMenuButton(Activity a, Items items) {
        TextView b = new TextView(a);
        b.setText("☰");
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription("תפריט");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        b.setBackground(bg);
        b.setAlpha(0.45f);
        int size = dp(a, 44);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        lp.topMargin = dp(a, 12);
        lp.rightMargin = dp(a, 12);
        b.setOnClickListener(v -> showPanel(a, items.get()));
        a.addContentView(b, lp);
        return b;
    }

    /** Side panel sliding in from the end edge, closed by tapping outside it. */
    public static void showPanel(Activity a, List<Item> items) {
        ViewGroup content = a.findViewById(android.R.id.content);
        FrameLayout scrim = new FrameLayout(a);
        scrim.setBackgroundColor(0x55000000);
        scrim.setClickable(true);

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        panel.setPadding(dp(a, 12), dp(a, 24), dp(a, 12), dp(a, 24));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xF0111827);
        pbg.setCornerRadius(dp(a, 16));
        panel.setBackground(pbg);

        Runnable close = () -> panel.animate().translationX(dp(a, 280)).alpha(0f).setDuration(150)
                .withEndAction(() -> content.removeView(scrim)).start();

        for (Item it : items) {
            TextView t = new TextView(a);
            t.setText(it.label);
            t.setTextColor(it.current ? 0xFF7FA8FF : Color.WHITE);
            t.setTypeface(it.current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setMinHeight(dp(a, 52));
            t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            t.setPadding(dp(a, 16), 0, dp(a, 16), 0);
            t.setOnClickListener(v -> {
                close.run();
                if (it.action != null) it.action.run();
            });
            panel.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(a, 240),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        plp.rightMargin = dp(a, 12);
        scrim.addView(panel, plp);
        scrim.setOnClickListener(v -> close.run());
        panel.setClickable(true);
        content.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setTranslationX(dp(a, 280));
        panel.setAlpha(0f);
        panel.animate().translationX(0).alpha(1f).setDuration(180).start();
    }
}

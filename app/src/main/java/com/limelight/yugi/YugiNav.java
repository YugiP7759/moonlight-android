package com.limelight.yugi;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Switching between "המחשב שלי" and "משחקים", and the shared side menu (same design system). */
public final class YugiNav {
    /** Set when the user picks "המחשב שלי" from the games screen: the launcher connects right away. */
    public static volatile boolean connectNow;

    private YugiNav() {}

    public static final class Item {
        final String label;
        final boolean current;
        final boolean section;
        final Runnable action;

        public Item(String label, boolean current, Runnable action) {
            this(label, current, false, action);
        }

        private Item(String label, boolean current, boolean section, Runnable action) {
            this.label = label;
            this.current = current;
            this.section = section;
            this.action = action;
        }

        public static Item section(String label) { return new Item(label, false, true, null); }
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

    /** Side panel at the end edge, below the toolbar; tapping outside closes it. */
    public static View showPanel(Activity a, List<Item> items, int topPx) {
        ViewGroup content = a.findViewById(android.R.id.content);
        FrameLayout scrim = buildPanel(a, items, topPx, () -> {});
        scrim.setTag("yugi-menu");
        content.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scrim;
    }

    /** Builds the panel (also used by screenshot tests). */
    public static FrameLayout buildPanel(Activity a, List<Item> items, int topPx, Runnable onClose) {
        int m = YugiTheme.dp(a, 12);
        FrameLayout scrim = new FrameLayout(a);
        scrim.setBackgroundColor(0xB3000000); // dark: the pad behind never mixes with the menu
        scrim.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // panel on the right; its content is RTL
        scrim.setClickable(true);

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        panel.setPadding(YugiTheme.dp(a, 6), YugiTheme.dp(a, 8), YugiTheme.dp(a, 6), YugiTheme.dp(a, 8));

        ScrollView sv = new ScrollView(a);
        sv.setBackground(YugiTheme.roundedStroke(a, YugiTheme.GLASS_STRONG, YugiTheme.RADIUS_L, YugiTheme.STROKE));
        sv.setElevation(YugiTheme.dp(a, 12));
        sv.setClickable(true);
        sv.addView(panel);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(YugiTheme.dp(a, 40));
        sv.setVerticalScrollBarEnabled(true);
        sv.setScrollbarFadingEnabled(false);

        Runnable close = () -> sv.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            ViewGroup p = (ViewGroup) scrim.getParent();
            if (p != null) p.removeView(scrim);
            onClose.run();
        }).start();

        for (Item it : items) {
            if (it.section) {
                TextView h = YugiTheme.text(a, it.label, 12, 500, YugiTheme.TEXT_FAINT);
                h.setPadding(YugiTheme.dp(a, 14), YugiTheme.dp(a, 12), YugiTheme.dp(a, 14), YugiTheme.dp(a, 4));
                panel.addView(h);
                continue;
            }
            TextView t = YugiTheme.text(a, it.label, 15, it.current ? 600 : 400, it.current ? YugiTheme.ACCENT : YugiTheme.TEXT);
            t.setMinHeight(YugiTheme.dp(a, 48));
            t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            t.setPadding(YugiTheme.dp(a, 14), 0, YugiTheme.dp(a, 14), 0);
            t.setBackground(YugiTheme.rounded(a, it.current ? YugiTheme.SURFACE : 0x00000000, YugiTheme.RADIUS_S));
            if (!it.current) {
                t.setOnClickListener(v -> {
                    close.run();
                    if (it.action != null) it.action.run();
                });
            }
            panel.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(YugiTheme.dp(a, 280),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        plp.rightMargin = m;
        plp.leftMargin = m;
        plp.topMargin = Math.max(m, topPx);
        plp.bottomMargin = m;
        scrim.addView(sv, plp);
        scrim.setOnClickListener(v -> close.run());
        sv.setAlpha(0f);
        sv.animate().alpha(1f).setDuration(140).start();
        return scrim;
    }
}

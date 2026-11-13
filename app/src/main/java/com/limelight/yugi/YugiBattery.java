package com.limelight.yugi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * First run: asks once to exempt YugiPC from battery optimization (Android may otherwise throttle
 * the network and the embedded tunnel while streaming). Our own sheet explains why, then the
 * system dialog asks. Never asked again, whatever the answer.
 */
public final class YugiBattery {
    private YugiBattery() {}

    private static final String PREFS = "yugipc";
    private static final String KEY_ASKED = "battery_asked";

    public static boolean shouldAsk(Activity a) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        if (a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)) return false;
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        return pm != null && !pm.isIgnoringBatteryOptimizations(a.getPackageName());
    }

    /**
     * Shows the sheet over the activity. {@code done} runs when the user skips; after "המשך"
     * {@code beforeSystemDialog} runs, the system dialog opens and the activity's onResume continues.
     */
    public static View ask(Activity a, Runnable beforeSystemDialog, Runnable done) {
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply();
        FrameLayout scrim = new FrameLayout(a);
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        int pad = YugiTheme.dp(a, 24);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, YugiTheme.dp(a, 16));
        card.setBackground(YugiTheme.roundedStroke(a, YugiTheme.GLASS_STRONG, YugiTheme.RADIUS_L, YugiTheme.STROKE));
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = YugiTheme.text(a, "שהחיבור לא ייחתך", 20, 600, YugiTheme.TEXT);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        card.addView(title);
        TextView body = YugiTheme.text(a,
                "כדי שהמחשב בענן יישאר זורם גם כשהמסך כבוי לרגע, צריך לפטור את YugiPC מחיסכון בסוללה. "
                        + "בחלון הבא בוחרים \"אישור\". זה נשאל פעם אחת בלבד.", 15, 400, YugiTheme.TEXT_DIM);
        body.setTextDirection(View.TEXT_DIRECTION_RTL);
        body.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = YugiTheme.dp(a, 10);
        card.addView(body, blp);

        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = YugiTheme.dp(a, 20);
        TextView go = button(a, "המשך", true);
        TextView skip = button(a, "לא עכשיו", false);
        actions.addView(go);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(YugiTheme.dp(a, 8));
        actions.addView(skip, slp);
        card.addView(actions, alp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Math.min(YugiTheme.dp(a, 420), a.getResources().getDisplayMetrics().widthPixels - 2 * pad),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(card, clp);
        a.addContentView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        scrim.animate().alpha(1f).setDuration(220).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.3f, 1f)).start();

        go.setOnClickListener(v -> {
            remove(scrim);
            beforeSystemDialog.run();
            if (!openSystemDialog(a)) done.run();
        });
        skip.setOnClickListener(v -> {
            remove(scrim);
            done.run();
        });
        return scrim;
    }

    private static TextView button(Activity a, String label, boolean primary) {
        TextView b = YugiTheme.text(a, label, 16, 600, primary ? YugiTheme.BRAND_BG : YugiTheme.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(YugiTheme.dp(a, YugiTheme.TOUCH));
        b.setPadding(YugiTheme.dp(a, 20), 0, YugiTheme.dp(a, 20), 0);
        b.setBackground(YugiTheme.rounded(a, primary ? YugiTheme.ACCENT : YugiTheme.SURFACE, 24));
        return b;
    }

    private static void remove(View v) {
        ViewGroup p = (ViewGroup) v.getParent();
        if (p != null) p.removeView(v);
    }

    @SuppressLint("BatteryLife") // a personal streaming client: the exemption is the documented use
    private static boolean openSystemDialog(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

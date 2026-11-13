package com.limelight.yugi;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

/** Small floating buttons over the stream: mute mic, camera on/off, switch front/back camera. */
public final class YugiOverlay {
    private YugiOverlay() {}

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    private static ImageButton button(Activity a, int icon, String desc) {
        ImageButton b = new ImageButton(a);
        b.setImageResource(icon);
        b.setContentDescription(desc);
        b.setColorFilter(Color.WHITE);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(a, 10);
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        b.setBackground(bg);
        b.setFocusable(false);
        return b;
    }

    private static void paint(ImageButton b, boolean on) {
        GradientDrawable bg = (GradientDrawable) b.getBackground();
        bg.setColor(on ? 0x66000000 : 0xCCD32F2F);
        b.setAlpha(on ? 0.75f : 1f);
    }

    /** Small floating button (bottom corner) that opens the phone's keyboard. */
    public static View attachKeyboardButton(Activity a, Runnable toggleKeyboard) {
        ImageButton kb = button(a, com.limelight.R.drawable.yugipc_ic_keyboard, "מקלדת");
        kb.setAlpha(0.7f);
        kb.setOnClickListener(v -> toggleKeyboard.run());
        int size = dp(a, 48);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
        flp.rightMargin = dp(a, 12);
        flp.bottomMargin = dp(a, 72);
        a.addContentView(kb, flp);
        return kb;
    }

    /** Adds the buttons to the activity's content root. Returns the container (for hiding in PiP). */
    public static View attach(Activity a) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.VERTICAL);
        int size = dp(a, 48); // 48dp touch targets
        int gap = dp(a, 8);

        ImageButton mic = button(a, android.R.drawable.ic_btn_speak_now, "מיקרופון");
        ImageButton cam = button(a, android.R.drawable.ic_menu_camera, "מצלמה");
        ImageButton flip = button(a, android.R.drawable.ic_popup_sync, "החלפת מצלמה");
        paint(mic, YugiMedia.isMicOn());
        paint(cam, YugiMedia.isCamOn());
        paint(flip, true);

        mic.setOnClickListener(v -> { YugiMedia.setMic(!YugiMedia.isMicOn()); paint(mic, YugiMedia.isMicOn()); });
        cam.setOnClickListener(v -> { YugiMedia.setCam(!YugiMedia.isCamOn()); paint(cam, YugiMedia.isCamOn()); });
        flip.setOnClickListener(v -> YugiMedia.switchCamera());

        for (ImageButton b : new ImageButton[]{mic, cam, flip}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.bottomMargin = gap;
            bar.addView(b, lp);
        }

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        flp.leftMargin = dp(a, 12);
        flp.topMargin = dp(a, 56);
        a.addContentView(bar, flp);
        return bar;
    }
}

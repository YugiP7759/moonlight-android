package com.limelight.yugi;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The branded "connecting" screen shared by the launcher and the stream screen, so the hand-off is
 * seamless: brand dark + bloom, the last frame of the previous session blurred behind (like waking a Mac),
 * the logo, a gentle loading animation and a status line. Optional action buttons for errors.
 */
public class YugiBrandView extends FrameLayout {
    private static final String LAST_FRAME = "last_frame.jpg";

    private final ImageView lastFrame;
    private final TextView status;
    private final LinearLayout dots;
    private final LinearLayout actions;
    private ValueAnimator pulse;

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    public YugiBrandView(Context c) {
        super(c);
        setBackgroundColor(YugiTheme.BRAND_BG);
        setClickable(true);

        lastFrame = new ImageView(c);
        lastFrame.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lastFrame.setAlpha(0.32f);
        addView(lastFrame, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Bitmap b = loadLastFrame(c);
        if (b != null) {
            lastFrame.setImageBitmap(b);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lastFrame.setRenderEffect(RenderEffect.createBlurEffect(dp(24), dp(24), Shader.TileMode.CLAMP));
            }
        } else {
            lastFrame.setVisibility(GONE);
        }

        // Bloom
        addView(new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas cv) {
                int w = getWidth(), h = getHeight();
                float r = Math.max(w, h) * 0.7f;
                p.setShader(new RadialGradient(w / 2f, h * 0.45f, r,
                        new int[]{0x2E3F7AE0, 0x123F5AB0, 0x000B0E14}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, w, h, p);
            }
        }, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setLayoutDirection(LAYOUT_DIRECTION_RTL);

        ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.yugipc_icon_fg);
        col.addView(logo, new LinearLayout.LayoutParams(dp(168), dp(168)));

        TextView title = YugiTheme.text(c, "YugiPC", 26, 600, YugiTheme.TEXT);
        title.setLetterSpacing(0.02f);
        title.setPadding(0, 0, 0, dp(18));
        title.setGravity(Gravity.CENTER);
        col.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dots = new LinearLayout(c);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View d = new View(c);
            d.setBackground(YugiTheme.rounded(c, YugiTheme.ACCENT, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
            lp.leftMargin = dp(5);
            lp.rightMargin = dp(5);
            dots.addView(d, lp);
        }
        col.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = YugiTheme.text(c, "מתחבר למחשב שלך…", 16, 400, YugiTheme.TEXT_DIM);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(16), dp(24), dp(16));
        col.addView(status);

        actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setVisibility(GONE);
        col.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setBusy(true);
    }

    public void setStatus(String s) { status.setText(s); }

    /** Busy: animated dots, no buttons. Not busy: buttons shown. */
    public void setBusy(boolean busy) {
        dots.setVisibility(busy ? VISIBLE : INVISIBLE);
        actions.setVisibility(busy ? GONE : (actions.getChildCount() > 0 ? VISIBLE : GONE));
        if (busy) startPulse(); else stopPulse();
    }

    /** Replaces the action buttons (primary first). */
    public void setActions(String primary, Runnable onPrimary, String secondary, Runnable onSecondary) {
        actions.removeAllViews();
        Context c = getContext();
        if (primary != null) actions.addView(pill(c, primary, true, onPrimary), pillLp());
        if (secondary != null) actions.addView(pill(c, secondary, false, onSecondary), pillLp());
    }

    private LinearLayout.LayoutParams pillLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(48));
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        return lp;
    }

    private TextView pill(Context c, String label, boolean primary, Runnable r) {
        TextView b = YugiTheme.text(c, label, 15, 600, primary ? 0xFF0B0E14 : YugiTheme.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? YugiTheme.rounded(c, YugiTheme.TEXT, 24)
                : YugiTheme.roundedStroke(c, YugiTheme.SURFACE, 24, YugiTheme.STROKE));
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private void startPulse() {
        if (pulse != null) return;
        pulse = ValueAnimator.ofFloat(0f, 3f);
        pulse.setDuration(1200);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < dots.getChildCount(); i++) {
                float d = Math.abs(t - i - 0.5f);
                float k = Math.max(0f, 1f - d);
                View v = dots.getChildAt(i);
                v.setAlpha(0.35f + 0.65f * k);
                v.setScaleX(0.85f + 0.3f * k);
                v.setScaleY(0.85f + 0.3f * k);
            }
        });
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) { pulse.cancel(); pulse = null; }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        super.onDetachedFromWindow();
    }

    /** Fades out and removes itself (crossfade to the stream). */
    public void fadeOutAndRemove(long ms) {
        animate().alpha(0f).setDuration(ms).withEndAction(() -> {
            stopPulse();
            ViewGroup p = (ViewGroup) getParent();
            if (p != null) p.removeView(this);
        }).start();
    }

    // ---------------------------------------------------------------- last frame

    static Bitmap loadLastFrame(Context c) {
        try {
            File f = new File(c.getFilesDir(), LAST_FRAME);
            if (!f.exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Saves the last stream frame (shown blurred while the next session connects). */
    public static void saveLastFrame(Context c, Bitmap b) {
        if (b == null) return;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                float s = Math.min(1f, 1280f / Math.max(b.getWidth(), b.getHeight()));
                Bitmap out = s < 1f ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true) : b;
                File tmp = new File(app.getFilesDir(), LAST_FRAME + ".tmp");
                try (FileOutputStream fo = new FileOutputStream(tmp)) {
                    out.compress(Bitmap.CompressFormat.JPEG, 80, fo);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(new File(app.getFilesDir(), LAST_FRAME));
            } catch (Throwable ignored) {
            }
        }, "YugiLastFrame").start();
    }
}

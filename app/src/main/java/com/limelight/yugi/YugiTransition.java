package com.limelight.yugi;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Seamless re-stream on rotation / fold: freeze the last frame, blur + scale it gently while
 * reconnecting at the new size in the background, then crossfade to the new stream.
 */
public final class YugiTransition {
    public static final long BLUR_IN_MS = 280;
    public static final long FADE_OUT_MS = 260;
    public static final long FADE_DELAY_AFTER_START_MS = 150;
    public static final float BLUR_RADIUS = 28f;

    /** Last frame of the previous stream, shown while the new one connects. */
    public static volatile Bitmap snapshot;
    /** Set by Game when it ends itself to re-stream at a new size. */
    public static volatile boolean pendingResize;
    /** Display rotation change (in 90° steps) between the old and new stream, for the turn animation. */
    public static volatile int rotationDelta;

    private YugiTransition() {}

    public interface Done { void run(); }

    /** Grabs the current video frame from the SurfaceView (API 24+), then calls done on the UI thread. */
    public static void capture(Activity a, SurfaceView sv, Done done) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || sv == null || sv.getWidth() <= 0 || sv.getHeight() <= 0
                || sv.getHolder().getSurface() == null || !sv.getHolder().getSurface().isValid()) {
            done.run();
            return;
        }
        final Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(sv.getWidth(), sv.getHeight(), Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            done.run();
            return;
        }
        HandlerThread ht = new HandlerThread("YugiSnap");
        ht.start();
        try {
            PixelCopy.request(sv, bmp, result -> {
                if (result == PixelCopy.SUCCESS) snapshot = bmp;
                ht.quitSafely();
                a.runOnUiThread(done::run);
            }, new Handler(ht.getLooper()));
        } catch (Throwable t) {
            ht.quitSafely();
            done.run();
        }
    }

    private static void setBlur(View v, float radius) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setRenderEffect(radius <= 0.5f ? null
                    : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
    }

    /** Full-screen view of the frozen frame. Returns null if there is no snapshot. */
    public static ImageView addFrozenFrame(Activity a, boolean alreadyBlurred) {
        Bitmap b = snapshot;
        if (b == null) return null;
        ImageView iv = new ImageView(a);
        iv.setImageBitmap(b);
        // Letterbox: keep the frozen frame's true aspect ratio (never stretch it to the new screen)
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFF000000);
        a.addContentView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (alreadyBlurred) {
            setBlur(iv, BLUR_RADIUS);
        }
        return iv;
    }

    /** Gentle scale-to-fit + blur ramp on the frozen frame (launcher side, while reconnecting). */
    public static void animateIn(ImageView iv, float fromRotation) {
        if (iv == null) return;
        iv.setRotation(fromRotation);
        iv.setScaleX(0.94f);
        iv.setScaleY(0.94f);
        iv.animate().rotation(0).scaleX(1f).scaleY(1f)
                .setDuration(BLUR_IN_MS).setInterpolator(new DecelerateInterpolator()).start();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, BLUR_RADIUS);
            va.setDuration(BLUR_IN_MS);
            va.addUpdateListener(an -> setBlur(iv, (float) an.getAnimatedValue()));
            va.start();
        }
    }

    /** Crossfade from the frozen frame to the live stream (Game side, after the stream starts). */
    public static void fadeOut(ImageView iv) {
        if (iv == null) return;
        iv.animate().alpha(0f).setStartDelay(FADE_DELAY_AFTER_START_MS).setDuration(FADE_OUT_MS)
                .withEndAction(() -> {
                    ViewGroup p = (ViewGroup) iv.getParent();
                    if (p != null) p.removeView(iv);
                    snapshot = null;
                }).start();
    }
}

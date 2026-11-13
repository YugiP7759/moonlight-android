package com.limelight.yugi;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Overlays for the desktop stream screen: the shared toolbar, mic/camera controls, menu and Yugi chat. */
public final class YugiPcOverlay {
    public YugiChrome chrome;
    public YugiChat chat;
    private YugiChrome.Button keyboardButton;
    private Runnable keyboardAction;

    private YugiPcOverlay() {}

    public static YugiPcOverlay attach(Activity a, SurfaceView surface, Runnable openGames) {
        YugiPcOverlay o = new YugiPcOverlay();
        List<YugiChrome.Button> buttons = new ArrayList<>();
        // יוגי: the draggable floating bubble (YugiChatBubble), not a toolbar button
        o.keyboardButton = new YugiChrome.Button(R.drawable.yugi_ic_keyboard, "מקלדת", () -> {
            if (o.keyboardAction != null) o.keyboardAction.run();
        });
        buttons.add(o.keyboardButton);
        if (YugiMedia.isAvailable()) {
            YugiChrome.Button[] mic = new YugiChrome.Button[1];
            mic[0] = new YugiChrome.Button(YugiMedia.isMicOn() ? R.drawable.yugi_ic_mic : R.drawable.yugi_ic_mic_off,
                    "מיקרופון", () -> {
                YugiMedia.setMic(!YugiMedia.isMicOn());
                mic[0].setIcon(YugiMedia.isMicOn() ? R.drawable.yugi_ic_mic : R.drawable.yugi_ic_mic_off);
            });
            buttons.add(mic[0]);
            YugiChrome.Button[] cam = new YugiChrome.Button[1];
            cam[0] = new YugiChrome.Button(YugiMedia.isCamOn() ? R.drawable.yugi_ic_videocam : R.drawable.yugi_ic_videocam_off,
                    "מצלמה", () -> {
                YugiMedia.setCam(!YugiMedia.isCamOn());
                cam[0].setIcon(YugiMedia.isCamOn() ? R.drawable.yugi_ic_videocam : R.drawable.yugi_ic_videocam_off);
            });
            buttons.add(cam[0]);
            buttons.add(new YugiChrome.Button(R.drawable.yugi_ic_switch_camera, "החלפת מצלמה", YugiMedia::switchCamera));
        }
        buttons.add(new YugiChrome.Button(R.drawable.yugi_ic_menu, "תפריט", () ->
                YugiNav.showPanel(a, Arrays.asList(
                        new YugiNav.Item("המחשב שלי", true, null),
                        new YugiNav.Item("משחקים", false, openGames)), o.chrome.getReservedTop())));
        o.chrome = YugiChrome.attach(a, buttons);
        o.chat = YugiChat.attach(a, new YugiChat.Host() {
            @Override public void captureScreen(YugiChat.BitmapCallback cb) { capture(surface, cb); }
            @Override public void pickFile() { YugiChat.openPicker(a); }
            @Override public int reservedTop() { return o.chrome.getReservedTop(); }
            @Override public void onKeyboard(boolean shown) { o.chrome.setChromeVisible(!shown); }
        });
        return o;
    }

    /** The toolbar's keyboard button opens / closes the YugiPC keyboard. */
    public void setKeyboardAction(Runnable r) { keyboardAction = r; }

    public void setKeyboardActive(boolean on) { keyboardButton.setActive(on); }

    /** Saves the current frame as the next session's placeholder. */
    public static void captureLastFrame(Activity a, SurfaceView sv) {
        capture(sv, b -> YugiBrandView.saveLastFrame(a, b));
    }

    /** Current desktop frame straight from the video surface (without overlays). */
    public static void captureFrame(SurfaceView sv, YugiChat.BitmapCallback cb) { capture(sv, cb); }

    static void capture(SurfaceView sv, YugiChat.BitmapCallback cb) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || sv == null || sv.getWidth() <= 0
                || sv.getHolder().getSurface() == null || !sv.getHolder().getSurface().isValid()) {
            cb.onBitmap(null);
            return;
        }
        Bitmap bmp = Bitmap.createBitmap(sv.getWidth(), sv.getHeight(), Bitmap.Config.ARGB_8888);
        Handler h = new Handler(Looper.getMainLooper());
        try {
            PixelCopy.request(sv, bmp, r -> cb.onBitmap(r == PixelCopy.SUCCESS ? bmp : null), h);
        } catch (Throwable t) {
            cb.onBitmap(null);
        }
    }
}

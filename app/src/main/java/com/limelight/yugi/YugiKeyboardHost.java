package com.limelight.yugi;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Shows the YugiPC keyboard and scrolls the desktop with it, as one motion.
 * <p>
 * One spring drives the reveal (0 = hidden, 1 = shown). Each frame sets both the keyboard's
 * translation and the desktop's: desktopY = -shift * reveal, so the desktop moves exactly with the
 * keyboard edge. A second spring only retargets the shift while the keyboard is up (the caret moved).
 * Both are interruptible: a new target continues from the current position and velocity.
 * <p>
 * The keyboard opens by itself when the server reports focus in a text field (yugi-caret-watch.py
 * through {@link YugiEvents}) and closes when focus leaves text, like the Windows touch keyboard; the
 * toolbar button and the keyboard's own hide key work any time. Without a caret from the server, the
 * place last touched stands in for it.
 */
public final class YugiKeyboardHost implements YugiEvents.Listener, YugiKeyboard.Host, YugiKbdFocus.Owner {
    public interface StateListener { void onShown(boolean shown); }

    private static final float MARGIN_DP = 24;
    private static final long AUTO_HIDE_DELAY_MS = 350;

    private final Activity activity;
    private final View desktop;        // wrapper around the stream: moves up
    private final View stream;         // the stream view inside it (for caret mapping)
    private final YugiKeyEngine engine;
    private final YugiKeyboard kb;
    private final YugiEvents events;
    private final YugiKeyEngine.Sender sender;
    private final YugiVoice voice;
    private YugiKbdSettingsView settingsView;
    private StateListener stateListener;

    private final FloatValueHolder revealValue = new FloatValueHolder(0f);
    private final FloatValueHolder shiftValue = new FloatValueHolder(0f);
    private final SpringAnimation reveal;
    private final SpringAnimation shift;

    private boolean wantShown;
    private boolean autoShown;         // opened by focus (closes by focus too)
    private boolean userHidden;        // closed by hand while focus stayed in the field
    private boolean lastEditable;
    private boolean appFieldFocused;   // an in-app text field (chat) takes the typing
    private YugiEvents.Caret caret;    // last caret with a rect
    private float lastTouchY = Float.NaN; // stream-local
    private long lastTouchAt = Long.MIN_VALUE / 4; // uptime ms (none yet)
    private static final long TAP_TO_FOCUS_MS = 800;
    private final Runnable autoHide = () -> { if (autoShown) hide(); };

    public static YugiKeyboardHost attach(Activity a, View desktopWrapper, View streamView, YugiKeyEngine.Sender sender) {
        return new YugiKeyboardHost(a, desktopWrapper, streamView, sender);
    }

    private YugiKeyboardHost(Activity a, View desktopWrapper, View streamView, YugiKeyEngine.Sender sender) {
        activity = a;
        desktop = desktopWrapper;
        stream = streamView;
        this.sender = sender;
        engine = new YugiKeyEngine(sender);
        voice = new YugiVoice(a);
        kb = new YugiKeyboard(a, engine, this);
        kb.setVisibility(View.GONE);
        a.addContentView(kb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        shift = new SpringAnimation(shiftValue);
        shift.setSpring(new SpringForce(0f).setDampingRatio(0.9f).setStiffness(420f));
        shift.addUpdateListener((anim, v, vel) -> apply());
        reveal = new SpringAnimation(revealValue);
        reveal.setSpring(new SpringForce(0f).setDampingRatio(0.9f).setStiffness(420f));
        reveal.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        reveal.addUpdateListener((anim, v, vel) -> apply());
        reveal.addEndListener((anim, canceled, v, vel) -> {
            if (!canceled && !wantShown) {
                kb.setVisibility(View.GONE); // nothing drawn while hidden
                shift.cancel();
                shiftValue.setValue(0f);
                apply();
            }
        });

        // re-place when the window changes (rotation is a restart anyway; fold posture, insets)
        kb.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((b - t) != (ob - ot) && wantShown) {
                apply();
                retargetShift();
            }
        });
        events = new YugiEvents(this);
    }

    public void setStateListener(StateListener l) { stateListener = l; }

    public void start() { events.start(); }

    /** The server event stream (shared with the media redirect). */
    public YugiEvents events() { return events; }

    public void stop() {
        voice.cancel();
        kb.setListening(false);
        events.stop();
        kb.removeCallbacks(autoHide);
    }

    public boolean isShown() { return wantShown; }

    /** Last touch on the stream, in the stream view's own pixels (the fallback caret). */
    public void noteTouch(float yInStream) {
        lastTouchY = yInStream;
        lastTouchAt = android.os.SystemClock.uptimeMillis();
    }

    /** Desktop offset (px, <= 0) currently applied, for touch mapping outside the stream view. */
    public float desktopOffset() { return desktop.getTranslationY(); }

    public void toggle() {
        if (wantShown) {
            userHidden = lastEditable;
            hide();
        } else {
            autoShown = false;
            show();
        }
    }

    public void show() {
        kb.removeCallbacks(autoHide);
        if (wantShown) {
            retargetShift();
            return;
        }
        YugiKbdFocus.claim(this);
        wantShown = true;
        boolean wasHidden = kb.getVisibility() != View.VISIBLE;
        kb.setVisibility(View.VISIBLE);
        if (wasHidden) {
            // first frame: fully below the screen, then spring up
            int h = kb.preferredHeight(rootWidth(), rootHeight());
            kb.setTranslationY(h);
        }
        // the shift is fixed for the opening, so the desktop rides exactly on the keyboard edge
        if (revealValue.getValue() <= 0.001f) {
            shift.cancel();
            shiftValue.setValue(targetShift());
        } else {
            shift.animateToFinalPosition(targetShift());
        }
        reveal.animateToFinalPosition(1f);
        if (stateListener != null) stateListener.onShown(true);
    }

    public void hide() {
        kb.removeCallbacks(autoHide);
        voice.cancel();
        kb.setListening(false);
        if (settingsView != null) { settingsView.close(); settingsView = null; }
        if (!wantShown) return;
        wantShown = false;
        YugiKbdFocus.release(this);
        autoShown = false;
        kb.releaseAll();
        reveal.animateToFinalPosition(0f);
        if (stateListener != null) stateListener.onShown(false);
    }

    /**
     * A text field of the app itself (the Yugi chat) has the focus: typing belongs to it, so this
     * keyboard (which always types into the desktop) goes down and the server caret does not bring it back.
     */
    public void setAppFieldFocused(boolean focused) {
        if (focused == appFieldFocused) return;
        appFieldFocused = focused;
        if (focused) hide();
    }

    @Override public void onHideRequested() {
        userHidden = lastEditable;
        hide();
    }

    /** The in-app keyboard (chat) took the screen. */
    @Override public android.app.Activity ownerActivity() { return activity; }

    @Override public void yieldKeyboard() {
        autoShown = false;
        hide();
    }

    @Override public void onGeometryChanged() {
        if (!wantShown) return;
        apply();
        retargetShift();
    }

    @Override public void onSettingsRequested() {
        if (settingsView != null && settingsView.getParent() != null) return;
        settingsView = YugiKbdSettingsView.show(activity, kb, () -> settingsView = null);
    }

    /**
     * Dictation to the desktop. Text sent to the server cannot be taken back, so the live words show
     * above the keys and only the final result is typed (UTF-8, any language).
     */
    @Override public void onMicRequested(boolean hebrew) {
        if (voice.isActive()) { voice.stop(); return; }
        voice.start(hebrew, new YugiVoice.Listener() {
            @Override public void onListening(boolean on) { kb.setListening(on); }
            @Override public void onLevel(float level) { kb.setVoiceLevel(level); }
            @Override public void onPartial(String text) { kb.setVoicePreview(text); }
            @Override public void onFinal(String text) {
                kb.setVoicePreview(null);
                if (!text.isEmpty()) sender.text(text);
            }
            @Override public void onProblem(String message) {
                kb.setListening(false);
                android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------------------------------------------------------------- server events

    @Override
    public void onCaret(YugiEvents.Caret c, boolean replay) {
        lastEditable = c.editable;
        if (c.editable && c.hasRect && c.screenH > 0) caret = c;
        if (!c.editable) {
            caret = null;
            userHidden = false;
            if (autoShown && wantShown) kb.postDelayed(autoHide, AUTO_HIDE_DELAY_MS);
            return;
        }
        kb.removeCallbacks(autoHide);
        // a tap into a text field brings the keyboard back even after it was hidden by hand (Windows does)
        if (userHidden && !replay && android.os.SystemClock.uptimeMillis() - lastTouchAt < TAP_TO_FOCUS_MS) userHidden = false;
        if (wantShown) {
            retargetShift();
        } else if (!replay && !userHidden && !appFieldFocused && !YugiKbdFocus.heldByOther(this)) {
            autoShown = true;
            show();
        }
    }

    // ---------------------------------------------------------------- motion

    private int rootWidth() {
        View root = (View) desktop.getParent();
        return root != null && root.getWidth() > 0 ? root.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
    }

    private int rootHeight() {
        View root = (View) desktop.getParent();
        return root != null && root.getHeight() > 0 ? root.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
    }

    private void retargetShift() {
        if (wantShown) shift.animateToFinalPosition(targetShift());
    }

    /** Pixels the desktop must move up so the caret ends 24dp above the keyboard. */
    float targetShift() {
        int h = rootHeight();
        float panel = kbPanelHeight();
        float kbTop = h - panel;
        float caretBottom;
        if (caret != null) {
            caretBottom = YugiShift.caretBottomInView(caret.y, caret.h, caret.screenH, stream.getY(), stream.getHeight());
        } else {
            caretBottom = YugiShift.fallbackCaretBottom(
                    Float.isNaN(lastTouchY) ? Float.NaN : stream.getY() + lastTouchY, dp(40));
        }
        return YugiShift.shiftFor(caretBottom, dp(MARGIN_DP), kbTop, panel);
    }

    private float kbPanelHeight() {
        kb.preferredHeight(rootWidth(), rootHeight()); // configures rows for this window
        return kb.pushHeight(); // 0 when floating: the desktop stays put
    }

    private void apply() {
        float t = Math.max(0f, Math.min(1f, revealValue.getValue()));
        int h = kb.getHeight() > 0 ? kb.getHeight() : kb.preferredHeight(rootWidth(), rootHeight());
        kb.setTranslationY((1f - t) * h);
        desktop.setTranslationY(-shiftValue.getValue() * t);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics());
    }

    /** For tests and screenshots. */
    public YugiKeyboard view() { return kb; }
    public YugiKeyEngine engine() { return engine; }

    /** Jumps to the end state without animating (screenshots). */
    public void settleForTest() {
        reveal.cancel();
        shift.cancel();
        revealValue.setValue(wantShown ? 1f : 0f);
        shiftValue.setValue(wantShown ? targetShift() : 0f);
        apply();
    }

    /** Caret as the server would report it (tests). */
    public void caretForTest(boolean editable, float y, float h, float screenW, float screenH) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("editable", editable);
            if (editable) { o.put("x", 10); o.put("y", y); o.put("w", 1); o.put("h", h); o.put("sw", screenW); o.put("sh", screenH); }
            onCaret(new YugiEvents.Caret(o), false);
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
    }
}

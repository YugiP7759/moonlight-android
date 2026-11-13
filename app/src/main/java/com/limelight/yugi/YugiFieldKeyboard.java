package com.limelight.yugi;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.Selection;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The YugiPC keyboard for text fields inside the app (Yugi chat), instead of the phone's keyboard.
 * <p>
 * A bound field never opens the system IME ({@code setShowSoftInputOnFocus(false)}); a tap or focus
 * opens this keyboard, which writes straight into the field (see {@link YugiEditTextSender}).
 * One spring drives the reveal, and every frame reports the covered height so the content above
 * (the chat card) rides up exactly with the keyboard edge, the same motion as the desktop keyboard.
 * The mic key dictates into the field with live partial results.
 */
public final class YugiFieldKeyboard implements YugiKeyboard.Host, YugiKbdFocus.Owner {
    public interface InsetListener {
        /** Pixels of the window bottom the keyboard covers right now (animated). */
        void onKeyboardInset(int px);
        default void onShown(boolean shown) {}
    }

    private final Activity activity;
    private final YugiKeyEngine engine;
    private final YugiKeyboard kb;
    private final YugiVoice voice;
    private InsetListener insetListener;

    private final java.util.Map<EditText, YugiEditTextSender> senders = new java.util.WeakHashMap<>();
    private EditText field;
    private YugiEditTextSender sender;

    private final FloatValueHolder revealValue = new FloatValueHolder(0f);
    private final SpringAnimation reveal;
    private boolean wantShown;
    private YugiKbdSettingsView settingsView;

    // dictation: the range in the field that the live text replaces
    private int voiceAnchor = -1, voiceLen;

    public static YugiFieldKeyboard attach(Activity a) { return new YugiFieldKeyboard(a); }

    private YugiFieldKeyboard(Activity a) {
        activity = a;
        engine = new YugiKeyEngine(new YugiKeyEngine.Sender() {
            @Override public void text(String s) { if (sender != null) sender.text(s); }
            @Override public void key(int code, boolean down, int mods) { if (sender != null) sender.key(code, down, mods); }
        });
        engine.hebrew = true; // the chat is in Hebrew
        kb = new YugiKeyboard(a, engine, this);
        kb.setVisibility(View.GONE);
        kb.setElevation(YugiTheme.dp(a, 20)); // above the chat card
        a.addContentView(kb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        voice = new YugiVoice(a);

        reveal = new SpringAnimation(revealValue);
        reveal.setSpring(new SpringForce(0f).setDampingRatio(0.9f).setStiffness(420f));
        reveal.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        reveal.addUpdateListener((anim, v, vel) -> apply());
        reveal.addEndListener((anim, canceled, v, vel) -> {
            if (!canceled && !wantShown) kb.setVisibility(View.GONE);
        });
        kb.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((b - t) != (ob - ot)) apply();
        });
    }

    public void setInsetListener(InsetListener l) { insetListener = l; }

    /** The field types with this keyboard only; Enter (and Esc) go to {@code actions}. */
    public void bind(EditText et, YugiEditTextSender.Actions actions) {
        et.setShowSoftInputOnFocus(false);
        YugiEditTextSender snd = new YugiEditTextSender(et, new YugiEditTextSender.Actions() {
            @Override public void onEnter() { actions.onEnter(); }
            @Override public void onEscape() { hide(); actions.onEscape(); }
        });
        senders.put(et, snd);
        // a tap opens it (not focus alone: a field that gets focus by itself must not pop a keyboard)
        et.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) v.post(() -> show(et));
            return false; // the field still places the caret / selection
        });
    }

    public boolean isShown() { return wantShown; }
    public EditText field() { return field; }

    public void show(EditText et) {
        YugiEditTextSender snd = senders.get(et);
        if (snd == null) return;
        if (field != et && voice.isActive()) voice.cancel();
        field = et;
        sender = snd;
        if (!et.hasFocus()) et.requestFocus();
        // belt and braces: the phone keyboard never shows over ours
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
        if (wantShown) return;
        YugiKbdFocus.claim(this);
        wantShown = true;
        if (kb.getVisibility() != View.VISIBLE) {
            kb.setVisibility(View.VISIBLE);
            kb.setTranslationY(kb.preferredHeight(rootWidth(), rootHeight()));
        }
        reveal.animateToFinalPosition(1f);
        if (insetListener != null) insetListener.onShown(true);
    }

    public void hide() {
        voice.cancel();
        kb.setListening(false);
        if (settingsView != null) { settingsView.close(); settingsView = null; }
        if (!wantShown) return;
        wantShown = false;
        YugiKbdFocus.release(this);
        kb.releaseAll();
        reveal.animateToFinalPosition(0f);
        if (insetListener != null) insetListener.onShown(false);
    }

    @Override public android.app.Activity ownerActivity() { return activity; }

    @Override public void yieldKeyboard() { hide(); }

    /** Call from the activity's onDestroy (or when the field goes away). */
    public void release() {
        voice.cancel();
        YugiKbdFocus.release(this);
    }

    private int rootWidth() {
        View root = (View) kb.getParent();
        return root != null && root.getWidth() > 0 ? root.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
    }

    private int rootHeight() {
        View root = (View) kb.getParent();
        return root != null && root.getHeight() > 0 ? root.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
    }

    private void apply() {
        float t = Math.max(0f, Math.min(1f, revealValue.getValue()));
        int h = kb.getHeight() > 0 ? kb.getHeight() : kb.preferredHeight(rootWidth(), rootHeight());
        kb.setTranslationY((1f - t) * h);
        if (insetListener != null) insetListener.onKeyboardInset(Math.round(kb.pushHeight() * t));
    }

    // ---------------------------------------------------------------- YugiKeyboard.Host

    @Override public void onHideRequested() { hide(); }

    @Override public void onGeometryChanged() { apply(); }

    @Override
    public void onSettingsRequested() {
        if (settingsView != null && settingsView.getParent() != null) return;
        settingsView = YugiKbdSettingsView.show(activity, kb, () -> settingsView = null);
    }

    @Override
    public void onMicRequested(boolean hebrew) {
        if (voice.isActive()) { voice.stop(); return; }
        if (field == null) return;
        voiceAnchor = -1;
        voice.start(hebrew, new YugiVoice.Listener() {
            @Override public void onListening(boolean on) { kb.setListening(on); }
            @Override public void onLevel(float level) { kb.setVoiceLevel(level); }
            @Override public void onPartial(String text) { put(text); }
            @Override public void onFinal(String text) {
                put(text);
                voiceAnchor = -1;
            }
            @Override public void onProblem(String message) {
                kb.setListening(false);
                kb.setVoicePreview(null);
                android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show();
            }
            private void put(String text) {
                if (field == null || text == null) return;
                Editable ed = field.getText();
                if (voiceAnchor < 0 || voiceAnchor + voiceLen > ed.length()) {
                    // first words: replace the selection, with a space after earlier text
                    int a = Math.max(0, Selection.getSelectionStart(ed)), b = Math.max(0, Selection.getSelectionEnd(ed));
                    int st = Math.min(a, b);
                    ed.delete(st, Math.max(a, b));
                    if (st > 0 && !Character.isWhitespace(ed.charAt(st - 1)) && !text.isEmpty()) {
                        ed.insert(st, " ");
                        st++;
                    }
                    voiceAnchor = st;
                    voiceLen = 0;
                }
                ed.replace(voiceAnchor, voiceAnchor + voiceLen, text);
                voiceLen = text.length();
                Selection.setSelection(ed, voiceAnchor + voiceLen);
            }
        });
    }

    // ---------------------------------------------------------------- tests

    public YugiKeyboard view() { return kb; }
    public YugiKeyEngine engine() { return engine; }

    public void settleForTest() {
        reveal.cancel();
        revealValue.setValue(wantShown ? 1f : 0f);
        apply();
    }
}

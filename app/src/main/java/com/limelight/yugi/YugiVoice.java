package com.limelight.yugi;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Keyboard dictation with Android's SpeechRecognizer, in the keyboard's language (he-IL or en-US),
 * with live partial results. On-device recognition is tried first when the phone has it; if that
 * language is not installed there, the regular recognizer takes over. The WHIP mic side channel
 * is paused while listening. A second tap stops and keeps what was heard.
 */
public final class YugiVoice {
    public interface Listener {
        void onListening(boolean on);
        /** 0..1, smoothed enough for drawing. */
        void onLevel(float level);
        void onPartial(String text);
        void onFinal(String text);
        default void onProblem(String message) {}
    }

    private final Context ctx;
    private Listener listener;
    private SpeechRecognizer rec;
    private boolean onDevice;
    private boolean hebrew;
    private boolean micWasOn;
    private boolean active;
    private String lastPartial = "";

    public YugiVoice(Context c) { ctx = c; }

    public boolean isActive() { return active; }

    /** Starts listening; false if there is no permission yet (it is requested) or no recognizer. */
    public boolean start(boolean hebrew, Listener l) {
        if (active) stop();
        listener = l;
        this.hebrew = hebrew;
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (ctx instanceof Activity) ((Activity) ctx).requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7102);
            l.onProblem("צריך הרשאת מיקרופון");
            return false;
        }
        boolean device = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx);
        if (!device && !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            l.onProblem("אין זיהוי דיבור בטלפון");
            return false;
        }
        micWasOn = YugiMedia.isMicOn();
        if (micWasOn) YugiMedia.setMic(false);
        active = true;
        lastPartial = "";
        begin(device);
        l.onListening(true);
        return true;
    }

    private void begin(boolean device) {
        destroy();
        onDevice = device;
        rec = device && Build.VERSION.SDK_INT >= 31
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                : SpeechRecognizer.createSpeechRecognizer(ctx);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String lang = hebrew ? "he-IL" : "en-US";
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        if (device) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        rec.setRecognitionListener(new RecognitionListener() {
            float level;
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float db) {
                // RMS dB is roughly -2..10: map to 0..1 and smooth (fast attack, slow release)
                float v = Math.max(0f, Math.min(1f, (db + 2f) / 12f));
                level = v > level ? level + (v - level) * 0.6f : level + (v - level) * 0.15f;
                if (active && listener != null) listener.onLevel(level);
            }
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int t, Bundle p) {}
            @Override public void onPartialResults(Bundle r) {
                String s = first(r);
                if (s != null && active && listener != null) {
                    lastPartial = s;
                    listener.onPartial(s);
                }
            }
            @Override public void onResults(Bundle r) {
                String s = first(r);
                finish(s != null ? s : lastPartial);
            }
            @Override public void onError(int error) {
                if (!active) return;
                if (onDevice && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        && SpeechRecognizer.isRecognitionAvailable(ctx)) {
                    begin(false); // this language is not on the device: use the regular recognizer
                    return;
                }
                if (!lastPartial.isEmpty()) { finish(lastPartial); return; }
                Listener l = listener;
                end();
                if (l != null && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    l.onProblem("ההקלטה נכשלה");
                }
            }
        });
        rec.startListening(i);
    }

    private static String first(Bundle r) {
        ArrayList<String> l = r == null ? null : r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return l != null && !l.isEmpty() ? l.get(0) : null;
    }

    private void finish(String text) {
        Listener l = listener;
        end();
        if (l != null) l.onFinal(text == null ? "" : text.trim());
    }

    /** Second tap: stop listening and keep what was heard (results arrive in a moment). */
    public void stop() {
        if (!active) return;
        if (rec != null) {
            try { rec.stopListening(); } catch (Exception e) { finish(lastPartial); }
        } else {
            finish(lastPartial);
        }
    }

    /** Drops everything (keyboard hidden, screen paused). */
    public void cancel() {
        if (!active) { destroy(); return; }
        listener = null;
        end();
    }

    private void end() {
        boolean was = active;
        active = false;
        destroy();
        if (micWasOn) YugiMedia.setMic(true);
        micWasOn = false;
        Listener l = listener;
        listener = null;
        if (was && l != null) l.onListening(false);
    }

    private void destroy() {
        if (rec != null) {
            try { rec.cancel(); rec.destroy(); } catch (Exception ignored) {}
            rec = null;
        }
    }
}

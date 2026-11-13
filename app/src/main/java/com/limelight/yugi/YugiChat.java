package com.limelight.yugi;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * "יוגי": floating button + semi-transparent side panel to chat with Yugi (Claude Code on the server,
 * via yugi-chat-bridge on port 8899). Text and voice; the stream keeps running underneath.
 */
public final class YugiChat {
    public static final int PORT = 8899;

    // ---- process-wide conversation model (survives switching between PC and games) ----
    static final class Msg {
        final String role; // user | assistant | system
        final StringBuilder text;
        Msg(String role, String text) { this.role = role; this.text = new StringBuilder(text); }
    }

    private static final List<Msg> messages = new ArrayList<>();
    private static boolean historyLoaded;
    private static volatile Call activeCall;
    private static volatile boolean busy;
    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE
            .build();

    public static boolean isEnabled() {
        return !BuildConfig.YUGI_CHAT_TOKEN.isEmpty();
    }

    private static String base() { return "http://" + YugiConfig.host + ":" + PORT; }

    private static Request.Builder req(String path) {
        return new Request.Builder().url(base() + path)
                .header("Authorization", "Bearer " + BuildConfig.YUGI_CHAT_TOKEN);
    }

    // ---- per-activity UI ----
    private final Activity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout panel;
    private LinearLayout list;
    private ScrollView scroll;
    private EditText input;
    private TextView micBtn, stopBtn, statusView;
    private boolean open;
    private TextView liveBubble;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean speakNext;
    private SpeechRecognizer recognizer;
    private MediaRecorder recorder;
    private File recordFile;
    private boolean micWasOn;

    private YugiChat(Activity a) { this.a = a; }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    /** Adds the floating "יוגי" button (under the menu button). Returns the controller, or null if disabled. */
    public static YugiChat attach(Activity a) {
        if (!isEnabled()) return null;
        YugiChat c = new YugiChat(a);
        c.build();
        return c;
    }

    private void build() {
        TextView b = new TextView(a);
        b.setText("יוגי");
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x993D7BFF);
        b.setBackground(bg);
        b.setAlpha(0.6f);
        b.setContentDescription("צ'אט עם יוגי");
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        blp.topMargin = dp(64);
        blp.rightMargin = dp(12);
        b.setOnClickListener(v -> toggle());
        a.addContentView(b, blp);

        panel = new FrameLayout(a);
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xCC0B1220);
        pbg.setCornerRadius(dp(16));
        panel.setBackground(pbg);
        panel.setClickable(true); // touches on the panel never reach the stream
        panel.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // header
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("יוגי", 17, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stopBtn = chip("עצור", v -> stop());
        stopBtn.setVisibility(View.GONE);
        head.addView(stopBtn);
        head.addView(chip("שיחה חדשה", v -> reset()));
        head.addView(chip("✕", v -> toggle()));
        col.addView(head);

        statusView = label("", 12, false);
        statusView.setTextColor(0xFF9DB4FF);
        statusView.setVisibility(View.GONE);
        col.addView(statusView);

        scroll = new ScrollView(a);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // input row
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(a);
        input.setHint("כתוב ליוגי…");
        input.setHintTextColor(0x99FFFFFF);
        input.setTextColor(Color.WHITE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendTyped();
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        micBtn = roundButton("🎤", v -> onMic());
        row.addView(micBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView send = roundButton("➤", v -> sendTyped());
        send.setRotation(180); // points left in RTL
        row.addView(send, new LinearLayout.LayoutParams(dp(48), dp(48)));
        col.addView(row);

        int width = Math.min(dp(380), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.5f));
        width = Math.max(width, Math.min(dp(300), a.getResources().getDisplayMetrics().widthPixels - dp(24)));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        plp.topMargin = dp(12);
        plp.bottomMargin = dp(12);
        plp.rightMargin = dp(64);
        a.addContentView(panel, plp);

        renderAll();
    }

    private TextView label(String t, int sp, boolean bold) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView chip(String t, View.OnClickListener l) {
        TextView v = label(t, 13, false);
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        v.setMinHeight(dp(40));
        v.setGravity(Gravity.CENTER);
        v.setOnClickListener(l);
        return v;
    }

    private TextView roundButton(String t, View.OnClickListener l) {
        TextView v = label(t, 18, false);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x553D7BFF);
        v.setBackground(bg);
        v.setOnClickListener(l);
        return v;
    }

    // ---------------------------------------------------------------- open / close

    public void toggle() {
        open = !open;
        if (open) {
            panel.setVisibility(View.VISIBLE);
            panel.setAlpha(0f);
            panel.setTranslationX(dp(60));
            panel.animate().alpha(1f).translationX(0).setDuration(160).start();
            if (!historyLoaded) loadHistory();
            scrollToEnd();
        } else {
            hideKeyboard();
            stopListening();
            if (tts != null) tts.stop();
            panel.animate().alpha(0f).translationX(dp(60)).setDuration(140)
                    .withEndAction(() -> panel.setVisibility(View.GONE)).start();
        }
    }

    public boolean isOpen() { return open; }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    // ---------------------------------------------------------------- rendering

    private void renderAll() {
        list.removeAllViews();
        liveBubble = null;
        synchronized (messages) {
            for (Msg m : messages) addBubble(m);
        }
        refreshBusy();
        scrollToEnd();
    }

    private TextView addBubble(Msg m) {
        TextView t = new TextView(a);
        t.setText(m.text);
        t.setTextIsSelectable(true);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        if ("user".equals(m.role)) {
            bg.setColor(0xCC3D7BFF);
            t.setTextColor(Color.WHITE);
            lp.gravity = Gravity.START; // RTL: user on the right
        } else if ("system".equals(m.role)) {
            bg.setColor(0x00000000);
            t.setTextColor(0x99FFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
        } else {
            bg.setColor(0xCC1F2937);
            t.setTextColor(Color.WHITE);
            lp.gravity = Gravity.END;
        }
        t.setBackground(bg);
        t.setMaxWidth(dp(320));
        list.addView(t, lp);
        return t;
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void refreshBusy() {
        stopBtn.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (!busy) statusView.setVisibility(View.GONE);
    }

    private void setStatus(String s) {
        statusView.setText(s);
        statusView.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- network

    private void loadHistory() {
        http.newCall(req("/history?n=50").get().build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                ui.post(() -> setStatus("אין חיבור ליוגי"));
            }
            @Override public void onResponse(Call call, Response r) {
                try (Response res = r) {
                    if (!res.isSuccessful() || res.body() == null) return;
                    JSONArray items = new JSONObject(res.body().string()).getJSONArray("items");
                    List<Msg> loaded = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject o = items.getJSONObject(i);
                        loaded.add(new Msg(o.optString("role"), o.optString("text")));
                    }
                    synchronized (messages) {
                        if (messages.isEmpty()) messages.addAll(loaded);
                    }
                    historyLoaded = true;
                    ui.post(YugiChat.this::renderAll);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void sendTyped() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        input.setText("");
        speakNext = false;
        send(t);
    }

    private void send(String text) {
        if (busy) {
            setStatus("יוגי עוד עונה. אפשר לעצור או לחכות");
            return;
        }
        busy = true;
        Msg user = new Msg("user", text);
        Msg answer = new Msg("assistant", "");
        synchronized (messages) { messages.add(user); messages.add(answer); }
        addBubble(user);
        liveBubble = addBubble(answer);
        liveBubble.setText("…");
        refreshBusy();
        setStatus("חושב…");
        scrollToEnd();

        JSONObject body = new JSONObject();
        try { body.put("text", text); } catch (Exception ignored) {}
        Call call = http.newCall(req("/chat")
                .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), MediaType.get("application/json")))
                .build());
        activeCall = call;
        new Thread(() -> runStream(call, answer), "YugiChat").start();
    }

    private void runStream(Call call, Msg answer) {
        String error = null;
        try (Response res = call.execute()) {
            if (res.code() == 409) {
                error = "יוגי עסוק בבקשה אחרת. נסה שוב בעוד רגע";
            } else if (!res.isSuccessful() || res.body() == null) {
                error = "שגיאה " + res.code();
            } else {
                BufferedReader r = new BufferedReader(new InputStreamReader(res.body().byteStream(), StandardCharsets.UTF_8));
                String line, event = null;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:") && event != null) {
                        JSONObject d = new JSONObject(line.substring(5).trim());
                        final String ev = event;
                        if ("delta".equals(ev)) {
                            String piece = d.optString("text");
                            synchronized (messages) { answer.text.append(piece); }
                            ui.post(() -> { if (liveBubble != null) liveBubble.setText(answer.text); scrollToEnd(); });
                        } else if ("status".equals(ev)) {
                            String tool = d.optString("tool", "");
                            ui.post(() -> setStatus(tool.isEmpty() ? "חושב…" : "עובד (" + tool + ")…"));
                        } else if ("done".equals(ev)) {
                            String full = d.optString("text");
                            synchronized (messages) {
                                if (!full.isEmpty()) { answer.text.setLength(0); answer.text.append(full); }
                            }
                        } else if ("error".equals(ev)) {
                            String err = d.optString("error");
                            error = "stopped".equals(err) ? "נעצר" : "שגיאה: " + err;
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (!call.isCanceled()) error = "אין חיבור ליוגי";
            LimeLog.warning("YugiChat: " + e);
        }
        final String err = error;
        ui.post(() -> {
            busy = false;
            activeCall = null;
            if (err != null) {
                synchronized (messages) {
                    if (answer.text.length() == 0) answer.text.append(err);
                    else answer.text.append("\n(").append(err).append(")");
                }
            }
            if (liveBubble != null) liveBubble.setText(answer.text);
            liveBubble = null;
            refreshBusy();
            setStatus("");
            scrollToEnd();
            if (err == null && speakNext) speak(answer.text.toString());
            speakNext = false;
        });
    }

    private void stop() {
        http.newCall(req("/stop").post(RequestBody.create(new byte[0], null)).build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {}
            @Override public void onResponse(Call call, Response r) { r.close(); }
        });
        setStatus("עוצר…");
    }

    private void reset() {
        if (busy) { setStatus("קודם עצור את התשובה הנוכחית"); return; }
        http.newCall(req("/reset").post(RequestBody.create(new byte[0], null)).build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                ui.post(() -> setStatus("אין חיבור ליוגי"));
            }
            @Override public void onResponse(Call call, Response r) {
                r.close();
                synchronized (messages) { messages.clear(); messages.add(new Msg("system", "שיחה חדשה")); }
                ui.post(YugiChat.this::renderAll);
            }
        });
    }

    // ---------------------------------------------------------------- voice

    private void onMic() {
        if (tts != null) tts.stop();
        if (recorder != null) { finishRecording(); return; }
        if (recognizer != null) { stopListening(); return; }
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7101);
            return;
        }
        // The WHIP side channel also uses the mic: pause it while dictating
        micWasOn = YugiMedia.isMicOn();
        if (micWasOn) YugiMedia.setMic(false);
        if (SpeechRecognizer.isRecognitionAvailable(a)) startRecognizer();
        else startRecording();
    }

    private void restoreMic() {
        if (micWasOn) YugiMedia.setMic(true);
        micWasOn = false;
    }

    private void startRecognizer() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(a);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { setStatus("מקשיב…"); micBtn.setText("■"); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { setStatus("מעבד…"); }
            @Override public void onEvent(int t, Bundle p) {}
            @Override public void onPartialResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty()) input.setText(l.get(0));
            }
            @Override public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = l != null && !l.isEmpty() ? l.get(0).trim() : "";
                stopListening();
                input.setText("");
                if (!text.isEmpty()) { speakNext = true; send(text); } else setStatus("");
            }
            @Override public void onError(int error) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    stopListening();
                    setStatus("לא שמעתי. נסה שוב");
                } else {
                    // Recognizer unavailable for Hebrew here: record and transcribe on the server (ivrit-ai)
                    destroyRecognizer();
                    startRecording();
                }
            }
        });
        recognizer.startListening(i);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private void stopListening() {
        destroyRecognizer();
        if (recorder == null) {
            micBtn.setText("🎤");
            restoreMic();
        }
    }

    private void startRecording() {
        try {
            recordFile = new File(a.getCacheDir(), "yugi-voice.m4a");
            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(a) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(48000);
            recorder.setOutputFile(recordFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            micBtn.setText("■");
            setStatus("מקליט… לחץ ■ לסיום");
        } catch (Exception e) {
            recorder = null;
            micBtn.setText("🎤");
            restoreMic();
            setStatus("אין גישה למיקרופון");
        }
    }

    private void finishRecording() {
        try { recorder.stop(); } catch (Exception ignored) {}
        recorder.release();
        recorder = null;
        micBtn.setText("🎤");
        restoreMic();
        setStatus("מתמלל…");
        final File f = recordFile;
        http.newCall(req("/voice").post(RequestBody.create(f, MediaType.get("audio/mp4"))).build())
                .enqueue(new okhttp3.Callback() {
                    @Override public void onFailure(Call call, java.io.IOException e) {
                        ui.post(() -> setStatus("התמלול נכשל"));
                    }
                    @Override public void onResponse(Call call, Response r) {
                        String text = "";
                        try (Response res = r) {
                            if (res.isSuccessful() && res.body() != null) {
                                text = new JSONObject(res.body().string()).optString("text").trim();
                            }
                        } catch (Exception ignored) {}
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                        final String t = text;
                        ui.post(() -> {
                            if (t.isEmpty()) { setStatus("לא שמעתי. נסה שוב"); return; }
                            setStatus("");
                            speakNext = true;
                            send(t);
                        });
                    }
                });
    }

    private void speak(String text) {
        if (text == null || text.isEmpty()) return;
        final String clean = text.replaceAll("[*#`_>|]", " ").trim();
        if (tts == null) {
            tts = new TextToSpeech(a.getApplicationContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(new Locale("he", "IL"));
                    tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "yugi");
                }
            });
        } else if (ttsReady) {
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "yugi");
        }
    }

    /** Call from the activity's onDestroy. */
    public void release() {
        stopListening();
        if (recorder != null) { try { recorder.release(); } catch (Exception ignored) {} recorder = null; }
        if (tts != null) { tts.shutdown(); tts = null; }
    }
}

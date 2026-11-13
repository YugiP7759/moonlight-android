package com.limelight.yugi;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
 * "יוגי" chat panel (Claude Code on the server via yugi-chat-bridge :8899).
 * A full-screen, touch-transparent container holding one glass card at the end side. The card always sits
 * below the toolbar and above the keyboard (IME insets), so the input field is never hidden.
 * Text, Hebrew voice, screenshot of the current stream and file attachments.
 */
public class YugiChat extends FrameLayout {
    public static final int PORT = 8899;
    public static final int MAX_ATTACH_BYTES = 20 * 1024 * 1024;

    /** What the hosting screen provides. */
    public interface Host {
        /** Grab the current frame (desktop stream or game), or null. Called on the UI thread. */
        void captureScreen(BitmapCallback cb);
        /** Open the file picker (normally {@link #openPicker(Activity)}). */
        void pickFile();
        /** Top edge (px) the panel must stay below (the toolbar). */
        int reservedTop();
        /** The keyboard opened or closed while the panel is open (hide the toolbar to make room). */
        default void onKeyboard(boolean shown) {}
    }

    public interface BitmapCallback { void onBitmap(Bitmap b); }

    // ---- shared conversation model (survives switching between PC and games) ----
    static final class Attachment {
        final String name;
        final String mime;
        final byte[] data;
        final Bitmap thumb; // images only
        String serverPath;
        Attachment(String name, String mime, byte[] data, Bitmap thumb) {
            this.name = name; this.mime = mime; this.data = data; this.thumb = thumb;
        }
    }

    static final class Msg {
        final String role; // user | assistant | system
        final StringBuilder text;
        final List<Attachment> attachments = new ArrayList<>();
        Msg(String role, String text) { this.role = role; this.text = new StringBuilder(text); }
    }

    private static final List<Msg> messages = new ArrayList<>();
    private static boolean historyLoaded;
    private static volatile boolean busy;
    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
    // The bridge pings every 15 s, so a silent minute means the connection died (phone slept, network
    // switched). Without this the read blocks forever and `busy` locks the chat until the app restarts.
    private static final OkHttpClient streamHttp = http.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private static volatile Call activeCall;

    public static boolean isChatEnabled() {
        return !BuildConfig.YUGI_CHAT_TOKEN.isEmpty();
    }

    private static String base() { return "http://" + YugiConfig.host + ":" + PORT; }

    private static Request.Builder req(String path) {
        return new Request.Builder().url(base() + path)
                .header("Authorization", "Bearer " + BuildConfig.YUGI_CHAT_TOKEN);
    }

    // ---- view ----
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ChatCard card;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final EditText input;
    private final ImageButton micSend;
    private final ImageButton stopBtn;
    private final TextView subtitle;
    private final LinearLayout attachRow;
    private final HorizontalScrollView attachScroll;
    private final LinearLayout inputBox;
    /** The server's Yugi sessions, shown in place of this conversation (same card). */
    private final YugiSessions sessions;
    // Attachments waiting to be sent (shared: the file picker may outlive the hosting screen)
    private static final List<Attachment> pending = new ArrayList<>();
    /** Set by the file picker: reopen the panel on the next screen that attaches it. */
    static volatile boolean reopenOnAttach;
    private static YugiChat current;
    private boolean open;
    private int imeInset;
    private int navInset;
    private int kbInset;             // our own keyboard (animated with its spring)
    private YugiFieldKeyboard fieldKb;
    private TextView liveBubble;
    private YugiChatBubble fab;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean speakNext;
    private SpeechRecognizer recognizer;
    private MediaRecorder recorder;
    private File recordFile;
    private boolean micWasOn;
    private boolean sendMode;

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    public YugiChat(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
        YugiTheme.keepOnTop(this);
        setLayoutDirection(LAYOUT_DIRECTION_LTR); // card on the right in every locale; its content is RTL

        card = new ChatCard(ctx);
        card.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        card.setBackground(YugiTheme.roundedStroke(ctx, YugiTheme.GLASS_STRONG, YugiTheme.RADIUS_L, YugiTheme.STROKE));
        card.setElevation(dp(12));
        card.setClickable(true); // touches on the card never reach the stream
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        addView(card);

        // ---- header ----
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), 0, 0, 0);
        ImageView avatar = new ImageView(ctx);
        avatar.setImageResource(R.drawable.yugi_ic_yugi);
        avatar.setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.ACCENT));
        avatar.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable av = new GradientDrawable();
        av.setShape(GradientDrawable.OVAL);
        av.setColor(YugiTheme.SURFACE);
        avatar.setBackground(av);
        head.addView(avatar, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, dp(10), 0);
        titles.addView(YugiTheme.text(ctx, "יוגי", 16, 600, YugiTheme.TEXT));
        subtitle = YugiTheme.text(ctx, "העוזר שלך במחשב", 12, 400, YugiTheme.TEXT_DIM);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stopBtn = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_stop, "עצור");
        stopBtn.setOnClickListener(v -> stop());
        stopBtn.setVisibility(GONE);
        head.addView(stopBtn, sq(48));
        ImageButton sessionsBtn = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_sessions, "סשנים");
        sessionsBtn.setOnClickListener(v -> showSessions(true));
        head.addView(sessionsBtn, sq(48));
        ImageButton newChat = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_add, "שיחה חדשה");
        newChat.setOnClickListener(v -> reset());
        head.addView(newChat, sq(48));
        ImageButton close = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_close, "סגור");
        close.setOnClickListener(v -> setOpen(false));
        head.addView(close, sq(48));
        card.header = head;
        card.addView(head);

        View hair = new View(ctx);
        hair.setBackgroundColor(YugiTheme.STROKE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        hlp.topMargin = dp(6);
        card.hair = hair;
        card.addView(hair);

        // ---- messages ----
        scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scroll.setOnScrollChangeListener((v, x, y, ox, oy) ->
                    stickToBottom = y >= ((ScrollView) v).getChildAt(0).getHeight() - v.getHeight() - dp(24));
        }
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(8), dp(4), dp(8));
        scroll.addView(list);
        card.scroll = scroll;
        card.addView(scroll);

        // ---- attachment preview ----
        attachRow = new LinearLayout(ctx);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.setPadding(dp(4), 0, dp(4), dp(6));
        attachScroll = new HorizontalScrollView(ctx);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.addView(attachRow);
        attachScroll.setVisibility(GONE);
        card.attach = attachScroll;
        card.addView(attachScroll);

        // ---- input ----
        inputBox = new LinearLayout(ctx);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setBackground(YugiTheme.roundedStroke(ctx, YugiTheme.SURFACE, 24, YugiTheme.STROKE));
        inputBox.setMinimumHeight(dp(56));
        inputBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageButton attach = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_attach, "צרף קובץ");
        attach.setOnClickListener(v -> { reopenOnAttach = true; host.pickFile(); });
        inputBox.addView(attach, sq(48));
        ImageButton shot = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_screenshot, "צלם מסך");
        shot.setOnClickListener(v -> takeScreenshot());
        inputBox.addView(shot, sq(48));
        input = new EditText(ctx);
        input.setHint("הודעה ליוגי");
        input.setHintTextColor(YugiTheme.TEXT_FAINT);
        input.setTextColor(YugiTheme.TEXT);
        input.setTypeface(YugiTheme.font(ctx, 400));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setBackground(null);
        input.setPadding(dp(8), dp(10), dp(8), dp(10));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(5);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        input.setShowSoftInputOnFocus(false); // the YugiPC keyboard types here, never the phone's
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                sendTyped();
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSendButton(); }
        });
        inputBox.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        micSend = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_mic, "הקלטה");
        micSend.setOnClickListener(v -> { if (sendMode) sendTyped(); else onMic(); });
        inputBox.addView(micSend, sq(48));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(4);
        card.input = inputBox;
        card.addView(inputBox);
        updateSendButton();

        // ---- sessions panel (on top of everything in the card while shown) ----
        sessions = new YugiSessions(ctx, new YugiSessions.Host() {
            @Override public void onBack() { showSessions(false); }
            @Override public void onClose() { setOpen(false); }
        });
        sessions.setVisibility(GONE);
        card.sessions = sessions;
        card.addView(sessions);

        // Keep the card above the keyboard and system bars
        setOnApplyWindowInsetsListener((v, insets) -> {
            int ime = 0, nav = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                nav = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            } else {
                ime = insets.getSystemWindowInsetBottom();
            }
            setInsets(ime, nav);
            return insets;
        });

        renderAll();
    }

    /**
     * Card layout with fixed priorities: the input row is always laid out first at the bottom, then the
     * attachment preview above it, the header at the top, and the messages get whatever is left.
     * With little room (keyboard open on the cover screen) the header is dropped.
     */
    static final class ChatCard extends ViewGroup {
        View header, hair, scroll, attach, input, sessions;

        private boolean sessionsShown() { return sessions != null && sessions.getVisibility() != GONE; }

        ChatCard(Context c) { super(c); }

        private int dp(float v) { return YugiTheme.dp(getContext(), v); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
            setMeasuredDimension(w, h);
            int innerW = w - getPaddingLeft() - getPaddingRight();
            int avail = h - getPaddingTop() - getPaddingBottom();
            int exactW = MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY);
            int atMost = MeasureSpec.makeMeasureSpec(Math.max(0, avail), MeasureSpec.AT_MOST);
            input.measure(exactW, atMost);
            avail -= input.getMeasuredHeight() + dp(4);
            if (attach.getVisibility() != GONE) {
                attach.measure(exactW, MeasureSpec.makeMeasureSpec(Math.max(0, avail), MeasureSpec.AT_MOST));
                avail -= attach.getMeasuredHeight();
            }
            boolean showHeader = avail >= dp(48 + 7 + 72); // keep at least a few lines of messages
            header.setVisibility(showHeader ? VISIBLE : GONE);
            hair.setVisibility(showHeader ? VISIBLE : GONE);
            if (showHeader) {
                header.measure(exactW, MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY));
                hair.measure(exactW, MeasureSpec.makeMeasureSpec(Math.max(1, dp(1)), MeasureSpec.EXACTLY));
                avail -= header.getMeasuredHeight() + hair.getMeasuredHeight() + dp(6);
            }
            scroll.measure(exactW, MeasureSpec.makeMeasureSpec(Math.max(0, avail), MeasureSpec.EXACTLY));
            if (sessionsShown()) {
                sessions.measure(exactW, MeasureSpec.makeMeasureSpec(Math.max(0, h - getPaddingTop() - getPaddingBottom()), MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) {
            // While the sessions panel is fully in, the chat underneath isn't drawn (or seen through it)
            if (child != sessions && sessionsShown() && sessions.getAlpha() >= 1f) return false;
            return super.drawChild(canvas, child, drawingTime);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int left = getPaddingLeft(), right = (r - l) - getPaddingRight();
            int y = getPaddingTop();
            if (header.getVisibility() != GONE) {
                header.layout(left, y, right, y + header.getMeasuredHeight());
                y += header.getMeasuredHeight() + dp(6);
                hair.layout(left, y, right, y + hair.getMeasuredHeight());
                y += hair.getMeasuredHeight();
            }
            scroll.layout(left, y, right, y + scroll.getMeasuredHeight());
            int bottom = (b - t) - getPaddingBottom();
            input.layout(left, bottom - input.getMeasuredHeight(), right, bottom);
            bottom -= input.getMeasuredHeight() + dp(4);
            if (attach.getVisibility() != GONE) {
                attach.layout(left, bottom - attach.getMeasuredHeight(), right, bottom);
            }
            if (sessionsShown()) {
                sessions.layout(left, getPaddingTop(), right, getPaddingTop() + sessions.getMeasuredHeight());
            }
        }
    }

    private LinearLayout.LayoutParams sq(int sizeDp) {
        return new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
    }

    /** Adds the (hidden) panel over an activity. */
    public static YugiChat attach(Activity a, Host host) {
        if (!isChatEnabled()) return null;
        YugiChat c = new YugiChat(a, host);
        a.addContentView(c, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        c.fab = YugiChatBubble.attach(a, c::toggle);
        c.attachKeyboard(a);
        current = c;
        c.renderPending();
        if (reopenOnAttach) {
            reopenOnAttach = false;
            c.post(() -> c.setOpen(true));
        }
        return c;
    }

    /** Our keyboard for the input field: the card rides up with it, on the same spring. */
    void attachKeyboard(Activity a) {
        fieldKb = YugiFieldKeyboard.attach(a);
        fieldKb.bind(input, this::sendTyped);
        sessions.bindKeyboard(fieldKb);
        fieldKb.setInsetListener(new YugiFieldKeyboard.InsetListener() {
            @Override public void onKeyboardInset(int px) {
                if (px == kbInset) return;
                boolean wasTight = tight();
                kbInset = px;
                applyTight(wasTight);
                requestLayout();
            }
            @Override public void onShown(boolean shown) {
                if (shown) { stickToBottom = true; scrollToEnd(); }
            }
        });
    }

    /** The in-app keyboard (tests). */
    public YugiFieldKeyboard fieldKeyboard() { return fieldKb; }

    /** Opens the shared file picker screen (works from the stream too, which can't host a picker). */
    public static void openPicker(Activity from) {
        from.startActivity(new Intent(from, YugiPickActivity.class));
    }

    // ---------------------------------------------------------------- layout

    /** Keyboard (IME) and navigation-bar insets in px. */
    public void setInsets(int ime, int nav) {
        if (ime != imeInset || nav != navInset) {
            boolean wasTight = tight();
            imeInset = ime;
            applyTight(wasTight);
            navInset = nav;
            requestLayout();
            if (open) scrollToEnd();
        }
    }

    /** Short screen (cover screen in landscape) or keyboard up: use the whole height, compact attachments. */
    private boolean tight() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return imeInset > 0 || kbInset > 0 || dm.heightPixels / dm.density < 500;
    }

    private void applyTight(boolean wasTight) {
        boolean t = tight();
        if (t != compactAttachments) {
            compactAttachments = t;
            renderPending();
        }
        if (host != null && open && t != wasTight) host.onKeyboard(t);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        Rect r = cardRect(w, h);
        card.measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        Rect c = cardRect(r - l, b - t);
        card.layout(c.left, c.top, c.right, c.bottom);
        if (stickToBottom) {
            // Newest messages stay in view when the card shrinks (keyboard) or grows
            scroll.scrollTo(0, Math.max(0, list.getMeasuredHeight() - scroll.getHeight()));
        }
    }

    private boolean stickToBottom = true;

    /** Where the card goes: end side, below the toolbar, above the keyboard. */
    Rect cardRect(int w, int h) {
        int m = dp(12);
        // With the keyboard up the toolbar hides, so the card can use the whole height above it
        int top = tight() ? m : Math.max(m, host != null ? host.reservedTop() : m);
        int bottom = h - Math.max(m, Math.max(Math.max(imeInset, kbInset), navInset) + m);
        int width = w >= dp(600) ? Math.min(dp(420), (int) (w * 0.46f)) : w - 2 * m;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int left = rtl ? m : w - m - width;
        return new Rect(left, top, left + width, bottom);
    }

    /** Input field position (screen coordinates within this view), for checks. */
    public Rect inputRect() {
        return new Rect(card.getLeft() + inputBox.getLeft(), card.getTop() + inputBox.getTop(),
                card.getLeft() + inputBox.getRight(), card.getTop() + inputBox.getBottom());
    }

    public Rect cardRect() {
        return new Rect(card.getLeft(), card.getTop(), card.getRight(), card.getBottom());
    }

    // ---------------------------------------------------------------- open / close

    public boolean isOpen() { return open; }

    /** Swaps the card between this conversation and the sessions list (crossfade with a small rise). */
    public void showSessions(boolean show) {
        boolean now = sessions.getVisibility() == VISIBLE;
        if (show == now) return;
        if (show) {
            hideKeyboard();
            sessions.setVisibility(VISIBLE);
            sessions.setAlpha(0f);
            sessions.setTranslationY(dp(12));
            sessions.animate().alpha(1f).translationY(0).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).withEndAction(card::invalidate).start();
            sessions.setShown(open);
        } else {
            sessions.setShown(false);
            sessions.setAlpha(0.99f); // draw the chat underneath while fading out
            sessions.animate().alpha(0f).translationY(dp(8)).setDuration(140)
                    .withEndAction(() -> { sessions.setVisibility(GONE); sessions.setTranslationY(0); card.requestLayout(); }).start();
        }
        card.requestLayout();
    }

    public boolean isSessionsShown() { return sessions.getVisibility() == VISIBLE; }

    /** For previews/tests: the sessions panel. */
    YugiSessions sessionsForTest() { return sessions; }

    public void toggle() { setOpen(!open); }

    public void setOpen(boolean o) {
        open = o;
        if (fab != null) fab.setShown(!o);
        if (o) {
            setVisibility(VISIBLE);
            card.setAlpha(0f);
            card.setTranslationX(dp(24) * (getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -1 : 1));
            card.animate().alpha(1f).translationX(0).setDuration(160).start();
            if (!historyLoaded) loadHistory();
            if (sessions.getVisibility() == VISIBLE) sessions.setShown(true);
            if (host != null && tight()) host.onKeyboard(true); // the toolbar makes room
            if (compactAttachments != tight()) { compactAttachments = tight(); renderPending(); }
            scrollToEnd();
            requestApplyInsets();
        } else {
            hideKeyboard();
            sessions.setShown(false);
            if (host != null) host.onKeyboard(false);
            stopListening();
            if (tts != null) tts.stop();
            card.animate().alpha(0f).setDuration(120).withEndAction(() -> setVisibility(GONE)).start();
        }
    }

    private void hideKeyboard() {
        if (fieldKb != null) fieldKb.hide();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    // ---------------------------------------------------------------- rendering

    private void renderAll() {
        list.removeAllViews();
        liveBubble = null;
        synchronized (messages) {
            if (messages.isEmpty()) {
                TextView empty = YugiTheme.text(getContext(), "מה תרצה שאעשה?", 15, 400, YugiTheme.TEXT_DIM);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, dp(24), 0, dp(24));
                list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            for (Msg m : messages) addBubble(m);
        }
        refreshBusy();
        scrollToEnd();
    }

    private TextView addBubble(Msg m) {
        Context c = getContext();
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(10);

        TextView t = YugiTheme.text(c, m.text.toString(), 15, 400, YugiTheme.TEXT);
        t.setLineSpacing(dp(3), 1f);
        t.setTextIsSelectable(true);
        t.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if ("user".equals(m.role)) {
            wrap.setGravity(Gravity.START); // RTL: Yagel's messages on the right
            t.setBackground(YugiTheme.rounded(c, YugiTheme.SURFACE_HI, YugiTheme.RADIUS_M));
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
            t.setMaxWidth(dp(300));
            for (Attachment a : m.attachments) wrap.addView(attachmentChip(a, false));
            if (m.text.length() == 0) t.setVisibility(GONE);
        } else if ("system".equals(m.role)) {
            wrap.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setTextColor(YugiTheme.TEXT_FAINT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        } else {
            wrap.setGravity(Gravity.START);
            t.setPadding(dp(4), dp(2), dp(4), dp(2));
            tlp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        wrap.addView(t, tlp);
        list.addView(wrap, wlp);
        return t;
    }

    /**
     * Attachment views. In the message list: a framed thumbnail or a file chip.
     * In the preview row: same, with a small remove button in the top corner, outside the content.
     * Compact (keyboard open): 32dp items in a 40dp row, no remove button.
     */
    private View attachmentChip(Attachment a, boolean removable, boolean compact) {
        Context c = getContext();
        int xs = dp(22);
        FrameLayout f = new FrameLayout(c);
        View content;
        if (a.thumb != null) {
            FrameLayout frame = new FrameLayout(c);
            frame.setBackground(YugiTheme.roundedStroke(c, YugiTheme.SURFACE, YugiTheme.RADIUS_S, 0x40FFFFFF));
            int pad = Math.max(1, dp(1));
            frame.setPadding(pad, pad, pad, pad);
            ImageView iv = new ImageView(c);
            iv.setImageBitmap(a.thumb);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setBackground(YugiTheme.rounded(c, YugiTheme.SURFACE, YugiTheme.RADIUS_S - 1));
            frame.addView(iv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            int w = compact ? 32 : removable ? 56 : 120, h = compact ? 32 : removable ? 56 : 72;
            content = frame;
            f.addView(frame, new LayoutParams(dp(w), dp(h)));
        } else {
            LinearLayout chip = new LinearLayout(c);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(YugiTheme.roundedStroke(c, YugiTheme.SURFACE, YugiTheme.RADIUS_S, 0x40FFFFFF));
            chip.setPadding(dp(10), 0, dp(12), 0);
            ImageView ic = new ImageView(c);
            ic.setImageResource(R.drawable.yugi_ic_file);
            ic.setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.TEXT_DIM));
            ic.setTag("part");
            chip.addView(ic, new LinearLayout.LayoutParams(dp(18), dp(18)));
            // File names read left-to-right ("הצעת מחיר.pdf"), isolated from the RTL layout
            TextView name = YugiTheme.text(c, "\u2066" + a.name + "\u2069", 13, 400, YugiTheme.TEXT);
            name.setPadding(dp(8), 0, 0, 0);
            name.setMaxLines(1);
            name.setMaxWidth(dp(compact ? 96 : 140));
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextDirection(TEXT_DIRECTION_LTR);
            name.setTag("part");
            chip.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content = chip;
            f.addView(chip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(compact ? 32 : removable ? 56 : 44)));
        }
        content.setTag("content");
        if (removable && !compact) {
            // Remove button in the corner gap (outside the content), with a 48dp touch area
            f.setPadding(0, xs, xs, 0);
            ImageButton x = new ImageButton(c);
            x.setImageResource(R.drawable.yugi_ic_close);
            x.setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.TEXT));
            x.setScaleType(ImageView.ScaleType.FIT_CENTER);
            x.setPadding(dp(4), dp(4), dp(4), dp(4));
            GradientDrawable xb = new GradientDrawable();
            xb.setShape(GradientDrawable.OVAL);
            xb.setColor(YugiTheme.SURFACE_HI);
            xb.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
            x.setBackground(xb);
            x.setContentDescription("הסר " + a.name);
            x.setTag("x");
            x.setOnClickListener(v -> { pending.remove(a); renderPending(); });
            LayoutParams xlp = new LayoutParams(xs, xs, Gravity.TOP | Gravity.END);
            xlp.topMargin = -xs; // into the padding gap above the content
            xlp.rightMargin = -xs;
            f.setClipToPadding(false);
            f.addView(x, xlp);
            f.post(() -> {
                Rect hit = new Rect();
                x.getHitRect(hit);
                int grow = (dp(48) - xs) / 2;
                hit.inset(-grow, -grow);
                f.setTouchDelegate(new android.view.TouchDelegate(hit, x));
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(compact ? 4 : 6);
        lp.setMarginEnd(dp(8));
        f.setLayoutParams(lp);
        return f;
    }

    private View attachmentChip(Attachment a, boolean removable) { return attachmentChip(a, removable, false); }

    private boolean compactAttachments;

    private void renderPending() {
        attachRow.removeAllViews();
        for (Attachment a : pending) attachRow.addView(attachmentChip(a, true, compactAttachments));
        attachScroll.setVisibility(pending.isEmpty() ? GONE : VISIBLE);
        updateSendButton();
    }

    /** For checks: the remove button must not overlap the chip's content (icon, name or image). */
    List<String> chipProblems() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < attachRow.getChildCount(); i++) {
            ViewGroup f = (ViewGroup) attachRow.getChildAt(i);
            View x = f.findViewWithTag("x");
            View content = f.findViewWithTag("content");
            if (x == null || content == null) continue;
            Rect xr = new Rect(x.getLeft(), x.getTop(), x.getRight(), x.getBottom());
            Rect cr = new Rect(content.getLeft(), content.getTop(), content.getRight(), content.getBottom());
            if (Rect.intersects(xr, cr)) out.add("chip " + i + ": remove button " + xr.toShortString() + " overlaps content " + cr.toShortString());
            if (content instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) content;
                for (int k = 0; k < g.getChildCount(); k++) {
                    View p = g.getChildAt(k);
                    if (!"part".equals(p.getTag())) continue;
                    if (p.getWidth() <= 0 || p.getHeight() <= 0) out.add("chip " + i + ": part " + k + " has no size");
                    if (p instanceof TextView) {
                        CharSequence t = ((TextView) p).getText();
                        boolean isolated = t.length() > 1 && t.charAt(0) == '\u2066' && t.charAt(t.length() - 1) == '\u2069'
                                && p.getTextDirection() == TEXT_DIRECTION_LTR;
                        if (!isolated) out.add("chip " + i + ": file name is not bidi-isolated LTR: " + t);
                    }
                }
            }
        }
        return out;
    }

    /** Messages fully visible in the list right now. */
    int visibleMessageCount() {
        int top = scroll.getScrollY(), bottom = top + scroll.getHeight(), n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.getHeight() > 0 && v.getTop() >= top && v.getBottom() <= bottom) n++;
        }
        return n;
    }

    private void updateSendButton() {
        boolean canSend = input.getText().toString().trim().length() > 0 || !pending.isEmpty();
        sendMode = canSend;
        micSend.setImageResource(canSend ? R.drawable.yugi_ic_send : R.drawable.yugi_ic_mic);
        micSend.setContentDescription(canSend ? "שלח" : "הקלטה");
        micSend.setRotation(canSend ? 180 : 0); // send arrow points left in RTL
        if (canSend) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(YugiTheme.TEXT);
            micSend.setBackground(g);
            micSend.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF0B0E14));
        } else {
            YugiTheme.setActive(micSend, false);
        }
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void refreshBusy() {
        stopBtn.setVisibility(busy ? VISIBLE : GONE);
        if (!busy) subtitle.setText("העוזר שלך במחשב");
    }

    private void setStatus(String s) {
        subtitle.setText(s == null || s.isEmpty() ? "העוזר שלך במחשב" : s);
    }

    // ---------------------------------------------------------------- attachments

    private void takeScreenshot() {
        // Capture without the panel on top
        card.setVisibility(INVISIBLE);
        ui.postDelayed(() -> host.captureScreen(bmp -> {
            card.setVisibility(VISIBLE);
            if (bmp == null) { setStatus("לא הצלחתי לצלם את המסך"); return; }
            Bitmap scaled = scaleDown(bmp, 1920);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out);
            String name = "screenshot-" + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ".jpg";
            pending.add(new Attachment(name, "image/jpeg", out.toByteArray(), scaleDown(scaled, 320)));
            renderPending();
        }), 60);
    }

    private static Bitmap scaleDown(Bitmap b, int maxSide) {
        int w = b.getWidth(), h = b.getHeight();
        float s = Math.min(1f, (float) maxSide / Math.max(w, h));
        if (s >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    /** Reads a picked file into the shared pending list (runs off the UI thread). */
    static void importUri(Context c, Uri uri, Runnable done) {
        new Thread(() -> {
            try {
                String name = "file";
                try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
                    if (cur != null && cur.moveToFirst()) {
                        int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (i >= 0) name = cur.getString(i);
                    }
                }
                String mime = c.getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while (in != null && (n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > MAX_ATTACH_BYTES) throw new Exception("too big");
                    }
                }
                byte[] data = out.toByteArray();
                Bitmap thumb = null;
                if (mime.startsWith("image/")) {
                    Bitmap full = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (full != null) thumb = scaleDown(full, 320);
                }
                Attachment a = new Attachment(name, mime, data, thumb);
                new Handler(Looper.getMainLooper()).post(() -> {
                    pending.add(a);
                    if (current != null) current.renderPending();
                    if (done != null) done.run();
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (current != null) current.setStatus("לא הצלחתי לצרף (עד 20MB)");
                    if (done != null) done.run();
                });
            }
        }, "YugiAttach").start();
    }

    /** For previews/tests: add an attachment directly. */
    void addPendingForTest(String name, String mime, byte[] data, Bitmap thumb) {
        pending.add(new Attachment(name, mime, data, thumb));
        renderPending();
    }

    static void clearPendingForTest() { pending.clear(); }

    /** For previews/tests: replace the conversation. */
    static void setMessagesForTest(List<Msg> ms) {
        synchronized (messages) { messages.clear(); messages.addAll(ms); }
        historyLoaded = true;
    }

    void rerenderForTest() { renderAll(); }

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
                        loaded.add(new Msg(o.optString("role"), o.optString("display", o.optString("text"))));
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
        if (t.isEmpty() && pending.isEmpty()) return;
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
        user.attachments.addAll(pending);
        pending.clear();
        renderPending();
        Msg answer = new Msg("assistant", "");
        synchronized (messages) {
            if (messages.isEmpty()) list.removeAllViews();
            messages.add(user);
            messages.add(answer);
        }
        addBubble(user);
        liveBubble = addBubble(answer);
        liveBubble.setText("…");
        refreshBusy();
        setStatus(user.attachments.isEmpty() ? "חושב…" : "מעלה קבצים…");
        scrollToEnd();
        new Thread(() -> {
            String err = uploadAll(user.attachments);
            if (err != null) {
                finish(answer, err);
                return;
            }
            StringBuilder prompt = new StringBuilder(text);
            if (!user.attachments.isEmpty()) {
                prompt.append(prompt.length() > 0 ? "\n\n" : "").append("קבצים מצורפים מיגל (קרא אותם):");
                for (Attachment a : user.attachments) prompt.append("\n- ").append(a.serverPath);
            }
            ui.post(() -> setStatus("חושב…"));
            runStream(prompt.toString(), text, answer);
        }, "YugiChat").start();
    }

    /** Uploads attachments; returns an error message or null. */
    private String uploadAll(List<Attachment> atts) {
        for (Attachment a : atts) {
            if (a.serverPath != null) continue;
            try {
                Request r = req("/upload?name=" + URLEncoder.encode(a.name, "UTF-8"))
                        .post(RequestBody.create(a.data, MediaType.get(a.mime))).build();
                try (Response res = http.newCall(r).execute()) {
                    if (!res.isSuccessful() || res.body() == null) return "העלאת הקובץ נכשלה (" + res.code() + ")";
                    a.serverPath = new JSONObject(res.body().string()).getString("path");
                }
            } catch (Exception e) {
                return "העלאת הקובץ נכשלה";
            }
        }
        return null;
    }

    private void runStream(String prompt, String display, Msg answer) {
        String error = null;
        JSONObject body = new JSONObject();
        try { body.put("text", prompt); body.put("display", display); } catch (Exception ignored) {}
        Call call = streamHttp.newCall(req("/chat")
                .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), MediaType.get("application/json")))
                .build());
        activeCall = call;
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
                        if ("delta".equals(event)) {
                            String piece = d.optString("text");
                            synchronized (messages) { answer.text.append(piece); }
                            ui.post(() -> { if (liveBubble != null) liveBubble.setText(answer.text); scrollToEnd(); });
                        } else if ("status".equals(event)) {
                            String tool = d.optString("tool", "");
                            ui.post(() -> setStatus(tool.isEmpty() ? "חושב…" : "עובד…"));
                        } else if ("done".equals(event)) {
                            String full = d.optString("text");
                            synchronized (messages) {
                                if (!full.isEmpty()) { answer.text.setLength(0); answer.text.append(full); }
                            }
                        } else if ("error".equals(event)) {
                            String err = d.optString("error");
                            error = "stopped".equals(err) ? "נעצר" : "שגיאה: " + err;
                        }
                    }
                }
            }
        } catch (Exception e) {
            error = call.isCanceled() ? "נעצר"
                    : (answer.text.length() > 0 ? "החיבור נותק. התשובה המלאה תופיע בשיחה" : "אין חיבור ליוגי");
            LimeLog.warning("YugiChat: " + e);
        } finally {
            if (activeCall == call) activeCall = null;
        }
        finish(answer, error);
    }

    private void finish(Msg answer, String err) {
        ui.post(() -> {
            busy = false;
            if (err != null) {
                synchronized (messages) {
                    if (answer.text.length() == 0) answer.text.append(err);
                    else answer.text.append("\n(").append(err).append(")");
                }
            }
            if (liveBubble != null) liveBubble.setText(answer.text);
            liveBubble = null;
            refreshBusy();
            scrollToEnd();
            if (err == null && speakNext) speak(answer.text.toString());
            speakNext = false;
        });
    }

    private void stop() {
        // Free the panel right away even if the stream is dead; the server stops the turn on its side.
        Call c = activeCall;
        if (c != null) c.cancel();
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
                synchronized (messages) { messages.clear(); }
                ui.post(YugiChat.this::renderAll);
            }
        });
    }

    // ---------------------------------------------------------------- voice

    private void onMic() {
        if (tts != null) tts.stop();
        if (recorder != null) { finishRecording(); return; }
        if (recognizer != null) { stopListening(); return; }
        Context c = getContext();
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (c instanceof Activity) ((Activity) c).requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7101);
            return;
        }
        // The WHIP side channel also uses the mic: pause it while dictating
        micWasOn = YugiMedia.isMicOn();
        if (micWasOn) YugiMedia.setMic(false);
        if (SpeechRecognizer.isRecognitionAvailable(c)) startRecognizer();
        else startRecording();
    }

    private void restoreMic() {
        if (micWasOn) YugiMedia.setMic(true);
        micWasOn = false;
    }

    private void setMicRecording(boolean rec) {
        micSend.setImageResource(rec ? R.drawable.yugi_ic_stop : R.drawable.yugi_ic_mic);
        micSend.setRotation(0);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(rec ? YugiTheme.DANGER : 0x00000000);
        micSend.setBackground(g);
        micSend.setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.TEXT));
        if (!rec) updateSendButton();
    }

    private void startRecognizer() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { setStatus("מקשיב…"); setMicRecording(true); }
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
            setMicRecording(false);
            restoreMic();
        }
    }

    private void startRecording() {
        try {
            recordFile = new File(getContext().getCacheDir(), "yugi-voice.m4a");
            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(getContext()) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(48000);
            recorder.setOutputFile(recordFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            setMicRecording(true);
            setStatus("מקליט… לחץ לסיום");
        } catch (Exception e) {
            recorder = null;
            setMicRecording(false);
            restoreMic();
            setStatus("אין גישה למיקרופון");
        }
    }

    private void finishRecording() {
        try { recorder.stop(); } catch (Exception ignored) {}
        recorder.release();
        recorder = null;
        setMicRecording(false);
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
            tts = new TextToSpeech(getContext().getApplicationContext(), status -> {
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
        if (current == this) current = null;
        if (fieldKb != null) fieldKb.release();
        stopListening();
        if (recorder != null) { try { recorder.release(); } catch (Exception ignored) {} recorder = null; }
        if (tts != null) { tts.shutdown(); tts = null; }
    }

    @SuppressWarnings("unused")
    static int clampColor(int c) { return Color.alpha(c) == 0 ? Color.BLACK : c; }
}

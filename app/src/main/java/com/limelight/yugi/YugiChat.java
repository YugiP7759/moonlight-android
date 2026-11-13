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

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

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
        /** The Yugi orb the card grows out of, in window coordinates (null: grow from the card's top corner). */
        default Rect anchorRect() { return null; }
        /** Whether the card sits on the right (the side the floating chrome is docked on). */
        default boolean anchorRight() { return true; }
        /** The chat opened or closed (the chrome folds into the orb while it is open). */
        default void onChatOpen(boolean open) {}
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
    private final TextView subtitle;
    private final YugiAvatar avatar;
    private final LinearLayout attachRow;
    private final HorizontalScrollView attachScroll;
    private final LinearLayout inputBox;
    private final LinearLayout composer;
    private final View hair;
    /** The server's Yugi sessions, shown in place of this conversation (same card). */
    private final YugiSessions sessions;
    private boolean sessionsWanted;
    // session chips under the header: Yugi + every server session, a tap or a swipe switches
    private final HorizontalScrollView chipScroll;
    private final LinearLayout chipRow;
    private org.json.JSONArray chipList;
    private final Runnable chipPoll = this::pollChips;
    private int pollGen;    // bumps on every poll / stop; a stale /sessions answer is dropped
    private boolean released;
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
    private YugiMessages.Row liveRow;
    private String lastRole;         // role of the last message in the list (spacing between speakers)
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean speakNext;
    private SpeechRecognizer recognizer;
    private MediaRecorder recorder;
    private File recordFile;
    private boolean micWasOn;
    private boolean sendMode;
    private boolean recording;
    private final SpringAnimation cardSX, cardSY;
    private float closedScaleX = 0.2f, closedScaleY = 0.1f;

    private static final String READY = "מחובר למחשב שלך";

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    public YugiChat(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
        YugiTheme.keepOnTop(this);
        setLayoutDirection(LAYOUT_DIRECTION_LTR); // the card's side is chosen explicitly; its content is RTL

        card = new ChatCard(ctx);
        card.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        card.setBackground(YugiTheme.roundedStroke(ctx, YugiTheme.GLASS_STRONG, 28, YugiTheme.STROKE));
        card.setElevation(dp(16));
        card.setClipToOutline(true); // the card grows out of the Yugi orb as one rounded shape
        card.setClickable(true); // touches on the card never reach the stream
        card.setPadding(dp(10), dp(8), dp(10), dp(10));
        addView(card);
        // Expanding from the orb: a little softer than the UI springs, with a hint of overshoot
        cardSX = new SpringAnimation(card, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X);
        cardSX.setSpring(new SpringForce(1f).setStiffness(420f).setDampingRatio(0.82f));
        cardSY = new SpringAnimation(card, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y);
        cardSY.setSpring(new SpringForce(1f).setStiffness(420f).setDampingRatio(0.82f));

        // ---- header: avatar with live badge, name + status line, actions ----
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPaddingRelative(dp(6), 0, 0, 0);
        avatar = new YugiAvatar(ctx).set(YugiAvatar.YUGI, "יוגי");
        avatar.setRingColor(0xFF10141B);
        head.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPaddingRelative(dp(12), 0, dp(8), 0);
        TextView title = YugiTheme.text(ctx, "יוגי", 17, 700, YugiTheme.TEXT);
        title.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        titles.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        subtitle = YugiTheme.text(ctx, READY, 13, 400, YugiTheme.TEXT_DIM);
        subtitle.setPadding(0, dp(3), 0, 0);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        titles.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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

        hair = new View(ctx);
        hair.setBackgroundColor(YugiTheme.STROKE);
        hair.setAlpha(0f); // appears once the conversation scrolls under the header
        card.hair = hair;
        card.addView(hair);

        // ---- messages (they run under the floating composer) ----
        scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
                ScrollView sv = (ScrollView) v;
                int max = sv.getChildAt(0).getHeight() + sv.getPaddingTop() + sv.getPaddingBottom() - sv.getHeight();
                stickToBottom = y >= max - dp(24);
                hair.setAlpha(Math.min(1f, y / (float) dp(16)));
            });
        }
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(6), dp(4), dp(6), dp(8));
        scroll.addView(list);
        card.scroll = scroll;
        card.addView(scroll);

        // soft fade where the conversation slides under the composer
        View fade = new View(ctx);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x000B0E14, 0xF20B0E14}));
        card.fade = fade;
        card.addView(fade);

        // ---- composer: attachment preview + a floating rounded input bar ----
        composer = new LinearLayout(ctx);
        composer.setOrientation(LinearLayout.VERTICAL);
        attachRow = new LinearLayout(ctx);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.setPadding(dp(4), 0, dp(4), dp(6));
        attachScroll = new HorizontalScrollView(ctx);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.addView(attachRow);
        attachScroll.setVisibility(GONE);
        composer.addView(attachScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        inputBox = new LinearLayout(ctx);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.BOTTOM);
        inputBox.setBackground(YugiTheme.roundedStroke(ctx, 0xFF1A202B, 28, 0x24FFFFFF));
        inputBox.setElevation(dp(6));
        inputBox.setMinimumHeight(dp(56));
        inputBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageButton attach = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_attach, "צרף קובץ");
        attach.setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.TEXT_DIM));
        attach.setOnClickListener(v -> { reopenOnAttach = true; host.pickFile(); });
        inputBox.addView(attach, sq(48));
        ImageButton shot = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_screenshot, "צלם מסך");
        shot.setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.TEXT_DIM));
        shot.setOnClickListener(v -> takeScreenshot());
        inputBox.addView(shot, sq(48));
        input = new EditText(ctx);
        input.setHint("הודעה ליוגי");
        input.setHintTextColor(YugiTheme.TEXT_FAINT);
        input.setTextColor(YugiTheme.TEXT);
        input.setTypeface(YugiTheme.font(ctx, 400));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        input.setBackground(null);
        input.setMinHeight(dp(48));
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        input.setPadding(dp(8), dp(11), dp(8), dp(11));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(6);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        input.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
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
        micSend = new ImageButton(ctx);
        micSend.setScaleType(ImageView.ScaleType.CENTER);
        micSend.setFocusable(false);
        micSend.setOnClickListener(v -> {
            if (busy && !recording) stop();
            else if (sendMode) sendTyped();
            else onMic();
        });
        inputBox.addView(micSend, sq(48));
        composer.addView(inputBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.input = composer;
        card.addView(composer);
        updateSendButton();

        // ---- sessions panel (on top of everything in the card while shown) ----
        sessions = new YugiSessions(ctx, new YugiSessions.Host() {
            @Override public void onBack() { showSessions(false); }
            @Override public void onClose() { setOpen(false); }
            @Override public void refreshList() { pollChips(); }
            @Override public JSONObject appChat() { return appChatRow(); }
            @Override public void newAppChat() { reset(); }
            @Override public void onHiddenChanged() { renderChips(); }
            @Override public void pickFile() { reopenOnAttach = true; YugiChat.this.host.pickFile(); }
            @Override public void screenshot() { takeScreenshot(); }
            @Override public View attachmentView(Attachment a) { return attachmentChip(a, true, compactAttachments); }
        });
        sessions.setVisibility(GONE);
        card.sessions = sessions;
        card.addView(sessions);
        // alpha springs end: a panel that went to 0 leaves the layout
        YugiSpring.of(sessions, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA).addEndListener((a, c, v, vel) -> {
            if (!c && !sessionsWanted) {
                sessions.setVisibility(GONE);
                sessions.setTranslationY(0);
                card.requestLayout();
            } else {
                card.invalidate();
            }
        });
        YugiSpring.of(card, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA).addEndListener((a, c, v, vel) -> {
            if (!c && !open) setVisibility(GONE);
        });

        // ---- session chips ----
        chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setPadding(dp(2), 0, dp(2), 0);
        chipScroll = new HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        chipScroll.addView(chipRow);
        chipScroll.setVisibility(GONE);
        card.chips = chipScroll;
        card.addView(chipScroll);
        card.swipe = this::swipeChip;
        // the chip swipe stays off on the sessions list, where a row swipe opens its actions
        card.swipeGate = () -> !sessionsWanted || sessions.isInDetail();

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
     * Card layout with fixed priorities: the composer floats at the bottom (the conversation scrolls under it),
     * the chips and the header at the top, the messages get everything in between and behind the composer.
     * With little room (keyboard open on the cover screen) the chips go first, then the header.
     */
    static final class ChatCard extends ViewGroup {
        View header, hair, scroll, fade, input, sessions, chips;
        interface Swipe { void onSwipe(int step); }
        interface SwipeGate { boolean allowed(); }
        Swipe swipe;
        SwipeGate swipeGate;
        boolean chipsWanted;
        private float downX, downY;
        private boolean swiping, swipeIgnored;

        private boolean sessionsShown() { return sessions != null && sessions.getVisibility() != GONE; }
        private int chipsH() { return chips != null && chips.getVisibility() != GONE ? chips.getMeasuredHeight() + dp(8) : 0; }
        private View content() { return sessionsShown() ? sessions : scroll; }

        /** A horizontal swipe on the conversation switches to the next / previous session chip. */
        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            if (swipe == null || chips == null || !chipsWanted) return false;
            if (swipeGate != null && !swipeGate.allowed()) return false;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    swiping = false;
                    // not on the chips, the composer or the attachments
                    swipeIgnored = (chips.getVisibility() != GONE && downY < chips.getBottom()) || downY > input.getTop() - dp(4);
                    return false;
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (swipeIgnored) return false;
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.8f) {
                        swiping = true;
                        return true;
                    }
                    return false;
                }
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (!swiping) return super.onTouchEvent(e);
            float dx = e.getX() - downX;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_MOVE:
                    content().setTranslationX(dx * 0.4f); // the conversation follows the finger, damped
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    swiping = false;
                    YugiSpring.to(content(), androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, 0f);
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && Math.abs(dx) > dp(64)) {
                        // RTL: the next chip is to the left, so a drag to the right brings it in
                        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                        swipe.onSwipe((dx > 0) == rtl ? 1 : -1);
                    }
                    return true;
                default:
                    return true;
            }
        }

        ChatCard(Context c) { super(c); }

        private int dp(float v) { return YugiTheme.dp(getContext(), v); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
            setMeasuredDimension(w, h);
            int innerW = w - getPaddingLeft() - getPaddingRight();
            int avail = h - getPaddingTop() - getPaddingBottom();
            int exactW = MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY);
            // chips only with room to spare (they go first when the keyboard leaves little, before the header)
            int roomAfterInput = avail - dp(60 + 4);
            boolean chipsOn = chips != null && chipsWanted && roomAfterInput >= dp(44 + 120);
            if (chips != null) chips.setVisibility(chipsOn ? VISIBLE : GONE);
            if (chipsOn) {
                chips.measure(exactW, MeasureSpec.makeMeasureSpec(dp(40), MeasureSpec.EXACTLY));
                avail -= chipsH();
            }
            int atMost = MeasureSpec.makeMeasureSpec(Math.max(0, avail), MeasureSpec.AT_MOST);
            input.measure(exactW, atMost);
            int composerH = input.getMeasuredHeight();
            int afterComposer = avail - composerH;
            boolean showHeader = afterComposer >= dp(56 + 72); // keep at least a few lines of messages
            header.setVisibility(showHeader ? VISIBLE : GONE);
            hair.setVisibility(showHeader ? VISIBLE : GONE);
            if (showHeader) {
                header.measure(exactW, MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
                hair.measure(exactW, MeasureSpec.makeMeasureSpec(Math.max(1, dp(1)), MeasureSpec.EXACTLY));
                avail -= header.getMeasuredHeight() + hair.getMeasuredHeight();
            }
            // the conversation runs to the card's bottom, its last message rests just above the composer
            int padBottom = composerH + dp(14);
            if (scroll.getPaddingBottom() != padBottom) scroll.setPadding(0, dp(6), 0, padBottom);
            scroll.measure(exactW, MeasureSpec.makeMeasureSpec(Math.max(0, avail), MeasureSpec.EXACTLY));
            fade.measure(exactW, MeasureSpec.makeMeasureSpec(dp(28), MeasureSpec.EXACTLY));
            if (sessionsShown()) {
                sessions.measure(exactW, MeasureSpec.makeMeasureSpec(Math.max(0, h - getPaddingTop() - getPaddingBottom() - chipsH()), MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) {
            // While the sessions panel is fully in, the chat underneath isn't drawn (or seen through it)
            if (child != sessions && child != chips && sessionsShown() && sessions.getAlpha() >= 1f) return false;
            return super.drawChild(canvas, child, drawingTime);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int left = getPaddingLeft(), right = (r - l) - getPaddingRight();
            int y = getPaddingTop();
            if (chips != null && chips.getVisibility() != GONE) {
                chips.layout(left, y, right, y + chips.getMeasuredHeight());
                y += chipsH();
            }
            int contentTop = y;
            if (header.getVisibility() != GONE) {
                header.layout(left, y, right, y + header.getMeasuredHeight());
                y += header.getMeasuredHeight();
                hair.layout(left - getPaddingLeft(), y, right + getPaddingRight(), y + hair.getMeasuredHeight());
                y += hair.getMeasuredHeight();
            }
            scroll.layout(left, y, right, y + scroll.getMeasuredHeight());
            int bottom = (b - t) - getPaddingBottom();
            input.layout(left, bottom - input.getMeasuredHeight(), right, bottom);
            int ft = bottom - input.getMeasuredHeight() - fade.getMeasuredHeight() + dp(10);
            fade.layout(left, ft, right, ft + fade.getMeasuredHeight());
            if (sessionsShown()) {
                sessions.layout(left, contentTop, right, contentTop + sessions.getMeasuredHeight());
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
            scroll.scrollTo(0, Math.max(0, list.getMeasuredHeight() + scroll.getPaddingTop() + scroll.getPaddingBottom() - scroll.getHeight()));
        }
    }

    private boolean stickToBottom = true;

    /**
     * Where the card goes: on the chrome's side, above the keyboard. With an orb to grow from, the chrome folds
     * away while the chat is open, so the card takes the full height; otherwise it stays below the toolbar.
     */
    Rect cardRect(int w, int h) {
        int m = dp(12);
        boolean folds = host != null && host.anchorRect() != null;
        int top = folds || tight() ? m : Math.max(m, host != null ? host.reservedTop() : m);
        int bottom = h - Math.max(m, Math.max(Math.max(imeInset, kbInset), navInset) + m);
        int width = w >= dp(600) ? Math.min(dp(460), (int) (w * 0.5f)) : w - 2 * m;
        boolean right = host == null || host.anchorRight();
        int left = right ? w - m - width : m;
        return new Rect(left, top, left + width, bottom);
    }

    /** Input field position (screen coordinates within this view), for checks. */
    public Rect inputRect() {
        int x = card.getLeft() + composer.getLeft() + inputBox.getLeft(), y = card.getTop() + composer.getTop() + inputBox.getTop();
        return new Rect(x, y, x + inputBox.getWidth(), y + inputBox.getHeight());
    }

    public Rect cardRect() {
        return new Rect(card.getLeft(), card.getTop(), card.getRight(), card.getBottom());
    }

    // ---------------------------------------------------------------- open / close

    public boolean isOpen() { return open; }

    /** Swaps the card between this conversation and the sessions list (crossfade with a small rise). */
    public void showSessions(boolean show) {
        if (show == sessionsWanted) return;
        sessionsWanted = show;
        if (show) {
            hideKeyboard();
            if (sessions.getVisibility() != VISIBLE) {
                sessions.setVisibility(VISIBLE);
                sessions.setAlpha(0f);
                sessions.setTranslationY(dp(12));
            }
            YugiSpring.to(sessions, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 1f);
            YugiSpring.to(sessions, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f);
            sessions.setShown(open);
        } else {
            sessions.setShown(false);
            if (sessions.getAlpha() >= 1f) sessions.setAlpha(0.99f); // draw the chat underneath while fading out
            YugiSpring.to(sessions, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 0f);
            YugiSpring.to(sessions, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, dp(8));
        }
        renderChips();
        card.requestLayout();
    }

    public boolean isSessionsShown() { return sessionsWanted; }

    // ---------------------------------------------------------------- session chips

    /**
     * The one /sessions poller: feeds the chips and the sessions list, and runs only while the chat is open
     * on a screen that is showing (every 3 s while the list is up, 4 s for the chips alone, slower offline).
     */
    private void pollChips() {
        removeCallbacks(chipPoll);
        final int gen = ++pollGen;
        if (!pollWanted()) return;
        YugiSessions.fetchList(list -> {
            if (gen != pollGen) return; // superseded, closed or released meanwhile
            if (list != null) { chipList = list; renderChips(); }
            sessions.onListResult(list);
            if (!pollWanted()) return;
            long ms = sessions.wantsList() ? YugiSessions.LIST_POLL_MS : 4000;
            postDelayed(chipPoll, list == null ? ms * 2 : ms);
        });
    }

    private boolean pollWanted() {
        return open && !released && !YugiSessions.offline && getWindowVisibility() == VISIBLE;
    }

    /** Screen went to the background / came back: stop or resume the poll and the live session stream. */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (released || !open) return;
        if (visibility == VISIBLE) {
            if (sessionsWanted) sessions.setShown(true);
            pollChips();
        } else {
            pollGen++;
            removeCallbacks(chipPoll);
            sessions.setShown(false);
        }
    }

    /** The chips' sessions: the server's list without the ones Yagel hid (a hidden one comes back when it works). */
    private JSONArray visibleSessions() {
        return chipList == null ? new JSONArray() : YugiSessions.Hidden.filter(getContext(), chipList);
    }

    /** Whether the app chat ("יוגי") shows as a chip / row: hidden only while idle and not on screen. */
    private boolean appChatVisible() {
        return !YugiSessions.Hidden.isHidden(getContext(), YugiSessions.APP_CHAT_NAME) || busy || !sessionsWanted;
    }

    /** Chip order: Yugi first, then the server's sessions. Null id = Yugi's own conversation. */
    private java.util.List<String> chipIds() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (appChatVisible()) ids.add(null);
        JSONArray l = visibleSessions();
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o != null) ids.add(o.optString("id"));
        }
        if (!ids.contains(activeChip())) ids.add(0, activeChip());
        return ids;
    }

    private String activeChip() { return sessionsWanted ? sessions.currentId() : null; }

    private boolean chipActive(String id) {
        return id == null ? !sessionsWanted : sessionsWanted && id.equals(sessions.currentId());
    }

    /** The app chat as a sessions-list row (it isn't in /sessions: it runs inside the bridge). */
    JSONObject appChatRow() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", YugiSessions.APP_CHAT_ID);
            o.put("name", YugiSessions.APP_CHAT_NAME);
            o.put("area", YugiAvatar.YUGI);
            o.put("areaLabel", "הצ'אט באפליקציה");
            o.put("state", busy ? "working" : "waiting");
            o.put("canSend", true);
            o.put("canEnd", false);
            o.put("app", true);
            synchronized (messages) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    Msg m = messages.get(i);
                    if ("system".equals(m.role) || m.text.length() == 0) continue;
                    o.put("lastMessage", new JSONObject().put("role", m.role).put("text", m.text.toString()));
                    break;
                }
            }
            o.put("updatedAt", lastActivity);
        } catch (Exception ignored) {}
        return o;
    }

    private static long lastActivity;

    private void renderChips() {
        JSONArray l = visibleSessions();
        boolean has = l.length() > 0;
        if (card.chipsWanted != has) {
            card.chipsWanted = has;
            if (has) {
                chipScroll.setAlpha(0f);
                YugiSpring.to(chipScroll, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 1f);
            }
            card.requestLayout();
        }
        chipRow.removeAllViews();
        if (!has) return;
        if (appChatVisible()) addChip(null, YugiSessions.APP_CHAT_NAME, YugiAvatar.YUGI, busy ? "working" : "waiting");
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null) continue;
            addChip(o.optString("id"), o.optString("name"), o.optString("area"), o.optString("state", "waiting"));
        }
    }

    /** A chip: mini avatar (with the live dot while it works) and the name; the current one is filled. */
    private void addChip(String id, String name, String area, String state) {
        Context c = getContext();
        boolean on = chipActive(id);
        LinearLayout chip = new LinearLayout(c);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPaddingRelative(dp(5), 0, dp(14), 0);
        chip.setBackground(on ? YugiTheme.roundedStroke(c, YugiTheme.SURFACE_HI, 20, 0x59FFFFFF)
                : YugiTheme.rounded(c, 0x12FFFFFF, 20));
        YugiAvatar av = new YugiAvatar(c).set(area, name);
        av.setRingColor(on ? YugiTheme.SURFACE_HI : 0xFF161A22);
        int sc = YugiSessions.stateColor(state);
        av.setState("waiting".equals(state) ? 0 : sc, "working".equals(state));
        chip.addView(av, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView t = YugiTheme.text(c, name, 14, on ? 600 : 500, on ? YugiTheme.TEXT : YugiTheme.TEXT_DIM);
        t.setMaxLines(1);
        t.setPaddingRelative(dp(8), 0, 0, 0);
        t.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        chip.addView(t);
        chip.setContentDescription(name);
        chip.setSelected(on);
        chip.setOnClickListener(v -> {
            java.util.List<String> ids = chipIds();
            int from = ids.indexOf(activeChip()), to = ids.indexOf(id);
            switchChip(id, to >= from ? 1 : -1);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMarginEnd(dp(8));
        chipRow.addView(chip, lp);
        if (on) chipRow.post(() -> chipScroll.smoothScrollTo(
                Math.max(0, chip.getLeft() - (chipScroll.getWidth() - chip.getWidth()) / 2), 0));
    }

    /** Tests: chips for these sessions, and a swipe as the card would report it. */
    void setChipsForTest(org.json.JSONArray l) { chipList = l; renderChips(); }
    void swipeForTest(int step) { swipeChip(step); }
    Rect chipsRect() { return new Rect(card.getLeft() + chipScroll.getLeft(), card.getTop() + chipScroll.getTop(),
            card.getLeft() + chipScroll.getRight(), card.getTop() + chipScroll.getBottom()); }

    private void swipeChip(int step) {
        java.util.List<String> ids = chipIds();
        int i = ids.indexOf(activeChip());
        int j = i + step;
        if (i < 0 || j < 0 || j >= ids.size()) return;
        switchChip(ids.get(j), step);
    }

    /** Switches the card to Yugi (null) or to a server session, with a slide in the swipe direction. */
    private void switchChip(String id, int step) {
        if (chipActive(id)) return;
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        boolean rtl = card.getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float from = (rtl ? -step : step) * card.getWidth() * 0.3f;
        if (id == null) {
            showSessions(false);
            slideIn(scroll, from);
        } else {
            JSONObject s = null;
            if (chipList != null) for (int i = 0; i < chipList.length(); i++) {
                JSONObject o = chipList.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) { s = o; break; }
            }
            if (s == null) return;
            boolean was = sessionsWanted;
            showSessions(true);
            sessions.openDetail(s);
            if (was) slideIn(sessions, from);
        }
        renderChips();
    }

    private static void slideIn(View v, float from) {
        v.setTranslationX(from);
        YugiSpring.to(v, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, 0f);
    }

    /** For previews/tests: the sessions panel. */
    YugiSessions sessionsForTest() { return sessions; }

    public void toggle() { setOpen(!open); }

    public void setOpen(boolean o) {
        if (o == open && (o ? getVisibility() == VISIBLE : true)) return;
        open = o;
        if (host != null) host.onChatOpen(o);
        if (o) {
            boolean appearing = getVisibility() != VISIBLE || card.getAlpha() < 0.05f;
            setVisibility(VISIBLE);
            aimAtOrb(appearing);
            YugiSpring.to(card, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 1f);
            cardSX.animateToFinalPosition(1f);
            cardSY.animateToFinalPosition(1f);
            pollChips();
            if (!historyLoaded) loadHistory();
            if (sessionsWanted) sessions.setShown(true);
            if (host != null && tight()) host.onKeyboard(true); // the toolbar makes room
            if (compactAttachments != tight()) { compactAttachments = tight(); renderPending(); }
            scrollToEnd();
            requestApplyInsets();
            // typing goes to the chat at once: the field takes the focus and its keyboard comes up
            if (!sessionsWanted) post(this::focusInput);
        } else {
            hideKeyboard();
            sessions.setShown(false);
            if (host != null) host.onKeyboard(false);
            stopListening();
            if (tts != null) tts.stop();
            removeCallbacks(chipPoll);
            pollGen++;
            aimAtOrb(false);
            // folds back into the orb: shrinks toward it while fading (alpha a little quicker than the scale)
            YugiSpring.to(card, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 0f);
            cardSX.animateToFinalPosition(closedScaleX);
            cardSY.animateToFinalPosition(closedScaleY);
        }
    }

    /**
     * Points the card's pivot at the Yugi orb (or its top outer corner without one), so the card grows out of
     * the orb and folds back into it. {@code reset}: start from the orb's size, invisible.
     */
    private void aimAtOrb(boolean reset) {
        View parent = (View) getParent();
        int w = getWidth() > 0 ? getWidth() : parent != null ? parent.getWidth() : 0;
        int h = getHeight() > 0 ? getHeight() : parent != null ? parent.getHeight() : 0;
        if (w <= 0 || h <= 0) return;
        Rect cr = cardRect(w, h);
        if (cr.width() <= 0 || cr.height() <= 0) return;
        Rect a = host != null ? host.anchorRect() : null;
        float px, py;
        if (a != null) {
            int[] me = new int[2];
            if (parent != null) parent.getLocationInWindow(me);
            px = a.centerX() - me[0] - cr.left;
            py = a.centerY() - me[1] - cr.top;
            closedScaleX = Math.max(0.12f, a.width() / (float) cr.width());
            closedScaleY = Math.max(0.06f, a.height() / (float) cr.height());
        } else {
            boolean right = host == null || host.anchorRight();
            px = right ? cr.width() : 0;
            py = 0;
            closedScaleX = 0.85f;
            closedScaleY = 0.85f;
        }
        card.setPivotX(Math.max(0, Math.min(cr.width(), px)));
        card.setPivotY(Math.max(0, Math.min(cr.height(), py)));
        if (reset) {
            cardSX.cancel();
            cardSY.cancel();
            card.setAlpha(0f);
            card.setScaleX(closedScaleX);
            card.setScaleY(closedScaleY);
        }
    }

    /** Ends the open / close springs at once (screenshots). */
    void settleForTest() {
        if (cardSX.isRunning()) cardSX.skipToEnd();
        if (cardSY.isRunning()) cardSY.skipToEnd();
        androidx.dynamicanimation.animation.SpringAnimation al = YugiSpring.of(card, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA);
        if (al.isRunning()) al.skipToEnd();
        card.setScaleX(open ? 1f : closedScaleX);
        card.setScaleY(open ? 1f : closedScaleY);
        card.setAlpha(open ? 1f : 0f);
        for (int i = 0; i < list.getChildCount(); i++) YugiMessages.settle(list.getChildAt(i));
        sessions.settleForTest();
    }

    /** For checks / a frame mid-animation: the card's current scale. */
    float cardScaleForTest() { return card.getScaleX(); }

    private void focusInput() {
        if (!open || sessionsWanted) return;
        input.requestFocus();
        if (fieldKb != null) fieldKb.show(input);
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
        liveRow = null;
        lastRole = null;
        synchronized (messages) {
            if (messages.isEmpty()) list.addView(emptyState(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (Msg m : messages) addRow(m, false);
        }
        refreshBusy();
        scrollToEnd();
    }

    private static final String[] SUGGESTIONS = {"מה יש לי היום?", "מה המצב בכל הסשנים?", "צלם את המסך ותסביר מה רואים"};

    /** Before the first message: the orb, one line of welcome and a few one-tap starters. */
    private View emptyState() {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setTag("empty");
        col.setPadding(dp(12), dp(36), dp(12), dp(16));
        YugiAvatar orb = new YugiAvatar(c).set(YugiAvatar.YUGI, "יוגי");
        col.addView(orb, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView t = YugiTheme.text(c, "במה אפשר לעזור?", 22, 700, YugiTheme.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(18), 0, 0);
        col.addView(t);
        TextView sub = YugiTheme.text(c, "אני מחובר למחשב שלך, לקבצים ולכל הסשנים", 15, 400, YugiTheme.TEXT_DIM);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(0, 1.25f);
        sub.setPadding(0, dp(8), 0, dp(22));
        col.addView(sub);
        for (String s : SUGGESTIONS) {
            TextView p = YugiTheme.pill(c, s, 0x14FFFFFF, YugiTheme.TEXT);
            p.setTypeface(YugiTheme.font(c, 500));
            p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            p.setOnClickListener(v -> { input.setText(s); input.setSelection(s.length()); focusInput(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            col.addView(p, lp);
        }
        return col;
    }

    /** Appends one message; {@code animate}: it springs in (new messages, never the loaded history). */
    private YugiMessages.Row addRow(Msg m, boolean animate) {
        View empty = list.findViewWithTag("empty");
        if (empty != null) list.removeView(empty);
        List<View> atts = new ArrayList<>();
        if ("user".equals(m.role)) for (Attachment a : m.attachments) atts.add(attachmentChip(a, false));
        YugiMessages.Row r = YugiMessages.build(getContext(), m.role, m.text.toString(), atts);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = YugiMessages.gapBefore(getContext(), lastRole, m.role);
        lastRole = m.role;
        list.addView(r.view, lp);
        if (animate) YugiMessages.enter(r.view);
        return r;
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
            int w = compact ? 32 : removable ? 56 : 200, h = compact ? 32 : removable ? 56 : 132;
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
        if (sessions != null) sessions.renderPending(); // a session's composer shows the same attachments
    }

    /** The shared attachments waiting to be sent (the chat or the open session sends them). */
    static List<Attachment> pendingAttachments() { return pending; }

    static void clearPending() {
        pending.clear();
        if (current != null) current.renderPending();
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
        // above the floating composer (the list's bottom padding is the composer's room)
        int top = scroll.getScrollY(), bottom = top + scroll.getHeight() - scroll.getPaddingBottom() + scroll.getPaddingTop(), n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.getHeight() > 0 && v.getTop() >= top && v.getBottom() <= bottom) n++;
        }
        return n;
    }

    /** The round button at the composer's end: send (brand) with text, stop while Yugi answers, else the mic. */
    private void updateSendButton() {
        if (recording) return;
        boolean canSend = input.getText().toString().trim().length() > 0 || !pending.isEmpty();
        sendMode = canSend;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        if (busy && !canSend) {
            micSend.setImageResource(R.drawable.yugi_ic_stop);
            micSend.setContentDescription("עצור");
            micSend.setRotation(0);
            g.setColor(YugiTheme.TEXT);
            micSend.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF0B0E14));
        } else if (canSend) {
            micSend.setImageResource(R.drawable.yugi_ic_send);
            micSend.setContentDescription("שלח");
            micSend.setRotation(180); // send arrow points left in RTL
            g.setColor(YugiTheme.BRAND);
            micSend.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        } else {
            micSend.setImageResource(R.drawable.yugi_ic_mic);
            micSend.setContentDescription("הקלטה");
            micSend.setRotation(0);
            g.setColor(0x00000000);
            micSend.setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.TEXT_DIM));
        }
        micSend.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(YugiTheme.RIPPLE), g, mask));
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0,
                list.getHeight() + scroll.getPaddingTop() + scroll.getPaddingBottom() - scroll.getHeight())));
    }

    /** Header status and the avatar's live badge follow the answer in progress. */
    private void refreshBusy() {
        avatar.setState(busy ? YugiTheme.GREEN : 0, busy);
        if (!busy) setStatus("");
        updateSendButton();
    }

    private void setStatus(String s) {
        boolean live = s != null && !s.isEmpty();
        subtitle.setText(live ? s : READY);
        subtitle.setTextColor(busy && live ? YugiTheme.GREEN : YugiTheme.TEXT_DIM);
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

    void rerenderForTest() { renderAll(); renderPending(); }

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
            if (messages.isEmpty()) { list.removeAllViews(); lastRole = null; }
            messages.add(user);
            messages.add(answer);
        }
        lastActivity = System.currentTimeMillis();
        stickToBottom = true;
        addRow(user, true);
        liveRow = addRow(answer, true); // the thinking dots until the first words arrive
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
                            ui.post(() -> { if (liveRow != null) { liveRow.setText(answer.text.toString()); if (stickToBottom) scrollToEnd(); } });
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
            if (liveRow != null) liveRow.setText(answer.text.toString());
            liveRow = null;
            lastActivity = System.currentTimeMillis();
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
        recording = rec;
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
        if (open) setOpen(false);
        released = true;
        pollGen++;
        removeCallbacks(chipPoll);
        sessions.setShown(false);
        sessions.release(); // streamGen++ and cancel: the session's SSE reader stops too
        if (current == this) current = null;
        if (fieldKb != null) fieldKb.release();
        stopListening();
        if (recorder != null) { try { recorder.release(); } catch (Exception ignored) {} recorder = null; }
        if (tts != null) { tts.shutdown(); tts = null; }
    }

    @SuppressWarnings("unused")
    static int clampColor(int c) { return Color.alpha(c) == 0 ? Color.BLACK : c; }
}

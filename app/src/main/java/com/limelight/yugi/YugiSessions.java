package com.limelight.yugi;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * "סשנים": every Yugi (Claude Code) session running on the server, live, inside the Yugi chat card.
 * Page 1 is an inset-grouped list (state dot, area, last message, running agents), refreshed every few
 * seconds. Tapping a card pushes page 2: that session's conversation streamed over SSE from
 * yugi-chat-bridge (/sessions/<id>/stream), with an input that types into the session on the server.
 * Pages move on interruptible springs, like a navigation stack on iOS (RTL: the new page comes from the left).
 */
public class YugiSessions extends FrameLayout {
    public interface Host {
        /** Leave the sessions panel (back to Yugi's own chat). */
        void onBack();
        /** Close the whole chat card. */
        void onClose();
        /** Fetch /sessions now; the chat's single poller hands the result back through {@link #onListResult}. */
        default void refreshList() {}
        /** The app's own chat as a row (it isn't in /sessions), or null. */
        default JSONObject appChat() { return null; }
        /** Start a new conversation in the app's chat. */
        default void newAppChat() {}
        /** A session was hidden or came back (the chips follow). */
        default void onHiddenChanged() {}
        /** Open the file picker for an attachment (it lands in the shared pending list). */
        default void pickFile() {}
        /** Capture the desktop / game into the shared pending list. */
        default void screenshot() {}
        /** A view for a pending attachment (with its remove button). */
        default View attachmentView(YugiChat.Attachment a) { return null; }
    }

    static final int GREEN = YugiTheme.GREEN;
    static final int ORANGE = YugiTheme.ORANGE;
    static final String APP_CHAT_ID = "app";
    static final String APP_CHAT_NAME = "יוגי";

    /**
     * Sessions Yagel hid from the list, by name (the area sessions get a new pid whenever their service restarts).
     * A hidden session comes back on its own once it works again.
     */
    static final class Hidden {
        private static final String PREFS = "yugi_sessions";
        private Hidden() {}

        static java.util.Set<String> all(Context c) {
            return new java.util.HashSet<>(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet("hidden", java.util.Collections.emptySet()));
        }

        static boolean isHidden(Context c, String name) { return all(c).contains(name); }

        static void set(Context c, String name, boolean hidden) {
            java.util.Set<String> h = all(c);
            if (hidden ? h.add(name) : h.remove(name)) {
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("hidden", h).apply();
            }
        }

        static void clear(Context c) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("hidden").apply();
        }

        /** The list without hidden sessions; a hidden one that works again is un-hidden for good. */
        static JSONArray filter(Context c, JSONArray list) {
            java.util.Set<String> h = all(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name");
                if (h.contains(name)) {
                    if (!"working".equals(o.optString("state"))) continue;
                    set(c, name, false);
                }
                out.put(o);
            }
            return out;
        }

        /** How many of {@code list} are hidden right now. */
        static int count(Context c, JSONArray list) {
            java.util.Set<String> h = all(c);
            int n = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null && h.contains(o.optString("name")) && !"working".equals(o.optString("state"))) n++;
            }
            return n;
        }
    }
    static final long LIST_POLL_MS = 3000;

    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    // The bridge pings every 15 s: a silent minute means the stream died, so reconnect.
    private static final OkHttpClient streamHttp = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build();
    /** Tests render without a server. */
    static boolean offline;

    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final FrameLayout listPage, detailPage;
    private final LinearLayout cards;
    private final TextView listStatus;
    private final TextView hiddenFooter;
    private final LinearLayout msgList;
    private final ScrollView msgScroll;
    private final TextView detailTitle, detailSubtitle;
    private final YugiAvatar detailAvatar;
    private final LinearLayout inputBox;
    private final LinearLayout attachRow;
    private final YugiSlashMenu slash;
    private final YugiSheet sheet;
    private final android.widget.HorizontalScrollView attachScroll;
    private final EditText input;
    private final ImageButton send;
    private final TextView readOnlyNote;
    private final View detailHead, detailHair;
    private final SpringAnimation listX, detailX;

    private boolean shown;
    private boolean inDetail;
    private JSONObject currentSession;
    private String currentId;
    private int streamGen; // bumps on every (re)connect / leave; stale readers stop
    private volatile Call streamCall;
    private final Set<String> seenIds = new HashSet<>();
    private final List<Pending> pendingBubbles = new ArrayList<>();
    private String lastRole;
    private final List<YugiSwipeRow> rows = new ArrayList<>();
    private JSONArray lastList;
    private boolean stickToBottom = true;

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    public YugiSessions(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setLayoutDirection(LAYOUT_DIRECTION_RTL);
        setClickable(true);
        setClipChildren(true);

        // ---------------- list page: a large title over one grouped list (Mail / Messages on iPad)
        listPage = new FrameLayout(ctx);
        LinearLayout lcol = column(ctx);
        LinearLayout lhead = header(ctx);
        ImageButton back = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_back, "חזרה ליוגי");
        back.setOnClickListener(v -> host.onBack());
        lhead.addView(back, sq(48));
        lhead.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1));
        ImageButton close = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_close, "סגור");
        close.setOnClickListener(v -> host.onClose());
        lhead.addView(close, sq(48));
        lcol.addView(lhead, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        ScrollView lscroll = new ScrollView(ctx);
        lscroll.setVerticalScrollBarEnabled(false);
        lscroll.setFillViewport(true);
        lscroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout lbody = column(ctx);
        lbody.setPadding(dp(4), 0, dp(4), dp(16));
        TextView big = YugiTheme.text(ctx, "סשנים", 30, 700, YugiTheme.TEXT);
        big.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        big.setPaddingRelative(dp(6), dp(2), 0, 0);
        lbody.addView(big, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listStatus = YugiTheme.text(ctx, "טוען…", 14, 400, YugiTheme.TEXT_DIM);
        listStatus.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        listStatus.setPaddingRelative(dp(6), dp(4), 0, dp(14));
        lbody.addView(listStatus, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cards = new LinearLayout(ctx);
        cards.setOrientation(LinearLayout.VERTICAL);
        cards.setBackground(YugiTheme.rounded(ctx, YugiTheme.CARD, 18));
        cards.setClipToOutline(true);
        lbody.addView(cards, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hiddenFooter = YugiTheme.pill(ctx, "", 0x00000000, YugiTheme.LINK);
        hiddenFooter.setVisibility(GONE);
        hiddenFooter.setOnClickListener(v -> {
            Hidden.clear(getContext());
            host.onHiddenChanged();
            if (lastList != null) renderList(lastList);
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.CENTER_HORIZONTAL;
        flp.topMargin = dp(10);
        lbody.addView(hiddenFooter, flp);
        TextView hint = YugiTheme.text(ctx, "החלקה שמאלה על שורה פותחת פעולות", 12.5f, 400, YugiTheme.TEXT_FAINT);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        lbody.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lscroll.addView(lbody);
        lcol.addView(lscroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        listPage.addView(lcol, match());
        addView(listPage, match());

        // ---------------- detail page
        detailPage = new FrameLayout(ctx);
        detailPage.setClickable(true);
        LinearLayout dcol = column(ctx);
        LinearLayout dhead = header(ctx);
        ImageButton dback = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_back, "חזרה לסשנים");
        dback.setOnClickListener(v -> popDetail());
        dhead.addView(dback, sq(48));
        LinearLayout dtitles = new LinearLayout(ctx);
        dtitles.setOrientation(LinearLayout.VERTICAL);
        dtitles.setPadding(dp(4), 0, dp(8), 0);
        detailAvatar = new YugiAvatar(ctx);
        detailAvatar.setRingColor(0xFF10141B);
        LinearLayout.LayoutParams dalp = new LinearLayout.LayoutParams(dp(38), dp(38));
        dalp.setMarginEnd(dp(12));
        dhead.addView(detailAvatar, dalp);
        detailTitle = YugiTheme.text(ctx, "", 17, 700, YugiTheme.TEXT);
        detailTitle.setMaxLines(1);
        detailTitle.setEllipsize(TextUtils.TruncateAt.END);
        detailTitle.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        dtitles.addView(detailTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout subRow = new LinearLayout(ctx);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, dp(3), 0, 0);
        detailSubtitle = YugiTheme.text(ctx, "", 13, 400, YugiTheme.TEXT_DIM);
        detailSubtitle.setMaxLines(1);
        detailSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        detailSubtitle.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        subRow.addView(detailSubtitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        dtitles.addView(subRow);
        dhead.addView(dtitles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton dclose = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_close, "סגור");
        dclose.setOnClickListener(v -> host.onClose());
        dhead.addView(dclose, sq(48));
        dcol.addView(dhead, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        View hair = new View(ctx);
        hair.setBackgroundColor(YugiTheme.STROKE);
        dcol.addView(hair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        detailHead = dhead;
        detailHair = hair;

        msgScroll = new ScrollView(ctx);
        msgScroll.setVerticalScrollBarEnabled(false);
        msgScroll.setFillViewport(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            msgScroll.setOnScrollChangeListener((v, x, y, ox, oy) ->
                    stickToBottom = y >= ((ScrollView) v).getChildAt(0).getHeight() - v.getHeight() - dp(24));
        }
        msgList = new LinearLayout(ctx);
        msgList.setOrientation(LinearLayout.VERTICAL);
        msgList.setPadding(dp(4), dp(8), dp(4), dp(8));
        msgScroll.addView(msgList);
        msgScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (stickToBottom && (b - t) != (ob - ot)) scrollToEnd();
        });
        dcol.addView(msgScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        readOnlyNote = YugiTheme.text(ctx, "", 13, 400, YugiTheme.TEXT_DIM);
        readOnlyNote.setGravity(Gravity.CENTER);
        readOnlyNote.setPadding(dp(12), dp(14), dp(12), dp(14));
        readOnlyNote.setBackground(YugiTheme.rounded(ctx, YugiTheme.SURFACE, 24));
        readOnlyNote.setVisibility(GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        dcol.addView(readOnlyNote, rlp);

        // "/" suggests the usual Claude Code commands; any command can still be typed and sent as is
        slash = new YugiSlashMenu(ctx, java.util.Arrays.asList(
                new YugiSlashMenu.Command("clear", "ניקוי ההקשר והתחלה מחדש", false),
                new YugiSlashMenu.Command("compact", "סיכום השיחה כדי לפנות מקום", false),
                new YugiSlashMenu.Command("model", "החלפת מודל, למשל /model opus", true),
                new YugiSlashMenu.Command("context", "כמה מההקשר בשימוש", false),
                new YugiSlashMenu.Command("cost", "עלות ושימוש בסשן", false),
                new YugiSlashMenu.Command("status", "מצב הסשן וההגדרות", false),
                new YugiSlashMenu.Command("effort", "רמת המאמץ של המודל", true)),
                cmd -> { String t = "/" + cmd.name + (cmd.takesArg ? " " : ""); YugiSessions.this.input.setText(t); YugiSessions.this.input.setSelection(t.length()); YugiSessions.this.slash.setShown(false); });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(8);
        dcol.addView(slash, slp);

        // attachments waiting for this session (shared with the chat: one picker, one screenshot)
        attachRow = new LinearLayout(ctx);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        attachScroll = new android.widget.HorizontalScrollView(ctx);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.addView(attachRow);
        attachScroll.setVisibility(GONE);
        dcol.addView(attachScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        inputBox = new LinearLayout(ctx);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.BOTTOM);
        inputBox.setBackground(YugiTheme.roundedStroke(ctx, 0xFF1A202B, 28, 0x24FFFFFF));
        inputBox.setElevation(dp(6));
        inputBox.setMinimumHeight(dp(56));
        inputBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageButton attach = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_attach, "צרף קובץ או תמונה");
        attach.setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.TEXT_DIM));
        attach.setOnClickListener(v -> host.pickFile());
        inputBox.addView(attach, sq(48));
        ImageButton shot = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_screenshot, "צלם את המסך");
        shot.setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.TEXT_DIM));
        shot.setOnClickListener(v -> host.screenshot());
        inputBox.addView(shot, sq(48));
        input = new EditText(ctx);
        input.setHint("הודעה לסשן");
        input.setHintTextColor(YugiTheme.TEXT_FAINT);
        input.setTextColor(YugiTheme.TEXT);
        input.setTypeface(YugiTheme.font(ctx, 400));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        input.setBackground(null);
        input.setMinHeight(dp(48));
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        input.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        input.setPadding(dp(8), dp(11), dp(8), dp(11));
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
        inputBox.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        send = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_send, "שלח");
        send.setRotation(180); // points left in RTL
        send.setOnClickListener(v -> sendTyped());
        inputBox.addView(send, sq(48));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSend(); slash.update(s); }
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(4);
        dcol.addView(inputBox, ilp);
        updateSend();
        detailPage.addView(dcol, match());
        sheet = new YugiSheet(ctx);
        detailPage.setVisibility(INVISIBLE);
        addView(detailPage, match());
        addView(sheet, match()); // command output, over both pages

        // Navigation springs: snappy, no bounce, interruptible mid-flight (tap back while pushing)
        listX = spring(listPage);
        detailX = spring(detailPage);
        detailX.addEndListener((a, canceled, v, vel) -> {
            if (canceled) return;
            if (inDetail) { listPage.setVisibility(INVISIBLE); detailPage.setBackground(null); }
            else detailPage.setVisibility(INVISIBLE);
        });

        renderEmpty("טוען…");
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // Little room (keyboard up on the cover screen): drop the detail header so messages stay visible
        boolean roomy = MeasureSpec.getSize(hSpec) >= dp(52 + 1 + 60 + 110);
        int v = roomy ? VISIBLE : GONE;
        if (detailHead.getVisibility() != v) { detailHead.setVisibility(v); detailHair.setVisibility(v); }
        super.onMeasure(wSpec, hSpec);
    }

    // ---------------------------------------------------------------- building blocks

    private LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout header(Context c) {
        LinearLayout h = new LinearLayout(c);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        return h;
    }

    private View dot(Context c, int color) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        return v;
    }

    private static void tintDot(View v, int color) {
        if (v.getBackground() instanceof GradientDrawable) ((GradientDrawable) v.getBackground()).setColor(color);
    }

    private SpringAnimation spring(View v) {
        SpringAnimation s = new SpringAnimation(v, DynamicAnimation.TRANSLATION_X);
        s.setSpring(new SpringForce(0).setStiffness(700f).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        return s;
    }

    private LinearLayout.LayoutParams sq(int sizeDp) { return new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)); }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** Apple-style press: the card shrinks a little under the finger and springs back. */
    private void pressable(View v) {
        SpringAnimation sx = new SpringAnimation(v, DynamicAnimation.SCALE_X);
        sx.setSpring(new SpringForce(1f).setStiffness(1500f).setDampingRatio(0.7f));
        SpringAnimation sy = new SpringAnimation(v, DynamicAnimation.SCALE_Y);
        sy.setSpring(new SpringForce(1f).setStiffness(1500f).setDampingRatio(0.7f));
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { sx.animateToFinalPosition(0.97f); sy.animateToFinalPosition(0.97f); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { sx.animateToFinalPosition(1f); sy.animateToFinalPosition(1f); }
            return false;
        });
    }

    static int stateColor(String state) {
        if ("working".equals(state)) return GREEN;
        if ("needs".equals(state)) return ORANGE;
        return YugiTheme.TEXT_FAINT;
    }

    static String stateLabel(String state) {
        if ("working".equals(state)) return "עובד";
        if ("needs".equals(state)) return "מחכה לך";
        if ("ended".equals(state)) return "הסתיים";
        return "ממתין";
    }

    /** "עכשיו", "לפני 5 דק׳", "לפני 3 שע׳", "לפני 2 ימים". */
    static String ago(long t, long now) {
        if (t <= 0) return "";
        long s = Math.max(0, (now - t) / 1000);
        if (s < 60) return "עכשיו";
        if (s < 3600) return "לפני " + (s / 60) + " דק׳";
        if (s < 86400) return "לפני " + (s / 3600) + " שע׳";
        long d = s / 86400;
        return d == 1 ? "אתמול" : "לפני " + d + " ימים";
    }

    // ---------------------------------------------------------------- show / hide

    /** Called by the chat card when the panel appears or goes away. */
    public void setShown(boolean s) {
        if (s == shown) return;
        shown = s;
        if (s) {
            if (inDetail && currentId != null) connectStream(); else pollList();
        } else {
            streamGen++;
            cancelStream();
            hideKeyboard();
        }
    }

    public boolean isInDetail() { return inDetail; }

    /** Back gesture: sheet -> detail -> list -> (false: let the chat handle it). */
    public boolean handleBack() {
        if (sheet.isShownNow()) { sheet.setShown(false); return true; }
        if (inDetail) { popDetail(); return true; }
        return false;
    }

    // ---------------------------------------------------------------- list

    private static String base() { return "http://" + YugiConfig.host + ":" + YugiChat.PORT; }

    private static Request.Builder req(String path) {
        return new Request.Builder().url(base() + path).header("Authorization", "Bearer " + BuildConfig.YUGI_CHAT_TOKEN);
    }

    public interface ListCallback { void onList(JSONArray sessions); }

    /** One /sessions fetch for the chat's session chips (null on failure; callback on the UI thread). */
    static void fetchList(ListCallback cb) {
        Handler h = new Handler(Looper.getMainLooper());
        http.newCall(req("/sessions").get().build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) { h.post(() -> cb.onList(null)); }
            @Override public void onResponse(Call call, Response r) {
                JSONArray arr = null;
                try (Response res = r) {
                    if (res.isSuccessful() && res.body() != null) arr = new JSONObject(res.body().string()).optJSONArray("sessions");
                } catch (Exception ignored) {}
                final JSONArray list = arr;
                h.post(() -> cb.onList(list));
            }
        });
    }

    /** Id of the session open in the detail page, or null. */
    String currentId() { return inDetail ? currentId : null; }

    /** The list comes from the chat's single /sessions poller (one request feeds the chips and this page). */
    private void pollList() {
        if (!shown || inDetail || offline) return;
        host.refreshList();
    }

    /** A /sessions result from the chat's poller (null: the server didn't answer). */
    void onListResult(JSONArray list) {
        if (!shown || inDetail) return;
        if (list != null) { renderList(list); return; }
        if (cards.getChildCount() <= 1) renderEmpty("אין חיבור לשרת");
        listStatus.setText("אין חיבור לשרת");
    }

    /** Whether the list page is what's showing (the chat polls faster for it). */
    boolean wantsList() { return shown && !inDetail; }

    boolean isShownForTest() { return shown; }

    /** The hosting screen is going away: stop the stream and every pending callback. */
    void release() {
        shown = false;
        streamGen++;
        cancelStream();
        ui.removeCallbacksAndMessages(null);
    }

    private void renderEmpty(String msg) {
        cards.removeAllViews();
        rows.clear();
        TextView t = YugiTheme.text(getContext(), msg, 15, 400, YugiTheme.TEXT_DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(32), 0, dp(32));
        cards.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    void renderList(JSONArray list) {
        lastList = list;
        long now = System.currentTimeMillis();
        int working = 0;
        // keep an open row / an open question across the 3 s refresh (don't yank it from under the finger)
        for (YugiSwipeRow r : rows) if (r.isOpen() || r.isConfirming()) { refreshStatus(list); return; }
        cards.removeAllViews();
        rows.clear();
        JSONArray shown = Hidden.filter(getContext(), list);
        JSONObject app = host.appChat();
        List<JSONObject> all = new ArrayList<>();
        if (app != null && (!Hidden.isHidden(getContext(), APP_CHAT_NAME) || "working".equals(app.optString("state")))) {
            if (Hidden.isHidden(getContext(), APP_CHAT_NAME)) Hidden.set(getContext(), APP_CHAT_NAME, false);
            all.add(app);
        }
        for (int i = 0; i < shown.length(); i++) if (shown.optJSONObject(i) != null) all.add(shown.optJSONObject(i));
        for (JSONObject s : all) if ("working".equals(s.optString("state"))) working++;
        if (all.isEmpty()) renderEmpty("אין סשנים להצגה");
        for (int i = 0; i < all.size(); i++) {
            YugiSwipeRow row = row(all.get(i), now, i < all.size() - 1);
            rows.add(row);
            cards.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        refreshStatus(list);
    }

    private void refreshStatus(JSONArray list) {
        int working = 0, total = 0;
        JSONArray shown = Hidden.filter(getContext(), list);
        for (int i = 0; i < shown.length(); i++) {
            JSONObject o = shown.optJSONObject(i);
            if (o == null) continue;
            total++;
            if ("working".equals(o.optString("state"))) working++;
        }
        listStatus.setText(total == 0 ? "אין סשנים פעילים בשרת"
                : (total == 1 ? "סשן אחד בשרת" : total + " סשנים בשרת") + (working > 0 ? " · " + working + " עובדים עכשיו" : ""));
        int hidden = Hidden.count(getContext(), list) + (Hidden.isHidden(getContext(), APP_CHAT_NAME) ? 1 : 0);
        hiddenFooter.setVisibility(hidden > 0 ? VISIBLE : GONE);
        hiddenFooter.setText(hidden == 1 ? "סשן אחד מוסתר · הצג" : hidden + " סשנים מוסתרים · הצג");
    }

    /**
     * One row, Mail-style: area avatar with a live badge, name and time on the first line, the state (and
     * agents) on the second, the last message on the third. Swiping left reveals its actions.
     */
    private YugiSwipeRow row(JSONObject s, long now, boolean separator) {
        Context c = getContext();
        String state = s.optString("state", "waiting");
        boolean app = s.optBoolean("app");
        String name = s.optString("name");
        LinearLayout face = new LinearLayout(c);
        face.setOrientation(LinearLayout.HORIZONTAL);
        face.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        face.setGravity(Gravity.TOP);
        face.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(YugiTheme.RIPPLE),
                new android.graphics.drawable.ColorDrawable(YugiTheme.CARD), new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        face.setPaddingRelative(dp(14), dp(12), dp(14), dp(12));
        face.setMinimumHeight(dp(76));
        face.setClickable(true);
        face.setTag("card");
        face.setContentDescription(name + ", " + stateLabel(state));

        YugiAvatar av = new YugiAvatar(c).set(app ? YugiAvatar.YUGI : s.optString("area"), name);
        av.setRingColor(YugiTheme.CARD);
        av.setState("waiting".equals(state) ? 0 : stateColor(state), "working".equals(state));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(46), dp(46));
        alp.setMarginEnd(dp(14));
        face.addView(av, alp);

        LinearLayout texts = column(c);
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameT = YugiTheme.text(c, name, 16, 600, YugiTheme.TEXT);
        nameT.setMaxLines(1);
        nameT.setEllipsize(TextUtils.TruncateAt.END);
        nameT.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        nameT.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        top.addView(nameT, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView when = YugiTheme.text(c, ago(s.optLong("updatedAt", 0), now), 13, 400, YugiTheme.TEXT_FAINT);
        when.setMaxLines(1);
        when.setPaddingRelative(dp(8), 0, 0, 0);
        top.addView(when);
        texts.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        StringBuilder st = new StringBuilder(stateLabel(state));
        String area = s.optString("areaLabel", "");
        if (!area.isEmpty()) st.append(" · ").append(area);
        JSONArray agents = s.optJSONArray("agents");
        if (agents != null && agents.length() > 0) st.append(" · ").append(agents.length() == 1 ? "סוכן אחד עובד" : agents.length() + " סוכנים עובדים");
        if (!s.optBoolean("canSend", true)) st.append(" · צפייה בלבד");
        TextView stateT = YugiTheme.text(c, st.toString(), 13, 500,
                "working".equals(state) ? GREEN : "needs".equals(state) ? ORANGE : YugiTheme.TEXT_DIM);
        stateT.setMaxLines(1);
        stateT.setEllipsize(TextUtils.TruncateAt.END);
        stateT.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        stateT.setPadding(0, dp(3), 0, 0);
        texts.addView(stateT, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        JSONObject last = s.optJSONObject("lastMessage");
        if (last != null) {
            String who = "user".equals(last.optString("role")) ? "אתה: " : "";
            TextView lm = YugiTheme.text(c, who + YugiMarkdown.plain(last.optString("text")), 14, 400, YugiTheme.TEXT_DIM);
            lm.setMaxLines(2);
            lm.setEllipsize(TextUtils.TruncateAt.END);
            lm.setLineSpacing(0, 1.2f);
            lm.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
            lm.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
            lm.setPadding(0, dp(5), 0, 0);
            texts.addView(lm, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        face.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // hairline separator, inset past the avatar like iOS lists
        FrameLayout holder = new FrameLayout(c);
        holder.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        holder.addView(face, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (separator) {
            View sep = new View(c);
            sep.setBackgroundColor(0x1AFFFFFF);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)), Gravity.BOTTOM);
            slp.setMarginStart(dp(14 + 46 + 14));
            holder.addView(sep, slp);
        }

        List<YugiSwipeRow.Action> acts = new ArrayList<>();
        final YugiSwipeRow[] self = new YugiSwipeRow[1];
        if (app) {
            acts.add(new YugiSwipeRow.Action("שיחה חדשה", YugiTheme.BRAND, null, null, () -> host.newAppChat()));
            acts.add(new YugiSwipeRow.Action("הסתר", YugiTheme.GRAY, null, null, () -> hide(self[0], name)));
        } else if (s.optBoolean("canEnd")) {
            acts.add(new YugiSwipeRow.Action("הסתר", YugiTheme.GRAY, null, null, () -> hide(self[0], name)));
            acts.add(new YugiSwipeRow.Action("סיים ומחק", YugiTheme.RED,
                    "לסיים את \"" + name + "\" ולמחוק אותו מהרשימה?", "סיים ומחק", () -> endSession(self[0], s)));
        } else {
            acts.add(new YugiSwipeRow.Action("הסתר", YugiTheme.GRAY, null, null, () -> hide(self[0], name)));
        }
        YugiSwipeRow row = new YugiSwipeRow(c, holder, acts);
        self[0] = row;
        row.setTag(s.optString("id"));
        row.setListener(opened -> { for (YugiSwipeRow r : rows) if (r != opened && r.isOpen()) r.setOpen(false, true); });
        pressable(face);
        face.setOnClickListener(v -> {
            if (app) host.onBack();
            else openDetail(s);
        });
        return row;
    }

    /** Hides a row (by name): it folds away, and comes back by itself when that session works again. */
    private void hide(YugiSwipeRow row, String name) {
        Hidden.set(getContext(), name, true);
        host.onHiddenChanged();
        if (row == null) return;
        row.collapse(() -> {
            cards.removeView(row);
            rows.remove(row);
            if (lastList != null) refreshStatus(lastList);
        });
    }

    /** "סיים ומחק": DELETE /sessions/<id> (the server ends only ad-hoc sessions); the row folds away on success. */
    private void endSession(YugiSwipeRow row, JSONObject s) {
        String id = s.optString("id");
        if (offline) { if (row != null) row.collapse(() -> { cards.removeView(row); rows.remove(row); }); return; }
        listStatus.setText("מסיים את " + s.optString("name") + "…");
        http.newCall(req("/sessions/" + id).delete().build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                ui.post(() -> listStatus.setText("אין חיבור לשרת. הסשן לא נסגר"));
            }
            @Override public void onResponse(Call call, Response r) {
                int code = r.code();
                String msg = "";
                try (Response res = r) {
                    if (res.body() != null) msg = new JSONObject(res.body().string()).optString("message", "");
                } catch (Exception ignored) {}
                final String m = msg;
                ui.post(() -> {
                    if (code == 200 && row != null) {
                        row.collapse(() -> { cards.removeView(row); rows.remove(row); host.refreshList(); });
                        listStatus.setText("הסשן נסגר");
                    } else {
                        listStatus.setText(m.isEmpty() ? "הסשן לא נסגר (" + code + ")" : m);
                    }
                });
            }
        });
    }

    /** Tests: the rows of the list. */
    List<YugiSwipeRow> rowsForTest() { return rows; }

    private static String oneLine(String s) { return s.replaceAll("\\s+", " ").trim(); }

    // ---------------------------------------------------------------- detail

    void openDetail(JSONObject s) {
        currentSession = s;
        currentId = s.optString("id");
        inDetail = true;
        msgList.removeAllViews();
        lastRole = null;
        live = false;
        seenIds.clear();
        pendingBubbles.clear();
        stickToBottom = true;
        input.setText("");
        applySession(s);
        renderPending();
        TextView loading = YugiTheme.text(getContext(), "טוען את השיחה…", 13, 400, YugiTheme.TEXT_FAINT);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(24), 0, dp(24));
        loading.setTag("loading");
        msgList.addView(loading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        push(true);
        connectStream();
    }

    private void popDetail() {
        inDetail = false;
        streamGen++;
        cancelStream();
        hideKeyboard();
        push(false);
        pollList();
    }

    /** Navigation: detail slides in from the start edge (left in RTL) over the list, which drifts a third. */
    private void push(boolean in) {
        int w = getWidth() > 0 ? getWidth() : dp(400);
        float dir = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -1f : 1f;
        listPage.setVisibility(VISIBLE);
        detailPage.setBackground(YugiTheme.rounded(getContext(), YugiTheme.BRAND_BG, YugiTheme.RADIUS_L - 8));
        if (in) {
            detailPage.setVisibility(VISIBLE);
            if (!detailX.isRunning()) detailPage.setTranslationX(dir * w);
            detailX.animateToFinalPosition(0f);
            listX.animateToFinalPosition(-dir * w * 0.3f);
        } else {
            detailX.animateToFinalPosition(dir * w);
            listX.animateToFinalPosition(0f);
        }
    }

    private void applySession(JSONObject s) {
        String state = s.optString("state", "waiting");
        detailTitle.setText(s.optString("name"));
        StringBuilder sub = new StringBuilder(stateLabel(state));
        String area = s.optString("areaLabel", "");
        if (!area.isEmpty()) sub.append(" · ").append(area);
        JSONArray agents = s.optJSONArray("agents");
        if (agents != null && agents.length() > 0) sub.append(" · ").append(agents.length() == 1 ? "סוכן אחד" : agents.length() + " סוכנים");
        detailSubtitle.setText(sub);
        detailAvatar.set(s.optBoolean("app") ? YugiAvatar.YUGI : s.optString("area"), s.optString("name"));
        detailAvatar.setState("waiting".equals(state) || "ended".equals(state) ? 0 : stateColor(state), "working".equals(state));
        detailSubtitle.setTextColor("working".equals(state) ? GREEN : "needs".equals(state) ? ORANGE : YugiTheme.TEXT_DIM);
        boolean canSend = s.optBoolean("canSend", true) && !"ended".equals(state);
        inputBox.setVisibility(canSend ? VISIBLE : GONE);
        readOnlyNote.setVisibility(canSend ? GONE : VISIBLE);
        if (!canSend) {
            String note = s.optString("sendNote", "");
            readOnlyNote.setText("ended".equals(state) ? "הסשן הסתיים" : note.isEmpty() ? "אפשר רק לצפות בסשן הזה" : note);
        }
    }

    private void cancelStream() {
        Call c = streamCall;
        streamCall = null;
        if (c != null) c.cancel();
    }

    private void connectStream() {
        cancelStream();
        if (offline || currentId == null) return;
        final int gen = ++streamGen;
        final String id = currentId;
        new Thread(() -> {
            int backoff = 1000;
            while (gen == streamGen) {
                Call call = streamHttp.newCall(req("/sessions/" + id + "/stream?n=80").get().build());
                streamCall = call;
                boolean got = false;
                try (Response res = call.execute()) {
                    if (res.code() == 404) {
                        ui.post(() -> { if (gen == streamGen) markEnded(); });
                        return;
                    }
                    if (res.isSuccessful() && res.body() != null) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(res.body().byteStream(), StandardCharsets.UTF_8));
                        String line, event = null;
                        while (gen == streamGen && (line = r.readLine()) != null) {
                            if (line.startsWith("event:")) event = line.substring(6).trim();
                            else if (line.startsWith("data:") && event != null) {
                                got = true;
                                final String ev = event;
                                final JSONObject d = new JSONObject(line.substring(5).trim());
                                ui.post(() -> { if (gen == streamGen) onEvent(ev, d); });
                            }
                        }
                    }
                } catch (Exception e) {
                    if (gen == streamGen) LimeLog.warning("YugiSessions stream: " + e);
                }
                if (gen != streamGen) return;
                if (got) backoff = 1000;
                final int wait = backoff;
                ui.post(() -> { if (gen == streamGen) detailSubtitle.setText("מתחבר מחדש…"); });
                try { Thread.sleep(wait); } catch (InterruptedException ignored) { return; }
                backoff = Math.min(backoff * 2, 15000);
            }
        }, "YugiSessionsStream").start();
    }

    void onEvent(String ev, JSONObject d) {
        switch (ev) {
            case "hello": {
                JSONObject s = d.optJSONObject("session");
                if (s != null) { currentSession = s; applySession(s); }
                JSONArray items = d.optJSONArray("items");
                // A reconnect replays the tail: keep what is on screen, add only what is new
                View loading = msgList.findViewWithTag("loading");
                if (loading != null) msgList.removeView(loading);
                live = false;
                if (items != null) for (int i = 0; i < items.length(); i++) addItem(items.optJSONObject(i));
                if (msgList.getChildCount() == 0) addSystem("אין עדיין הודעות בסשן הזה");
                live = true;
                scrollToEnd();
                break;
            }
            case "msg":
                live = true;
                addItem(d);
                if (stickToBottom) scrollToEnd();
                break;
            case "state":
                if ("ended".equals(d.optString("state"))) { markEnded(); break; }
                currentSession = d;
                applySession(d);
                break;
            case "reset":
                msgList.removeAllViews();
                lastRole = null;
                seenIds.clear();
                addSystem("השיחה בסשן התחילה מחדש");
                break;
            default:
        }
    }

    private void markEnded() {
        try {
            JSONObject s = currentSession != null ? new JSONObject(currentSession.toString()) : new JSONObject();
            s.put("state", "ended");
            applySession(s);
        } catch (Exception ignored) {}
    }

    private void addItem(JSONObject it) {
        if (it == null) return;
        String id = it.optString("id");
        if (!id.isEmpty() && !seenIds.add(id)) return;
        String role = it.optString("role");
        String text = it.optString("text");
        if ("user".equals(role)) {
            // My own optimistic bubble: confirm it instead of adding a second one
            for (Pending p : pendingBubbles) {
                if (oneLine(p.sent).equals(oneLine(text))) {
                    pendingBubbles.remove(p);
                    YugiSpring.to(p.row.view, DynamicAnimation.ALPHA, 1f);
                    return;
                }
            }
        }
        addBubble(role, text, null, live);
    }

    /** Set while the stream delivers live messages (they spring in); the replayed tail doesn't. */
    private boolean live;

    private YugiMessages.Row addBubble(String role, String text, List<View> atts, boolean animate) {
        Context c = getContext();
        YugiMessages.Row r = YugiMessages.build(c, role, text, atts);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = YugiMessages.gapBefore(c, lastRole, role);
        lastRole = role;
        msgList.addView(r.view, lp);
        if (animate) YugiMessages.enter(r.view);
        // Trim very long conversations on the phone (the server keeps everything)
        while (msgList.getChildCount() > 300) msgList.removeViewAt(0);
        return r;
    }

    private void addSystem(String text) { addBubble("system", text, null, true); }

    // ---------------------------------------------------------------- send

    /** A message typed here, faint until the session's transcript shows it. */
    private static final class Pending {
        final YugiMessages.Row row;
        final String sent;
        Pending(YugiMessages.Row row, String sent) { this.row = row; this.sent = sent; }
    }

    private void updateSend() {
        boolean can = input.getText().toString().trim().length() > 0 || !YugiChat.pendingAttachments().isEmpty();
        send.setEnabled(can);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(can ? YugiTheme.BRAND : 0x00000000);
        send.setBackground(g);
        send.setImageTintList(android.content.res.ColorStateList.valueOf(can ? 0xFFFFFFFF : YugiTheme.TEXT_FAINT));
    }

    /** The in-app YugiPC keyboard types into this panel's field (Enter sends). */
    void bindKeyboard(YugiFieldKeyboard k) { k.bind(input, this::sendTyped); }

    /** The shared pending attachments, shown above this session's input. */
    void renderPending() {
        attachRow.removeAllViews();
        for (YugiChat.Attachment a : YugiChat.pendingAttachments()) {
            View v = host.attachmentView(a);
            if (v != null) attachRow.addView(v);
        }
        attachScroll.setVisibility(attachRow.getChildCount() == 0 ? GONE : VISIBLE);
        updateSend();
    }

    private void sendTyped() {
        String text = input.getText().toString().trim();
        List<YugiChat.Attachment> atts = new ArrayList<>(YugiChat.pendingAttachments());
        if ((text.isEmpty() && atts.isEmpty()) || currentId == null) return;
        if (text.startsWith("/") && atts.isEmpty()) { input.setText(""); sendCommand(text); return; }
        input.setText("");
        YugiChat.clearPending();
        View loading = msgList.findViewWithTag("loading");
        if (loading != null) msgList.removeView(loading);
        List<View> thumbs = new ArrayList<>();
        for (YugiChat.Attachment a : atts) {
            View v = host.attachmentView(new YugiChat.Attachment(a.name, a.mime, new byte[0], a.thumb));
            if (v != null) { stripRemove(v); thumbs.add(v); }
        }
        YugiMessages.Row mine = addBubble("user", text, thumbs, true);
        stickToBottom = true;
        scrollToEnd();
        if (offline) { mine.view.setAlpha(0.55f); return; }
        final String id = currentId;
        new Thread(() -> {
            // files first: the server keeps them in the session's area (uploads/) and the message carries the paths
            StringBuilder msg = new StringBuilder(text);
            if (!atts.isEmpty()) {
                msg.append(msg.length() > 0 ? "\n\n" : "").append("קבצים מצורפים מיגל (קרא אותם):");
                for (YugiChat.Attachment a : atts) {
                    String path = uploadTo(id, a);
                    if (path == null) {
                        ui.post(() -> failed(mine, text, "העלאת הקובץ " + a.name + " נכשלה. ההודעה לא נשלחה"));
                        return;
                    }
                    msg.append("\n- ").append(path);
                }
            }
            String full = msg.toString();
            Pending p = new Pending(mine, full);
            ui.post(() -> { mine.view.setAlpha(0.55f); pendingBubbles.add(p); });
            JSONObject body = new JSONObject();
            try { body.put("text", full); } catch (Exception ignored) {}
            http.newCall(req("/sessions/" + id + "/message")
                    .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), MediaType.get("application/json")))
                    .build()).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {
                    ui.post(() -> { pendingBubbles.remove(p); failed(mine, text, "אין חיבור לשרת. ההודעה לא נשלחה"); });
                }
                @Override public void onResponse(Call call, Response r) {
                    int code = r.code();
                    String m = "";
                    try (Response res = r) {
                        if (res.body() != null) m = new JSONObject(res.body().string()).optString("message", "");
                    } catch (Exception ignored) {}
                    final String why = m;
                    ui.post(() -> {
                        // Delivered. Stays in pendingBubbles so the transcript's echo of it is swallowed.
                        if (code == 200) YugiSpring.to(mine.view, DynamicAnimation.ALPHA, 1f);
                        else if (code == 202) { /* typed in; stays faint until the transcript shows it */ }
                        else { pendingBubbles.remove(p); failed(mine, text, (why.isEmpty() ? "ההודעה לא נשלחה (" + code + ")" : why + ". ההודעה לא נשלחה")); }
                    });
                }
            });
        }, "YugiSessionsSend").start();
    }

    /**
     * A command (/clear, /model opus, /cost…) goes to the session as is, like in the terminal. What it printed,
     * or the panel it opened, comes back and shows in a sheet.
     */
    private void sendCommand(String text) {
        slash.setShown(false);
        View loading = msgList.findViewWithTag("loading");
        if (loading != null) msgList.removeView(loading);
        hideKeyboard();
        YugiMessages.Row mine = addBubble("user", text, null, true);
        stickToBottom = true;
        scrollToEnd();
        if (offline || currentId == null) return;
        final String id = currentId;
        JSONObject body = new JSONObject();
        try { body.put("text", text); } catch (Exception ignored) {}
        http.newCall(req("/sessions/" + id + "/message")
                .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), MediaType.get("application/json")))
                .build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                ui.post(() -> failed(mine, text, "אין חיבור לשרת. הפקודה לא נשלחה"));
            }
            @Override public void onResponse(Call call, Response r) {
                int code = r.code();
                JSONObject o = null;
                try (Response res = r) {
                    if (res.body() != null) o = new JSONObject(res.body().string());
                } catch (Exception ignored) {}
                final JSONObject d = o == null ? new JSONObject() : o;
                ui.post(() -> {
                    if (code != 200) {
                        String m = d.optString("message", "");
                        failed(mine, text, m.isEmpty() ? "הפקודה לא נשלחה (" + code + ")" : m);
                        return;
                    }
                    String out = d.optString("output", "");
                    String msg = d.optString("message", "");
                    if (!out.isEmpty()) showCommandOutput(text.split("\\s")[0], d.isNull("note") ? null : d.optString("note", null), out);
                    else if (!msg.isEmpty()) addSystem(msg);
                    scrollToEnd();
                });
            }
        });
    }

    /** The sheet with what a command printed (tests call it directly). */
    void showCommandOutput(String title, String note, String output) {
        sheet.show(title, note, output);
    }

    boolean isSheetShownForTest() { return sheet.isShownNow(); }

    /** Tests: type into the session's composer. */
    void typeForTest(String t) { input.setText(t); }

    /** Uploads one attachment for this session; returns the server path or null. Runs off the UI thread. */
    private static String uploadTo(String id, YugiChat.Attachment a) {
        try {
            Request r = req("/sessions/" + id + "/upload?name=" + java.net.URLEncoder.encode(a.name, "UTF-8"))
                    .post(RequestBody.create(a.data, MediaType.get(a.mime))).build();
            try (Response res = http.newCall(r).execute()) {
                if (!res.isSuccessful() || res.body() == null) return null;
                return new JSONObject(res.body().string()).optString("path", null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** A sent message's thumbnails don't keep the remove button. */
    private static void stripRemove(View v) {
        View x = v.findViewWithTag("x");
        if (x != null && x.getParent() instanceof ViewGroup) ((ViewGroup) x.getParent()).removeView(x);
        if (v instanceof ViewGroup) { v.setPadding(0, 0, 0, 0); ((ViewGroup) v).setTouchDelegate(null); }
    }

    private void failed(YugiMessages.Row bubble, String text, String why) {
        msgList.removeView(bubble.view);
        addSystem(why);
        if (input.getText().length() == 0) { input.setText(text); input.setSelection(text.length()); }
        scrollToEnd();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void scrollToEnd() { msgScroll.post(() -> msgScroll.fullScroll(View.FOCUS_DOWN)); }

    // ---------------------------------------------------------------- checks (tests)

    /** Session cards in the list (screen rects relative to this view). */
    List<Rect> cardRects() {
        List<Rect> out = new ArrayList<>();
        for (YugiSwipeRow r : rows) out.add(rectIn(r));
        return out;
    }

    /** Problems in the rows: text under the revealed actions, actions smaller than 48dp. */
    List<String> rowProblems() {
        List<String> out = new ArrayList<>();
        for (YugiSwipeRow r : rows) {
            Rect face = r.contentRect();
            for (Rect a : r.actionRects()) {
                if (Rect.intersects(face, a)) out.add("row " + r.getTag() + ": face " + face.toShortString() + " overlaps action " + a.toShortString());
                if (a.width() < dp(48) || a.height() < dp(48)) out.add("row " + r.getTag() + ": action smaller than 48dp " + a.toShortString());
            }
        }
        return out;
    }

    /** Input row (or the read-only note) in this view's coordinates. */
    Rect bottomRect() {
        return rectIn(inputBox.getVisibility() == VISIBLE ? inputBox : readOnlyNote);
    }

    /** A descendant's bounds in this view's coordinates (scroll and translation included). */
    private Rect rectIn(View v) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != null && cur != this) {
            x += cur.getLeft() + cur.getTranslationX();
            y += cur.getTop() + cur.getTranslationY();
            if (cur.getParent() instanceof View) {
                View par = (View) cur.getParent();
                if (par != this) { x -= par.getScrollX(); y -= par.getScrollY(); }
                cur = par;
            } else cur = null;
        }
        return new Rect(Math.round(x), Math.round(y), Math.round(x) + v.getWidth(), Math.round(y) + v.getHeight());
    }

    int visibleMessageCount() {
        int top = msgScroll.getScrollY(), bottom = top + msgScroll.getHeight(), n = 0;
        for (int i = 0; i < msgList.getChildCount(); i++) {
            View v = msgList.getChildAt(i);
            if (v.getHeight() > 0 && v.getTop() >= top && v.getBottom() <= bottom) n++;
        }
        return n;
    }

    /** Ends springs at once so a screenshot shows the final state. */
    void settleForTest() {
        if (listX.isRunning()) listX.skipToEnd();
        if (detailX.isRunning()) detailX.skipToEnd();
        if (inDetail) {
            detailPage.setTranslationX(0);
            detailPage.setVisibility(VISIBLE);
            detailPage.setBackground(null);
            listPage.setVisibility(INVISIBLE);
        }
        for (int i = 0; i < msgList.getChildCount(); i++) YugiMessages.settle(msgList.getChildAt(i));
        for (YugiSwipeRow r : rows) r.settleForTest();
        sheet.settleForTest();
    }
}

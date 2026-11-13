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
    }

    static final int GREEN = 0xFF30D158;  // iOS system green (dark)
    static final int ORANGE = 0xFFFF9F0A; // iOS system orange (dark)
    private static final long LIST_POLL_MS = 3000;

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
    private final LinearLayout msgList;
    private final ScrollView msgScroll;
    private final TextView detailTitle, detailSubtitle;
    private final View detailDot;
    private final LinearLayout inputBox;
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
    private final List<TextView> pendingBubbles = new ArrayList<>();
    private boolean stickToBottom = true;

    private int dp(float v) { return YugiTheme.dp(getContext(), v); }

    public YugiSessions(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setLayoutDirection(LAYOUT_DIRECTION_RTL);
        setClickable(true);
        setClipChildren(true);

        // ---------------- list page
        listPage = new FrameLayout(ctx);
        LinearLayout lcol = column(ctx);
        LinearLayout lhead = header(ctx);
        ImageButton back = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_back, "חזרה ליוגי");
        back.setOnClickListener(v -> host.onBack());
        lhead.addView(back, sq(48));
        LinearLayout ltitles = new LinearLayout(ctx);
        ltitles.setOrientation(LinearLayout.VERTICAL);
        ltitles.setPadding(dp(4), 0, dp(8), 0);
        ltitles.addView(YugiTheme.text(ctx, "סשנים", 20, 700, YugiTheme.TEXT));
        listStatus = YugiTheme.text(ctx, "טוען…", 12, 400, YugiTheme.TEXT_DIM);
        listStatus.setPadding(0, dp(2), 0, 0);
        ltitles.addView(listStatus);
        lhead.addView(ltitles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton close = YugiTheme.iconButton(ctx, R.drawable.yugi_ic_close, "סגור");
        close.setOnClickListener(v -> host.onClose());
        lhead.addView(close, sq(48));
        lcol.addView(lhead, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        ScrollView lscroll = new ScrollView(ctx);
        lscroll.setVerticalScrollBarEnabled(false);
        lscroll.setFillViewport(true);
        cards = new LinearLayout(ctx);
        cards.setOrientation(LinearLayout.VERTICAL);
        cards.setPadding(dp(2), dp(8), dp(2), dp(12));
        lscroll.addView(cards);
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
        detailTitle = YugiTheme.text(ctx, "", 17, 600, YugiTheme.TEXT);
        detailTitle.setMaxLines(1);
        detailTitle.setEllipsize(TextUtils.TruncateAt.END);
        detailTitle.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        dtitles.addView(detailTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout subRow = new LinearLayout(ctx);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, dp(3), 0, 0);
        detailDot = dot(ctx, YugiTheme.TEXT_FAINT);
        subRow.addView(detailDot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        detailSubtitle = YugiTheme.text(ctx, "", 12, 400, YugiTheme.TEXT_DIM);
        detailSubtitle.setMaxLines(1);
        detailSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        detailSubtitle.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        detailSubtitle.setPadding(dp(6), 0, 0, 0);
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

        inputBox = new LinearLayout(ctx);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setBackground(YugiTheme.roundedStroke(ctx, YugiTheme.SURFACE, 24, YugiTheme.STROKE));
        inputBox.setMinimumHeight(dp(56));
        inputBox.setPadding(dp(12), dp(4), dp(4), dp(4));
        input = new EditText(ctx);
        input.setHint("הודעה לסשן");
        input.setHintTextColor(YugiTheme.TEXT_FAINT);
        input.setTextColor(YugiTheme.TEXT);
        input.setTypeface(YugiTheme.font(ctx, 400));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setBackground(null);
        input.setPadding(dp(4), dp(10), dp(8), dp(10));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(5);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
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
            @Override public void afterTextChanged(Editable s) { updateSend(); }
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(4);
        dcol.addView(inputBox, ilp);
        updateSend();
        detailPage.addView(dcol, match());
        detailPage.setVisibility(INVISIBLE);
        addView(detailPage, match());

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
            ui.removeCallbacks(pollTask);
            hideKeyboard();
        }
    }

    public boolean isInDetail() { return inDetail; }

    /** Back gesture: detail -> list -> (false: let the chat handle it). */
    public boolean handleBack() {
        if (inDetail) { popDetail(); return true; }
        return false;
    }

    // ---------------------------------------------------------------- list

    private final Runnable pollTask = this::pollList;

    private static String base() { return "http://" + YugiConfig.host + ":" + YugiChat.PORT; }

    private static Request.Builder req(String path) {
        return new Request.Builder().url(base() + path).header("Authorization", "Bearer " + BuildConfig.YUGI_CHAT_TOKEN);
    }

    private void pollList() {
        ui.removeCallbacks(pollTask);
        if (!shown || inDetail || offline) return;
        http.newCall(req("/sessions").get().build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                ui.post(() -> {
                    if (cards.getChildCount() <= 1) renderEmpty("אין חיבור לשרת");
                    listStatus.setText("אין חיבור לשרת");
                    if (shown && !inDetail) ui.postDelayed(pollTask, LIST_POLL_MS * 2);
                });
            }
            @Override public void onResponse(Call call, Response r) {
                JSONArray arr = null;
                try (Response res = r) {
                    if (res.isSuccessful() && res.body() != null) arr = new JSONObject(res.body().string()).optJSONArray("sessions");
                } catch (Exception ignored) {}
                final JSONArray list = arr;
                ui.post(() -> {
                    if (list != null) renderList(list);
                    if (shown && !inDetail) ui.postDelayed(pollTask, LIST_POLL_MS);
                });
            }
        });
    }

    private void renderEmpty(String msg) {
        cards.removeAllViews();
        TextView t = YugiTheme.text(getContext(), msg, 15, 400, YugiTheme.TEXT_DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(32), 0, dp(32));
        cards.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    void renderList(JSONArray list) {
        long now = System.currentTimeMillis();
        int working = 0;
        cards.removeAllViews();
        if (list.length() == 0) { renderEmpty("אין סשנים פעילים"); listStatus.setText("אין סשנים פעילים"); return; }
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            if ("working".equals(s.optString("state"))) working++;
            cards.addView(card(s, now));
        }
        listStatus.setText(list.length() + " פעילים" + (working > 0 ? " · " + working + " עובדים עכשיו" : ""));
    }

    private View card(JSONObject s, long now) {
        Context c = getContext();
        String state = s.optString("state", "waiting");
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(YugiTheme.rounded(c, YugiTheme.SURFACE, YugiTheme.RADIUS_M));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setMinimumHeight(dp(64));
        card.setClickable(true);
        card.setFocusable(true);
        card.setTag("card");
        card.setContentDescription(s.optString("name") + ", " + stateLabel(state));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View d = dot(c, stateColor(state));
        top.addView(d, new LinearLayout.LayoutParams(dp(9), dp(9)));
        TextView name = YugiTheme.text(c, s.optString("name"), 16, 600, YugiTheme.TEXT);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(8), 0, dp(8), 0);
        name.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        name.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String area = s.optString("areaLabel", "");
        String meta = stateLabel(state) + (area.isEmpty() ? "" : " · " + area);
        TextView metaT = YugiTheme.text(c, meta, 12, 500, "working".equals(state) ? GREEN : "needs".equals(state) ? ORANGE : YugiTheme.TEXT_DIM);
        metaT.setMaxLines(1);
        top.addView(metaT);
        card.addView(top);

        JSONObject last = s.optJSONObject("lastMessage");
        if (last != null) {
            String who = "user".equals(last.optString("role")) ? "אתה: " : "";
            TextView lm = YugiTheme.text(c, who + oneLine(last.optString("text")), 14, 400, YugiTheme.TEXT_DIM);
            lm.setMaxLines(2);
            lm.setEllipsize(TextUtils.TruncateAt.END);
            lm.setLineSpacing(dp(2), 1f);
            lm.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
            lm.setPadding(0, dp(6), 0, 0);
            card.addView(lm);
        }

        JSONArray agents = s.optJSONArray("agents");
        StringBuilder foot = new StringBuilder();
        if (agents != null && agents.length() > 0) {
            foot.append(agents.length() == 1 ? "סוכן אחד עובד: " : agents.length() + " סוכנים עובדים: ");
            for (int k = 0; k < agents.length() && k < 3; k++) {
                if (k > 0) foot.append(", ");
                foot.append(agents.optJSONObject(k) != null ? agents.optJSONObject(k).optString("description") : "");
            }
        }
        long upd = s.optLong("updatedAt", 0);
        String when = ago(upd, now);
        if (!s.optBoolean("canSend", true)) when = (when.isEmpty() ? "" : when + " · ") + "צפייה בלבד";
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(6), 0, 0);
        TextView ag = YugiTheme.text(c, foot.toString(), 12, 500, YugiTheme.ACCENT);
        ag.setMaxLines(1);
        ag.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(ag, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView wt = YugiTheme.text(c, when, 12, 400, YugiTheme.TEXT_FAINT);
        wt.setPadding(dp(8), 0, 0, 0);
        bottom.addView(wt);
        card.addView(bottom);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        pressable(card);
        card.setOnClickListener(v -> openDetail(s));
        return card;
    }

    private static String oneLine(String s) { return s.replaceAll("\\s+", " ").trim(); }

    // ---------------------------------------------------------------- detail

    void openDetail(JSONObject s) {
        currentSession = s;
        currentId = s.optString("id");
        inDetail = true;
        ui.removeCallbacks(pollTask);
        msgList.removeAllViews();
        seenIds.clear();
        pendingBubbles.clear();
        stickToBottom = true;
        input.setText("");
        applySession(s);
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
        tintDot(detailDot, stateColor(state));
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
                if (items != null) for (int i = 0; i < items.length(); i++) addItem(items.optJSONObject(i));
                if (msgList.getChildCount() == 0) addSystem("אין עדיין הודעות בסשן הזה");
                scrollToEnd();
                break;
            }
            case "msg":
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
            for (TextView p : pendingBubbles) {
                if (oneLine(String.valueOf(p.getText())).equals(oneLine(text))) {
                    pendingBubbles.remove(p);
                    p.animate().alpha(1f).setDuration(180).start();
                    return;
                }
            }
        }
        addBubble(role, text);
    }

    private TextView addBubble(String role, String text) {
        Context c = getContext();
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(10);
        TextView t = YugiTheme.text(c, text, 15, 400, YugiTheme.TEXT);
        t.setLineSpacing(dp(3), 1f);
        t.setTextIsSelectable(true);
        t.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if ("user".equals(role)) {
            wrap.setGravity(Gravity.START); // RTL: Yagel's messages on the right
            t.setBackground(YugiTheme.rounded(c, YugiTheme.SURFACE_HI, YugiTheme.RADIUS_M));
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
            t.setMaxWidth(dp(300));
        } else if ("system".equals(role)) {
            wrap.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setTextColor(YugiTheme.TEXT_FAINT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setTextIsSelectable(false);
        } else {
            wrap.setGravity(Gravity.START);
            t.setPadding(dp(4), dp(2), dp(4), dp(2));
            tlp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        wrap.addView(t, tlp);
        msgList.addView(wrap, wlp);
        // Trim very long conversations on the phone (the server keeps everything)
        while (msgList.getChildCount() > 300) msgList.removeViewAt(0);
        return t;
    }

    private void addSystem(String text) { addBubble("system", text); }

    // ---------------------------------------------------------------- send

    private void updateSend() {
        boolean can = input.getText().toString().trim().length() > 0;
        send.setEnabled(can);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(can ? YugiTheme.TEXT : YugiTheme.SURFACE_HI);
        send.setBackground(g);
        send.setImageTintList(android.content.res.ColorStateList.valueOf(can ? 0xFF0B0E14 : YugiTheme.TEXT_FAINT));
    }

    private void sendTyped() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || currentId == null) return;
        if (text.startsWith("/")) { addSystem("פקודות עם / לא נשלחות מכאן"); scrollToEnd(); return; }
        input.setText("");
        View loading = msgList.findViewWithTag("loading");
        if (loading != null) msgList.removeView(loading);
        TextView mine = addBubble("user", text);
        mine.setAlpha(0.55f); // until the session's transcript shows it
        pendingBubbles.add(mine);
        stickToBottom = true;
        scrollToEnd();
        if (offline) return;
        final String id = currentId;
        JSONObject body = new JSONObject();
        try { body.put("text", text); } catch (Exception ignored) {}
        http.newCall(req("/sessions/" + id + "/message")
                .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), MediaType.get("application/json")))
                .build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                ui.post(() -> failed(mine, text, "אין חיבור לשרת. ההודעה לא נשלחה"));
            }
            @Override public void onResponse(Call call, Response r) {
                int code = r.code();
                String msg = "";
                try (Response res = r) {
                    if (res.body() != null) msg = new JSONObject(res.body().string()).optString("message", "");
                } catch (Exception ignored) {}
                final String m = msg;
                ui.post(() -> {
                    // Delivered. Stays in pendingBubbles so the transcript's echo of it is swallowed.
                    if (code == 200) mine.animate().alpha(1f).setDuration(180).start();
                    else if (code == 202) { /* typed in; stays faint until the transcript shows it */ }
                    else failed(mine, text, (m.isEmpty() ? "ההודעה לא נשלחה (" + code + ")" : m + ". ההודעה לא נשלחה"));
                });
            }
        });
    }

    private void failed(TextView bubble, String text, String why) {
        pendingBubbles.remove(bubble);
        View wrap = (View) bubble.getParent();
        msgList.removeView(wrap);
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
        for (int i = 0; i < cards.getChildCount(); i++) {
            View v = cards.getChildAt(i);
            if (!"card".equals(v.getTag())) continue;
            out.add(rectIn(v));
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
    }
}

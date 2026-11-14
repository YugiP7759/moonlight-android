package com.limelight.yugi;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The chat's header with the keyboard open, on both Fold 7 screens and with a large system font (the
 * screenshot bug: the status line under the session name was cut and the first message sat on it). The
 * header keeps its height, only the messages give way; no text sits on other text; 12dp+ between the chips
 * and the header. Renders PNGs to app/build/yugi-screens.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class YugiChatKeyboardTest {
    private static final File OUT = new File("build/yugi-screens");

    @Test @Config(qualifiers = "w411dp-h960dp-port-420dpi")
    public void coverPortrait() throws Exception { run("chat-kb-cover-portrait", 1.3f); }

    @Test @Config(qualifiers = "w960dp-h411dp-land-420dpi")
    public void coverLandscape() throws Exception { run("chat-kb-cover-landscape", 1.3f); }

    @Test @Config(qualifiers = "w750dp-h832dp-port-420dpi")
    public void innerPortrait() throws Exception { run("chat-kb-inner-portrait", 1.3f); }

    @Test @Config(qualifiers = "w832dp-h750dp-land-420dpi")
    public void innerLandscape() throws Exception { run("chat-kb-inner-landscape", 1.3f); }

    @Test @Config(qualifiers = "w750dp-h832dp-port-420dpi")
    public void innerPortraitDefaultFont() throws Exception { run("chat-kb-inner-portrait-font1", 1f); }

    private void run(String name, float fontScale) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        boolean land = w >= h;
        List<String> problems = new ArrayList<>();

        YugiMessages.offline = true;
        YugiSessions.offline = true;
        YugiSessions.Hidden.clear(a);
        a.getSharedPreferences("yugi_chrome", android.content.Context.MODE_PRIVATE).edit().clear().commit();
        FrameLayout pc = new FrameLayout(a);
        pc.setBackgroundColor(0xFF1F3B5C);
        final YugiChat[] chatRef = new YugiChat[1];
        YugiChrome chrome = new YugiChrome(a, Arrays.asList(
                new YugiChrome.Button(R.drawable.yugi_ic_keyboard, "מקלדת", () -> {}),
                new YugiChrome.Button(R.drawable.yugi_ic_menu, "תפריט", () -> {})),
                new YugiChrome.Button(R.drawable.yugi_ic_yugi, "יוגי", () -> chatRef[0].toggle()));
        chrome.setAutoHide(false);
        pc.addView(chrome, match());
        layout(pc, w, h);

        YugiChat.setMessagesForTest(new ArrayList<>());
        YugiChat.clearPendingForTest();
        YugiChat chat = new YugiChat(a, new YugiChat.Host() {
            @Override public void captureScreen(YugiChat.BitmapCallback cb) { cb.onBitmap(null); }
            @Override public void pickFile() {}
            @Override public int reservedTop() { return chrome.getReservedTop(); }
            @Override public void onKeyboard(boolean shown) { chrome.setChromeVisible(!shown); }
            @Override public Rect anchorRect() { return chrome.primaryRectInWindow(); }
            @Override public boolean anchorRight() { return chrome.isDockedRight(); }
            @Override public void onChatOpen(boolean open) { chrome.setChatOpen(open); }
        });
        chatRef[0] = chat;
        pc.addView(chat, match());
        YugiChat.setMessagesForTest(Arrays.asList(
                new YugiChat.Msg("user", "עדכנתי ועדיין"),
                new YugiChat.Msg("assistant", "אני לא רואה בצילום חלון צף. הסרטון נראה כאילו הוא מתנגן בתוך הדף, "
                        + "וזה בדיוק הנגן שלנו מהטלפון. איפה מופיע החלון הצף?"),
                new YugiChat.Msg("user", "תשאיר לפתוח ולמחוק לפי הצורך?"),
                new YugiChat.Msg("assistant", "בסדר. מה שהיה פתוח נשאר, ומה שלא צריך נמחק.")));
        chat.rerenderForTest();
        chat.setOpen(true);
        chat.setChipsForTest(new org.json.JSONArray(SESSIONS_JSON));
        idle();
        layout(pc, w, h);
        chat.settleForTest();
        layout(pc, w, h);

        int ime = Math.round(h * (land ? 0.45f : 0.31f)); // the YugiPC keyboard's share of the screen
        chat.setInsets(ime, 0);
        layout(pc, w, h);
        layout(pc, w, h);
        View[] cp = chat.headerPartsForTest(); // header, subtitle, hair, scroll, chips
        checkHeader("chat", pc, cp[0], new TextView[]{(TextView) cp[1]}, cp[2], cp[3], cp[4], problems);
        drawKeyboard(pc, w, h, ime, name + "-yugi");

        // a session's page (the screenshot): chips, header with name + status, messages
        chat.setInsets(0, 0);
        chat.showSessions(true);
        YugiSessions ss = chat.sessionsForTest();
        ss.animate().cancel();
        ss.setAlpha(1f);
        ss.setTranslationY(0);
        org.json.JSONArray list = new org.json.JSONArray(SESSIONS_JSON);
        ss.renderList(list);
        ss.openDetail(list.getJSONObject(0));
        ss.onEvent("hello", new org.json.JSONObject("{\"session\":" + list.getJSONObject(0) + ",\"items\":" + ITEMS_JSON + "}"));
        ss.settleForTest();
        idle();
        layout(pc, w, h);
        chat.settleForTest();
        ss.setAlpha(1f);
        chat.setInsets(ime, 0);
        layout(pc, w, h);
        layout(pc, w, h);
        View[] sp = ss.detailPartsForTest(); // head, title, subtitle, hair, scroll
        checkHeader("session", pc, sp[0], new TextView[]{(TextView) sp[1], (TextView) sp[2]}, sp[3], sp[4], cp[4], problems);
        if (cp[4].getVisibility() == View.VISIBLE && sp[0].getVisibility() == View.VISIBLE) {
            Rect chips = rectIn(pc, cp[4]), page = rectIn(pc, ss);
            if (page.top - chips.bottom < dp(a, 12)) problems.add("session: chips " + chips.toShortString()
                    + " less than 12dp above the session page " + page.toShortString());
        }
        drawKeyboard(pc, w, h, ime, name + "-session");
        chat.setInsets(0, 0);
        RuntimeEnvironment.setFontScale(1f);

        assertTrue(name + ":\n" + String.join("\n", problems), problems.isEmpty());
    }

    /**
     * The header (when shown) holds all of its text, the hairline and the messages start under it, and the
     * chips end at least 12dp above it.
     */
    private static void checkHeader(String what, View root, View head, TextView[] texts, View hair, View scroll, View chips,
                                    List<String> problems) {
        if (head.getVisibility() != View.VISIBLE) return; // little room: no header at all, nothing to overlap
        Rect hr = rectIn(root, head);
        if (hr.height() < dp(head.getContext(), 52)) problems.add(what + ": header shorter than 52dp " + hr.toShortString());
        for (TextView t : texts) {
            if (t.getVisibility() != View.VISIBLE || t.getText().length() == 0) continue;
            Rect tr = rectIn(root, t);
            if (!hr.contains(tr)) problems.add(what + ": \"" + t.getText() + "\" " + tr.toShortString()
                    + " runs out of the header " + hr.toShortString());
            if (t.getLayout() != null && t.getLayout().getHeight() + t.getTotalPaddingTop() + t.getTotalPaddingBottom() > t.getHeight() + 1)
                problems.add(what + ": \"" + t.getText() + "\" is cut (" + t.getLayout().getHeight() + " in " + t.getHeight() + ")");
        }
        if (hair.getVisibility() == View.VISIBLE && rectIn(root, hair).top < hr.bottom)
            problems.add(what + ": the hairline crosses the header");
        Rect sr = rectIn(root, scroll);
        if (sr.top < hr.bottom) problems.add(what + ": messages " + sr.toShortString() + " start inside the header " + hr.toShortString());
        if (sr.height() < dp(head.getContext(), 40)) problems.add(what + ": no room left for messages " + sr.toShortString());
        if (chips != null && chips.getVisibility() == View.VISIBLE) {
            Rect cr = rectIn(root, chips);
            if (hr.top - cr.bottom < dp(head.getContext(), 12))
                problems.add(what + ": chips " + cr.toShortString() + " less than 12dp above the header " + hr.toShortString());
        }
    }

    /** A view's bounds in {@code root}'s coordinates (layout + translation, up the parents). */
    private static Rect rectIn(View root, View v) {
        float x = 0, y = 0;
        View c = v;
        while (c != null && c != root) {
            x += c.getLeft() + c.getTranslationX();
            y += c.getTop() + c.getTranslationY();
            Object p = c.getParent();
            if (p instanceof View) {
                x -= ((View) p).getScrollX();
                y -= ((View) p).getScrollY();
            }
            c = p instanceof View ? (View) p : null;
        }
        return new Rect(Math.round(x), Math.round(y), Math.round(x + v.getWidth()), Math.round(y + v.getHeight()));
    }

    private static final String SESSIONS_JSON = "["
            + "{\"id\":\"1348\",\"name\":\"Personal\",\"area\":\"personal\",\"canEnd\":false,\"areaLabel\":\"אישי\",\"state\":\"waiting\",\"canSend\":true,\"updatedAt\":" + (System.currentTimeMillis() - 20000)
            + ",\"lastMessage\":{\"role\":\"assistant\",\"text\":\"השאר לפתוח ולמחוק לפי הצורך?\"},\"agents\":[]},"
            + "{\"id\":\"1354\",\"name\":\"Mamila\",\"area\":\"mamila\",\"canEnd\":false,\"areaLabel\":\"ממילא\",\"state\":\"waiting\",\"canSend\":true,\"updatedAt\":" + (System.currentTimeMillis() - 3 * 3600000)
            + ",\"lastMessage\":{\"role\":\"user\",\"text\":\"תכין סיכום\"},\"agents\":[]},"
            + "{\"id\":\"1362\",\"name\":\"NextPress\",\"area\":\"nextpress\",\"canEnd\":false,\"areaLabel\":\"נקסט פרס\",\"state\":\"needs\",\"canSend\":true,\"updatedAt\":" + (System.currentTimeMillis() - 600000)
            + ",\"lastMessage\":{\"role\":\"assistant\",\"text\":\"לא צריך APK חדש.\"},\"agents\":[]},"
            + "{\"id\":\"3811174\",\"name\":\"Business\",\"area\":\"workspace\",\"canEnd\":true,\"areaLabel\":\"כללי\",\"state\":\"waiting\",\"canSend\":true,\"updatedAt\":" + (System.currentTimeMillis() - 86400000L)
            + ",\"lastMessage\":{\"role\":\"assistant\",\"text\":\"מוכן.\"},\"agents\":[]}"
            + "]";

    private static final String ITEMS_JSON = "["
            + "{\"id\":\"m1\",\"role\":\"assistant\",\"text\":\"השאר לפתוח ולמחוק לפי הצורך?\"},"
            + "{\"id\":\"m2\",\"role\":\"user\",\"text\":\"עדכנתי ועדיין\"},"
            + "{\"id\":\"m3\",\"role\":\"assistant\",\"text\":\"אני לא רואה בצילום חלון צף. הסרטון נראה כאילו הוא מתנגן בתוך הדף, וזה בדיוק הנגן שלנו מהטלפון. איפה מופיע החלון הצף?\"}"
            + "]";

    // ---------------------------------------------------------------- helpers

    private static int dp(android.content.Context c, float v) { return YugiTheme.dp(c, v); }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static void layout(View v, int w, int h) {
        for (int i = 0; i < 2; i++) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            v.layout(0, 0, w, h);
            idle();
        }
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }

    private static void drawKeyboard(View v, int w, int h, int ime, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        v.draw(c);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF202124);
        c.drawRect(0, h - ime, w, h, p);
        p.setColor(0xFF3C4043);
        int rows = 5, cols = 13, gap = 10;
        float kw = (w - gap * (cols + 1)) / (float) cols, kh = (ime - gap * (rows + 1)) / (float) rows;
        for (int r = 0; r < rows; r++) {
            for (int k = 0; k < cols; k++) {
                float x = gap + k * (kw + gap), y = h - ime + gap + r * (kh + gap);
                c.drawRoundRect(x, y, x + kw, y + kh, 12, 12, p);
            }
        }
        OUT.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}

package com.limelight.yugi;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Visual QA of every YugiPC overlay on the Galaxy Z Fold 7 screens (cover 2520x1080 and inner 2184x1968,
 * landscape and portrait). Renders PNGs to app/build/yugi-screens and fails on any overlap, on controls
 * leaving the screen, or on the chat input being hidden by the keyboard.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class YugiUiScreenshotTest {
    private static final File OUT = new File("build/yugi-screens");

    @Test @Config(qualifiers = "w960dp-h411dp-land-420dpi")
    public void coverLandscape() throws Exception { run("1-cover-landscape", "cover_l"); }

    @Test @Config(qualifiers = "w411dp-h960dp-port-420dpi")
    public void coverPortrait() throws Exception { run("2-cover-portrait", "cover_p"); }

    @Test @Config(qualifiers = "w832dp-h750dp-land-420dpi")
    public void innerLandscape() throws Exception { run("3-inner-landscape", "inner_l"); }

    @Test @Config(qualifiers = "w750dp-h832dp-port-420dpi")
    public void innerPortrait() throws Exception { run("4-inner-portrait", "inner_p"); }

    // ---------------------------------------------------------------- scenario

    private void run(String name, String layoutKey) throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        boolean land = w >= h;
        List<String> problems = new ArrayList<>();

        // ---- games: pad + toolbar + HUD
        FrameLayout games = screen(a, "game");
        VirtualPadView pad = new VirtualPadView(a);
        pad.loadLayout(layoutKey, land);
        games.addView(pad, match());
        YugiChrome.Button padBtn = new YugiChrome.Button(R.drawable.yugi_ic_gamepad, "שלט", () -> {});
        padBtn.active = true;
        YugiChrome chrome = new YugiChrome(a, Arrays.asList(
                new YugiChrome.Button(R.drawable.yugi_ic_yugi, "יוגי", () -> {}),
                padBtn,
                new YugiChrome.Button(R.drawable.yugi_ic_edit, "עריכה", () -> {}),
                new YugiChrome.Button(R.drawable.yugi_ic_menu, "תפריט", () -> {})));
        chrome.setHudVisible(true);
        chrome.setStats(60, 98, 1920, 1080);
        games.addView(chrome, match());
        layout(games, w, h);
        pad.setTopInset(chrome.getReservedTop());
        layout(games, w, h);
        checkInside("chrome", chrome.visibleRects(), w, h, problems);
        checkInside("pad", pad.controlRects(), w, h, problems);
        checkNoOverlap("chrome", chrome.visibleRects(), "chrome", chrome.visibleRects(), problems);
        checkNoOverlap("pad", pad.controlRects(), "pad", pad.controlRects(), problems);
        checkNoOverlap("chrome", chrome.visibleRects(), "pad", pad.controlRects(), problems);
        save(games, name + "-a-games");

        // ---- games: pad edit mode
        YugiPadEditor editor = new YugiPadEditor(a, new YugiPadEditor.Actions() {
            @Override public void onSave() {}
            @Override public void onCancel() {}
            @Override public void onReset() {}
            @Override public void onSize(float d) {}
            @Override public void onAlpha(float al) {}
        });
        games.addView(editor, match());
        pad.setEditing(true);
        editor.setVisibility(View.VISIBLE);
        chrome.setChromeVisible(false);
        layout(games, w, h);
        checkInside("editor", List.of(editor.cardRect()), w, h, problems);
        save(games, name + "-b-pad-edit");

        // ---- games: menu panel
        games.removeView(editor);
        pad.setEditing(false);
        chrome.setChromeVisible(true);
        FrameLayout menu = YugiNav.buildPanel(a, Arrays.asList(
                new YugiNav.Item("המחשב שלי", false, null),
                new YugiNav.Item("משחקים", true, null),
                YugiNav.Item.section("תצוגה"),
                new YugiNav.Item("תמונה: מלא (חיתוך)", false, null),
                new YugiNav.Item("רזולוציה: 16:9 (\u20661920×1080\u2069)", false, null),
                new YugiNav.Item("סטטיסטיקות: מוצגות", false, null),
                YugiNav.Item.section("שלט"),
                new YugiNav.Item("הגדרות שלט", false, null),
                new YugiNav.Item("ג'ירוסקופ: כבוי", false, null),
                new YugiNav.Item("כפתור Menu (Start)", false, null)), chrome.getReservedTop(), () -> {});
        games.addView(menu, match());
        layout(games, w, h);
        View menuCard = menu.getChildAt(0);
        Rect menuRect = new Rect(menuCard.getLeft(), menuCard.getTop(), menuCard.getRight(), menuCard.getBottom());
        checkInside("menu", List.of(menuRect), w, h, problems);
        checkNoOverlap("menu", List.of(menuRect), "chrome", chrome.visibleRects(), problems);
        save(games, name + "-c-menu");
        games.removeView(menu);

        // ---- games: pad settings
        YugiPadSettingsView settings = new YugiPadSettingsView(a, new PadSettings(), new YugiPadSettingsView.Listener() {
            @Override public void onChanged(PadSettings s) {}
            @Override public void onClose() {}
        });
        games.addView(settings, match());
        layout(games, w, h);
        checkInside("settings", List.of(settings.cardRect()), w, h, problems);
        save(games, name + "-d-pad-settings");

        // ---- desktop: toolbar + chat, without and with the keyboard
        FrameLayout pc = screen(a, "pc");
        YugiChrome pcChrome = new YugiChrome(a, Arrays.asList(
                new YugiChrome.Button(R.drawable.yugi_ic_yugi, "יוגי", () -> {}),
                new YugiChrome.Button(R.drawable.yugi_ic_mic, "מיקרופון", () -> {}),
                new YugiChrome.Button(R.drawable.yugi_ic_videocam, "מצלמה", () -> {}),
                new YugiChrome.Button(R.drawable.yugi_ic_switch_camera, "החלפת מצלמה", () -> {}),
                new YugiChrome.Button(R.drawable.yugi_ic_menu, "תפריט", () -> {})));
        pc.addView(pcChrome, match());
        YugiChat.Msg m1 = new YugiChat.Msg("user", "תפתח לי את הקובץ של ההצעה ללקוח ותסכם מה חסר");
        YugiChat.Msg m2 = new YugiChat.Msg("assistant", "פתחתי את ההצעה. חסרים שני דברים: לוח זמנים לתשלום ופרטי איש קשר. להוסיף אותם עכשיו?");
        YugiChat.Msg m3 = new YugiChat.Msg("user", "כן, ותוסיף גם את הלוגו");
        m3.attachments.add(new YugiChat.Attachment("logo.png", "image/png", new byte[0], thumb(a)));
        YugiChat.Msg m4 = new YugiChat.Msg("assistant", "הוספתי את הלוגו ואת שני הסעיפים. הקובץ שמור ב-Drive.");
        YugiChat.setMessagesForTest(Arrays.asList(m1, m2, m3, m4));
        YugiChat.clearPendingForTest();
        YugiChat chat = new YugiChat(a, new YugiChat.Host() {
            @Override public void captureScreen(YugiChat.BitmapCallback cb) { cb.onBitmap(null); }
            @Override public void pickFile() {}
            @Override public int reservedTop() { return pcChrome.getReservedTop(); }
            @Override public void onKeyboard(boolean shown) { pcChrome.setChromeVisible(!shown); }
        });
        chat.rerenderForTest();
        chat.addPendingForTest("screenshot-1412.jpg", "image/jpeg", new byte[0], thumb(a));
        chat.addPendingForTest("הצעת מחיר.pdf", "application/pdf", new byte[0], null);
        pc.addView(chat, match());
        chat.setOpen(true);
        idle();
        layout(pc, w, h);
        layout(pc, w, h);
        checkChat("chat", chat, pcChrome, w, h, 0, problems);
        save(pc, name + "-e-chat");

        int ime = Math.round(h * (land ? 0.55f : 0.40f)); // a real keyboard's share of the screen
        chat.setInsets(ime, 0);
        layout(pc, w, h);
        checkChat("chat+keyboard", chat, pcChrome, w, h, ime, problems);
        drawKeyboard(pc, w, h, ime, name + "-f-chat-keyboard");

        // ---- sessions: list of the server's Yugi sessions, then one session's live chat (+ keyboard)
        chat.setInsets(0, 0);
        YugiSessions.offline = true;
        chat.showSessions(true);
        YugiSessions ss = chat.sessionsForTest();
        ss.animate().cancel();
        ss.setAlpha(1f);
        ss.setTranslationY(0);
        ss.renderList(new org.json.JSONArray(SESSIONS_JSON));
        idle();
        layout(pc, w, h);
        layout(pc, w, h);
        checkSessionsList(chat, ss, pcChrome, w, h, problems);
        save(pc, name + "-h1-sessions");

        org.json.JSONArray list = new org.json.JSONArray(SESSIONS_JSON);
        ss.openDetail(list.getJSONObject(0));
        ss.onEvent("hello", new org.json.JSONObject("{\"session\":" + list.getJSONObject(0) + ",\"items\":" + ITEMS_JSON + "}"));
        ss.settleForTest();
        idle();
        layout(pc, w, h);
        layout(pc, w, h);
        checkSessionDetail("session", chat, ss, w, h, 0, problems);
        save(pc, name + "-h2-session");

        chat.setInsets(ime, 0);
        layout(pc, w, h);
        layout(pc, w, h);
        checkSessionDetail("session+keyboard", chat, ss, w, h, ime, problems);
        drawKeyboard(pc, w, h, ime, name + "-h3-session-keyboard");
        chat.setInsets(0, 0);

        ss.openDetail(list.getJSONObject(3)); // opened from the Claude app: read only
        ss.onEvent("hello", new org.json.JSONObject("{\"session\":" + list.getJSONObject(3) + ",\"items\":" + ITEMS_JSON + "}"));
        ss.settleForTest();
        idle();
        layout(pc, w, h);
        layout(pc, w, h);
        checkSessionDetail("session read-only", chat, ss, w, h, 0, problems);
        save(pc, name + "-h4-session-readonly");
        chat.showSessions(false);
        ss.animate().cancel();

        // ---- launch sequence: splash -> branded connecting (with and without last frame) -> error
        android.graphics.drawable.Drawable splash = a.getDrawable(R.drawable.yugipc_launch_bg);
        Bitmap sb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        splash.setBounds(0, 0, w, h);
        splash.draw(new Canvas(sb));
        write(sb, name + "-g1-splash");
        new java.io.File(a.getFilesDir(), "last_frame.jpg").delete();
        YugiBrandView brand = new YugiBrandView(a);
        layout(brand, w, h);
        save(brand, name + "-g2-connecting");
        Bitmap last = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        screen(a, "pc").draw(new Canvas(last)); // placeholder content
        FrameLayout desk = screen(a, "pc");
        layout(desk, w, h);
        desk.draw(new Canvas(last));
        try (FileOutputStream fo = new FileOutputStream(new java.io.File(a.getFilesDir(), "last_frame.jpg"))) {
            last.compress(Bitmap.CompressFormat.JPEG, 80, fo);
        }
        YugiBrandView brand2 = new YugiBrandView(a);
        layout(brand2, w, h);
        save(brand2, name + "-g3-connecting-last-frame");
        brand2.setActions("נסה שוב", () -> {}, "יציאה", () -> {});
        brand2.setStatus("אין חיבור. ודא שיש אינטרנט ונסה שוב");
        brand2.setBusy(false);
        layout(brand2, w, h);
        save(brand2, name + "-g4-error");

        assertTrue(name + ":\n" + String.join("\n", problems), problems.isEmpty());
    }

    // ---------------------------------------------------------------- checks

    private void checkChat(String what, YugiChat chat, YugiChrome chrome, int w, int h, int ime, List<String> problems) {
        Rect card = chat.cardRect();
        Rect input = chat.inputRect();
        checkInside(what + " card", List.of(card), w, h - ime, problems);
        int visible = chat.visibleMessageCount();
        if (visible < (ime > 0 ? 1 : 2)) problems.add(what + ": only " + visible + " message(s) fully visible");
        for (String p : chat.chipProblems()) problems.add(what + ": " + p);
        checkInside(what + " input", List.of(input), w, h - ime, problems);
        if (input.height() < 40) problems.add(what + ": input too small " + input);
        checkNoOverlap(what, List.of(card), "chrome", chrome.visibleRects(), problems);
    }

    private void checkSessionsList(YugiChat chat, YugiSessions ss, YugiChrome chrome, int w, int h, List<String> problems) {
        Rect card = chat.cardRect();
        int ox = card.left + ss.getLeft(), oy = card.top + ss.getTop();
        List<Rect> cards = new ArrayList<>();
        for (Rect r : ss.cardRects()) { r.offset(ox, oy); cards.add(r); }
        if (cards.isEmpty()) problems.add("sessions: no cards rendered");
        Rect panel = new Rect(ox, oy, ox + ss.getWidth(), oy + ss.getHeight());
        checkInside("sessions panel", List.of(panel), w, h, problems);
        for (Rect r : cards) {
            if (r.height() < dp(ss.getContext(), 48)) problems.add("sessions: card shorter than 48dp " + r.toShortString());
            if (r.left < panel.left || r.right > panel.right) problems.add("sessions: card leaves the panel " + r.toShortString());
        }
        checkNoOverlap("sessions cards", cards, "sessions cards", cards, problems);
        checkNoOverlap("sessions", List.of(card), "chrome", chrome.visibleRects(), problems);
    }

    private void checkSessionDetail(String what, YugiChat chat, YugiSessions ss, int w, int h, int ime, List<String> problems) {
        Rect card = chat.cardRect();
        Rect bottom = ss.bottomRect();
        bottom.offset(card.left + ss.getLeft(), card.top + ss.getTop());
        checkInside(what + " card", List.of(card), w, h - ime, problems);
        checkInside(what + " input", List.of(bottom), w, h - ime, problems);
        if (bottom.height() < 40) problems.add(what + ": input too small " + bottom);
        if (bottom.bottom > card.bottom) problems.add(what + ": input below the card " + bottom.toShortString());
        int visible = ss.visibleMessageCount();
        if (visible < (ime > 0 ? 1 : 2)) problems.add(what + ": only " + visible + " message(s) fully visible");
    }

    private static final String SESSIONS_JSON = "["
            + "{\"id\":\"1348\",\"name\":\"Personal\",\"areaLabel\":\"אישי\",\"state\":\"working\",\"canSend\":true,\"updatedAt\":" + (System.currentTimeMillis() - 20000)
            + ",\"lastMessage\":{\"role\":\"assistant\",\"text\":\"יצא סוכן לתקן. התפריט ייגרר גם כשהוא פתוח וגם כשהוא סגור, וייצמד לקצה המסך. אשלח קישור כשיהיה מוכן.\"},"
            + "\"agents\":[{\"id\":\"a1\",\"description\":\"מסך סשנים ב-YugiPC\"},{\"id\":\"a2\",\"description\":\"תפריט צף נגרר\"}]},"
            + "{\"id\":\"1354\",\"name\":\"Mamila\",\"areaLabel\":\"ממילא\",\"state\":\"waiting\",\"canSend\":true,\"updatedAt\":" + (System.currentTimeMillis() - 3 * 3600000)
            + ",\"lastMessage\":{\"role\":\"user\",\"text\":\"תכין סיכום של הפגישה עם הזוג מאשדוד\"},\"agents\":[]},"
            + "{\"id\":\"1362\",\"name\":\"NextPress\",\"areaLabel\":\"נקסט פרס\",\"state\":\"needs\",\"canSend\":true,\"updatedAt\":" + (System.currentTimeMillis() - 600000)
            + ",\"lastMessage\":{\"role\":\"assistant\",\"text\":\"לא צריך APK חדש, מספיק להיכנס לאפליקציה.\"},\"agents\":[]},"
            + "{\"id\":\"3811174\",\"name\":\"טובה בשמחות\",\"areaLabel\":\"כללי\",\"state\":\"waiting\",\"canSend\":false,\"sendNote\":\"הסשן נפתח מאפליקציית Claude, אז אפשר רק לצפות בו מכאן\",\"updatedAt\":" + (System.currentTimeMillis() - 2 * 86400000L)
            + ",\"lastMessage\":{\"role\":\"assistant\",\"text\":\"התצוגה מוכנה, ושונה בה רק מה שטובה ביקשה.\"},\"agents\":[]}"
            + "]";

    private static final String ITEMS_JSON = "["
            + "{\"id\":\"m1\",\"role\":\"user\",\"text\":\"מה המצב עם התפריט הצף?\"},"
            + "{\"id\":\"m2\",\"role\":\"assistant\",\"text\":\"סוכן עובד עליו עכשיו. התפריט ייגרר גם כשהוא פתוח, וייצמד לקצה הקרוב בתנועת קפיץ.\"},"
            + "{\"id\":\"m3\",\"role\":\"user\",\"text\":\"מעולה, ותבדוק שזה עובד גם במסך החיצוני\"},"
            + "{\"id\":\"m4\",\"role\":\"assistant\",\"text\":\"בודק בשני המסכים, לאורך ולרוחב. אעדכן כשיש APK.\"}"
            + "]";

    private static void checkInside(String what, List<Rect> rects, int w, int h, List<String> problems) {
        for (Rect r : rects) {
            if (r.left < 0 || r.top < 0 || r.right > w || r.bottom > h) {
                problems.add(what + " outside the visible area " + r.toShortString() + " in " + w + "x" + h);
            }
        }
    }

    private static void checkNoOverlap(String an, List<Rect> a, String bn, List<Rect> b, List<String> problems) {
        for (int i = 0; i < a.size(); i++) {
            for (int j = 0; j < b.size(); j++) {
                if (an.equals(bn) && j <= i) continue;
                if (Rect.intersects(a.get(i), b.get(j))) {
                    problems.add(an + "#" + i + " " + a.get(i).toShortString() + " overlaps " + bn + "#" + j + " " + b.get(j).toShortString());
                }
            }
        }
    }

    // ---------------------------------------------------------------- rendering helpers

    private static int dp(Context c, float v) { return YugiTheme.dp(c, v); }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** A stand-in for the stream so the overlays are judged on a realistic background. */
    private static FrameLayout screen(Activity a, String kind) {
        FrameLayout f = new FrameLayout(a);
        f.addView(new View(a) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                p.setShader(new LinearGradient(0, 0, w, h,
                        "game".equals(kind) ? 0xFF2B2F36 : 0xFF1F3B5C, "game".equals(kind) ? 0xFF0E1013 : 0xFF3A2A4E,
                        Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
                p.setColor(0x22FFFFFF);
                for (int x = 0; x < w; x += w / 12) c.drawLine(x, 0, x, h, p);
                for (int y = 0; y < h; y += h / 8) c.drawLine(0, y, w, y, p);
                p.setColor(0x66FFFFFF);
                p.setTextSize(h / 12f);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("game".equals(kind) ? "GAME STREAM" : "DESKTOP", w / 2f, h / 2f, p);
            }
        }, match());
        return f;
    }

    private static Bitmap thumb(Context c) {
        Bitmap b = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 320, 200, 0xFF4B5563, 0xFF111827, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, 320, 200, p);
        return b;
    }

    private static void layout(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        idle();
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }

    private static void save(View v, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        write(b, name);
    }

    private static void drawKeyboard(View v, int w, int h, int ime, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        v.draw(c);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF202124);
        c.drawRect(0, h - ime, w, h, p);
        p.setColor(0xFF3C4043);
        int rows = 4, cols = 10, gap = 10;
        float kw = (w - gap * (cols + 1)) / (float) cols, kh = (ime - gap * (rows + 1)) / (float) rows;
        for (int r = 0; r < rows; r++) {
            for (int k = 0; k < cols; k++) {
                float x = gap + k * (kw + gap), y = h - ime + gap + r * (kh + gap);
                c.drawRoundRect(x, y, x + kw, y + kh, 12, 12, p);
            }
        }
        p.setColor(Color.WHITE);
        p.setTextSize(Math.min(kh * 0.4f, 48));
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("מקלדת (סימולציה)", w / 2f, h - ime / 2f, p);
        write(b, name);
    }

    private static void write(Bitmap b, String name) throws Exception {
        OUT.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}

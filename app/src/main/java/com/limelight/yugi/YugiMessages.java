package com.limelight.yugi;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;

import com.limelight.R;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * One look for every conversation (Yugi's chat and the server sessions), after ChatGPT / Messages on iPad:
 * Yagel's messages in brand-colored bubbles on the start side, Yugi's as clean text at reading width (no bubble),
 * Markdown with tappable links, APK and image links as cards, system notes as small centered pills.
 * New messages come in on a spring; a long press copies the message.
 */
final class YugiMessages {
    private YugiMessages() {}

    static final int MAX_READING_DP = 680;

    /** A built message; {@link #setText} re-renders it (streaming). */
    static final class Row {
        final LinearLayout view;
        final String role;
        private final LinearLayout body;
        private String text = "";
        private YugiTypingDots dots;

        Row(LinearLayout view, LinearLayout body, String role) { this.view = view; this.body = body; this.role = role; }

        String text() { return text; }

        /** Empty assistant text shows the thinking dots. */
        void setText(String t) {
            if (t == null) t = "";
            if (t.equals(text) && body.getChildCount() > 0) return;
            text = t;
            Context c = view.getContext();
            body.removeAllViews();
            dots = null;
            if ("assistant".equals(role) && t.isEmpty()) {
                dots = new YugiTypingDots(c);
                body.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return;
            }
            if ("user".equals(role)) {
                TextView tv = textView(c, t, YugiTheme.TEXT_ON_BRAND);
                tv.setText(t);
                if (t.isEmpty()) tv.setVisibility(View.GONE);
                body.addView(tv);
                return;
            }
            if ("system".equals(role)) {
                TextView tv = YugiTheme.text(c, t, 12.5f, 500, YugiTheme.TEXT_DIM);
                tv.setGravity(Gravity.CENTER);
                tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                body.addView(tv);
                return;
            }
            YugiMarkdown.Result md = YugiMarkdown.parse(c, t, url -> open(c, url));
            boolean first = true;
            for (YugiMarkdown.Block b : md.blocks) {
                View v;
                if (b.code) {
                    TextView code = YugiTheme.text(c, b.text.toString(), 13.5f, 400, 0xFFE6E8EE);
                    code.setTypeface(android.graphics.Typeface.MONOSPACE);
                    code.setTextDirection(View.TEXT_DIRECTION_LTR);
                    code.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                    code.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                    code.setLineSpacing(YugiTheme.dp(c, 2), 1f);
                    code.setPadding(YugiTheme.dp(c, 14), YugiTheme.dp(c, 12), YugiTheme.dp(c, 14), YugiTheme.dp(c, 12));
                    code.setBackground(YugiTheme.roundedStroke(c, 0xFF0E1218, 12, YugiTheme.STROKE));
                    code.setTextIsSelectable(true);
                    v = code;
                } else {
                    TextView tv = textView(c, null, YugiTheme.TEXT);
                    tv.setText(b.text);
                    tv.setMovementMethod(LinkMovementMethod.getInstance());
                    tv.setFocusable(false);
                    copyOnLongPress(tv, t);
                    v = tv;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (!first) lp.topMargin = YugiTheme.dp(c, 10);
                first = false;
                body.addView(v, lp);
            }
            for (String url : md.cards) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = YugiTheme.dp(c, 10);
                body.addView(YugiMarkdown.isApk(url) ? apkCard(c, url) : imageCard(c, url), lp);
            }
        }

        boolean isThinking() { return dots != null; }
    }

    private static TextView textView(Context c, String s, int color) {
        TextView t = YugiTheme.text(c, s == null ? "" : s, 16, 400, color);
        t.setLineSpacing(0, 1.18f);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        return t;
    }

    /**
     * Builds a message row. {@code attachments} (user messages) go above the bubble.
     * The row's container is RTL, so the start side (Yagel's) is the right.
     */
    static Row build(Context c, String role, String text, List<View> attachments) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setTag("msg:" + role);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int pad = YugiTheme.dp(c, 2);
        if ("user".equals(role)) {
            wrap.setGravity(Gravity.START);
            if (attachments != null) for (View a : attachments) wrap.addView(a);
            body.setBackground(YugiTheme.corners(c, YugiTheme.BRAND, 20, 20, 6, 20)); // tail on the right (RTL start)
            body.setPadding(YugiTheme.dp(c, 15), YugiTheme.dp(c, 10), YugiTheme.dp(c, 15), YugiTheme.dp(c, 11));
            body.setLongClickable(true);
        } else if ("system".equals(role)) {
            wrap.setGravity(Gravity.CENTER_HORIZONTAL);
            body.setBackground(YugiTheme.rounded(c, 0x14FFFFFF, 14));
            body.setPadding(YugiTheme.dp(c, 12), YugiTheme.dp(c, 6), YugiTheme.dp(c, 12), YugiTheme.dp(c, 6));
        } else {
            wrap.setGravity(Gravity.START);
            body.setPadding(YugiTheme.dp(c, 4), pad, YugiTheme.dp(c, 4), pad);
            blp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        wrap.addView(body, blp);
        Row r = new Row(wrap, body, role);
        r.setText(text);
        if ("user".equals(role)) {
            // bubble width: up to 78% of the conversation, never wider than 360dp
            wrap.addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, or, ob) -> {
                int max = Math.min(YugiTheme.dp(c, 360), Math.round((rr - l) * 0.78f));
                for (int i = 0; i < body.getChildCount(); i++) {
                    View ch = body.getChildAt(i);
                    if (ch instanceof TextView && ((TextView) ch).getMaxWidth() != max - body.getPaddingLeft() - body.getPaddingRight()) {
                        int m = max - body.getPaddingLeft() - body.getPaddingRight();
                        ((TextView) ch).setMaxWidth(m);
                    }
                }
            });
            for (int i = 0; i < body.getChildCount(); i++) {
                View ch = body.getChildAt(i);
                if (ch instanceof TextView) {
                    ((TextView) ch).setMaxWidth(YugiTheme.dp(c, 300));
                    copyOnLongPress(ch, text);
                }
            }
        }
        return r;
    }

    /** Space above a message: tighter inside a run from the same side, roomier when the speaker changes. */
    static int gapBefore(Context c, String prevRole, String role) {
        if (prevRole == null) return YugiTheme.dp(c, 8);
        return YugiTheme.dp(c, prevRole.equals(role) ? 8 : 18);
    }

    /** New message: rises 14dp and fades in on a spring (only for messages that arrive, not for history). */
    static void enter(View v) {
        Context c = v.getContext();
        v.setAlpha(0f);
        v.setTranslationY(YugiTheme.dp(c, 14));
        v.setScaleX(0.97f);
        v.setScaleY(0.97f);
        YugiSpring.to(v, DynamicAnimation.ALPHA, 1f);
        YugiSpring.to(v, DynamicAnimation.TRANSLATION_Y, 0f);
        YugiSpring.to(v, DynamicAnimation.SCALE_X, 1f);
        YugiSpring.to(v, DynamicAnimation.SCALE_Y, 1f);
    }

    /** Ends a running entrance at once (screenshots). */
    static void settle(View v) {
        for (DynamicAnimation.ViewProperty p : new DynamicAnimation.ViewProperty[]{
                DynamicAnimation.ALPHA, DynamicAnimation.TRANSLATION_Y, DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y}) {
            androidx.dynamicanimation.animation.SpringAnimation a = YugiSpring.of(v, p);
            if (a.isRunning()) a.skipToEnd();
        }
        v.setAlpha(1f);
        v.setTranslationY(0);
        v.setScaleX(1f);
        v.setScaleY(1f);
    }

    private static void copyOnLongPress(View v, String text) {
        v.setOnLongClickListener(x -> {
            ClipboardManager cm = (ClipboardManager) x.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("יוגי", text));
            x.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            android.widget.Toast.makeText(x.getContext(), "הועתק", android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    static void open(Context c, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {}
    }

    private static String fileName(String url) {
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        String n = p.substring(p.lastIndexOf('/') + 1);
        try { n = java.net.URLDecoder.decode(n, "UTF-8"); } catch (Exception ignored) {}
        return n.isEmpty() ? url : n;
    }

    /** APK link: an install card (icon tile, file name, "tap to download"). */
    static View apkCard(Context c, String url) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setTag("card:apk");
        card.setPadding(YugiTheme.dp(c, 12), YugiTheme.dp(c, 12), YugiTheme.dp(c, 14), YugiTheme.dp(c, 12));
        card.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(YugiTheme.RIPPLE),
                YugiTheme.roundedStroke(c, YugiTheme.CARD, 16, YugiTheme.STROKE),
                YugiTheme.rounded(c, 0xFFFFFFFF, 16)));
        card.setMinimumHeight(YugiTheme.dp(c, 72));
        FrameLayout tile = new FrameLayout(c);
        tile.setBackground(YugiTheme.rounded(c, 0xFF2E7D5B, 12));
        TextView apk = YugiTheme.text(c, "APK", 12, 700, 0xFFFFFFFF);
        apk.setGravity(Gravity.CENTER);
        tile.addView(apk, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(tile, new LinearLayout.LayoutParams(YugiTheme.dp(c, 48), YugiTheme.dp(c, 48)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPaddingRelative(YugiTheme.dp(c, 12), 0, YugiTheme.dp(c, 8), 0);
        TextView name = YugiTheme.text(c, "⁦" + fileName(url) + "⁩", 15, 600, YugiTheme.TEXT);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        texts.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = YugiTheme.text(c, "קובץ התקנה · הקש להורדה", 13, 400, YugiTheme.TEXT_DIM);
        sub.setPadding(0, YugiTheme.dp(c, 4), 0, 0);
        sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        texts.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView dl = new ImageView(c);
        dl.setImageResource(R.drawable.yugi_ic_back);
        dl.setRotation(90); // arrow down
        dl.setImageTintList(android.content.res.ColorStateList.valueOf(YugiTheme.LINK));
        dl.setScaleType(ImageView.ScaleType.CENTER);
        card.addView(dl, new LinearLayout.LayoutParams(YugiTheme.dp(c, 32), YugiTheme.dp(c, 32)));
        card.setContentDescription("הורדת " + fileName(url));
        card.setOnClickListener(v -> open(c, url));
        return wrapMax(c, card, 420);
    }

    private static final OkHttpClient imgHttp = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();
    private static final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    /** Tests render without network. */
    static boolean offline;

    /** Image link: a rounded preview card that loads in the background; tap opens it. */
    static View imageCard(Context c, String url) {
        FrameLayout card = new FrameLayout(c);
        card.setTag("card:image");
        card.setBackground(YugiTheme.roundedStroke(c, YugiTheme.CARD, 16, YugiTheme.STROKE));
        card.setClipToOutline(true);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView label = YugiTheme.text(c, "⁦" + fileName(url) + "⁩", 12.5f, 500, YugiTheme.TEXT);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        label.setPadding(YugiTheme.dp(c, 10), YugiTheme.dp(c, 5), YugiTheme.dp(c, 10), YugiTheme.dp(c, 5));
        label.setBackground(YugiTheme.rounded(c, 0x99000000, 12));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        llp.setMargins(YugiTheme.dp(c, 8), 0, YugiTheme.dp(c, 8), YugiTheme.dp(c, 8));
        card.addView(label, llp);
        card.setContentDescription("תמונה " + fileName(url));
        card.setClickable(true);
        card.setOnClickListener(v -> open(c, url));
        Bitmap cached = images.get(url);
        if (cached != null) iv.setImageBitmap(cached);
        else if (!offline) load(url, iv);
        View w = wrapMax(c, card, 360);
        w.setMinimumHeight(YugiTheme.dp(c, 180));
        card.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, YugiTheme.dp(c, 180)));
        return w;
    }

    private static void load(String url, ImageView into) {
        Handler h = new Handler(Looper.getMainLooper());
        imgHttp.newCall(new Request.Builder().url(url).build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {}
            @Override public void onResponse(Call call, Response r) {
                try (Response res = r) {
                    if (!res.isSuccessful() || res.body() == null) return;
                    byte[] data = res.body().bytes();
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    int s = 1;
                    while (Math.max(o.outWidth, o.outHeight) / s > 1280) s *= 2;
                    o = new BitmapFactory.Options();
                    o.inSampleSize = s;
                    Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    if (b == null) return;
                    images.put(url, b);
                    h.post(() -> {
                        into.setAlpha(0f);
                        into.setImageBitmap(b);
                        YugiSpring.to(into, DynamicAnimation.ALPHA, 1f);
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    /** Caps a card's width (dp) inside a full-width row, on the start side. */
    private static View wrapMax(Context c, View card, int maxDp) {
        FrameLayout f = new FrameLayout(c) {
            @Override protected void onMeasure(int w, int h) {
                int max = YugiTheme.dp(c, maxDp);
                int ws = MeasureSpec.getSize(w);
                if (ws > max) w = MeasureSpec.makeMeasureSpec(max, MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED ? MeasureSpec.AT_MOST : MeasureSpec.getMode(w));
                super.onMeasure(w, h);
            }
        };
        if (card.getLayoutParams() == null) {
            card.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        f.addView(card);
        return f;
    }

    @SuppressWarnings("unused")
    static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }
}

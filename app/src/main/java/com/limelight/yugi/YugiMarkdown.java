package com.limelight.yugi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bit of Markdown Yugi actually writes, as Android spans: **bold**, *italic*, `code`, headings, bullet and
 * numbered lists (markers drawn on the paragraph's start side, so Hebrew lists sit on the right), quotes,
 * fenced code blocks, [links](url) and bare URLs (tappable, bidi-isolated inside RTL text). APK and image links
 * are also returned as cards.
 */
final class YugiMarkdown {
    private YugiMarkdown() {}

    interface LinkHandler { void open(String url); }

    static final class Block {
        final boolean code;
        final CharSequence text;
        Block(boolean code, CharSequence text) { this.code = code; this.text = text; }
    }

    static final class Result {
        final List<Block> blocks = new ArrayList<>();
        final List<String> cards = new ArrayList<>(); // APK / image URLs, in order, no duplicates
    }

    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*"                                   // 1 bold
            + "|`([^`\\n]+)`"                                    // 2 code
            + "|\\[([^\\]\\n]+)\\]\\((https?://[^)\\s]+)\\)"      // 3,4 link
            + "|(https?://[^\\s<>\"'\\u2066\\u2069]+)"           // 5 bare url
            + "|(?<![\\w*])\\*(?![\\s*])([^*\\n]+?)(?<!\\s)\\*(?![\\w*])"); // 6 italic
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*•]\\s+(.*)$");
    private static final Pattern NUMBER = Pattern.compile("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.*)$");

    static boolean isApk(String url) { return path(url).endsWith(".apk"); }

    static boolean isImage(String url) {
        String p = path(url);
        return p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".webp") || p.endsWith(".gif");
    }

    private static String path(String url) {
        String u = url.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        return u;
    }

    static Result parse(Context c, String src, LinkHandler links) {
        Result r = new Result();
        String[] lines = src.replace("\r\n", "\n").split("\n", -1);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        StringBuilder code = null;
        boolean blank = false;
        for (String raw : lines) {
            if (raw.trim().startsWith("```")) {
                if (code == null) {
                    code = new StringBuilder();
                    flush(r, sb);
                    sb = new SpannableStringBuilder();
                } else {
                    r.blocks.add(new Block(true, code.toString().replaceAll("\\n$", "")));
                    code = null;
                }
                continue;
            }
            if (code != null) { code.append(raw).append('\n'); continue; }
            if (raw.trim().isEmpty()) { blank = sb.length() > 0; continue; }
            if (sb.length() > 0) sb.append(blank ? "\n\n" : "\n");
            blank = false;
            int start = sb.length();
            Matcher m;
            if ((m = HEADING.matcher(raw)).matches()) {
                inline(c, sb, m.group(2), r, links);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                float size = m.group(1).length() <= 1 ? 1.25f : m.group(1).length() == 2 ? 1.15f : 1.05f;
                sb.setSpan(new RelativeSizeSpan(size), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if ((m = BULLET.matcher(raw)).matches()) {
                int level = Math.min(3, m.group(1).length() / 2);
                inline(c, sb, m.group(2), r, links);
                sb.setSpan(new Marker(c, null, level), start, sb.length(), Spanned.SPAN_PARAGRAPH);
            } else if ((m = NUMBER.matcher(raw)).matches()) {
                int level = Math.min(3, m.group(1).length() / 2);
                inline(c, sb, m.group(3), r, links);
                sb.setSpan(new Marker(c, m.group(2) + ".", level), start, sb.length(), Spanned.SPAN_PARAGRAPH);
            } else if (raw.startsWith(">")) {
                inline(c, sb, raw.replaceFirst("^>\\s?", ""), r, links);
                sb.setSpan(new Quote(c), start, sb.length(), Spanned.SPAN_PARAGRAPH);
                sb.setSpan(new ForegroundColorSpan(YugiTheme.TEXT_DIM), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                inline(c, sb, raw, r, links);
            }
        }
        if (code != null) r.blocks.add(new Block(true, code.toString().replaceAll("\\n$", "")));
        flush(r, sb);
        return r;
    }

    private static void flush(Result r, SpannableStringBuilder sb) {
        if (sb.length() > 0) r.blocks.add(new Block(false, sb));
    }

    private static void inline(Context c, SpannableStringBuilder sb, String s, Result r, LinkHandler links) {
        Matcher m = INLINE.matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            int a = sb.length();
            if (m.group(1) != null) {
                inline(c, sb, m.group(1), r, links);
                sb.setSpan(new StyleSpan(Typeface.BOLD), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null) {
                sb.append('⁦').append(m.group(2)).append('⁩');
                sb.setSpan(new TypefaceSpan("monospace"), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(0xFFE3C9FF), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(3) != null) {
                link(sb, m.group(3), m.group(4), r, links);
            } else if (m.group(5) != null) {
                String url = m.group(5);
                String tail = "";
                while (!url.isEmpty() && ".,;:!?)]}׳״".indexOf(url.charAt(url.length() - 1)) >= 0) {
                    tail = url.charAt(url.length() - 1) + tail;
                    url = url.substring(0, url.length() - 1);
                }
                link(sb, "⁦" + url + "⁩", url, r, links);
                sb.append(tail);
            } else if (m.group(6) != null) {
                inline(c, sb, m.group(6), r, links);
                sb.setSpan(new StyleSpan(Typeface.ITALIC), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        sb.append(s, last, s.length());
    }

    private static void link(SpannableStringBuilder sb, String label, String url, Result r, LinkHandler links) {
        int a = sb.length();
        sb.append(label);
        sb.setSpan(new Link(url, links), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if ((isApk(url) || isImage(url)) && !r.cards.contains(url)) r.cards.add(url);
    }

    static final class Link extends ClickableSpan {
        final String url;
        private final LinkHandler handler;
        Link(String url, LinkHandler handler) { this.url = url; this.handler = handler; }
        @Override public void onClick(View w) { if (handler != null) handler.open(url); }
        @Override public void updateDrawState(TextPaint p) {
            p.setColor(YugiTheme.LINK);
            p.setUnderlineText(false);
        }
    }

    /** List marker (dot or "1.") drawn in the paragraph's start margin, in either direction. */
    static final class Marker implements LeadingMarginSpan {
        private final String number;
        private final int level;
        private final float width, dot;
        Marker(Context c, String number, int level) {
            this.number = number;
            this.level = level;
            width = YugiTheme.dp(c, number == null ? 20 : 26);
            dot = YugiTheme.dp(c, 2.6f);
        }
        @Override public int getLeadingMargin(boolean first) { return (int) (width * (level + 1)); }
        @Override public void drawLeadingMargin(Canvas cv, Paint p, int x, int dir, int top, int baseline, int bottom,
                                                CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!first || !(text instanceof Spanned) || ((Spanned) text).getSpanStart(this) != start) return;
            int oldColor = p.getColor();
            Paint.Style oldStyle = p.getStyle();
            Paint.Align oldAlign = p.getTextAlign();
            float edge = x + dir * width * level; // this level's outer edge
            if (number == null) {
                p.setColor(YugiTheme.TEXT_DIM);
                p.setStyle(level == 0 ? Paint.Style.FILL : Paint.Style.STROKE);
                float cy = baseline - p.getTextSize() * 0.33f;
                cv.drawCircle(edge + dir * width * 0.4f, cy, dot, p);
            } else {
                p.setColor(YugiTheme.TEXT_DIM);
                p.setTextAlign(dir > 0 ? Paint.Align.LEFT : Paint.Align.RIGHT);
                cv.drawText(number, edge, baseline, p);
            }
            p.setColor(oldColor);
            p.setStyle(oldStyle);
            p.setTextAlign(oldAlign);
        }
    }

    /** Quote: a thin bar on the start side. */
    static final class Quote implements LeadingMarginSpan {
        private final int width, bar;
        Quote(Context c) { width = YugiTheme.dp(c, 14); bar = Math.max(2, YugiTheme.dp(c, 3)); }
        @Override public int getLeadingMargin(boolean first) { return width; }
        @Override public void drawLeadingMargin(Canvas cv, Paint p, int x, int dir, int top, int baseline, int bottom,
                                                CharSequence text, int start, int end, boolean first, Layout layout) {
            int old = p.getColor();
            Paint.Style st = p.getStyle();
            p.setColor(0x40FFFFFF);
            p.setStyle(Paint.Style.FILL);
            float l = dir > 0 ? x : x - bar;
            cv.drawRect(l, top, l + bar, bottom, p);
            p.setColor(old);
            p.setStyle(st);
        }
    }
}

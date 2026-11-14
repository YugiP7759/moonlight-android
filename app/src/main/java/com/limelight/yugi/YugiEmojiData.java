package com.limelight.yugi;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The emoji the picker offers, by category, with their skin-tone variants and English and Hebrew
 * keywords (CLDR annotations), read from {@code assets/yugi/emoji.tsv} (made by tools/emoji/gen_emoji_data.py).
 * <p>
 * Loaded once, the first time the picker opens ({@link #get}); {@link #prefetch} starts that load on a
 * background thread when the emoji key goes down, so the picker usually finds it ready.
 * Recents and the chosen skin tone of each emoji live in {@link Prefs}.
 */
public final class YugiEmojiData {
    public static final int CATEGORIES = 9; // smileys, people, nature, food, activities, travel, objects, symbols, flags
    public static final String ASSET = "yugi/emoji.tsv";

    /** The emoji, in Unicode order (categories are contiguous). */
    public final String[] emoji;
    /** Five skin-tone variants (light to dark), or null. */
    public final String[][] tones;
    /** First index of each category, and {@code emoji.length} at the end. */
    public final int[] catStart = new int[CATEGORIES + 1];
    private final String[][] nameWords;   // normalized words of the English and Hebrew names
    private final String[][] keyWords;    // normalized keyword words
    private final String[] names;         // normalized full names ("en\nhe")

    private static YugiEmojiData loaded;

    /** Emoji this device can draw as one glyph (all, until {@link #keepOnly}), and their indices per category. */
    private final boolean[] shown;
    private int[][] byCat;

    /** Whether the device's font draws an emoji (sequence) as one glyph. */
    public interface Glyphs { boolean has(String s); }

    private YugiEmojiData(List<String[]> rows) {
        int n = rows.size();
        emoji = new String[n];
        tones = new String[n][];
        nameWords = new String[n][];
        keyWords = new String[n][];
        names = new String[n];
        Arrays.fill(catStart, -1);
        int lastCat = -1;
        for (int i = 0; i < n; i++) {
            String[] f = rows.get(i);
            int cat = Integer.parseInt(f[0]);
            while (lastCat < cat) catStart[++lastCat] = i;
            emoji[i] = f[1];
            tones[i] = f[2].isEmpty() ? null : f[2].split(" ");
            String en = f.length > 3 ? f[3] : "", enK = f.length > 4 ? f[4] : "";
            String he = f.length > 5 ? f[5] : "", heK = f.length > 6 ? f[6] : "";
            names[i] = normalize(en) + "\n" + normalize(he);
            nameWords[i] = words(en + " " + he);
            keyWords[i] = words(enK.replace('|', ' ') + " " + heK.replace('|', ' '));
        }
        while (lastCat < CATEGORIES) catStart[++lastCat] = n;
        shown = new boolean[n];
        Arrays.fill(shown, true);
        index();
    }

    private void index() {
        byCat = new int[CATEGORIES][];
        for (int c = 0; c < CATEGORIES; c++) {
            int k = 0;
            for (int i = catStart[c]; i < catStart[c + 1]; i++) if (shown[i]) k++;
            byCat[c] = new int[k];
            k = 0;
            for (int i = catStart[c]; i < catStart[c + 1]; i++) if (shown[i]) byCat[c][k++] = i;
        }
    }

    /**
     * Hides emoji the font can't draw (an older phone, a newer emoji: no empty boxes, no ZWJ sequences
     * falling apart), and skin tones it lacks.
     */
    public void keepOnly(Glyphs g) {
        for (int i = 0; i < emoji.length; i++) {
            shown[i] = g.has(emoji[i]);
            if (tones[i] != null) {
                for (String t : tones[i]) if (!g.has(t)) { tones[i] = null; break; }
            }
        }
        index();
    }

    /** Emoji shown in a category, and the data index of the i-th. */
    public int catCount(int c) { return byCat[c].length; }
    public int catItem(int c, int i) { return byCat[c][i]; }
    public boolean isShown(int i) { return shown[i]; }

    /** Parses the asset format (tests feed it directly). */
    public static YugiEmojiData parse(InputStream in) throws IOException {
        List<String[]> rows = new ArrayList<>(2000);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length >= 3) rows.add(f);
        }
        return new YugiEmojiData(rows);
    }

    /** The data, loading it on first use (blocks while a {@link #prefetch} is still reading). */
    public static synchronized YugiEmojiData get(Context c) {
        if (loaded == null) {
            YugiEmojiData d;
            try (InputStream in = c.getApplicationContext().getAssets().open(ASSET)) {
                d = parse(in);
            } catch (IOException e) {
                d = new YugiEmojiData(new ArrayList<>());
            }
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.graphics.Paint p = new android.graphics.Paint();
                // a font that reports no glyph even for 😀 can't be asked (keep everything)
                if (p.hasGlyph("😀")) d.keepOnly(p::hasGlyph);
            }
            loaded = d;
        }
        return loaded;
    }

    /** Starts loading in the background (the emoji key went down). */
    public static void prefetch(Context c) {
        synchronized (YugiEmojiData.class) {
            if (loaded != null) return;
        }
        Context app = c.getApplicationContext();
        Thread t = new Thread(() -> get(app), "yugi-emoji-load");
        t.setDaemon(true);
        t.start();
    }

    public int size() { return emoji.length; }

    public int indexOf(String e) {
        if (e == null) return -1;
        for (int i = 0; i < emoji.length; i++) if (emoji[i].equals(e)) return i;
        return -1;
    }

    /** Index of the base emoji of {@code e} (itself, or the emoji whose skin-tone variant it is), or -1. */
    public int baseOf(String e) {
        int i = indexOf(e);
        if (i >= 0) return i;
        for (int j = 0; j < tones.length; j++) {
            if (tones[j] == null) continue;
            for (String t : tones[j]) if (t.equals(e)) return j;
        }
        return -1;
    }

    // ---------------------------------------------------------------- search

    /** Everyday emoji: first among equally good matches ("heart" is ❤️ before 💘). */
    private static final java.util.Set<String> POPULAR = new java.util.HashSet<>(Arrays.asList(
            "❤️", "😂", "🤣", "👍", "🙏", "😍", "😊", "🥰", "😘", "🔥", "😭", "🥺", "✨", "🎉", "😁", "👏",
            "💪", "😎", "🤔", "🙌", "😅", "😉", "💯", "🌹", "🎂", "☀️", "⭐", "🌙", "🐶", "🐱", "🍕", "☕",
            "🎁", "✅", "❌", "👋", "😢", "😡", "🤗", "🙂", "😀", "🇮🇱", "🌸", "🍰", "🎈", "💐", "🥳", "😴"));

    /**
     * Emoji matching a query in English or Hebrew: every word of the query must start a word of the
     * emoji's name or keywords. Best first: the whole name; then names that hold the query's words
     * whole; then names that start with them; then keywords. Among equals: everyday emoji, shorter
     * names, Unicode order. Case, niqqud, final letters and punctuation don't matter.
     */
    public int[] search(String query, int max) {
        String[] q = words(query);
        if (q.length == 0) return new int[0];
        String whole = normalize(query).trim();
        int n = emoji.length;
        List<long[]> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!shown[i]) continue;
            int level = match(i, q, whole);
            if (level < 0) continue;
            long key = ((long) level << 40) | ((long) (POPULAR.contains(emoji[i]) ? 0 : 1) << 36)
                    | ((long) Math.min(15, nameWords[i].length) << 24) | i;
            hits.add(new long[]{key, i});
        }
        java.util.Collections.sort(hits, (a, b) -> Long.compare(a[0], b[0]));
        int[] out = new int[Math.min(hits.size(), max)];
        for (int k = 0; k < out.length; k++) out[k] = (int) hits.get(k)[1];
        return out;
    }

    /** 0 whole name, 1 the name holds every query word, 2 the name starts them, 3 keywords, -1 no match. */
    private int match(int i, String[] q, String whole) {
        String nm = names[i];
        int nl = nm.indexOf('\n');
        if (whole.equals(nm.substring(0, nl)) || whole.equals(nm.substring(nl + 1))) return 0;
        boolean inName = true, exact = true;
        for (String w : q) {
            boolean name = prefixOfAny(w, nameWords[i]);
            if (!name) inName = false;
            if (!name && !prefixOfAny(w, keyWords[i])) return -1;
            if (exact && !Arrays.asList(nameWords[i]).contains(w)) exact = false;
        }
        if (!inName) return 3;
        return exact ? 1 : 2;
    }

    private static boolean prefixOfAny(String w, String[] words) {
        for (String x : words) if (x.startsWith(w)) return true;
        return false;
    }

    static String[] words(String s) {
        String n = normalize(s);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String w : n.split(" ")) if (!w.isEmpty()) out.add(w);
        return out.toArray(new String[0]);
    }

    /** Lower case, Hebrew without niqqud and final forms, punctuation as spaces. */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        String low = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < low.length(); i++) {
            char c = low.charAt(i);
            if (c >= 0x0591 && c <= 0x05C7 && c != 0x05BE) continue; // niqqud and cantillation
            switch (c) {
                case 'ך': c = 'כ'; break;
                case 'ם': c = 'מ'; break;
                case 'ן': c = 'נ'; break;
                case 'ף': c = 'פ'; break;
                case 'ץ': c = 'צ'; break;
                case '\'': case '"': case '׳': case '״': case '’': case '‘': case '“': case '”':
                    continue; // צ'יפס = ציפס
                default: break;
            }
            if (Character.isLetterOrDigit(c) || c == '+' || c == '#' || c == '*') b.append(c);
            else b.append(' ');
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- recents and skin tones

    /** Recently used emoji (newest first) and the skin tone chosen for each emoji, kept on the device. */
    public static final class Prefs {
        public static final int MAX_RECENTS = 32;
        private static final String FILE = "yugi_emoji";
        private final SharedPreferences sp;
        private final List<String> recents = new ArrayList<>();

        public Prefs(Context c) {
            sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
            String r = sp.getString("recents", "");
            for (String e : r.split("\n")) if (!e.isEmpty()) recents.add(e);
        }

        public List<String> recents() { return recents; }

        public void used(String e) {
            recents.remove(e);
            recents.add(0, e);
            while (recents.size() > MAX_RECENTS) recents.remove(recents.size() - 1);
            sp.edit().putString("recents", String.join("\n", recents)).apply();
        }

        /** The variant shown for a base emoji (its last chosen skin tone, or the base itself). */
        public String variant(String base) { return sp.getString("t:" + base, base); }

        public void setVariant(String base, String chosen) {
            if (chosen.equals(base)) sp.edit().remove("t:" + base).apply();
            else sp.edit().putString("t:" + base, chosen).apply();
        }
    }
}

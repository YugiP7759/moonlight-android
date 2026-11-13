package com.limelight.yugi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** The emoji asset and the search, without Android. */
public class YugiEmojiDataTest {
    private static YugiEmojiData d;

    @BeforeClass public static void load() throws Exception {
        File f = new File("src/main/assets/" + YugiEmojiData.ASSET);
        long size = f.length();
        assertTrue("asset of reasonable size: " + size, size > 100_000 && size < 600_000);
        try (InputStream in = new FileInputStream(f)) {
            d = YugiEmojiData.parse(in);
        }
    }

    private static int first(String q) {
        int[] r = d.search(q, 50);
        assertTrue("results for " + q, r.length > 0);
        return r[0];
    }

    private static boolean has(String q, String emoji) {
        for (int i : d.search(q, 300)) if (d.emoji[i].equals(emoji)) return true;
        return false;
    }

    @Test public void categoriesInOrder() {
        assertTrue(d.size() > 1800);
        for (int c = 0; c < YugiEmojiData.CATEGORIES; c++) {
            assertTrue("category " + c + " not empty", d.catStart[c + 1] > d.catStart[c]);
        }
        assertEquals(d.size(), d.catStart[YugiEmojiData.CATEGORIES]);
        assertEquals("😀", d.emoji[d.catStart[0]]);
        assertTrue(d.indexOf("👍") >= d.catStart[1] && d.indexOf("👍") < d.catStart[2]);
        assertTrue(d.indexOf("🇮🇱") >= d.catStart[8]);
    }

    @Test public void skinTones() {
        String[] t = d.tones[d.indexOf("👍")];
        assertArrayEquals(new String[]{"👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿"}, t);
        assertNotNull(d.tones[d.indexOf("🧑‍💻")]);
        assertNull(d.tones[d.indexOf("😀")]);
        assertEquals(d.indexOf("👍"), d.baseOf("👍🏽"));
        // no tone variant shows up as an emoji of its own
        assertEquals(-1, d.indexOf("👍🏽"));
    }

    @Test public void searchEnglish() {
        assertEquals("👍", d.emoji[first("thumbs up")]);
        assertEquals("🐱", d.emoji[first("cat face")]);
        assertTrue(has("heart", "❤️"));
        assertTrue(has("HEA", "❤️"));
        assertTrue(has("israel", "🇮🇱"));
        assertTrue(has("pizza", "🍕"));
        assertEquals(0, d.search("", 10).length);
        assertEquals(0, d.search("   ", 10).length);
        assertEquals(0, d.search("zzqqxx", 10).length);
    }

    @Test public void searchHebrew() {
        assertTrue(has("לב", "❤️"));
        assertTrue(has("חתול", "🐱"));
        assertTrue(has("חתו", "🐱"));       // prefix, with ת in the middle
        assertTrue(has("ישראל", "🇮🇱"));
        assertTrue(has("פיצה", "🍕"));
        assertTrue(has("אגודל", "👍"));
        assertTrue(has("לייק", "👍"));
        // final letters typed mid-word, and niqqud, don't matter
        assertTrue(has("ישראלּ", "🇮🇱"));
        assertTrue(has("חיוך", "😀"));
        assertTrue(has("חיוכ", "😀"));
    }

    @Test public void searchIsFast() {
        long t = System.nanoTime();
        for (String q : new String[]{"a", "he", "hea", "heart", "ל", "לב", "שמ", "שמש"}) d.search(q, 300);
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue("8 searches took " + ms + " ms", ms < 1500);
    }
}

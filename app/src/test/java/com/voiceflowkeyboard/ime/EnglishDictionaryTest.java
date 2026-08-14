package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Phase 2 gate for English autocorrect: does the generated lexicon load with
 * the shape the corrector assumes, and do the lookups the hot path depends on
 * actually hold on the real 82k-word asset?
 *
 * <p>The asset is read straight off disk rather than through AssetManager, so
 * these run as plain JVM tests with no Android framework involved.
 */
public class EnglishDictionaryTest {

    private static EnglishDictionary dictionary;

    @BeforeClass
    public static void loadDictionary() throws IOException {
        File asset = new File("src/main/assets/english_dict.txt");
        assertTrue(
                "Missing " + asset.getAbsolutePath() + " — run: node scripts/build-english-dict.mjs",
                asset.isFile()
        );
        try (InputStream stream = new FileInputStream(asset)) {
            dictionary = EnglishDictionary.load(stream);
        }
    }

    @Test
    public void dictionaryLoadsExpectedShape() {
        assertEquals(82834, dictionary.size());
        assertEquals(28, dictionary.maxWordLength());
        assertEquals(1000, dictionary.logScale());
    }

    @Test
    public void ordinaryWordsArePresentAndTyposAreNot() {
        assertTrue(dictionary.contains("the"));
        assertTrue(dictionary.contains("keyboard"));
        assertTrue(dictionary.contains("separate"));
        assertTrue(dictionary.contains("definitely"));
        // These are handled by the explicit typo table, never by lookup. If any
        // of them ever enters the lexicon, silent correction stops firing for
        // the most common typos there are.
        assertFalse(dictionary.contains("teh"));
        assertFalse(dictionary.contains("dont"));
        assertFalse(dictionary.contains("im"));
        assertFalse(dictionary.contains("seperate"));
    }

    /**
     * The upstream data ships a typo, {@code you'v}, and is missing the word it
     * was meant to be. The generator drops one and adds the other; if a future
     * regeneration loses that fixup, autocorrect would start "correcting"
     * you've into a non-word.
     */
    @Test
    public void upstreamContractionDefectIsRepaired() {
        assertTrue(dictionary.contains("you've"));
        assertFalse(dictionary.contains("you'v"));
    }

    @Test
    public void contractionsSurvivedTheApostropheFilter() {
        assertTrue(dictionary.contains("don't"));
        assertTrue(dictionary.contains("can't"));
        assertTrue(dictionary.contains("o'clock"));
        assertTrue(dictionary.contains("i'm"));
    }

    /**
     * The generator sorts by UTF-16 code unit so this binary search works. A
     * locale-aware sort would put "don't" after "donate" and every apostrophe
     * word would silently stop resolving.
     */
    @Test
    public void apostropheWordsSortBeforePlainLetters() {
        int contraction = dictionary.indexOf("don't");
        int plain = dictionary.indexOf("donate");
        assertTrue(contraction >= 0);
        assertTrue(plain >= 0);
        assertTrue("don't must sort before donate", contraction < plain);
    }

    @Test
    public void wholeAssetIsStrictlyAscending() {
        for (int i = 1; i < dictionary.size(); i++) {
            String previous = dictionary.wordAt(i - 1);
            String current = dictionary.wordAt(i);
            assertTrue(
                    "Not ascending at " + i + ": " + previous + " then " + current,
                    previous.compareTo(current) < 0
            );
        }
    }

    @Test
    public void commonWordsScoreAboveRareOnes() {
        assertTrue(dictionary.logFrequency("the") > dictionary.logFrequency("keyboard"));
        assertTrue(dictionary.logFrequency("keyboard") > dictionary.logFrequency("zyuganov"));
        // Stored as scaled natural log of a probability, so always negative.
        assertTrue(dictionary.logFrequency("the") < 0);
    }

    @Test
    public void missingWordReportsNotFound() {
        assertEquals(EnglishDictionary.NOT_FOUND, dictionary.logFrequency("qqqqqqzz"));
        assertEquals(EnglishDictionary.NOT_FOUND, dictionary.logFrequency(""));
        assertEquals(EnglishDictionary.NOT_FOUND, dictionary.logFrequency(null));
    }

    /**
     * The corrector scans exactly one first-letter bucket, so the bucket bounds
     * are load-bearing: too wide wastes the scan budget, too narrow silently
     * loses candidates.
     */
    @Test
    public void firstLetterBucketsCoverTheirLetterExactly() {
        int total = 0;
        for (char letter = 'a'; letter <= 'z'; letter++) {
            int start = dictionary.bucketStart(letter);
            int end = dictionary.bucketEnd(letter);
            assertTrue("Empty bucket for " + letter, start >= 0 && end > start);
            assertEquals(letter, dictionary.wordAt(start).charAt(0));
            assertEquals(letter, dictionary.wordAt(end - 1).charAt(0));
            total += end - start;
        }
        // No word in this lexicon begins with an apostrophe, so the 26 letter
        // buckets should partition the whole asset with nothing left over.
        assertEquals(dictionary.size(), total);
    }

    @Test
    public void bucketsRejectNonLetters() {
        assertEquals(-1, dictionary.bucketStart('\''));
        assertEquals(-1, dictionary.bucketEnd('1'));
        assertEquals(-1, dictionary.bucketStart('A'));
    }

    @Test
    public void completionsAreFrequencyOrderedAndExcludeThePrefix() {
        List<String> completions = dictionary.completionsFor("keyboar", 3);
        assertTrue(completions.contains("keyboard"));
        assertFalse(completions.contains("keyboar"));

        List<String> fromThe = dictionary.completionsFor("the", 3);
        assertEquals(3, fromThe.size());
        assertFalse("the exact prefix is not a completion", fromThe.contains("the"));
        for (String completion : fromThe) {
            assertTrue(completion.startsWith("the"));
            assertTrue(completion.length() > 3);
        }
        // Ranked by frequency, so the commonest survivor leads.
        assertEquals("they", fromThe.get(0));
        assertTrue(
                dictionary.logFrequency("they") > dictionary.logFrequency("their"));
    }

    @Test
    public void completionsHandleMissingPrefixAndZeroLimit() {
        assertTrue(dictionary.completionsFor("qqqqqq", 3).isEmpty());
        assertTrue(dictionary.completionsFor("the", 0).isEmpty());
        assertTrue(dictionary.completionsFor("", 3).isEmpty());
        assertTrue(dictionary.completionsFor(null, 3).isEmpty());
    }

    @Test
    public void malformedRowsAreSkippedRatherThanFailingTheLoad() throws IOException {
        String asset = String.join("\n",
                "#v1",
                "#words=3",
                "#logscale=1000",
                "#maxword=5",
                "alpha\t-1000",
                "broken-no-tab",
                "gamma\tnot-a-number",
                "\tleading-tab",
                "omega\t-2000",
                ""
        );
        EnglishDictionary partial = EnglishDictionary.load(
                new ByteArrayInputStream(asset.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, partial.size());
        assertTrue(partial.contains("alpha"));
        assertTrue(partial.contains("omega"));
        assertFalse(partial.contains("gamma"));
    }

    @Test
    public void missingHeadersFallBackRatherThanCollapsing() throws IOException {
        String asset = String.join("\n", "alpha\t-1000", "epsilon\t-2000", "");
        EnglishDictionary headerless = EnglishDictionary.load(
                new ByteArrayInputStream(asset.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, headerless.size());
        // Measured from the data when the header is absent.
        assertEquals(7, headerless.maxWordLength());
        // A zero scale would make every log probability infinite downstream.
        assertNotEquals(0, headerless.logScale());
        assertEquals(1000, headerless.logScale());
    }

    /**
     * Not a benchmark, just a tripwire: the asset loads once per process on a
     * phone, and a regression that made it seconds-slow would be felt.
     */
    @Test
    public void assetLoadsQuickly() throws IOException {
        File asset = new File("src/main/assets/english_dict.txt");
        long start = System.nanoTime();
        try (InputStream stream = new FileInputStream(asset)) {
            EnglishDictionary.load(stream);
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        // Printed so the number is recoverable from the test report; a device
        // figure has to come from Phase 4, since ART is not the JVM.
        System.out.println("english_dict.txt JVM load: " + millis + " ms");
        assertTrue("Dictionary load took " + millis + " ms", millis < 2000);
    }
}

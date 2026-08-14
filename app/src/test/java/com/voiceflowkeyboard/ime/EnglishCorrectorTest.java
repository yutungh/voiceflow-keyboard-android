package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 gate for English autocorrect: against the real 82k lexicon, does the
 * corrector put the intended word first, and — the part that actually matters —
 * does it keep its hands off text that was already correct?
 *
 * <p>Precision is weighted far above recall throughout. A missed correction is
 * invisible; a wrong silent one rewrites what someone meant to say.
 */
public class EnglishCorrectorTest {

    private static EnglishDictionary dictionary;
    private static EnglishCorrector corrector;

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
        corrector = new EnglishCorrector(dictionary);
    }

    private static String best(String typed) {
        EnglishCorrector.Result result = corrector.suggest(typed, 3);
        return result.isEmpty() ? "" : result.words.get(0);
    }

    // ---------------------------------------------------------------- typos

    /**
     * The gate. Ordinary single-slip typos, each with the word that was meant.
     * All are reachable without changing the first letter, which is the one
     * class of error this engine deliberately cannot see.
     */
    @Test
    public void everydayTyposCorrectToTheIntendedWord() {
        String[][] corpus = {
                {"speling", "spelling"},
                {"tomorow", "tomorrow"},
                {"begining", "beginning"},
                {"neccessary", "necessary"},
                {"occured", "occurred"},
                {"reccomend", "recommend"},
                {"tommorrow", "tomorrow"},
                {"untill", "until"},
                {"wich", "which"},
                {"woudl", "would"},
                {"thnak", "thank"},
                {"jsut", "just"},
                {"witht", "with"},
                {"acount", "account"},
                {"adress", "address"},
                {"answe", "answer"},
                {"appreciat", "appreciate"},
                {"recieve", "receive"},
                {"seperate", "separate"},
        };
        List<String> misses = new ArrayList<>();
        for (String[] row : corpus) {
            String got = best(row[0]);
            if (!row[1].equals(got)) {
                misses.add(row[0] + " -> " + (got.isEmpty() ? "(nothing)" : got) + ", wanted " + row[1]);
            }
        }
        assertTrue("Top candidate wrong for: " + misses, misses.isEmpty());
    }

    @Test
    public void transpositionCountsAsOneEdit() {
        // Damerau, not plain Levenshtein: "teh" is one swap from "the", not two
        // substitutions, which is what keeps it ahead of every other candidate.
        assertEquals("the", best("teh"));
        assertEquals("receive", best("recieve"));
    }

    /**
     * Cases where two real words are each one edit away and frequency decides.
     * These are pinned not because the answer is obviously right but because it
     * is a judgement the model is making, and a change to the scoring constants
     * should have to notice it. Resolving them properly needs sentence context,
     * which this engine deliberately does not have.
     */
    @Test
    public void ambiguousInputsResolveByFrequency() {
        // "ther": dropped the 'e' of "there", or typed a stray 'r' after "the"?
        assertEquals("the", best("ther"));
        // "fomr": swapped m and r in "form", or a stray m in "for"?
        assertEquals("for", best("fomr"));
        // Neither may be imposed silently — the runner-up is too close.
        assertFalse(corrector.suggest("ther", 3).autoAccept);
    }

    /**
     * Two limits worth stating out loud rather than discovering later.
     */
    @Test
    public void knownOutOfScopeInputs() {
        // "abut" is a real word. The precision guard refuses to touch it, even
        // though the user very likely meant "about".
        assertTrue(dictionary.contains("abut"));
        assertTrue(corrector.suggest("abut", 3).isEmpty());

        // "alot" wants "a lot", which needs word splitting. We have no such
        // thing, so whatever comes back is a single word and not the answer.
        assertFalse("allot".equals(best("alot")));
    }

    /** Adjacency has to break a tie between two equally-close real words. */
    @Test
    public void keyboardAdjacencyBreaksTiesBetweenEqualEdits() {
        // "hime" is one substitution from both "home" (i/o adjacent) and
        // "hire" (m/r not adjacent).
        assertEquals("home", best("hime"));
    }

    // ------------------------------------------------------------ precision

    /**
     * The single most important behaviour here. Correctly spelled words must
     * come back untouched, whatever their frequency.
     */
    @Test
    public void realWordsAreNeverCorrected() {
        String[] real = {
                "the", "keyboard", "definitely", "separate", "receive", "their",
                "cat", "run", "android", "voice", "transcription", "pinyin",
                "don't", "o'clock", "you've", "zebra", "quixotic",
        };
        List<String> violations = new ArrayList<>();
        for (String word : real) {
            EnglishCorrector.Result result = corrector.suggest(word, 3);
            if (!result.isEmpty()) {
                violations.add(word + " -> " + result.words);
            }
        }
        assertTrue("Correctly spelled words were offered corrections: " + violations,
                violations.isEmpty());
    }

    @Test
    public void firstLetterIsNeverChanged() {
        // hte -> the is deliberately out of reach; it belongs to the caller's
        // explicit typo table. If this ever starts passing, the first-letter
        // bucket has been widened and the scan cost went with it.
        for (String candidate : corrector.suggest("hte", 5).words) {
            assertEquals("Correction changed the first letter: hte -> " + candidate,
                    'h', candidate.charAt(0));
        }
    }

    @Test
    public void gibberishBeyondTwoEditsIsLeftAlone() {
        assertTrue(corrector.suggest("qwrtyuip", 3).isEmpty());
        assertTrue(corrector.suggest("zzzzzzzz", 3).isEmpty());
    }

    @Test
    public void inputsThatAreNotWordsAreRejected() {
        assertTrue(corrector.suggest(null, 3).isEmpty());
        assertTrue(corrector.suggest("", 3).isEmpty());
        assertTrue(corrector.suggest("a", 3).isEmpty());
        assertTrue(corrector.suggest("h3llo", 3).isEmpty());
        assertTrue(corrector.suggest("hello!", 3).isEmpty());
        assertFalse(corrector.suggest("speling", 0).autoAccept);
        assertTrue(corrector.suggest("speling", 0).isEmpty());
    }

    // --------------------------------------------------------- silent gate

    @Test
    public void confidentSingleEditFixesReplaceSilently() {
        assertTrue(corrector.suggest("speling", 3).autoAccept);
        assertTrue(corrector.suggest("tomorow", 3).autoAccept);
    }

    @Test
    public void shortWordsAreNeverReplacedSilently() {
        // Two characters is too little evidence: plenty of real two-letter
        // strings are one edit from a dictionary word.
        EnglishCorrector.Result result = corrector.suggest("th", 3);
        assertFalse(result.autoAccept);
    }

    @Test
    public void twoEditCandidatesAreOfferedButNeverImposed() {
        // Far enough out that nothing is one edit away, so whatever surfaces is
        // a chip suggestion only.
        EnglishCorrector.Result result = corrector.suggest("comunicat", 3);
        assertFalse("Two-edit candidates must never auto-accept", result.autoAccept);
    }

    /**
     * A word that might simply be unfinished must never be replaced outright.
     * The typed string is not in the lexicon, so anything it prefixes is a
     * longer word — it reads equally well as half-typed, or as a longer word
     * missing its last letter, and picking for the user would silently change
     * the sentence.
     */
    @Test
    public void wordsThatCouldBeUnfinishedAreNeverReplacedSilently() {
        String[] prefixes = {"ther", "cet", "wor", "hel"};
        for (String typed : prefixes) {
            assertTrue(typed + " should prefix a longer word",
                    dictionary.firstIndexWithPrefix(typed) >= 0);
            assertFalse(typed + " must not auto-accept", corrector.suggest(typed, 5).autoAccept);
        }
    }

    /**
     * Whenever a silent replacement is allowed, the guards must all hold. This
     * sweeps a broad set of inputs rather than asserting one hand-picked case,
     * so a loosened threshold shows up here.
     */
    @Test
    public void everySilentReplacementSatisfiesTheGuards() {
        String[] probes = {
                "speling", "tomorow", "recieve", "adress", "acount", "wich", "teh",
                "ther", "cet", "th", "bat", "hin", "wan", "keyboad", "definately",
                "somethin", "appreciat", "answe", "witht", "jsut", "woudl",
        };
        for (String typed : probes) {
            EnglishCorrector.Result result = corrector.suggest(typed, 5);
            if (!result.autoAccept) {
                continue;
            }
            assertTrue(typed + ": too short to replace silently",
                    typed.length() >= EnglishCorrector.SILENT_MIN_LENGTH);
            assertFalse(typed + ": already a real word",
                    dictionary.contains(typed));
            assertTrue(typed + ": could be an unfinished longer word",
                    dictionary.firstIndexWithPrefix(typed) < 0);
            assertFalse(typed + ": auto-accepted with no candidate", result.isEmpty());
        }
    }

    // ------------------------------------------------------------- casing

    /**
     * The lexicon inherited lowercase first-person contractions from upstream.
     * Correcting into a bare "i'm" would produce text that itself needs fixing,
     * and the caller's generic case matching cannot repair it because the typed
     * word is lowercase too.
     */
    @Test
    public void firstPersonContractionsComeBackCapitalised() {
        List<String> completions = corrector.complete("i'", 5);
        assertFalse(completions.isEmpty());
        for (String completion : completions) {
            assertTrue("Expected a capital I in " + completion, completion.startsWith("I'"));
        }
    }

    // -------------------------------------------------------- completions

    @Test
    public void completionsExtendAPrefixAndRankByFrequency() {
        List<String> completions = corrector.complete("keyboar", 3);
        assertTrue(completions.contains("keyboard"));

        List<String> shortPrefix = corrector.complete("th", 3);
        assertEquals(3, shortPrefix.size());
        for (String completion : shortPrefix) {
            assertTrue(completion.toLowerCase().startsWith("th"));
        }
    }

    @Test
    public void completionsIgnoreUnusableInput() {
        assertTrue(corrector.complete(null, 3).isEmpty());
        assertTrue(corrector.complete("k", 3).isEmpty());
        assertTrue(corrector.complete("k3", 3).isEmpty());
        assertTrue(corrector.complete("zzzzzz", 3).isEmpty());
    }

    // ------------------------------------------------------------ latency

    /**
     * Not a benchmark — a tripwire. This runs on a background thread per
     * keystroke, so a regression that made the scan tens of milliseconds would
     * show up as stale chips. The real budget is on-device and belongs to
     * Phase 4; this only catches an algorithmic blow-up.
     */
    @Test
    public void scanStaysFastEnoughToRunPerKeystroke() {
        String[] inputs = {"speling", "tomorow", "recieve", "somethin", "keyboad", "wich"};
        for (String input : inputs) {
            corrector.suggest(input, 3);
        }
        int rounds = 200;
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            corrector.suggest(inputs[i % inputs.length], 3);
        }
        long micros = (System.nanoTime() - start) / 1000L / rounds;
        System.out.println("EnglishCorrector JVM scan: " + micros + " us/word");
        assertTrue("Scan averaged " + micros + " us per word", micros < 20000);
    }

    /**
     * The worst realistic case: the largest first-letter bucket, at the length
     * where the window admits the most words.
     */
    @Test
    public void worstCaseBucketIsStillBounded() {
        char worst = 'a';
        int widest = 0;
        for (char letter = 'a'; letter <= 'z'; letter++) {
            int span = dictionary.bucketEnd(letter) - dictionary.bucketStart(letter);
            if (span > widest) {
                widest = span;
                worst = letter;
            }
        }
        String probe = worst + "onsiderat";
        corrector.suggest(probe, 3);
        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            corrector.suggest(probe, 3);
        }
        long micros = (System.nanoTime() - start) / 1000L / 50L;
        System.out.println("EnglishCorrector worst bucket '" + worst + "' (" + widest
                + " words) JVM scan: " + micros + " us/word");
        assertTrue("Worst-bucket scan averaged " + micros + " us", micros < 40000);
    }
}

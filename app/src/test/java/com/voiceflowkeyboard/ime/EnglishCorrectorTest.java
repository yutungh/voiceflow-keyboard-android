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
     * Correctly spelled words come back untouched. The one exception is a real
     * word whose neighbour is overwhelmingly commoner, covered below.
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

    // ------------------------------------------- overruling a real-but-rare word

    /**
     * The motivating case. "nit" is a real word, but "not" is ~3,250x commoner
     * and i/o are neighbouring keys, so typing "nit" and pressing space should
     * silently give "not".
     */
    @Test
    public void aRareRealWordLosesToAnOverwhelminglyCommonNeighbour() {
        assertTrue(dictionary.contains("nit"));
        EnglishCorrector.Result result = corrector.suggest("nit", 3);
        assertFalse("nit should now be correctable", result.isEmpty());
        assertEquals("not", result.words.get(0));
        assertTrue("and it should apply without asking", result.autoAccept);
    }

    /**
     * The case that sets the threshold. "ate" is one adjacent-key substitution
     * from "are", which is ~400x commoner — but "ate" is an everyday verb and
     * rewriting "I ate lunch" would be indefensible. It must stay untouched.
     */
    @Test
    public void anOrdinaryRealWordSurvivesACommonNeighbour() {
        assertTrue(dictionary.contains("ate"));
        assertTrue("ate must not be correctable", corrector.suggest("ate", 3).isEmpty());
    }

    /**
     * Only adjacent-key substitutions may overrule a real word. "fro" is a
     * transposition away from the far commoner "for", but "to and fro" is
     * ordinary English and unigram frequency cannot see the difference.
     */
    @Test
    public void onlyAdjacentSubstitutionsMayOverruleARealWord() {
        assertTrue(dictionary.contains("fro"));
        assertTrue("fro -> for is a transposition and must not fire",
                corrector.suggest("fro", 3).isEmpty());
        // "sunk" has no overwhelmingly commoner adjacent neighbour at all.
        assertTrue(corrector.suggest("sunk", 3).isEmpty());
    }

    /**
     * A real word that fails the bar gets nothing at all, not chips. Offering
     * corrections for correctly spelled words would make the strip noise.
     */
    @Test
    public void realWordsBelowTheBarGetNoChipsEither() {
        for (String word : new String[]{"ate", "fro", "cat", "sunk"}) {
            assertTrue(word + " should produce no correction chips",
                    corrector.suggest(word, 3).words.isEmpty());
        }
    }

    /**
     * Sweeps the whole lexicon: every real word the engine is now willing to
     * correct must independently satisfy the adjacency and gap conditions. This
     * is the guard against the exception quietly widening.
     */
    @Test
    public void everyOverruledRealWordSatisfiesBothConditions() {
        List<String> violations = new ArrayList<>();
        int overruled = 0;
        for (int i = 0; i < dictionary.size(); i++) {
            String word = dictionary.wordAt(i);
            if (word.length() < 3 || word.length() > 12) {
                continue;
            }
            EnglishCorrector.Result result = corrector.suggest(word, 3);
            if (result.isEmpty()) {
                continue;
            }
            overruled++;
            String winner = result.words.get(0).toLowerCase();
            int gap = dictionary.logFrequency(winner) - dictionary.logFrequency(word);
            if ((!isSingleAdjacentSubstitution(word, winner)
                    || gap < EnglishCorrector.REAL_WORD_MIN_FREQUENCY_GAP)
                    && violations.size() < 10) {
                violations.add(word + " -> " + winner + " gap=" + gap);
            }
        }
        System.out.println("real words the engine will overrule: " + overruled);
        assertEquals("Overruled a real word without both conditions: " + violations,
                0, violations.size());
        assertTrue("Expected the exception to fire for some words", overruled > 0);
        assertTrue("Exception is far wider than measured (56 pairs)", overruled < 120);
    }

    /** Independent of the production adjacency check, so the test does not assume it. */
    private static boolean isSingleAdjacentSubstitution(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int at = -1;
        int differences = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                if (differences == 0) {
                    at = i;
                }
                differences++;
            }
        }
        return differences == 1 && KeyProximity.isAdjacent(a.charAt(at), b.charAt(at));
    }

    /**
     * The first letter changes in exactly one way: by swapping the first two
     * characters. Anything else means the bucket scan has been widened and the
     * scan cost went with it.
     */
    @Test
    public void theFirstLetterChangesOnlyByASwapOfTheFirstTwo() {
        for (String typed : new String[]{"hte", "speling", "recieve", "nit", "wich", "kbow"}) {
            String swapped = typed.length() < 2 ? typed
                    : "" + typed.charAt(1) + typed.charAt(0) + typed.substring(2);
            for (String candidate : corrector.suggest(typed, 5).words) {
                String lower = candidate.toLowerCase();
                assertTrue(
                        "Correction changed the first letter without being the swap: "
                                + typed + " -> " + candidate,
                        lower.charAt(0) == typed.charAt(0) || lower.equals(swapped));
            }
        }
    }

    /**
     * Swapping the first two letters is the commonest slip the bucket scan
     * cannot see, so it is probed directly.
     */
    @Test
    public void swappedFirstLettersAreFound() {
        assertEquals("the", best("hte"));
        assertEquals("and", best("nad"));
        assertEquals("you", best("oyu"));
        assertEquals("from", best("rfom"));
        assertEquals("that", best("htat"));
    }

    /**
     * The swap must not outrank an adjacent-key slip. On a touchscreen,
     * substitutions outnumber transpositions by roughly fifty to one, so a
     * neighbouring-key explanation beats a reordering one.
     */
    @Test
    public void anAdjacentKeySlipStillOutranksASwap() {
        // "ti" swaps to the real word "it", but "to" is one adjacent-key
        // substitution away and wins.
        assertEquals("to", best("ti"));
        assertTrue(dictionary.contains("it"));
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
     * A word that might simply be unfinished must not be replaced outright —
     * but only when the completion is a plausible thing to have meant. "ther"
     * reads as an unfinished "there", so it is blocked; "wich" only prefixes
     * "wichita", which is not what anyone was typing.
     */
    @Test
    public void onlyPlausibleCompletionsBlockReplacement() {
        for (String typed : new String[]{"ther", "wor", "hel"}) {
            assertTrue(typed + " should prefix a longer word",
                    dictionary.firstIndexWithPrefix(typed) >= 0);
            assertFalse(typed + " must not auto-accept", corrector.suggest(typed, 5).autoAccept);
        }
        // An obscure completion must not shield a common typo. Both of these
        // prefix something longer ("tehran", "himeji") and must still be fixed.
        assertTrue("teh should prefix a longer word",
                dictionary.firstIndexWithPrefix("teh") >= 0);
        assertEquals("the", best("teh"));
        assertTrue("teh must be corrected despite prefixing tehran",
                corrector.suggest("teh", 3).autoAccept);
        assertEquals("home", best("hime"));
        assertTrue(corrector.suggest("hime", 3).autoAccept);
    }

    /**
     * When the best completion and the best correction are the same word they
     * agree rather than compete, so the completion guard must not fire.
     */
    @Test
    public void aCompletionThatIsAlsoTheCorrectionDoesNotBlock() {
        assertEquals("because", best("becaus"));
        assertTrue(corrector.suggest("becaus", 3).autoAccept);
    }

    // ------------------------------------------------------- two-letter words

    /**
     * The exhaustive sweep behind {@code SHORT_PAIRS}: with the length gate
     * lowered to two, 279 of the 676 two-letter strings would be silently
     * rewritten — including {@code mr -> me}, {@code tv -> to} and
     * {@code oz -> of}. So only named pairs are allowed through, and this pins
     * that nothing else is.
     */
    @Test
    public void onlyNamedTwoLetterPairsAreEverReplaced() {
        List<String> unexpected = new ArrayList<>();
        for (char a = 'a'; a <= 'z'; a++) {
            for (char b = 'a'; b <= 'z'; b++) {
                String typed = "" + a + b;
                EnglishCorrector.Result result = corrector.suggest(typed, 3);
                if (result.autoAccept && !result.isEmpty()) {
                    unexpected.add(typed + "->" + result.words.get(0));
                }
            }
        }
        // Exactly the reviewed list, nothing more.
        assertEquals("Unreviewed two-letter strings are being silently replaced: "
                + unexpected, 2, unexpected.size());
        assertTrue(unexpected.toString(), unexpected.contains("ti->to"));
        assertTrue(unexpected.toString(), unexpected.contains("od->of"));
    }

    /** The pair the user asked for, plus the one in the same shape. */
    @Test
    public void reviewedShortSlipsAreFixed() {
        assertEquals("to", best("ti"));
        assertTrue(corrector.suggest("ti", 3).autoAccept);
        assertEquals("of", best("od"));
        assertTrue(corrector.suggest("od", 3).autoAccept);
    }

    /**
     * Being on the reviewed list is not a licence to guess. "si" ranks "is"
     * first but "so" is within the runner-up margin, so it stays chip-only —
     * the review waives the length and completion proxies, never the margin.
     */
    @Test
    public void aReviewedPairStillLosesToAmbiguity() {
        assertEquals("is", best("si"));
        assertFalse("si is genuinely ambiguous with so",
                corrector.suggest("si", 3).autoAccept);
        assertEquals("in", best("ni"));
        assertFalse("ni is genuinely ambiguous with no",
                corrector.suggest("ni", 3).autoAccept);
    }

    // -------------------------------------------------- supplementary lexicon

    /**
     * The pinned corpus is Google Books, so it has no texting or software
     * vocabulary and the corrector used to rewrite "omg" to "org" and "thx" to
     * "the". Every supplementary word must now survive untouched.
     */
    @Test
    public void supplementaryVocabularyIsNeverRewritten() {
        String[] supplement = {
                "ok", "lol", "omg", "lmao", "smh", "nvm", "brb", "ttyl", "idk", "imo",
                "tbh", "fyi", "btw", "tldr", "pls", "plz", "thx", "ty", "ya",
                "api", "url", "json", "xml", "html", "css", "sms", "gps", "usb", "pdf",
                "faq", "ios", "github", "instagram", "tiktok", "spotify", "uber",
                "emoji", "selfie", "hashtag",
        };
        List<String> broken = new ArrayList<>();
        for (String word : supplement) {
            assertTrue(word + " missing from the lexicon", dictionary.contains(word));
            EnglishCorrector.Result result = corrector.suggest(word, 3);
            if (!result.isEmpty()) {
                broken.add(word + " -> " + result.words);
            }
        }
        assertTrue("Supplementary words are being corrected: " + broken, broken.isEmpty());
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
            assertFalse(typed + ": auto-accepted with no candidate", result.isEmpty());
            // A real word may now be overruled, but only under the much
            // stricter adjacency-plus-gap rule, checked in its own sweep.
            if (dictionary.contains(typed)) {
                int gap = dictionary.logFrequency(result.words.get(0).toLowerCase())
                        - dictionary.logFrequency(typed);
                assertTrue(typed + ": real word overruled without the gap",
                        gap >= EnglishCorrector.REAL_WORD_MIN_FREQUENCY_GAP);
            }
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

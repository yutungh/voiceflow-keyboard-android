package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
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
 * Phase 6, offline half: measured accuracy over a generated typo corpus rather
 * than a handful of hand-picked examples.
 *
 * <p>Typos are synthesised by applying one realistic slip — an adjacent-key
 * substitution, a dropped letter, a doubled letter, or a transposition — to
 * common words, deterministically, so the numbers are reproducible and a
 * scoring change moves them visibly. Every slip is applied at position 1 or
 * later, because correction deliberately never changes the first letter and
 * measuring that known limitation would only dilute the signal.
 *
 * <p>The thresholds below sit just under the values actually measured when this
 * was written; they are floors that catch regression, not targets that were
 * aimed at. Real-device feel is the other half of Phase 6 and is not something
 * a JVM test can speak to.
 */
public class EnglishCorrectorCorpusTest {

    /**
     * Measured 2026-08-13 over 1,551 generated cases: top-1 98.5%, silent
     * precision 99.5% (8 wrong out of 1,488). Floors sit just under each, so a
     * scoring regression trips them but ordinary noise does not.
     *
     * <p>Read these as indicative, not authoritative. The corpus applies one
     * slip at the midpoint of common words, which is not how real typing is
     * distributed — it under-represents long words, repeated slips, and the
     * short-word errors that are hardest to call.
     *
     * <p>What the residual failures have in common is worth knowing: every one
     * is a dropped letter that lands one edit from a commoner, shorter word —
     * {@code aded} to {@code add} rather than {@code added}, {@code frind} to
     * {@code find} rather than {@code friend}. Beating those needs context, not
     * a better prior.
     */
    private static final double TOP1_FLOOR = 0.95;
    private static final double SILENT_PRECISION_FLOOR = 0.98;

    private static EnglishDictionary dictionary;
    private static EnglishCorrector corrector;

    @BeforeClass
    public static void loadDictionary() throws IOException {
        File asset = new File("src/main/assets/english_dict.txt");
        try (InputStream stream = new FileInputStream(asset)) {
            dictionary = EnglishDictionary.load(stream);
        }
        corrector = new EnglishCorrector(dictionary);
    }

    private static final class Case {
        final String intended;
        final String typed;
        final String kind;

        Case(String intended, String typed, String kind) {
            this.intended = intended;
            this.typed = typed;
            this.kind = kind;
        }
    }

    /** A QWERTY neighbour of {@code key}, chosen deterministically. */
    private static char neighbourOf(char key) {
        for (char candidate = 'a'; candidate <= 'z'; candidate++) {
            if (KeyProximity.isAdjacent(key, candidate)) {
                return candidate;
            }
        }
        return key;
    }

    /**
     * The most frequent words long enough to slip on, taken in lexicon order so
     * the sample is fixed.
     */
    private static List<String> sampleWords(int wanted) {
        List<String> best = new ArrayList<>();
        int threshold = -9000; // roughly the commonest few thousand words
        for (int i = 0; i < dictionary.size() && best.size() < wanted; i++) {
            String word = dictionary.wordAt(i);
            if (word.length() >= 5
                    && word.indexOf('\'') < 0
                    && dictionary.logFrequencyAt(i) > threshold) {
                best.add(word);
            }
        }
        return best;
    }

    private static List<Case> buildCorpus() {
        List<Case> corpus = new ArrayList<>();
        for (String word : sampleWords(400)) {
            int mid = word.length() / 2;

            char original = word.charAt(mid);
            char neighbour = neighbourOf(original);
            if (neighbour != original) {
                add(corpus, word,
                        word.substring(0, mid) + neighbour + word.substring(mid + 1),
                        "adjacent-substitution");
            }
            add(corpus, word, word.substring(0, mid) + word.substring(mid + 1), "deletion");
            add(corpus, word, word.substring(0, mid) + original + word.substring(mid), "doubling");
            if (mid + 1 < word.length()) {
                add(corpus, word,
                        word.substring(0, mid) + word.charAt(mid + 1) + original
                                + word.substring(mid + 2),
                        "transposition");
            }
        }
        return corpus;
    }

    private static void add(List<Case> corpus, String intended, String typed, String kind) {
        // A "typo" that is itself a real word is not a typo — the engine
        // correctly refuses to touch it, and counting it as a miss would
        // measure the precision guard rather than the ranking.
        if (typed.equals(intended) || dictionary.contains(typed)) {
            return;
        }
        corpus.add(new Case(intended, typed, kind));
    }

    @Test
    public void measuredAccuracyOverAGeneratedTypoCorpus() {
        List<Case> corpus = buildCorpus();
        assertTrue("Corpus too small to mean anything: " + corpus.size(), corpus.size() > 800);

        int top1 = 0;
        int offered = 0;
        int silent = 0;
        int silentCorrect = 0;
        List<String> silentMistakes = new ArrayList<>();

        for (Case testCase : corpus) {
            EnglishCorrector.Result result = corrector.suggest(testCase.typed, 3);
            if (result.isEmpty()) {
                continue;
            }
            offered++;
            boolean hit = result.words.get(0).equalsIgnoreCase(testCase.intended);
            if (hit) {
                top1++;
            }
            if (result.autoAccept) {
                silent++;
                if (hit) {
                    silentCorrect++;
                } else if (silentMistakes.size() < 12) {
                    silentMistakes.add(testCase.typed + " -> " + result.words.get(0)
                            + " (meant " + testCase.intended + ", " + testCase.kind + ")");
                }
            }
        }

        double top1Rate = top1 / (double) corpus.size();
        double silentPrecision = silent == 0 ? 1.0 : silentCorrect / (double) silent;

        System.out.printf(
                "corpus=%d offered=%d top1=%d (%.1f%%) silent=%d silent-correct=%d (%.1f%%)%n",
                corpus.size(), offered, top1, top1Rate * 100, silent, silentCorrect,
                silentPrecision * 100);
        System.out.println("silent mistakes (sample): " + silentMistakes);

        assertTrue(
                String.format("Top-1 fell to %.1f%%, floor is %.1f%%",
                        top1Rate * 100, TOP1_FLOOR * 100),
                top1Rate >= TOP1_FLOOR);
        assertTrue(
                String.format("Silent-replacement precision fell to %.1f%%, floor is %.1f%%%n%s",
                        silentPrecision * 100, SILENT_PRECISION_FLOOR * 100, silentMistakes),
                silentPrecision >= SILENT_PRECISION_FLOOR);
    }

    /**
     * The precision guard, swept over the whole lexicon rather than sampled.
     *
     * <p>This used to assert a flat zero. It no longer can: a real word whose
     * neighbour is ~3,000x commoner is now deliberately correctable, which is
     * what makes {@code nit -> not} work. What must still hold is that the
     * exception stays vanishingly rare — measured at 55 words out of 82,834,
     * or 0.066%. The per-word conditions are checked in
     * {@code EnglishCorrectorTest.everyOverruledRealWordSatisfiesBothConditions};
     * what this pins is the overall rate, so the exception cannot widen quietly.
     */
    @Test
    public void theRealWordExceptionStaysVanishinglyRare() {
        List<String> overruled = new ArrayList<>();
        for (int i = 0; i < dictionary.size(); i++) {
            String word = dictionary.wordAt(i);
            if (!corrector.suggest(word, 3).isEmpty()) {
                overruled.add(word);
            }
        }
        double rate = overruled.size() / (double) dictionary.size();
        System.out.printf("real words overruled: %d of %d (%.3f%%)%n",
                overruled.size(), dictionary.size(), rate * 100);
        assertTrue("The real-word exception has widened well past its measured "
                        + "size: " + overruled.size() + " words, sample "
                        + overruled.subList(0, Math.min(15, overruled.size())),
                overruled.size() <= 120);
    }

    /** Two edits may be offered, but must never be imposed. */
    @Test
    public void noTwoEditCandidateIsEverAutoAccepted() {
        List<String> violations = new ArrayList<>();
        for (Case testCase : buildCorpus()) {
            EnglishCorrector.Result result = corrector.suggest(testCase.typed, 3);
            if (!result.autoAccept || result.isEmpty()) {
                continue;
            }
            String winner = result.words.get(0).toLowerCase();
            if (!withinOneEdit(testCase.typed, winner) && violations.size() < 10) {
                violations.add(testCase.typed + " -> " + winner);
            }
        }
        assertEquals("Auto-accepted a candidate more than one edit away: " + violations,
                0, violations.size());
    }

    /** Independent Damerau check, so the test does not reuse the code it verifies. */
    private static boolean withinOneEdit(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > 1) {
            return false;
        }
        if (n == m) {
            int differences = 0;
            int firstDifference = -1;
            for (int i = 0; i < n; i++) {
                if (a.charAt(i) != b.charAt(i)) {
                    if (differences == 0) {
                        firstDifference = i;
                    }
                    differences++;
                }
            }
            if (differences == 1) {
                return true;
            }
            return differences == 2
                    && firstDifference + 1 < n
                    && a.charAt(firstDifference) == b.charAt(firstDifference + 1)
                    && a.charAt(firstDifference + 1) == b.charAt(firstDifference)
                    && a.substring(firstDifference + 2).equals(b.substring(firstDifference + 2));
        }
        String longer = n > m ? a : b;
        String shorter = n > m ? b : a;
        for (int i = 0; i < longer.length(); i++) {
            if ((longer.substring(0, i) + longer.substring(i + 1)).equals(shorter)) {
                return true;
            }
        }
        return false;
    }
}

package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Accuracy against real human misspellings rather than ones we generated.
 *
 * <p>The generated corpus in {@link EnglishCorrectorCorpusTest} can only contain
 * the errors we thought to simulate, which makes it good at catching regressions
 * and useless at finding blind spots. These are misspellings people actually
 * typed: Wikipedia editors and the GNU Aspell test set.
 *
 * <p><b>The data is not in the repository and must not be.</b> Roger Mitton's
 * corpora page states no licence for any of them, so we can measure locally but
 * cannot redistribute. Run {@code sh scripts/fetch-corpora.sh} to populate
 * {@code .corpora/} (gitignored); without it every test here skips, so a clean
 * checkout still passes.
 *
 * <p>Only the typed corpora are used. Birkbeck and Holbrook are transcriptions
 * of schoolchildren's handwriting — they model not knowing how a word is spelt,
 * not a thumb missing a key, and mixing them in would make the number describe
 * a population this keyboard never serves.
 */
public class EnglishCorrectorRealCorpusTest {

    private static EnglishDictionary dictionary;
    private static EnglishCorrector corrector;

    @BeforeClass
    public static void load() throws IOException {
        try (InputStream s = new FileInputStream(new File("src/main/assets/english_dict.txt"))) {
            dictionary = EnglishDictionary.load(s);
        }
        corrector = new EnglishCorrector(dictionary);
    }

    private static final class Case {
        final String typed;
        final String intended;

        Case(String typed, String intended) {
            this.typed = typed;
            this.intended = intended;
        }
    }

    /** Mitton's format: a "$word" line, then its misspellings until the next "$". */
    private static List<Case> read(String name) throws IOException {
        File file = new File("../.corpora/" + name);
        assumeTrue("Missing .corpora/" + name + " — run: sh scripts/fetch-corpora.sh",
                file.isFile());
        List<Case> cases = new ArrayList<>();
        String intended = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim().toLowerCase(Locale.US);
                if (value.isEmpty()) {
                    continue;
                }
                if (value.charAt(0) == '$') {
                    intended = value.substring(1);
                } else if (intended != null) {
                    cases.add(new Case(value, intended));
                }
            }
        }
        return cases;
    }

    private void report(String name, double precisionFloor) throws IOException {
        List<Case> all = read(name);

        int inScope = 0;
        int top1 = 0;
        int silent = 0;
        int silentRight = 0;
        int outOfVocabulary = 0;
        List<String> silentMistakes = new ArrayList<>();

        for (Case testCase : all) {
            // A target our lexicon has never heard of measures coverage, not
            // correction quality. Counted separately rather than dropped
            // silently, because the size of that bucket is itself a finding.
            if (!dictionary.contains(testCase.intended)) {
                outOfVocabulary++;
                continue;
            }
            if (testCase.typed.equals(testCase.intended)) {
                continue;
            }
            inScope++;
            EnglishCorrector.Result result = corrector.suggest(testCase.typed, 3);
            if (result.isEmpty()) {
                continue;
            }
            boolean hit = result.words.get(0).equalsIgnoreCase(testCase.intended);
            if (hit) {
                top1++;
            }
            if (result.autoAccept) {
                silent++;
                if (hit) {
                    silentRight++;
                } else if (silentMistakes.size() < 15) {
                    silentMistakes.add(testCase.typed + "->" + result.words.get(0)
                            + " (meant " + testCase.intended + ")");
                }
            }
        }

        double top1Rate = inScope == 0 ? 0 : top1 / (double) inScope;
        double precision = silent == 0 ? 1 : silentRight / (double) silent;
        System.out.printf(
                "%-14s cases=%-6d in-vocab=%-5d out-of-vocab=%-5d top1=%-5d (%.1f%%)"
                        + " silent=%-5d correct=%-5d (%.1f%%)%n",
                name, all.size(), inScope, outOfVocabulary, top1, top1Rate * 100,
                silent, silentRight, precision * 100);
        System.out.println("   silent mistakes: " + silentMistakes);

        assertTrue(name + ": suspiciously few cases in scope", inScope > 50);
        assertTrue(
                String.format("%s: silent precision %.1f%% is below the %.0f%% floor",
                        name, precision * 100, precisionFloor * 100),
                precision >= precisionFloor);
    }

    /**
     * Misspellings Wikipedia editors actually typed — the closest thing here to
     * ordinary prose typing. Measured 91.4% silent precision.
     */
    @Test
    public void wikipediaEditorTypos() throws IOException {
        report("wikipedia.dat", 0.90);
    }

    /**
     * GNU Aspell's test set, and a deliberately harsh one: it exists to stress a
     * spellchecker, so it is thick with proper nouns and misspellings several
     * edits from their target. Measured 77.5%, which is the pessimistic bound
     * rather than a picture of everyday typing — the floor is set accordingly
     * and this number is not a quality target to chase.
     */
    @Test
    public void aspellTestSet() throws IOException {
        report("aspell.dat", 0.75);
    }
}

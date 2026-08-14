package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Phase 5 gate: the two typographic rules, and — more importantly — every
 * position where they must decline to fire.
 */
public class TypingRulesTest {

    /** Applies an outcome to a string, the way the service applies it to the editor. */
    private static String apply(String before, String separator) {
        TypingRules.Outcome outcome = TypingRules.forSeparator(separator, before);
        if (!outcome.applies()) {
            return before + separator;
        }
        return before.substring(0, before.length() - outcome.deleteBefore) + outcome.insert;
    }

    // -------------------------------------------------- double space to stop

    @Test
    public void doubleSpaceBecomesAFullStop() {
        assertEquals("hello. ", apply("hello ", " "));
        assertEquals("I said hello. ", apply("I said hello ", " "));
        assertEquals("it's. ", apply("it's ", " "));
        assertEquals("chapter 12. ", apply("chapter 12 ", " "));
    }

    @Test
    public void aThirdSpaceDoesNotStack() {
        // "hello. " already ends space-preceded-by-space, so the rule declines
        // and the run stops rather than producing "hello.. ".
        assertEquals("hello.  ", apply("hello. ", " "));
        assertEquals("hello   ", apply("hello  ", " "));
    }

    @Test
    public void spaceAfterPunctuationIsLeftAlone() {
        assertEquals("wait, ", apply("wait,", " "));
        assertEquals("really? ", apply("really?", " "));
        assertEquals("end. ", apply("end.", " "));
    }

    @Test
    public void ruleCannotFireAtTheStartOfAField() {
        assertEquals(" ", apply("", " "));
        assertEquals("  ", apply(" ", " "));
    }

    // ---------------------------------------------- space before punctuation

    @Test
    public void spaceBeforeClosingPunctuationIsDropped() {
        assertEquals("hello.", apply("hello ", "."));
        assertEquals("hello,", apply("hello ", ","));
        assertEquals("hello?", apply("hello ", "?"));
        assertEquals("hello!", apply("hello ", "!"));
        assertEquals("hello:", apply("hello ", ":"));
        assertEquals("hello;", apply("hello ", ";"));
    }

    @Test
    public void punctuationDirectlyAfterAWordIsUntouched() {
        assertEquals("hello.", apply("hello", "."));
        assertEquals("hello,", apply("hello", ","));
    }

    @Test
    public void punctuationAfterTwoSpacesIsUntouched() {
        // Two spaces reads as deliberate; only a single space gets absorbed.
        assertEquals("hello  .", apply("hello  ", "."));
    }

    @Test
    public void otherKeysAreNeverRewritten() {
        assertFalse(TypingRules.forSeparator("a", "hello ").applies());
        assertFalse(TypingRules.forSeparator("-", "hello ").applies());
        assertFalse(TypingRules.forSeparator("'", "hello ").applies());
        assertFalse(TypingRules.forSeparator("", "hello ").applies());
    }

    @Test
    public void nullsAreSafe() {
        assertFalse(TypingRules.forSeparator(null, "hello ").applies());
        assertFalse(TypingRules.forSeparator(" ", null).applies());
    }

    /**
     * The service only reads two characters of context, so the rules must reach
     * their verdict from exactly that much.
     */
    @Test
    public void twoCharactersOfContextAreEnough() {
        assertEquals("o. ", apply("o ", " "));
        assertEquals("o.", apply("o ", "."));
        assertFalse(TypingRules.forSeparator(" ", ". ").applies());
        assertFalse(TypingRules.forSeparator(" ", "  ").applies());
    }

    /**
     * A rewrite always removes exactly the one space it is absorbing. If this
     * ever grew, the service's fixed two-character lookbehind would no longer
     * be enough to justify the deletion.
     */
    @Test
    public void aRewriteOnlyEverConsumesTheSingleTrailingSpace() {
        String[][] firing = {
                {"hello ", " "}, {"hello ", "."}, {"hello ", ","},
                {"hello ", "?"}, {"hello ", "!"}, {"hello ", ":"}, {"hello ", ";"},
        };
        for (String[] row : firing) {
            TypingRules.Outcome outcome = TypingRules.forSeparator(row[1], row[0]);
            assertTrue(row[0] + " + " + row[1] + " should rewrite", outcome.applies());
            assertEquals(1, outcome.deleteBefore);
        }
    }
}

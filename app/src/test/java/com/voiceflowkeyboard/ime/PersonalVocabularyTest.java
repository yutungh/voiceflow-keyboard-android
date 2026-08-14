package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PersonalVocabularyTest {
    private final List<PhraseReplacement> replacements = Arrays.asList(
            new PhraseReplacement(
                    "poopee\npoo pee\npoopy",
                    "boobee",
                    "boobee is the user's nickname for his wife Amanda."
            ),
            new PhraseReplacement(
                    "home link\nhome linc",
                    "HomeLinq",
                    "HomeLinq is the user's personal software project."
            ),
            new PhraseReplacement(
                    "n p m run sign off",
                    "npm run signoff",
                    "npm run signoff is an exact lowercase command."
            )
    );

    @Test
    public void appliesNicknameAndProjectCorrections() {
        assertEquals(
                "Tell boobee that HomeLinq is ready",
                PersonalVocabulary.applyReplacements(
                        "Tell poopee that Home Link is ready",
                        replacements
                )
        );
    }

    @Test
    public void normalizesAcronymSpacingDotsAndHyphenation() {
        assertEquals(
                "npm run signoff",
                PersonalVocabulary.applyReplacements("N.P.M. run sign-off", replacements)
        );
        assertEquals(
                "Please npm run signoff now",
                PersonalVocabulary.applyReplacements("Please n p m run sign off now", replacements)
        );
    }

    @Test
    public void doesNotReplaceInsideAnotherWord() {
        assertEquals(
                "spoopee",
                PersonalVocabulary.applyReplacements("spoopee", replacements)
        );
    }

    @Test
    public void buildsDeduplicatedKeywordsAndContextPrompt() {
        List<String> keywords = PersonalVocabulary.keywords(replacements);
        assertEquals(Arrays.asList("boobee", "HomeLinq", "npm run signoff"), keywords);

        String prompt = PersonalVocabulary.transcriptionPrompt(replacements);
        assertTrue(prompt.contains("boobee"));
        assertTrue(prompt.contains("Amanda"));
        assertTrue(prompt.contains("HomeLinq"));
        assertTrue(prompt.contains("npm run signoff"));
        assertFalse(prompt.contains("poopee"));
    }

    @Test
    public void findsFlexibleReplacementAtTextEnd() {
        PersonalVocabulary.ReplacementMatch match = PersonalVocabulary.findReplacementAtEnd(
                "Please N P M run sign off",
                replacements
        );
        assertTrue(match != null);
        assertEquals("npm run signoff", match.replacement);
        assertEquals("N P M run sign off".length(), match.matchedLength);
    }
}

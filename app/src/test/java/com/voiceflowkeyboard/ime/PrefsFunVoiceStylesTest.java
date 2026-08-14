package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrefsFunVoiceStylesTest {
    private static final String[] FUN_PRESETS = {
            Prefs.PRESET_FUN_HAIKU,
            Prefs.PRESET_FUN_PIRATE,
            Prefs.PRESET_FUN_SHAKESPEARE,
            Prefs.PRESET_FUN_NOIR,
            Prefs.PRESET_FUN_WIZARD
    };

    @Test
    public void funStylesHaveBuiltInProfilesAndCannotBeDeleted() {
        for (String preset : FUN_PRESETS) {
            assertTrue(Prefs.isFunVoiceStyle(preset));
            assertFalse(Prefs.defaultLabelForPreset(preset).isEmpty());
            assertFalse(Prefs.defaultIconForPreset(preset).isEmpty());
            assertFalse(Prefs.defaultPromptForPreset(preset).isEmpty());
            assertFalse(Prefs.canDeletePromptProfile(preset));
        }
    }

    @Test
    public void haikuPromptAndExpressionKeepThreeLinePoemContract() {
        String prompt = Prefs.defaultPromptForPreset(Prefs.PRESET_FUN_HAIKU);
        String expression = Prefs.expressionGuidance(
                Prefs.PRESET_FUN_HAIKU,
                Prefs.EXPRESSION_EXPRESSIVE
        );

        assertTrue(prompt.contains("exactly three lines"));
        assertTrue(prompt.contains("5-7-5"));
        assertTrue(prompt.contains("no title"));
        assertTrue(expression.contains("exactly three lines"));
        assertTrue(expression.contains("never add a title"));
        assertTrue(expression.contains("emoji"));
    }

    @Test
    public void personaPromptsBlockInventedPersonaFacts() {
        assertTrue(Prefs.defaultPromptForPreset(Prefs.PRESET_FUN_PIRATE).contains("Never invent a ship"));
        assertTrue(Prefs.defaultPromptForPreset(Prefs.PRESET_FUN_SHAKESPEARE).contains("Never invent a metaphor"));
        assertTrue(Prefs.defaultPromptForPreset(Prefs.PRESET_FUN_NOIR).contains("Never invent crime"));
        assertTrue(Prefs.defaultPromptForPreset(Prefs.PRESET_FUN_WIZARD).contains("Never invent a spell"));
    }
}

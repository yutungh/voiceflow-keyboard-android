package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which language a recording is sent as, and which providers can honour it.
 */
public class DictationLanguageTest {

    /**
     * AUTO must send nothing. Narrowing to English would break the main case the
     * owner cares about: speaking Chinese while the keyboard is in English mode.
     */
    @Test
    public void autoSendsNoHintSoDetectionStillWorks() {
        assertArrayEquals(new String[0], DictationLanguage.AUTO.openAiLanguages());
        assertEquals("", DictationLanguage.AUTO.deepgramLanguage());
        assertFalse(DictationLanguage.AUTO.isChinese());
    }

    /** English rides along so a dropped-in English word still transcribes. */
    @Test
    public void chineseKeepsEnglishForCodeSwitching() {
        assertArrayEquals(new String[]{"zh", "en"}, DictationLanguage.CHINESE.openAiLanguages());
        assertTrue(DictationLanguage.CHINESE.isChinese());
    }

    /**
     * Deepgram takes a single language. Its multilingual "multi" code covers
     * English, Spanish, French, German, Hindi, Russian, Portuguese, Japanese,
     * Italian and Dutch but NOT Mandarin, so Chinese must be requested as "zh"
     * and cannot be combined with English on that provider.
     */
    @Test
    public void deepgramGetsExplicitChineseNotMulti() {
        assertEquals("zh", DictationLanguage.CHINESE.deepgramLanguage());
        assertFalse("multi does not include Mandarin",
                "multi".equals(DictationLanguage.CHINESE.deepgramLanguage()));
    }

    @Test
    public void onlyCloudProvidersClaimChinese() {
        assertTrue(Prefs.supportsChineseDictation(Prefs.PROVIDER_OPENAI));
        assertTrue(Prefs.supportsChineseDictation(Prefs.PROVIDER_DEEPGRAM));
        assertFalse("xAI STT language support is undocumented",
                Prefs.supportsChineseDictation(Prefs.PROVIDER_XAI));
    }

    /**
     * The bundled offline models are English. They would return confident
     * nonsense for Mandarin rather than an error, so they must never be treated
     * as Chinese-capable.
     */
    @Test
    public void offlineProvidersAreNeverChineseCapable() {
        assertFalse(Prefs.supportsChineseDictation(Prefs.PROVIDER_OFFLINE_VOSK));
        assertFalse(Prefs.supportsChineseDictation(Prefs.PROVIDER_OFFLINE_PARAKEET));
        assertTrue(Prefs.defaultTranscriptionModel(Prefs.PROVIDER_OFFLINE_VOSK).contains("en-us"));
        assertTrue(Prefs.defaultTranscriptionModel(Prefs.PROVIDER_OFFLINE_PARAKEET).contains("parakeet"));
    }

    /**
     * An unrecognised provider falls back to OpenAI everywhere else in the app,
     * so it must report Chinese as supported here too — the recording really
     * will go to OpenAI. Reporting false would block Chinese dictation on a
     * provider that would have handled it.
     */
    @Test
    public void unknownProviderFollowsTheOpenAiFallback() {
        assertEquals(Prefs.PROVIDER_OPENAI, Prefs.sanitizeTranscriptionProvider("something-else"));
        assertTrue(Prefs.supportsChineseDictation("something-else"));
        assertTrue(Prefs.supportsChineseDictation(null));
    }
}

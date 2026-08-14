package com.voiceflowkeyboard.ime;

/**
 * What language the user is expected to speak for one recording, decided when
 * recording starts and threaded through to the provider.
 *
 * <p>It has to be a decision rather than a lookup because the providers differ.
 * OpenAI takes a {@code languages[]} set and can hold Chinese and English at
 * once. Deepgram's nova-3 takes a single {@code language}, and its multilingual
 * {@code multi} code covers English, Spanish, French, German, Hindi, Russian,
 * Portuguese, Japanese, Italian and Dutch — not Mandarin, which needs an
 * explicit {@code zh}. So Deepgram must be told one or the other up front.
 */
enum DictationLanguage {

    /**
     * Let the provider work it out. OpenAI auto-detects, which is what keeps
     * spoken Chinese working on a keyboard that is otherwise in English mode.
     */
    AUTO,

    /** The keyboard is in a Chinese mode, so bias hard toward Mandarin. */
    CHINESE;

    boolean isChinese() {
        return this == CHINESE;
    }

    /**
     * ISO-639-1 codes for OpenAI's {@code languages[]}. English rides along so a
     * dropped-in English word or product name still transcribes correctly.
     * Empty means send nothing and let the model detect.
     */
    String[] openAiLanguages() {
        return this == CHINESE ? new String[]{"zh", "en"} : new String[0];
    }

    /** Deepgram's single {@code language} value, or empty to leave the default. */
    String deepgramLanguage() {
        return this == CHINESE ? "zh" : "";
    }
}

package com.voiceflowkeyboard.ime;

/**
 * What the letter keys currently mean.
 *
 * <p>Modelled as one enum rather than a language plus an orthogonal "keypad"
 * flag, because English-on-a-9-key-pad is not a state this keyboard has.
 */
enum InputMode {
    /** Latin letters committed straight to the editor. */
    ENGLISH,
    /** Full QWERTY keys feeding the pinyin composer. */
    CHINESE_QWERTY,
    /** Samsung-style 3x4 keypad feeding the pinyin composer through digits. */
    CHINESE_KEYPAD;

    boolean isChinese() {
        return this != ENGLISH;
    }

    boolean isKeypad() {
        return this == CHINESE_KEYPAD;
    }

    /** Short label for the language key. */
    String keyLabel() {
        return isChinese() ? "中" : "EN";
    }

    static InputMode chineseFor(String prefsLayout) {
        return Prefs.CHINESE_LAYOUT_KEYPAD.equals(Prefs.sanitizeChineseLayout(prefsLayout))
                ? CHINESE_KEYPAD
                : CHINESE_QWERTY;
    }

    String prefsLayout() {
        return isKeypad() ? Prefs.CHINESE_LAYOUT_KEYPAD : Prefs.CHINESE_LAYOUT_QWERTY;
    }
}

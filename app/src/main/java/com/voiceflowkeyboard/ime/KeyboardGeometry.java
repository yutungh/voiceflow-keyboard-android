package com.voiceflowkeyboard.ime;

/**
 * Where the keyboard splits and how tall its keys are.
 *
 * <p>Pure arithmetic over screen dimensions, deliberately free of Android types
 * so the decisions can be unit tested against real device measurements. The
 * service reads the values out of {@code Configuration} and delegates here.
 */
final class KeyboardGeometry {

    static final int KEY_HEIGHT_DP = 48;
    static final int KEY_HEIGHT_LANDSCAPE_DP = 42;

    /** sw600dp — the standard "this is a large device" line. */
    static final int SPLIT_MIN_SMALLEST_WIDTH_DP = 600;

    /** Combined width the two halves aim for before the gutter takes the rest. */
    static final int SPLIT_TARGET_KEY_SPAN_DP = 540;
    static final int SPLIT_MIN_GUTTER_DP = 56;
    static final int SPLIT_MAX_GUTTER_DP = 320;

    /** Rows that need to know where their halves divide. */
    enum Row { TOP, MIDDLE, BOTTOM_LETTERS, SYMBOLS, SYMBOLS_THIRD, KEYPAD }

    private KeyboardGeometry() {
    }

    static int keyHeightDp(boolean landscape) {
        return landscape ? KEY_HEIGHT_LANDSCAPE_DP : KEY_HEIGHT_DP;
    }

    /**
     * Whether to split, from {@code smallestScreenWidthDp} — NOT screenWidthDp.
     *
     * <p>screenWidthDp is the current window width, so an ordinary phone turned
     * landscape reports roughly 730-900 and would split when nobody asked it to.
     * smallestScreenWidthDp is orientation-invariant: roughly 360-410 on phones,
     * roughly 320 on a folded cover screen, and 700+ on an unfolded inner
     * display, so only genuinely large devices cross the line.
     */
    static boolean shouldSplit(int smallestScreenWidthDp) {
        return smallestScreenWidthDp >= SPLIT_MIN_SMALLEST_WIDTH_DP;
    }

    /**
     * Width of the gap between the halves. The halves keep a thumb-friendly
     * size and the slack goes to the gutter, which is what pushes them out to
     * the screen edges where the thumbs actually are.
     */
    static int gutterDp(int screenWidthDp) {
        int slack = screenWidthDp - SPLIT_TARGET_KEY_SPAN_DP;
        return Math.max(SPLIT_MIN_GUTTER_DP, Math.min(SPLIT_MAX_GUTTER_DP, slack));
    }

    /**
     * How many keys sit left of the gutter, or -1 when the row should not split.
     *
     * <p>Splitting on the QWERTY seam (qwert|yuiop) rather than the arithmetic
     * middle is what makes the halves read as two halves of a keyboard.
     */
    static int splitIndex(Row row, int keyCount, boolean split) {
        if (!split || keyCount < 4) {
            return -1;
        }
        switch (row) {
            case TOP:
            case SYMBOLS:
                return keyCount / 2;               // qwert | yuiop
            case MIDDLE:
            case SYMBOLS_THIRD:
                return (keyCount + 1) / 2;         // asdfg | hjkl
            case BOTTOM_LETTERS:
                return keyCount / 2;               // shift zxcv | bnm del
            case KEYPAD:
            default:
                return -1;                         // the 3x4 pad is centred, not split
        }
    }
}

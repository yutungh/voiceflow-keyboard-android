package com.voiceflowkeyboard.ime;

/**
 * Where the keyboard splits and how big its keys are.
 *
 * <p>Pure arithmetic over screen dimensions, deliberately free of Android types
 * so the decisions can be unit tested against real device measurements. The
 * service reads the values out of {@code Configuration} and delegates here.
 */
final class KeyboardGeometry {

    /** Ordinary phone, upright. Also the usual accessibility touch-target size. */
    static final int KEY_HEIGHT_DP = 48;

    /** Ordinary phone, landscape — shorter because the window is. */
    static final int KEY_HEIGHT_LANDSCAPE_DP = 42;

    /**
     * Key height once the keyboard splits, which only happens on a large screen.
     *
     * <p>Until now a large screen got a phone's keyboard: 48dp keys and a 192dp
     * four-row surface on a display 835dp tall. dp is meant to hold physical
     * size constant across devices, so that is defensible in principle, but it
     * leaves an unfolded Fold noticeably smaller than the keyboard beside it —
     * which is the complaint.
     *
     * <p>56dp is deliberately unadventurous. Android's own framework sizes its
     * keyguard password keyboard at 56dp upright and 47dp landscape, rising to
     * 75dp on {@code large} resources
     * ({@code platforms/android-35/data/res/values/dimens.xml}), so this sits at
     * the bottom of the platform's own range for a big screen rather than past
     * the top of it.
     *
     * <p>Note the visible key face is about 6dp shorter than these numbers: the
     * background is inset 3dp a side, so a 56dp cell shows a 50dp face.
     */
    static final int SPLIT_KEY_HEIGHT_DP = 56;
    static final int SPLIT_KEY_HEIGHT_LANDSCAPE_DP = 50;

    /** sw600dp — the standard "this is a large device" line. */
    static final int SPLIT_MIN_SMALLEST_WIDTH_DP = 600;

    /**
     * Combined width the two halves aim for before the gutter takes the rest.
     *
     * <p>Raised from 540, which was simply too small: it left a 752dp Fold with
     * a 212dp gutter — 28% of the screen given to empty space — and 54dp keys.
     * 640 puts that gutter at 112dp and the top-row cells at 64dp.
     *
     * <p>Kept as a fixed span rather than a share of the width, which is the
     * more obvious-looking model and the wrong one. Slack belongs in the middle:
     * the halves stay a constant, thumb-reachable size and the gap grows, which
     * is the entire point of splitting. A percentage gutter instead grows the
     * keys without limit — on a 2000dp display it would produce 179dp keys.
     */
    static final int SPLIT_TARGET_KEY_SPAN_DP = 640;
    static final int SPLIT_MIN_GUTTER_DP = 56;

    /** Rows that need to know where their halves divide. */
    enum Row { TOP, MIDDLE, BOTTOM_LETTERS, SYMBOLS, SYMBOLS_THIRD, KEYPAD }

    private KeyboardGeometry() {
    }

    /**
     * Key height, which depends on whether the keyboard split — not on the
     * screen height.
     *
     * <p>Height is useless as a signal here: an unfolded Fold is 835dp tall and
     * an ordinary phone is 832dp, so any rule keyed off it either scales both or
     * neither. Splitting already keys off {@code smallestScreenWidthDp}, which
     * is what actually separates the two (752dp against 384dp), so reusing that
     * decision costs nothing and adds no second threshold to reason about.
     */
    static int keyHeightDp(boolean landscape, boolean split) {
        if (split) {
            return landscape ? SPLIT_KEY_HEIGHT_LANDSCAPE_DP : SPLIT_KEY_HEIGHT_DP;
        }
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
     * size and all the slack goes to the gutter, which is what pushes them out
     * to the screen edges where the thumbs actually are.
     *
     * <p>Deliberately unbounded above. There used to be a 320dp ceiling, and it
     * quietly defeated the whole model: past a 960dp window the gutter stopped
     * absorbing slack and every extra dp went into the keys instead, so a
     * 2000dp display produced 168dp keys. Capping the gap is the same mistake as
     * making it a percentage, just further out. A gap is allowed to be large —
     * that is what splitting a keyboard across a big screen means.
     */
    static int gutterDp(int screenWidthDp) {
        return Math.max(SPLIT_MIN_GUTTER_DP, screenWidthDp - SPLIT_TARGET_KEY_SPAN_DP);
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

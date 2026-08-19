package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Split and sizing decisions, checked against the dimensions actually reported
 * by the devices this is built for.
 */
public class KeyboardGeometryTest {

    // Measured with `adb shell dumpsys window displays` / `wm density`:
    //   Galaxy Z Fold SM-F976U inner:  2256x2504 @ 480dpi -> 752 x 835 dp
    //   Galaxy Z Fold SM-F976U cover:  1080x2520 @ 480dpi -> 360 x 840 dp
    //   Galaxy SM-S948U:               1440x3120 @ 600dpi -> 384 x 832 dp
    private static final int FOLD_UNFOLDED_SW_DP = 752;
    private static final int FOLD_COVER_SW_DP = 360;
    private static final int PHONE_SW_DP = 384;
    private static final int PHONE_LANDSCAPE_WIDTH_DP = 832;
    private static final int FOLD_UNFOLDED_HEIGHT_DP = 835;
    private static final int PHONE_HEIGHT_DP = 832;

    @Test
    public void unfoldedFoldSplits() {
        assertTrue(KeyboardGeometry.shouldSplit(FOLD_UNFOLDED_SW_DP));
    }

    /** The bug this threshold exists to prevent. */
    @Test
    public void ordinaryPhoneNeverSplits() {
        assertFalse("phone upright must not split", KeyboardGeometry.shouldSplit(PHONE_SW_DP));
        // Turned landscape the WINDOW is 832dp wide, which is why screenWidthDp
        // is the wrong signal — smallestScreenWidthDp stays 384 and stays false.
        assertTrue(PHONE_LANDSCAPE_WIDTH_DP > KeyboardGeometry.SPLIT_MIN_SMALLEST_WIDTH_DP);
        assertFalse("phone in landscape must not split", KeyboardGeometry.shouldSplit(PHONE_SW_DP));
    }

    @Test
    public void foldedCoverScreenDoesNotSplit() {
        assertFalse(KeyboardGeometry.shouldSplit(FOLD_COVER_SW_DP));
    }

    @Test
    public void thresholdBoundaryIsInclusive() {
        assertFalse(KeyboardGeometry.shouldSplit(599));
        assertTrue(KeyboardGeometry.shouldSplit(600));
    }

    @Test
    public void landscapeShortensKeys() {
        assertTrue(KeyboardGeometry.keyHeightDp(true, false)
                < KeyboardGeometry.keyHeightDp(false, false));
        assertTrue(KeyboardGeometry.keyHeightDp(true, true)
                < KeyboardGeometry.keyHeightDp(false, true));
    }

    /**
     * A phone must be left exactly as it was. The owner types on one daily and
     * this change is for the Fold.
     */
    @Test
    public void ordinaryPhoneKeepsItsKeyHeight() {
        assertFalse(KeyboardGeometry.shouldSplit(PHONE_SW_DP));
        assertEquals(48, KeyboardGeometry.keyHeightDp(false, false));
        assertEquals(42, KeyboardGeometry.keyHeightDp(true, false));
    }

    /**
     * The defect this change exists to fix: an unfolded Fold was getting a
     * phone's keyboard, 48dp keys on a screen 835dp tall.
     */
    @Test
    public void aSplitKeyboardGetsTallerKeys() {
        assertTrue(KeyboardGeometry.shouldSplit(FOLD_UNFOLDED_SW_DP));
        assertEquals(56, KeyboardGeometry.keyHeightDp(false, true));
        assertEquals(50, KeyboardGeometry.keyHeightDp(true, true));
        assertTrue(KeyboardGeometry.keyHeightDp(false, true)
                > KeyboardGeometry.keyHeightDp(false, false));
    }

    /**
     * Height keys off the split decision, not the screen height, because the
     * Fold is 835dp tall and the phone 832dp — height cannot tell them apart.
     */
    @Test
    public void heightIsNotAUsableSignalForDeviceSize() {
        assertTrue("the premise: the two devices are the same height",
                Math.abs(FOLD_UNFOLDED_HEIGHT_DP - PHONE_HEIGHT_DP) < 10);
        assertTrue("width is what separates them",
                FOLD_UNFOLDED_SW_DP > PHONE_SW_DP * 1.9);
    }

    /**
     * The old 540dp span left a 752dp Fold with a 212dp gutter -- 28% of the
     * screen spent on empty space -- and 54dp keys.
     */
    @Test
    public void gutterLeavesUsableHalvesOnTheFold() {
        int gutter = KeyboardGeometry.gutterDp(FOLD_UNFOLDED_SW_DP);
        assertEquals(752 - 640, gutter);
        assertTrue("gutter should have shrunk from the old 212dp", gutter < 212);
        int perHalf = (FOLD_UNFOLDED_SW_DP - gutter) / 2;
        assertTrue("each half should stay thumb-sized, was " + perHalf + "dp",
                perHalf >= 280 && perHalf <= 320);
        int keyWidth = perHalf / 5;
        assertEquals("top-row key width", 64, keyWidth);
        assertTrue("should beat the old 54dp", keyWidth > 54);
    }

    /** Rotating the Fold keeps the key size and widens the gap, by design. */
    @Test
    public void rotatingTheFoldKeepsKeyWidthAndGrowsTheGap() {
        int portrait = KeyboardGeometry.gutterDp(FOLD_UNFOLDED_SW_DP);
        int landscape = KeyboardGeometry.gutterDp(FOLD_UNFOLDED_HEIGHT_DP);
        assertTrue("the gap absorbs the extra width", landscape > portrait);
        int portraitKey = (FOLD_UNFOLDED_SW_DP - portrait) / 10;
        int landscapeKey = (FOLD_UNFOLDED_HEIGHT_DP - landscape) / 10;
        assertEquals("key width should not change with rotation", portraitKey, landscapeKey);
    }

    /**
     * Floored so the halves never touch, and deliberately not capped: the gap
     * has to keep absorbing slack or the keys start growing instead.
     */
    @Test
    public void gutterIsFlooredButNotCapped() {
        assertEquals(KeyboardGeometry.SPLIT_MIN_GUTTER_DP, KeyboardGeometry.gutterDp(400));
        assertTrue(KeyboardGeometry.gutterDp(800) > KeyboardGeometry.SPLIT_MIN_GUTTER_DP);
        assertEquals(4000 - 640, KeyboardGeometry.gutterDp(4000));
    }

    /**
     * However wide the screen, a key stays a key. The halves hold their size
     * and the gap takes everything else.
     */
    @Test
    public void keyWidthIsStableAcrossEveryScreenWidth() {
        for (int width = 700; width <= 4000; width += 10) {
            int keyWidth = (width - KeyboardGeometry.gutterDp(width)) / 10;
            assertEquals("key width drifted at " + width + "dp", 64, keyWidth);
        }
    }

    /** qwert|yuiop, asdfg|hjkl, zxcv|bnm — the seam, not the arithmetic middle. */
    @Test
    public void rowsSplitOnTheQwertySeam() {
        assertEquals(5, KeyboardGeometry.splitIndex(KeyboardGeometry.Row.TOP, 10, true));
        assertEquals(5, KeyboardGeometry.splitIndex(KeyboardGeometry.Row.MIDDLE, 9, true));
        assertEquals(3, KeyboardGeometry.splitIndex(KeyboardGeometry.Row.BOTTOM_LETTERS, 7, true));
        assertEquals(5, KeyboardGeometry.splitIndex(KeyboardGeometry.Row.SYMBOLS, 10, true));
    }

    @Test
    public void noSplitWhenNotSplitting() {
        for (KeyboardGeometry.Row row : KeyboardGeometry.Row.values()) {
            assertEquals("row " + row + " must not split when split=false",
                    -1, KeyboardGeometry.splitIndex(row, 10, false));
        }
    }

    /** The 3x4 pinyin keypad is centred on a wide screen, never split. */
    @Test
    public void keypadIsNeverSplit() {
        assertEquals(-1, KeyboardGeometry.splitIndex(KeyboardGeometry.Row.KEYPAD, 4, true));
    }

    @Test
    public void veryShortRowsAreLeftAlone() {
        assertEquals(-1, KeyboardGeometry.splitIndex(KeyboardGeometry.Row.TOP, 3, true));
        assertEquals(-1, KeyboardGeometry.splitIndex(KeyboardGeometry.Row.TOP, 0, true));
    }

    /** Every split index must leave keys on both sides. */
    @Test
    public void splitAlwaysLeavesBothHalvesPopulated() {
        for (KeyboardGeometry.Row row : KeyboardGeometry.Row.values()) {
            for (int count = 4; count <= 12; count++) {
                int index = KeyboardGeometry.splitIndex(row, count, true);
                if (index < 0) {
                    continue;
                }
                assertTrue(row + "/" + count + " put nothing on the left", index > 0);
                assertTrue(row + "/" + count + " put nothing on the right", index < count);
            }
        }
    }
}

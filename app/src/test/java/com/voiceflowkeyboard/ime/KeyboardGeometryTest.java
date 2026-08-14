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
        assertEquals(KeyboardGeometry.KEY_HEIGHT_DP, KeyboardGeometry.keyHeightDp(false));
        assertEquals(KeyboardGeometry.KEY_HEIGHT_LANDSCAPE_DP, KeyboardGeometry.keyHeightDp(true));
        assertTrue(KeyboardGeometry.keyHeightDp(true) < KeyboardGeometry.keyHeightDp(false));
    }

    @Test
    public void gutterLeavesUsableHalvesOnTheFold() {
        int gutter = KeyboardGeometry.gutterDp(FOLD_UNFOLDED_SW_DP);
        assertEquals(752 - 540, gutter);
        int perHalf = (FOLD_UNFOLDED_SW_DP - gutter) / 2;
        assertTrue("each half should stay thumb-sized, was " + perHalf + "dp",
                perHalf >= 240 && perHalf <= 320);
    }

    @Test
    public void gutterIsClampedAtBothEnds() {
        assertEquals(KeyboardGeometry.SPLIT_MIN_GUTTER_DP, KeyboardGeometry.gutterDp(400));
        assertEquals(KeyboardGeometry.SPLIT_MAX_GUTTER_DP, KeyboardGeometry.gutterDp(4000));
        assertTrue(KeyboardGeometry.gutterDp(700) > KeyboardGeometry.SPLIT_MIN_GUTTER_DP);
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

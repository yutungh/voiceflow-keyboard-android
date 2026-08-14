package com.voiceflowkeyboard.ime;

/**
 * Which letter keys physically touch which, on the QWERTY layout the keyboard
 * actually draws.
 *
 * <p>This is the whole of the touch model. A real one would weight candidates by
 * where the finger landed, but {@code commitKey} receives a key's label and not
 * its coordinates, so the only spatial fact available after the fact is which
 * key the user probably meant to hit instead. That is still the difference
 * between a spellchecker and something that feels like a keyboard: it is what
 * makes {@code hime} correct to {@code home} — {@code i} and {@code o} are
 * neighbours — rather than to the equally-one-edit {@code hire}.
 *
 * <p>Adjacency is derived from the row geometry rather than hand-listed, so it
 * cannot drift out of step with the layout. Rows are staggered by half a key,
 * which makes a key's neighbours below it the two it straddles.
 */
final class KeyProximity {

    private static final String[] ROWS = {
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm",
    };

    private static final boolean[][] ADJACENT = build();

    private KeyProximity() {
    }

    /** True when {@code a} and {@code b} are different, neighbouring letter keys. */
    static boolean isAdjacent(char a, char b) {
        int left = index(a);
        int right = index(b);
        if (left < 0 || right < 0) {
            return false;
        }
        return ADJACENT[left][right];
    }

    private static int index(char value) {
        if (value >= 'a' && value <= 'z') {
            return value - 'a';
        }
        if (value >= 'A' && value <= 'Z') {
            return value - 'A';
        }
        return -1;
    }

    private static boolean[][] build() {
        boolean[][] adjacent = new boolean[26][26];
        for (int row = 0; row < ROWS.length; row++) {
            String keys = ROWS[row];
            for (int column = 0; column < keys.length(); column++) {
                char key = keys.charAt(column);
                if (column + 1 < keys.length()) {
                    link(adjacent, key, keys.charAt(column + 1));
                }
                if (row + 1 >= ROWS.length) {
                    continue;
                }
                // The next row sits half a key to the right, so this key
                // overhangs exactly the two below it at column - 1 and column.
                String below = ROWS[row + 1];
                if (column - 1 >= 0 && column - 1 < below.length()) {
                    link(adjacent, key, below.charAt(column - 1));
                }
                if (column < below.length()) {
                    link(adjacent, key, below.charAt(column));
                }
            }
        }
        return adjacent;
    }

    private static void link(boolean[][] adjacent, char a, char b) {
        int left = a - 'a';
        int right = b - 'a';
        adjacent[left][right] = true;
        adjacent[right][left] = true;
    }
}

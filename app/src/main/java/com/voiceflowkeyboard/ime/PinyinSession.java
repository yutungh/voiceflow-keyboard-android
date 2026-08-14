package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The composing half of Chinese input: what has been typed but not yet turned
 * into Hanzi.
 *
 * <p>This exists as its own object because ending a composition is a
 * transaction with many entry points — space, punctuation, return, switching
 * layout or language, starting a recording, the cursor moving away, the editor
 * being torn down, the keyboard being rebuilt on rotation. Letting each of those
 * call sites do its own partial cleanup is how composing text leaks into other
 * apps, which is the classic IME bug. They all go through {@link #settle}
 * instead, which decides the outcome and clears the session atomically.
 *
 * <p>The session never touches an InputConnection. It returns a {@link Settlement}
 * describing what should happen and lets the service apply it, which keeps this
 * class free of Android types and unit testable.
 */
final class PinyinSession {

    /** Why a composition is ending. Determines what happens to the buffer. */
    enum SettleReason {
        /** Space, punctuation, or return: take the best reading. */
        ACCEPT_TOP,
        /** Switching language or layout: take the best reading rather than lose it. */
        MODE_CHANGED,
        /** A recording is starting; the buffer must not interleave with the transcript. */
        RECORDING_STARTED,
        /** Cursor moved out of the composing span; leave what is on screen alone. */
        CURSOR_MOVED,
        /** User backed out of the whole buffer, or explicitly cancelled. */
        CANCELLED,
        /**
         * The editor is going away or has already changed. Nothing may be written:
         * committing here can land text in the wrong field.
         */
        EDITOR_GONE
    }

    enum Action {
        /** Nothing was composing; the caller should carry on unchanged. */
        NOTHING,
        /** Replace the composing span with {@link Settlement#text}. */
        COMMIT,
        /** Finish composing and leave the raw letters the user typed in place. */
        KEEP_RAW,
        /** Remove the composing span entirely. */
        DISCARD,
        /** Drop internal state only; do not touch any input connection. */
        CLEAR_STATE_ONLY
    }

    static final class Settlement {
        final Action action;
        final String text;

        private Settlement(Action action, String text) {
            this.action = action;
            this.text = text;
        }

        static final Settlement NOTHING = new Settlement(Action.NOTHING, "");

        boolean isNothing() {
            return action == Action.NOTHING;
        }
    }

    /** How many candidates to surface; the strip scrolls, so this is generous. */
    private static final int CANDIDATE_LIMIT = 30;

    private PinyinComposer composer;
    private final StringBuilder buffer = new StringBuilder();
    private List<PinyinCandidate> candidates = Collections.emptyList();

    PinyinSession(PinyinComposer composer) {
        this.composer = composer;
    }

    /**
     * Swaps the search used for subsequent input, as when moving between the
     * QWERTY and 9-key layouts. The caller is expected to have settled first —
     * a half-typed buffer means nothing in the other mode.
     */
    void setComposer(PinyinComposer composer) {
        this.composer = composer;
        refresh();
    }

    boolean isComposing() {
        return buffer.length() > 0;
    }

    /** The raw letters or digits typed so far, shown as composing text. */
    String rawText() {
        return buffer.toString();
    }

    List<PinyinCandidate> candidates() {
        return candidates;
    }

    /** Best reading, or null when the buffer resolves to nothing. */
    private String topText() {
        return candidates.isEmpty() ? null : candidates.get(0).text;
    }

    /**
     * Feeds one typed character into the buffer.
     *
     * @return false when the character is not usable in this mode, in which case
     *     the caller should handle the key normally
     */
    boolean append(char typed) {
        if (composer == null) {
            return false;
        }
        String candidateBuffer = buffer.toString() + typed;
        if (candidateBuffer.length() > PinyinComposer.MAX_INPUT_LENGTH) {
            return false;
        }
        buffer.append(typed);
        refresh();
        return true;
    }

    /**
     * Handles a backspace.
     *
     * @return true when the buffer absorbed it; false when there was nothing
     *     composing and the caller should delete real text instead
     */
    boolean backspace() {
        if (buffer.length() == 0) {
            return false;
        }
        buffer.deleteCharAt(buffer.length() - 1);
        refresh();
        return true;
    }

    /**
     * Commits the candidate at {@code index}. When it only covers part of the
     * buffer the remainder stays composing, which is how a long run is committed
     * piece by piece.
     */
    Settlement select(int index) {
        if (index < 0 || index >= candidates.size()) {
            return Settlement.NOTHING;
        }
        PinyinCandidate chosen = candidates.get(index);
        int consumed = Math.min(Math.max(chosen.consumed, 0), buffer.length());
        buffer.delete(0, consumed);
        refresh();
        return new Settlement(Action.COMMIT, chosen.text);
    }

    /** Ends the composition, clearing the session whatever the outcome. */
    Settlement settle(SettleReason reason) {
        if (buffer.length() == 0) {
            clear();
            return Settlement.NOTHING;
        }
        String raw = buffer.toString();
        String top = topText();
        clear();

        switch (reason) {
            case EDITOR_GONE:
                return new Settlement(Action.CLEAR_STATE_ONLY, "");
            case CANCELLED:
                return new Settlement(Action.DISCARD, "");
            case CURSOR_MOVED:
                return new Settlement(Action.KEEP_RAW, raw);
            case ACCEPT_TOP:
            case MODE_CHANGED:
            case RECORDING_STARTED:
            default:
                // Falling back to the raw letters rather than dropping them keeps
                // a mistyped run recoverable instead of silently vanishing.
                return top == null
                        ? new Settlement(Action.KEEP_RAW, raw)
                        : new Settlement(Action.COMMIT, top);
        }
    }

    private void clear() {
        buffer.setLength(0);
        candidates = Collections.emptyList();
    }

    private void refresh() {
        if (buffer.length() == 0 || composer == null) {
            candidates = Collections.emptyList();
            return;
        }
        List<PinyinCandidate> found = composer.candidates(buffer.toString(), CANDIDATE_LIMIT);
        candidates = found.isEmpty() ? Collections.<PinyinCandidate>emptyList() : new ArrayList<>(found);
    }
}

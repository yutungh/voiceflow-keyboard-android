package com.voiceflowkeyboard.ime;

/** One choice offered for the current pinyin buffer. */
final class PinyinCandidate {

    /** The Hanzi to commit. */
    final String text;

    /**
     * How many characters of the raw pinyin buffer this candidate consumes.
     * Equal to the whole buffer for a full match or a completion; shorter when
     * the candidate only covers a leading part of what was typed.
     */
    final int consumed;

    /**
     * True when the candidate spells out more syllables than the user has typed
     * so far, i.e. it is a completion rather than an exact reading.
     */
    final boolean completion;

    final double score;

    PinyinCandidate(String text, int consumed, boolean completion, double score) {
        this.text = text;
        this.consumed = consumed;
        this.completion = completion;
        this.score = score;
    }

    @Override
    public String toString() {
        return text + "(" + consumed + (completion ? ",completion" : "") + ")";
    }
}

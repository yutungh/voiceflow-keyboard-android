package com.voiceflowkeyboard.ime;

import java.util.List;

/**
 * The only thing {@link PinyinComposer} needs from a dictionary: given a span of
 * typed input, what words could it spell?
 *
 * <p>This seam is what lets full-QWERTY pinyin and the 9-key keypad share one
 * search. The Viterbi pass does not care whether a span is letters ("nihao") or
 * digits ("64426") — only whether the span resolves to entries.
 */
interface PinyinLookup {

    /**
     * Strips the raw buffer down to the characters this mode understands,
     * lower-casing where relevant, capped at {@code maxLength}.
     */
    String normalize(String rawInput, int maxLength);

    /** Entries spelled exactly by {@code span}, ordered by descending frequency. */
    List<PinyinDictionary.Entry> entriesFor(String span);

    /**
     * Entries for spans that begin with {@code prefix} but are longer — the
     * completions offered mid-word. At most one per distinct key, so a short
     * prefix cannot fan out into tens of thousands of parses.
     */
    List<PinyinDictionary.Entry> completionsFor(String prefix, int limit);

    /** Longest span worth attempting; spans beyond this can never match. */
    int maxSpanLength();

    /** Sum of all entry frequencies, the unigram normaliser. */
    long totalFrequency();
}

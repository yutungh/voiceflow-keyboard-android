package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a raw pinyin buffer into ranked Hanzi candidates.
 *
 * <p>The dictionary caps entries at four syllables, so anything longer has to be
 * assembled from several entries. That is done with a k-best Viterbi pass over
 * the buffer: every position keeps the best few partial readings, each extended
 * by any dictionary key that matches the text starting there.
 *
 * <p>Scoring is a unigram language model: each segment contributes
 * {@code log(frequency / totalFrequency)}, which is negative, so every extra
 * segment makes a reading strictly worse and a known phrase beats the same
 * characters stitched together from commoner parts. Summing raw log-frequencies
 * instead would do the opposite — each additional segment would add a large
 * positive term, and "nihao" would decode as 你 + 哈 + 哦.
 *
 * <p>Candidates come back in three tiers, which is what makes the ordering
 * predictable: readings that consume the whole buffer, then completions that
 * spell out more than was typed, then readings that cover only a leading part
 * of the buffer (the escape hatch when a typo leaves a dangling tail).
 *
 * <p>Pure Java by design — no Android types — so it is unit tested directly.
 */
final class PinyinComposer {

    /** Longest buffer we will analyse; beyond this the user should commit something. */
    static final int MAX_INPUT_LENGTH = 32;

    /** Partial readings retained per position during the Viterbi pass. */
    private static final int BEAM_WIDTH = 6;

    /** Dictionary entries considered per key when extending a path. */
    private static final int ENTRIES_PER_SEGMENT = 4;

    private final PinyinLookup dictionary;

    /**
     * log(totalFrequency) — the unigram normaliser, and therefore the implicit
     * cost of opening another segment. Derived from the shipped data rather
     * than hand-tuned.
     */
    private final double logTotalFrequency;

    PinyinComposer(PinyinLookup dictionary) {
        this.dictionary = dictionary;
        this.logTotalFrequency = Math.log(Math.max(1L, dictionary.totalFrequency()));
    }

    /** Unigram log-probability of one dictionary entry. Always negative. */
    private double logProbability(int frequency) {
        return Math.log(frequency + 1.0) - logTotalFrequency;
    }

    private static final class Path {
        final String text;
        final double score;

        Path(String text, double score) {
            this.text = text;
            this.score = score;
        }
    }

    /**
     * Ranked candidates for {@code rawInput}.
     *
     * @param limit maximum number of candidates to return
     */
    List<PinyinCandidate> candidates(String rawInput, int limit) {
        if (rawInput == null || limit <= 0) {
            return Collections.emptyList();
        }

        String input = dictionary.normalize(rawInput, MAX_INPUT_LENGTH);
        if (input.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Path>> paths = viterbi(input);

        List<PinyinCandidate> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        addFullReadings(input, paths, results, seen);
        addCompletions(input, results, seen, limit);
        addPartialReadings(input, paths, results, seen, limit);

        return results.size() > limit ? new ArrayList<>(results.subList(0, limit)) : results;
    }

    /** Best single reading of the whole buffer, or null when it cannot be covered. */
    String topCandidateText(String rawInput) {
        List<PinyinCandidate> found = candidates(rawInput, 1);
        return found.isEmpty() ? null : found.get(0).text;
    }

    private List<List<Path>> viterbi(String input) {
        int n = input.length();
        List<List<Path>> paths = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            paths.add(new ArrayList<>(BEAM_WIDTH));
        }
        paths.get(0).add(new Path("", 0.0));

        int maxKey = Math.min(dictionary.maxSpanLength(), n);
        for (int start = 0; start < n; start++) {
            List<Path> prefixPaths = paths.get(start);
            if (prefixPaths.isEmpty()) {
                continue;
            }
            for (int length = 1; length <= maxKey && start + length <= n; length++) {
                int end = start + length;
                List<PinyinDictionary.Entry> entries = dictionary.entriesFor(input.substring(start, end));
                if (entries.isEmpty()) {
                    continue;
                }
                int considered = Math.min(entries.size(), ENTRIES_PER_SEGMENT);
                for (int e = 0; e < considered; e++) {
                    PinyinDictionary.Entry entry = entries.get(e);
                    double gain = logProbability(entry.frequency);
                    for (Path prefix : prefixPaths) {
                        offer(paths.get(end), new Path(prefix.text + entry.word, prefix.score + gain));
                    }
                }
            }
        }
        return paths;
    }

    /** Keeps a beam sorted by descending score, bounded at {@link #BEAM_WIDTH}. */
    private static void offer(List<Path> beam, Path candidate) {
        for (int i = 0; i < beam.size(); i++) {
            if (beam.get(i).text.equals(candidate.text)) {
                if (candidate.score > beam.get(i).score) {
                    beam.set(i, candidate);
                    beam.sort((a, b) -> Double.compare(b.score, a.score));
                }
                return;
            }
        }
        if (beam.size() < BEAM_WIDTH) {
            beam.add(candidate);
            beam.sort((a, b) -> Double.compare(b.score, a.score));
            return;
        }
        Path weakest = beam.get(beam.size() - 1);
        if (candidate.score > weakest.score) {
            beam.set(beam.size() - 1, candidate);
            beam.sort((a, b) -> Double.compare(b.score, a.score));
        }
    }

    /**
     * Tier one: readings covering the entire buffer. Every exact entry for the
     * whole buffer is included — that is the homophone list the user expects for
     * a single syllable — alongside the multi-segment paths the beam found.
     */
    private void addFullReadings(
            String input,
            List<List<Path>> paths,
            List<PinyinCandidate> results,
            Set<String> seen
    ) {
        Map<String, Double> scored = new HashMap<>();
        for (Path path : paths.get(input.length())) {
            merge(scored, path.text, path.score);
        }
        for (PinyinDictionary.Entry entry : dictionary.entriesFor(input)) {
            merge(scored, entry.word, logProbability(entry.frequency));
        }
        emit(scored, input.length(), false, results, seen, Integer.MAX_VALUE);
    }

    /** Tier two: the buffer is a strict prefix of longer dictionary keys. */
    private void addCompletions(
            String input,
            List<PinyinCandidate> results,
            Set<String> seen,
            int limit
    ) {
        if (results.size() >= limit) {
            return;
        }
        Map<String, Double> scored = new HashMap<>();
        for (PinyinDictionary.Entry entry : dictionary.completionsFor(input, limit * 4)) {
            merge(scored, entry.word, logProbability(entry.frequency));
        }
        emit(scored, input.length(), true, results, seen, limit - results.size());
    }

    /**
     * Tier three: nothing covers the whole buffer, so offer the longest leading
     * stretch that does. Lets the user commit the good part of a mistyped run
     * instead of deleting back to the start.
     */
    private void addPartialReadings(
            String input,
            List<List<Path>> paths,
            List<PinyinCandidate> results,
            Set<String> seen,
            int limit
    ) {
        if (results.size() >= limit) {
            return;
        }
        for (int end = input.length() - 1; end > 0; end--) {
            List<Path> beam = paths.get(end);
            if (beam.isEmpty()) {
                continue;
            }
            Map<String, Double> scored = new HashMap<>();
            for (Path path : beam) {
                merge(scored, path.text, path.score);
            }
            emit(scored, end, false, results, seen, limit - results.size());
            return;
        }
    }

    private static void merge(Map<String, Double> scored, String text, double score) {
        Double existing = scored.get(text);
        if (existing == null || score > existing) {
            scored.put(text, score);
        }
    }

    private static void emit(
            Map<String, Double> scored,
            int consumed,
            boolean completion,
            List<PinyinCandidate> results,
            Set<String> seen,
            int room
    ) {
        if (room <= 0) {
            return;
        }
        List<Map.Entry<String, Double>> ordered = new ArrayList<>(scored.entrySet());
        ordered.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int added = 0;
        for (Map.Entry<String, Double> entry : ordered) {
            if (added >= room) {
                return;
            }
            if (seen.add(entry.getKey())) {
                results.add(new PinyinCandidate(entry.getKey(), consumed, completion, entry.getValue()));
                added++;
            }
        }
    }
}

package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ranks spelling corrections for a typed English word against
 * {@link EnglishDictionary}.
 *
 * <p>Deliberately free of Android dependencies so it can be unit tested on the
 * JVM, and free of any {@code InputConnection} knowledge so the service stays
 * the only thing that mutates the editor.
 *
 * <p><b>Not thread-safe.</b> The distance scratch buffers are per-instance to
 * keep several thousand small allocations per keystroke out of the GC, so a
 * single instance must stay on one thread — the service's dedicated typing
 * executor.
 *
 * <h2>How a candidate is chosen</h2>
 *
 * Correction never changes the first letter, so the search scans exactly one
 * first-letter bucket of the lexicon, narrowed to words within
 * {@link #LENGTH_WINDOW} characters of what was typed. Everything else is
 * unreachable and must not be paid for. First-letter transpositions
 * ({@code hte} for {@code the}) are consequently out of reach here and stay the
 * job of the caller's explicit typo table.
 *
 * <p>Ordinary Damerau-Levenshtein decides <i>eligibility</i> and the edit count;
 * keyboard adjacency and word frequency only ever <i>rank</i> within a fixed
 * edit count. Keeping those separate is what makes "one edit, safe to replace
 * silently" and "two edits, offer but never impose" a crisp distinction rather
 * than a blended score that drifts.
 */
final class EnglishCorrector {

    /** Beyond this many edits a word is a different word, not a typo. */
    static final int MAX_EDITS = 2;

    /** Only consider dictionary words within this many characters of the input. */
    static final int LENGTH_WINDOW = 2;

    /** Below this length a typo is indistinguishable from a deliberate short word. */
    static final int SILENT_MIN_LENGTH = 3;

    /**
     * How far the best candidate must beat the runner-up before we replace
     * without asking. Scores are natural-log-scaled by the dictionary's
     * {@code logScale}, so 1000 means "about 2.7x more likely".
     */
    static final int SILENT_MARGIN = 1000;

    /**
     * Added to a candidate that differs by exactly one substitution of a
     * physically adjacent key. Worth roughly {@code e^1.5} in likelihood — enough
     * to lift a genuine fat-finger fix over a marginally commoner word, not
     * enough to beat a large frequency gap.
     */
    static final int ADJACENCY_BONUS = 1500;

    /**
     * Subtracted from a candidate reached by substituting a key nowhere near the
     * one typed. Hitting {@code t} while aiming for {@code c} is not a slip the
     * hand makes; dropping or doubling a letter is. Without this the model is
     * lopsided — it rewards plausible substitutions but treats implausible ones
     * as free, so raw frequency decides and {@code wich} corrects to
     * {@code with} rather than {@code which}.
     *
     * <p>{@code e^2}, about sevenfold, is a prior rather than a measurement.
     * It and {@link #ADJACENCY_BONUS} are the two numbers Phase 6 should
     * calibrate against a real typo corpus.
     */
    static final int NON_ADJACENT_PENALTY = 2000;

    /** Ranked corrections plus the verdict on replacing without asking. */
    static final class Result {
        final List<String> words;
        final boolean autoAccept;

        Result(List<String> words, boolean autoAccept) {
            this.words = words;
            this.autoAccept = autoAccept;
        }

        boolean isEmpty() {
            return words.isEmpty();
        }

        static final Result NONE = new Result(Collections.<String>emptyList(), false);
    }

    private static final class Candidate {
        final String word;
        final int distance;
        final int score;

        Candidate(String word, int distance, int score) {
            this.word = word;
            this.distance = distance;
            this.score = score;
        }
    }

    private final EnglishDictionary dictionary;
    private final int[] rowBeforeLast;
    private final int[] previousRow;
    private final int[] currentRow;

    EnglishCorrector(EnglishDictionary dictionary) {
        this.dictionary = dictionary;
        int width = dictionary.maxWordLength() + 2;
        this.rowBeforeLast = new int[width];
        this.previousRow = new int[width];
        this.currentRow = new int[width];
    }

    /**
     * Best corrections for {@code typed}, most likely first, capped at
     * {@code limit}.
     *
     * <p>Returns {@link Result#NONE} when the input is already a real word: the
     * commonest way an autocorrect feels broken is changing something the user
     * spelled correctly on purpose.
     */
    Result suggest(String typed, int limit) {
        if (typed == null || limit <= 0) {
            return Result.NONE;
        }
        String word = typed.toLowerCase(Locale.US);
        if (word.length() < 2 || word.length() > dictionary.maxWordLength()) {
            return Result.NONE;
        }
        if (!isWordCharacters(word) || dictionary.contains(word)) {
            return Result.NONE;
        }

        char first = word.charAt(0);
        int start = dictionary.bucketStart(first);
        int end = dictionary.bucketEnd(first);
        if (start < 0) {
            return Result.NONE;
        }

        int shortest = word.length() - LENGTH_WINDOW;
        int longest = word.length() + LENGTH_WINDOW;
        List<Candidate> found = new ArrayList<>();
        for (int i = start; i < end; i++) {
            String candidate = dictionary.wordAt(i);
            int length = candidate.length();
            if (length < shortest || length > longest) {
                continue;
            }
            int distance = boundedDistance(word, candidate, MAX_EDITS);
            if (distance < 1 || distance > MAX_EDITS) {
                continue;
            }
            int score = dictionary.logFrequencyAt(i) + substitutionAdjustment(word, candidate);
            found.add(new Candidate(candidate, distance, score));
        }
        if (found.isEmpty()) {
            return Result.NONE;
        }

        // Fewer edits always wins; ranking only ever breaks ties inside one
        // edit count, so a two-edit word can never displace a one-edit word
        // however common it is.
        Collections.sort(found, (a, b) -> a.distance != b.distance
                ? Integer.compare(a.distance, b.distance)
                : Integer.compare(b.score, a.score));

        List<String> ranked = new ArrayList<>(Math.min(limit, found.size()));
        for (int i = 0; i < found.size() && ranked.size() < limit; i++) {
            ranked.add(restoreContractionCase(found.get(i).word));
        }
        return new Result(ranked, allowsSilentReplacement(word, found));
    }

    /** Completions for a word still being typed. Never a correction. */
    List<String> complete(String prefix, int limit) {
        if (prefix == null || prefix.length() < 2) {
            return Collections.emptyList();
        }
        String lowered = prefix.toLowerCase(Locale.US);
        if (!isWordCharacters(lowered)) {
            return Collections.emptyList();
        }
        List<String> completions = dictionary.completionsFor(lowered, limit);
        List<String> cased = new ArrayList<>(completions.size());
        for (String completion : completions) {
            cased.add(restoreContractionCase(completion));
        }
        return cased;
    }

    /**
     * Whether the top candidate may replace the typed word with no interaction.
     *
     * <p>Independent guards, because a wrong silent replacement is far more
     * damaging than a missed one: the word must be long enough to be a plausible
     * typo, it must not look like an unfinished word, the fix must be a single
     * edit, and it must be clearly ahead of the next single-edit candidate. The
     * typed word having already been rejected as a real word is checked by the
     * caller in {@link #suggest}.
     */
    private boolean allowsSilentReplacement(String word, List<Candidate> ranked) {
        if (word.length() < SILENT_MIN_LENGTH) {
            return false;
        }
        // The typed word is not in the lexicon, so any word it prefixes is a
        // strictly longer one — meaning it reads equally well as something
        // half-typed, or as a longer word with its last letter dropped.
        // "ther" is a prefix of "there"; replacing it outright with the far
        // commoner "the" would silently change what was said. Offer both as
        // chips instead and let the user pick.
        if (dictionary.firstIndexWithPrefix(word) >= 0) {
            return false;
        }
        Candidate best = ranked.get(0);
        if (best.distance != 1) {
            return false;
        }
        for (int i = 1; i < ranked.size(); i++) {
            Candidate other = ranked.get(i);
            if (other.distance != 1) {
                // Everything past here is a two-edit candidate and cannot
                // meaningfully compete with a one-edit fix.
                break;
            }
            if (best.score - other.score < SILENT_MARGIN) {
                return false;
            }
        }
        return true;
    }

    /**
     * The lexicon stores the first-person contractions lowercase ({@code i'm},
     * {@code i've}), inherited from the upstream frequency list. Emitting them
     * verbatim would have the keyboard "correct" a typo into something that
     * itself needs correcting, and the caller's generic case matching cannot fix
     * it because the typed word is lowercase too.
     */
    private static String restoreContractionCase(String word) {
        if (word.length() >= 2 && word.charAt(0) == 'i' && word.charAt(1) == '\'') {
            return 'I' + word.substring(1);
        }
        return word;
    }

    /**
     * How much the shape of the edit argues for or against a candidate, on the
     * same log scale as the frequencies.
     *
     * <p>Only single substitutions get an opinion. Insertions, deletions and
     * transpositions score zero — all are ordinary slips, and the model has
     * nothing to distinguish them by. A substitution is different: it says the
     * finger landed on a specific wrong key, which is likely if that key is next
     * to the intended one and unlikely if it is across the keyboard.
     */
    private static int substitutionAdjustment(String typed, String candidate) {
        if (typed.length() != candidate.length()) {
            return 0;
        }
        int difference = -1;
        for (int i = 0; i < typed.length(); i++) {
            if (typed.charAt(i) != candidate.charAt(i)) {
                if (difference >= 0) {
                    // Two or more differing positions: a transposition, or too
                    // far away to reason about.
                    return 0;
                }
                difference = i;
            }
        }
        if (difference < 0) {
            return 0;
        }
        char typedKey = typed.charAt(difference);
        char candidateKey = candidate.charAt(difference);
        if (!Character.isLetter(typedKey) || !Character.isLetter(candidateKey)) {
            // An apostrophe is not on the letter grid, so proximity has no
            // opinion either way and pretending otherwise would penalise every
            // contraction.
            return 0;
        }
        return KeyProximity.isAdjacent(typedKey, candidateKey)
                ? ADJACENCY_BONUS
                : -NON_ADJACENT_PENALTY;
    }

    private static boolean isWordCharacters(String word) {
        for (int i = 0; i < word.length(); i++) {
            char value = word.charAt(i);
            if (!Character.isLetter(value) && value != '\'') {
                return false;
            }
        }
        return true;
    }

    /**
     * Optimal-string-alignment Damerau-Levenshtein, abandoning a candidate as
     * soon as every cell in a row exceeds {@code max}. Returns {@code max + 1}
     * for anything further away, which callers treat as "not a candidate".
     *
     * <p>Counting an adjacent transposition as one edit rather than two is what
     * makes {@code teh} a near miss for {@code the} instead of a distant one.
     */
    private int boundedDistance(String a, String b, int max) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > max) {
            return max + 1;
        }
        int[] beforeLast = rowBeforeLast;
        int[] previous = previousRow;
        int[] current = currentRow;
        for (int j = 0; j <= m; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            current[0] = i;
            int rowMinimum = current[0];
            char ai = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ai == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
                if (i > 1 && j > 1
                        && ai == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    value = Math.min(value, beforeLast[j - 2] + 1);
                }
                current[j] = value;
                if (value < rowMinimum) {
                    rowMinimum = value;
                }
            }
            if (rowMinimum > max) {
                return max + 1;
            }
            int[] recycled = beforeLast;
            beforeLast = previous;
            previous = current;
            current = recycled;
        }
        return previous[m];
    }
}

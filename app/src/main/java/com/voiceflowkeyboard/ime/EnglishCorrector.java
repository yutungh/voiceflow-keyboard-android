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
     * Two-letter slips allowed through {@link #SILENT_MIN_LENGTH}, as an
     * explicit reviewed list rather than a rule.
     *
     * <p>Lowering the gate to two was measured instead of assumed, by running
     * all 676 two-letter strings through the engine: 279 of them would be
     * silently rewritten. Among them {@code mr -> me}, {@code ms -> me},
     * {@code tv -> to}, {@code ai -> a}, {@code oz -> of}, {@code cc -> cd} and
     * {@code rx -> re}. Two letters is simply too little evidence — the space is
     * dense with initials, titles, units, state codes and abbreviations, and no
     * lexicon can enumerate them.
     *
     * <p>So the length rule stays and the exceptions are named. Each pair here
     * was checked to be the engine's top candidate already.
     *
     * <p>A named pair waives both the length gate and the completion guard, and
     * nothing else — the single-edit and runner-up-margin checks still apply.
     * Waiving the completion guard is deliberate: at two characters almost
     * everything prefixes a common word, so it blocks indiscriminately. It asks
     * "did the user drop a suffix?", and {@code si} standing in for
     * {@code since} would mean dropping three letters, which is not a slip. Both
     * guards are proxies for uncertainty, and an explicit review is the thing
     * they were standing in for.
     *
     * <p>Kept in the corrector rather than the service's {@code COMMON_TYPOS}
     * table on purpose: that table is deliberately unlearnable, so rejecting one
     * of its corrections would not stick, whereas rejecting one of these teaches
     * the word permanently.
     */
    /**
     * The runner-up margin applied to a reviewed short pair, instead of
     * {@link #SILENT_MARGIN}.
     *
     * <p>{@link #SILENT_MARGIN} was raised to 3000 by calibration against long
     * real-word typos, where the danger is a plausible wrong word. That number
     * has nothing to say about a hand-checked two-letter pair, and applying it
     * blindly would kill {@code ti -> to}, whose margin is 2962 — "to" being
     * twenty times likelier than the runner-up "it" is not ambiguity, it just
     * fell under a threshold raised for a different reason.
     *
     * <p>1000 is the original general margin, so a reviewed pair still has to
     * clear the bar the whole engine used to use. It remains a real filter:
     * {@code si -> is} and {@code ni -> in} were both on this list and both fail
     * it, at margins of roughly 114 and 702.
     */
    static final int SHORT_PAIR_MARGIN = 1000;

    private static final String[][] SHORT_PAIRS = {
            {"ti", "to"},
            {"od", "of"},
            // Considered and rejected: si -> is and ni -> in. Both are ranked
            // top by the engine, but only barely: "si" is nearly as close to
            // "so" as to "is", and "ni" to "no" as to "in", so each falls to the
            // runner-up margin. That guard is not a proxy for uncertainty the
            // way the other two are — it is the measurement of it — so a review
            // does not get to overrule it. They stay chip-only.
    };

    /**
     * How far the best candidate must beat the runner-up before we replace
     * without asking. Scores are natural-log-scaled by the dictionary's
     * {@code logScale}, so 3000 means "about 20x more likely".
     *
     * <p>Calibrated against real human typos rather than chosen — see
     * {@link EnglishCorrectorRealCorpusTest}. Measured on 2,392 Wikipedia editor
     * misspellings, silent-replacement precision against this value:
     *
     * <pre>
     *   1000 -> 86.9%   (1,852 silent)
     *   2000 -> 89.8%   (1,751)
     *   3000 -> 91.4%   (1,668)
     *   4000 -> 92.8%   (1,595)
     *   6000 -> 94.1%   (1,486)
     * </pre>
     *
     * <p>It started at 1000, picked off a generated corpus that flattered it
     * badly — that corpus reported 99.3% precision where real typos gave 86.9%,
     * because it only ever contained the errors we thought to simulate.
     *
     * <p>3000 rather than higher because of what the trade costs. Going from
     * 1000 to 3000 gives up 85 correct silent fixes to prevent 99 wrong ones,
     * which is worth it when a wrong one is the more damaging outcome. By 4000
     * the exchange is roughly one-for-one, and past that it is losing more good
     * corrections than it prevents bad ones.
     */
    static final int SILENT_MARGIN = 3000;

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

    /**
     * How much likelier the alternative must be before a word that is itself in
     * the lexicon may be silently replaced. 8000 is about 3,000:1.
     *
     * <p>Without an exception here the keyboard cannot fix {@code nit} to
     * {@code not}, which is a slip people make constantly — {@code i} and
     * {@code o} are neighbours and {@code not} is some 3,250 times commoner —
     * and refusing it is far more visibly wrong than the risk of allowing it.
     *
     * <p>The bar is this high because of what sits just below it. At a 400:1 gap
     * the rule would also catch {@code ate -> are}: "ate" is an everyday verb,
     * and silently rewriting "I ate lunch" would be indefensible. A frequency
     * floor on the typed word does not save it — "ate" ranks below plenty of
     * obscure words in a book corpus, which badly understates ordinary
     * conversational vocabulary. At 3,000:1 "ate" and its neighbours drop out
     * and 56 word pairs remain, the least obscure being {@code foe -> for} and
     * {@code mire -> more}.
     *
     * <p>Those last two are the honest cost, and what the backspace-revert
     * contract is for: reverting restores the word and teaches it permanently
     * via {@code Prefs.learnWord}. Note that is also why {@code nit} must not
     * simply be added to {@code COMMON_TYPOS} instead — the service refuses to
     * learn any word in that table, so rejecting the correction would not stick.
     */
    static final int REAL_WORD_MIN_FREQUENCY_GAP = 8000;

    /**
     * How close a possible completion has to be before it blocks a silent
     * replacement. About 55:1.
     *
     * <p>This guard used to refuse outright whenever the typed word prefixed
     * anything longer, which was far too blunt: {@code wich} prefixes
     * {@code wichita} and {@code teh} prefixes {@code tehran}, so two of the
     * commonest typos in English were being protected by obscure place names.
     * What matters is not whether a completion exists but whether it is a
     * plausible thing to have meant.
     *
     * <p>The margin sits between the measured cases: {@code ther} leads its
     * completion {@code there} by 3,496 and must still be blocked, while
     * {@code wich} leads {@code wichita} by 4,684 and must be allowed through.
     */
    static final int COMPLETION_AMBIGUITY_MARGIN = 4000;

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
     * <p>A word that is itself in the lexicon is almost always left alone — the
     * commonest way an autocorrect feels broken is changing something the user
     * spelled correctly on purpose. The one exception is
     * {@link #REAL_WORD_MIN_FREQUENCY_GAP}, below. When a real word does not
     * clear that bar it returns {@link Result#NONE} outright rather than
     * offering chips, because suggesting corrections for correctly spelled words
     * would make the strip noise.
     */
    Result suggest(String typed, int limit) {
        if (typed == null || limit <= 0) {
            return Result.NONE;
        }
        String word = typed.toLowerCase(Locale.US);
        if (word.length() < 2 || word.length() > dictionary.maxWordLength()) {
            return Result.NONE;
        }
        if (!isWordCharacters(word)) {
            return Result.NONE;
        }
        boolean realWord = dictionary.contains(word);

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
        addFirstLetterSwap(word, realWord, found);
        if (found.isEmpty()) {
            return Result.NONE;
        }

        // Fewer edits always wins; ranking only ever breaks ties inside one
        // edit count, so a two-edit word can never displace a one-edit word
        // however common it is.
        Collections.sort(found, (a, b) -> a.distance != b.distance
                ? Integer.compare(a.distance, b.distance)
                : Integer.compare(b.score, a.score));

        boolean autoAccept = allowsSilentReplacement(word, found, realWord);
        if (realWord && !autoAccept) {
            return Result.NONE;
        }

        List<String> ranked = new ArrayList<>(Math.min(limit, found.size()));
        for (int i = 0; i < found.size() && ranked.size() < limit; i++) {
            ranked.add(restoreContractionCase(found.get(i).word));
        }
        return new Result(ranked, autoAccept);
    }

    /**
     * Adds the word with its first two characters swapped, when that happens to
     * be a real word.
     *
     * <p>Swapping the first two letters is the one common slip the bucket scan
     * structurally cannot see, because it is the only single edit that changes
     * the first letter — and it accounts for {@code hte}, {@code ot},
     * {@code si}, {@code ni}, {@code nad}, {@code ofr}, {@code htat},
     * {@code iwth}, {@code oyu} and {@code rfom}, which are among the most
     * frequent typos in English.
     *
     * <p>Probed directly rather than by scanning a second bucket. There is
     * exactly one candidate to consider, so one binary search answers it; the
     * alternative doubles a scan that already costs most of the keystroke budget.
     *
     * <p>Restricted to inputs that are not themselves real words, so it cannot
     * feed the deliberately narrow real-word override, and given the plain
     * dictionary score with no bonus: on a touchscreen, transpositions are far
     * rarer than adjacent-key slips, so this must not outrank one.
     */
    private void addFirstLetterSwap(String word, boolean realWord, List<Candidate> found) {
        if (realWord || word.length() < 2 || word.charAt(0) == word.charAt(1)) {
            return;
        }
        String swapped = word.charAt(1) + ("" + word.charAt(0)) + word.substring(2);
        int index = dictionary.indexOf(swapped);
        if (index >= 0) {
            found.add(new Candidate(swapped, 1, dictionary.logFrequencyAt(index)));
        }
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
     * typo, the fix must be a single edit, and it must be clearly ahead of the
     * next single-edit candidate. A word already in the lexicon has to clear a
     * much higher bar again — see {@link #REAL_WORD_MIN_FREQUENCY_GAP}.
     */
    private boolean allowsSilentReplacement(String word, List<Candidate> ranked, boolean realWord) {
        Candidate best = ranked.get(0);
        boolean reviewedShortPair = isAllowedShortPair(word, best.word);
        if (word.length() < SILENT_MIN_LENGTH && !reviewedShortPair) {
            return false;
        }
        if (best.distance != 1) {
            return false;
        }
        if (realWord) {
            // Overruling a word the user spelled correctly needs both kinds of
            // evidence at once: the physical one, that the two keys touch, and
            // the lexical one, that the alternative is overwhelmingly commoner.
            // Either alone is not enough.
            boolean adjacentSubstitution =
                    substitutionAdjustment(word, best.word) == ADJACENCY_BONUS;
            // Raw frequencies, deliberately not Candidate.score: the score
            // already contains ADJACENCY_BONUS, and counting it here would let
            // the adjacency gate pay for part of the frequency gate.
            int rawGap = dictionary.logFrequency(best.word) - dictionary.logFrequency(word);
            if (!adjacentSubstitution || rawGap < REAL_WORD_MIN_FREQUENCY_GAP) {
                return false;
            }
        } else if (!reviewedShortPair && hasCompetingCompletion(word, best)) {
            // Pressing space proves the token is finished, but not that the user
            // did not drop a suffix: "ther" reads as well as an unfinished
            // "there" as it does as a typo for the far commoner "the".
            //
            // Only applies to non-words. A real word is a prefix of itself, so
            // this would block every one of them rather than the ambiguous ones.
            return false;
        }
        for (int i = 1; i < ranked.size(); i++) {
            Candidate other = ranked.get(i);
            if (other.distance != 1) {
                // Everything past here is a two-edit candidate and cannot
                // meaningfully compete with a one-edit fix.
                break;
            }
            int margin = reviewedShortPair ? SHORT_PAIR_MARGIN : SILENT_MARGIN;
            if (best.score - other.score < margin) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code typed -> correction} is one of the named short exceptions. */
    private static boolean isAllowedShortPair(String typed, String correction) {
        for (String[] pair : SHORT_PAIRS) {
            if (pair[0].equals(typed) && pair[1].equals(correction)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when some longer word starting with {@code word} is a plausible thing
     * to have been typing, judged against how strongly we believe the correction.
     *
     * <p>A completion identical to the correction is skipped rather than
     * counted: for {@code becaus} the best completion and the best correction
     * are both {@code because}, so the two agree and there is nothing ambiguous
     * about it.
     */
    private boolean hasCompetingCompletion(String word, Candidate best) {
        for (String completion : dictionary.completionsFor(word, 2)) {
            if (completion.equals(best.word)) {
                continue;
            }
            int completionScore = dictionary.logFrequency(completion);
            if (completionScore >= best.score - COMPLETION_AMBIGUITY_MARGIN) {
                return true;
            }
        }
        return false;
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

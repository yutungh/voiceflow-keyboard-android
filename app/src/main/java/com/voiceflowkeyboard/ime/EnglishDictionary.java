package com.voiceflowkeyboard.ime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Read-only English lexicon backed by assets/english_dict.txt, which is
 * generated from the pinned SymSpell frequency list by
 * scripts/build-english-dict.mjs.
 *
 * <p>Deliberately free of Android dependencies so the corrector can be unit
 * tested on the JVM. The caller supplies the stream: AssetManager on device, a
 * plain file in tests.
 *
 * <p>Two parallel arrays rather than a trie or a symmetric-delete index. A
 * distance-2 delete index over 80k words is the most heap-expensive structure
 * available here, in a process Android kills aggressively, and a trie's one
 * real advantage — free prefix completion — is already covered by binary
 * searching a sorted array. Words are sorted by UTF-16 code unit to match
 * {@link String#compareTo}, which is what the generator asserts before writing.
 *
 * <p>Frequencies arrive pre-logged as {@code round(ln(p) * logScale)} and are
 * therefore always negative; larger (closer to zero) means more common.
 */
final class EnglishDictionary {

    /** Returned by {@link #logFrequency} for a word that is not in the lexicon. */
    static final int NOT_FOUND = Integer.MIN_VALUE;

    private static final int LETTERS = 26;

    private final String[] words;
    private final int[] logFrequencies;
    private final int[] letterStart;
    private final int[] letterEnd;
    private final int maxWordLength;
    private final int logScale;

    private EnglishDictionary(
            String[] words,
            int[] logFrequencies,
            int[] letterStart,
            int[] letterEnd,
            int maxWordLength,
            int logScale
    ) {
        this.words = words;
        this.logFrequencies = logFrequencies;
        this.letterStart = letterStart;
        this.letterEnd = letterEnd;
        this.maxWordLength = maxWordLength;
        this.logScale = logScale;
    }

    static EnglishDictionary load(InputStream stream) throws IOException {
        List<String> wordList = new ArrayList<>(90000);
        List<Integer> frequencyList = new ArrayList<>(90000);
        int declaredMaxWord = 0;
        int declaredLogScale = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) == '#') {
                    if (line.startsWith("#maxword=")) {
                        try {
                            declaredMaxWord = Integer.parseInt(line.substring("#maxword=".length()).trim());
                        } catch (NumberFormatException ignored) {
                            // Fall back to measuring the words below.
                        }
                    } else if (line.startsWith("#logscale=")) {
                        try {
                            declaredLogScale = Integer.parseInt(line.substring("#logscale=".length()).trim());
                        } catch (NumberFormatException ignored) {
                            // Fall back to the generator's default below.
                        }
                    }
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    continue;
                }
                int frequency;
                try {
                    frequency = Integer.parseInt(line.substring(tab + 1).trim());
                } catch (NumberFormatException ignored) {
                    // Skip a malformed row rather than failing the whole load.
                    continue;
                }
                wordList.add(line.substring(0, tab));
                frequencyList.add(frequency);
            }
        }

        String[] words = wordList.toArray(new String[0]);
        int[] frequencies = new int[frequencyList.size()];
        for (int i = 0; i < frequencies.length; i++) {
            frequencies[i] = frequencyList.get(i);
        }

        int maxWordLength = declaredMaxWord;
        if (maxWordLength <= 0) {
            for (String word : words) {
                maxWordLength = Math.max(maxWordLength, word.length());
            }
        }
        if (declaredLogScale <= 0) {
            declaredLogScale = 1000;
        }

        int[] letterStart = new int[LETTERS];
        int[] letterEnd = new int[LETTERS];
        Arrays.fill(letterStart, -1);
        Arrays.fill(letterEnd, -1);
        // One pass rather than 26 binary searches, and it tolerates a leading
        // character outside a-z (an apostrophe, say) by simply not bucketing it.
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            int slot = words[i].charAt(0) - 'a';
            if (slot < 0 || slot >= LETTERS) {
                continue;
            }
            if (letterStart[slot] < 0) {
                letterStart[slot] = i;
            }
            letterEnd[slot] = i + 1;
        }

        return new EnglishDictionary(
                words, frequencies, letterStart, letterEnd, maxWordLength, declaredLogScale);
    }

    int size() {
        return words.length;
    }

    int maxWordLength() {
        return maxWordLength;
    }

    /** Divisor that turns a stored frequency back into a natural log probability. */
    int logScale() {
        return logScale;
    }

    boolean contains(String word) {
        return indexOf(word) >= 0;
    }

    /** Index of {@code word}, or negative when absent. */
    int indexOf(String word) {
        if (word == null || word.isEmpty()) {
            return -1;
        }
        return Arrays.binarySearch(words, word);
    }

    /** Scaled log probability of {@code word}, or {@link #NOT_FOUND}. */
    int logFrequency(String word) {
        int index = indexOf(word);
        return index < 0 ? NOT_FOUND : logFrequencies[index];
    }

    String wordAt(int index) {
        return words[index];
    }

    int logFrequencyAt(int index) {
        return logFrequencies[index];
    }

    /**
     * First index of the bucket of words beginning with {@code letter}, or -1
     * when no word does. Paired with {@link #bucketEnd} this is the corrector's
     * scan window: correction never changes the first letter, so everything
     * outside one bucket is unreachable and must not be scanned.
     */
    int bucketStart(char letter) {
        int slot = letter - 'a';
        return slot < 0 || slot >= LETTERS ? -1 : letterStart[slot];
    }

    /** Exclusive end of the {@link #bucketStart} bucket, or -1 when empty. */
    int bucketEnd(char letter) {
        int slot = letter - 'a';
        return slot < 0 || slot >= LETTERS ? -1 : letterEnd[slot];
    }

    /** First index whose word starts with {@code prefix}, or -1 when none does. */
    int firstIndexWithPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return -1;
        }
        int index = Arrays.binarySearch(words, prefix);
        int start = index >= 0 ? index : -(index + 1);
        return start < words.length && words[start].startsWith(prefix) ? start : -1;
    }

    /**
     * Words that start with {@code prefix} but are longer than it — the
     * completions offered while the user is still mid-word — ordered by
     * descending frequency and capped at {@code limit}.
     */
    List<String> completionsFor(String prefix, int limit) {
        int start = firstIndexWithPrefix(prefix);
        if (start < 0 || limit <= 0) {
            return Collections.emptyList();
        }
        List<Integer> found = new ArrayList<>();
        for (int i = start; i < words.length && words[i].startsWith(prefix); i++) {
            if (words[i].length() == prefix.length()) {
                continue;
            }
            found.add(i);
        }
        Collections.sort(found, (a, b) -> Integer.compare(logFrequencies[b], logFrequencies[a]));
        List<String> result = new ArrayList<>(Math.min(limit, found.size()));
        for (int i = 0; i < found.size() && result.size() < limit; i++) {
            result.add(words[found.get(i)]);
        }
        return result;
    }
}

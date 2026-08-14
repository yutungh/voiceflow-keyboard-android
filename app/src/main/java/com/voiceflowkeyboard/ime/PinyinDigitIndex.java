package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 9-key (T9) view of the dictionary: every letter key projected onto the phone
 * keypad, so 你好 ("nihao") is reachable by typing 6-4-4-2-6.
 *
 * <p>Building this costs a projection and a sort over ~39k keys, so it is
 * created on demand — see {@link #build} — rather than at dictionary load. Most
 * sessions never touch 9-key and should not pay for it.
 *
 * <p>Instances are immutable once built and safe to publish to the UI thread.
 *
 * <p>Two things make 9-key affordable despite being far more ambiguous than
 * QWERTY. Each digit key remembers its best entry up front, so offering
 * completions for a one-digit prefix is a scan of precomputed pairs rather than
 * thousands of payload parses. And the letter keys behind a digit key are held
 * as indices into the dictionary, so no duplicate strings are retained.
 */
final class PinyinDigitIndex implements PinyinLookup {

    /** Standard phone keypad mapping; index 0 is 'a'. */
    private static final char[] LETTER_TO_DIGIT = {
            '2', '2', '2',            // a b c
            '3', '3', '3',            // d e f
            '4', '4', '4',            // g h i
            '5', '5', '5',            // j k l
            '6', '6', '6',            // m n o
            '7', '7', '7', '7',       // p q r s
            '8', '8', '8',            // t u v
            '9', '9', '9', '9'        // w x y z
    };

    private final PinyinDictionary dictionary;
    private final String[] digitKeys;
    private final int[] letterKeyStart;
    private final int[] letterKeyIndices;
    private final String[] bestWord;
    private final int[] bestFrequency;
    private final int maxSpanLength;

    private PinyinDigitIndex(
            PinyinDictionary dictionary,
            String[] digitKeys,
            int[] letterKeyStart,
            int[] letterKeyIndices,
            String[] bestWord,
            int[] bestFrequency,
            int maxSpanLength
    ) {
        this.dictionary = dictionary;
        this.digitKeys = digitKeys;
        this.letterKeyStart = letterKeyStart;
        this.letterKeyIndices = letterKeyIndices;
        this.bestWord = bestWord;
        this.bestFrequency = bestFrequency;
        this.maxSpanLength = maxSpanLength;
    }

    static char digitFor(char letter) {
        if (letter >= 'a' && letter <= 'z') {
            return LETTER_TO_DIGIT[letter - 'a'];
        }
        if (letter >= 'A' && letter <= 'Z') {
            return LETTER_TO_DIGIT[letter - 'A'];
        }
        return 0;
    }

    static String project(String letterKey) {
        StringBuilder projected = new StringBuilder(letterKey.length());
        for (int i = 0; i < letterKey.length(); i++) {
            char digit = digitFor(letterKey.charAt(i));
            if (digit != 0) {
                projected.append(digit);
            }
        }
        return projected.toString();
    }

    /** Projects the whole dictionary. Call off the main thread. */
    static PinyinDigitIndex build(PinyinDictionary dictionary) {
        int keyCount = dictionary.keyCount();
        Integer[] order = new Integer[keyCount];
        String[] projected = new String[keyCount];
        int maxSpan = 0;
        for (int i = 0; i < keyCount; i++) {
            order[i] = i;
            projected[i] = project(dictionary.keyAt(i));
            maxSpan = Math.max(maxSpan, projected[i].length());
        }
        Arrays.sort(order, (a, b) -> {
            int byDigits = projected[a].compareTo(projected[b]);
            return byDigits != 0 ? byDigits : dictionary.keyAt(a).compareTo(dictionary.keyAt(b));
        });

        List<String> distinctDigits = new ArrayList<>(keyCount);
        List<Integer> starts = new ArrayList<>(keyCount);
        int[] letterIndices = new int[keyCount];
        for (int position = 0; position < keyCount; position++) {
            int keyIndex = order[position];
            letterIndices[position] = keyIndex;
            String digits = projected[keyIndex];
            if (distinctDigits.isEmpty() || !distinctDigits.get(distinctDigits.size() - 1).equals(digits)) {
                distinctDigits.add(digits);
                starts.add(position);
            }
        }

        int groupCount = distinctDigits.size();
        String[] digitKeys = distinctDigits.toArray(new String[0]);
        int[] groupStart = new int[groupCount + 1];
        for (int g = 0; g < groupCount; g++) {
            groupStart[g] = starts.get(g);
        }
        groupStart[groupCount] = keyCount;

        String[] bestWord = new String[groupCount];
        int[] bestFrequency = new int[groupCount];
        for (int g = 0; g < groupCount; g++) {
            for (int p = groupStart[g]; p < groupStart[g + 1]; p++) {
                PinyinDictionary.Entry candidate = dictionary.firstEntryAt(letterIndices[p]);
                if (candidate != null && candidate.frequency > bestFrequency[g]) {
                    bestWord[g] = candidate.word;
                    bestFrequency[g] = candidate.frequency;
                }
            }
        }

        return new PinyinDigitIndex(
                dictionary, digitKeys, groupStart, letterIndices, bestWord, bestFrequency, maxSpan);
    }

    int digitKeyCount() {
        return digitKeys.length;
    }

    @Override
    public int maxSpanLength() {
        return maxSpanLength;
    }

    @Override
    public long totalFrequency() {
        return dictionary.totalFrequency();
    }

    @Override
    public String normalize(String rawInput, int maxLength) {
        if (rawInput == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(rawInput.length());
        for (int i = 0; i < rawInput.length() && cleaned.length() < maxLength; i++) {
            char c = rawInput.charAt(i);
            if (c >= '2' && c <= '9') {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    @Override
    public List<PinyinDictionary.Entry> entriesFor(String span) {
        int group = Arrays.binarySearch(digitKeys, span);
        if (group < 0) {
            return Collections.emptyList();
        }
        List<PinyinDictionary.Entry> merged = new ArrayList<>();
        for (int p = letterKeyStart[group]; p < letterKeyStart[group + 1]; p++) {
            merged.addAll(dictionary.entriesAt(letterKeyIndices[p]));
        }
        Collections.sort(merged, (a, b) -> Integer.compare(b.frequency, a.frequency));
        return merged;
    }

    @Override
    public List<PinyinDictionary.Entry> completionsFor(String prefix, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        int index = Arrays.binarySearch(digitKeys, prefix);
        int start = index >= 0 ? index : -(index + 1);
        if (start >= digitKeys.length || !digitKeys[start].startsWith(prefix)) {
            return Collections.emptyList();
        }
        List<PinyinDictionary.Entry> found = new ArrayList<>();
        for (int g = start; g < digitKeys.length && digitKeys[g].startsWith(prefix); g++) {
            if (digitKeys[g].length() == prefix.length() || bestWord[g] == null) {
                continue;
            }
            found.add(new PinyinDictionary.Entry(bestWord[g], bestFrequency[g]));
        }
        Collections.sort(found, (a, b) -> Integer.compare(b.frequency, a.frequency));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }
}

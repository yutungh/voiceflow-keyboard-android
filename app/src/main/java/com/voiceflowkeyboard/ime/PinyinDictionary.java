package com.voiceflowkeyboard.ime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only pinyin lookup table backed by assets/pinyin_dict.txt, which is
 * generated from the pinned Rime pinyin_simp dictionary by
 * scripts/build-pinyin-dict.mjs.
 *
 * <p>Deliberately free of Android dependencies so the composer can be unit
 * tested on the JVM. The caller supplies the stream: AssetManager on device, a
 * plain file in tests.
 *
 * <p>Keys are the entry's pinyin with syllable spaces removed, which is exactly
 * what the user types, and are stored sorted so lookups are a binary search.
 * Payloads stay as raw strings and are parsed only when a key is actually hit,
 * which keeps ~65k word objects off the heap at load time.
 */
final class PinyinDictionary implements PinyinLookup {

    static final class Entry {
        final String word;
        final int frequency;

        Entry(String word, int frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }

    private final String[] keys;
    private final String[] payloads;
    private final Set<String> syllables;
    private final int maxKeyLength;
    private final long totalFrequency;

    private PinyinDictionary(
            String[] keys,
            String[] payloads,
            Set<String> syllables,
            int maxKeyLength,
            long totalFrequency
    ) {
        this.keys = keys;
        this.payloads = payloads;
        this.syllables = syllables;
        this.maxKeyLength = maxKeyLength;
        this.totalFrequency = totalFrequency;
    }

    static PinyinDictionary load(InputStream stream) throws IOException {
        List<String> keyList = new ArrayList<>(40000);
        List<String> payloadList = new ArrayList<>(40000);
        Set<String> syllableSet = new HashSet<>();
        int declaredMaxKey = 0;
        long declaredTotalFrequency = 0L;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) == '#') {
                    if (line.startsWith("#syllables=")) {
                        for (String syllable : line.substring("#syllables=".length()).split(" ")) {
                            if (!syllable.isEmpty()) {
                                syllableSet.add(syllable);
                            }
                        }
                    } else if (line.startsWith("#maxkey=")) {
                        try {
                            declaredMaxKey = Integer.parseInt(line.substring("#maxkey=".length()).trim());
                        } catch (NumberFormatException ignored) {
                            // Fall back to measuring the keys below.
                        }
                    } else if (line.startsWith("#totalfreq=")) {
                        try {
                            declaredTotalFrequency =
                                    Long.parseLong(line.substring("#totalfreq=".length()).trim());
                        } catch (NumberFormatException ignored) {
                            // Fall back to the summed default below.
                        }
                    }
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    continue;
                }
                keyList.add(line.substring(0, tab));
                payloadList.add(line.substring(tab + 1));
            }
        }

        String[] keys = keyList.toArray(new String[0]);
        String[] payloads = payloadList.toArray(new String[0]);
        int maxKeyLength = declaredMaxKey;
        if (maxKeyLength <= 0) {
            for (String key : keys) {
                maxKeyLength = Math.max(maxKeyLength, key.length());
            }
        }
        if (declaredTotalFrequency <= 0L) {
            // Header missing or corrupt: fall back to a positive value so the
            // composer's log-probability maths stays finite.
            declaredTotalFrequency = 1L;
            for (String payload : payloads) {
                for (Entry entry : parse(payload)) {
                    declaredTotalFrequency += entry.frequency;
                }
            }
        }
        return new PinyinDictionary(keys, payloads, syllableSet, maxKeyLength, declaredTotalFrequency);
    }

    int size() {
        return keys.length;
    }

    int maxKeyLength() {
        return maxKeyLength;
    }

    @Override
    public int maxSpanLength() {
        return maxKeyLength;
    }

    @Override
    public long totalFrequency() {
        return totalFrequency;
    }

    @Override
    public String normalize(String rawInput, int maxLength) {
        if (rawInput == null) {
            return "";
        }
        // Apostrophes are accepted so typing "xi'an" is never rejected, but they
        // are only a soft separator today: keys are stored with syllable spaces
        // removed, so 西安 and 先 share the key "xian" and an apostrophe cannot
        // tell them apart. Enforcing a hard boundary would exclude the key
        // "xian" outright and lose 西安 altogether. Real disambiguation needs
        // per-entry syllable boundaries in the generated asset.
        StringBuilder cleaned = new StringBuilder(rawInput.length());
        for (int i = 0; i < rawInput.length() && cleaned.length() < maxLength; i++) {
            char c = rawInput.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                cleaned.append((char) (c - 'A' + 'a'));
            } else if (c >= 'a' && c <= 'z') {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    /** Number of distinct keys; paired with {@link #keyAt} for index building. */
    int keyCount() {
        return keys.length;
    }

    String keyAt(int index) {
        return keys[index];
    }

    List<Entry> entriesAt(int index) {
        return parse(payloads[index]);
    }

    /**
     * Highest-frequency entry for the key at {@code index}, parsed without
     * touching the rest of the payload. Payloads are written frequency-ordered,
     * so the leading record is always the best one.
     */
    Entry firstEntryAt(int index) {
        return parseFirst(payloads[index]);
    }

    boolean isSyllable(String candidate) {
        return syllables.contains(candidate);
    }

    int syllableCount() {
        return syllables.size();
    }

    /** Entries whose key is exactly {@code key}, already ordered by descending frequency. */
    @Override
    public List<Entry> entriesFor(String key) {
        int index = Arrays.binarySearch(keys, key);
        if (index < 0) {
            return Collections.emptyList();
        }
        return parse(payloads[index]);
    }

    /** True when any key starts with {@code prefix} (including an exact match). */
    boolean hasKeyWithPrefix(String prefix) {
        return firstIndexWithPrefix(prefix) >= 0;
    }

    /**
     * Entries for keys that start with {@code prefix} but are longer than it —
     * the completions offered while the user is still mid-word. Ordered by
     * descending frequency, capped at {@code limit}.
     *
     * <p>Only the best entry of each matching key is considered. A one-character
     * prefix can span thousands of keys, and parsing every record under all of
     * them was measured to be the single most expensive thing the composer does;
     * taking the leading record keeps it cheap and also gives a more varied list
     * than ten readings of the same key would.
     */
    @Override
    public List<Entry> completionsFor(String prefix, int limit) {
        int start = firstIndexWithPrefix(prefix);
        if (start < 0 || limit <= 0) {
            return Collections.emptyList();
        }
        List<Entry> found = new ArrayList<>();
        for (int i = start; i < keys.length && keys[i].startsWith(prefix); i++) {
            if (keys[i].length() == prefix.length()) {
                continue;
            }
            Entry best = parseFirst(payloads[i]);
            if (best != null) {
                found.add(best);
            }
        }
        Collections.sort(found, (a, b) -> Integer.compare(b.frequency, a.frequency));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    private int firstIndexWithPrefix(String prefix) {
        int index = Arrays.binarySearch(keys, prefix);
        int start = index >= 0 ? index : -(index + 1);
        return start < keys.length && keys[start].startsWith(prefix) ? start : -1;
    }

    /** Parses only the leading record of a payload. Null when it is malformed. */
    private static Entry parseFirst(String payload) {
        int comma = payload.indexOf(',');
        int end = comma < 0 ? payload.length() : comma;
        int colon = payload.lastIndexOf(':', end);
        if (colon <= 0) {
            return null;
        }
        try {
            return new Entry(payload.substring(0, colon), Integer.parseInt(payload.substring(colon + 1, end)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<Entry> parse(String payload) {
        List<Entry> entries = new ArrayList<>(4);
        int position = 0;
        while (position < payload.length()) {
            int comma = payload.indexOf(',', position);
            int end = comma < 0 ? payload.length() : comma;
            int colon = payload.lastIndexOf(':', end);
            if (colon > position) {
                try {
                    entries.add(new Entry(
                            payload.substring(position, colon),
                            Integer.parseInt(payload.substring(colon + 1, end))
                    ));
                } catch (NumberFormatException ignored) {
                    // Skip a malformed record rather than failing the whole lookup.
                }
            }
            if (comma < 0) {
                break;
            }
            position = comma + 1;
        }
        return entries;
    }
}

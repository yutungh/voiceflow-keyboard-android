package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 gate for Chinese input: does the bundled dictionary plus the beam
 * search actually put the wanted word near the top for everyday Mandarin?
 *
 * <p>The asset is read straight off disk rather than through AssetManager, so
 * these run as plain JVM tests with no Android framework involved.
 */
public class PinyinComposerTest {

    /** Where the wanted candidate has to appear for the keyboard to feel usable. */
    private static final int TOP_N = 5;

    private static PinyinDictionary dictionary;
    private static PinyinComposer composer;

    @BeforeClass
    public static void loadDictionary() throws IOException {
        File asset = new File("src/main/assets/pinyin_dict.txt");
        assertTrue(
                "Missing " + asset.getAbsolutePath() + " — run: node scripts/build-pinyin-dict.mjs",
                asset.isFile()
        );
        try (InputStream stream = new FileInputStream(asset)) {
            dictionary = PinyinDictionary.load(stream);
        }
        composer = new PinyinComposer(dictionary);
    }

    @Test
    public void dictionaryLoadsExpectedShape() {
        assertEquals(38999, dictionary.size());
        assertEquals(415, dictionary.syllableCount());
        assertEquals(20, dictionary.maxKeyLength());
        assertTrue(dictionary.isSyllable("zhuang"));
        assertFalse(dictionary.isSyllable("zzz"));
    }

    /**
     * The gate. Everyday phrases a person actually texts, each with the reading
     * they meant. Multi-syllable runs beyond four syllables exercise the beam's
     * ability to stitch several dictionary entries together.
     */
    @Test
    public void everydayPhrasesRankInTopFive() {
        String[][] corpus = {
                {"nihao", "你好"},
                {"xiexie", "谢谢"},
                {"zaijian", "再见"},
                {"woaini", "我爱你"},
                {"zhongguo", "中国"},
                {"shenmeshihou", "什么时候"},
                {"duibuqi", "对不起"},
                {"meiguanxi", "没关系"},
                {"zenmeyang", "怎么样"},
                {"chifanle", "吃饭了"},
                {"womenshi", "我们是"},
                {"jintianwanshang", "今天晚上"},
                {"mingtianjian", "明天见"},
                {"womenqunali", "我们去哪里"},
                {"nizainali", "你在哪里"},
                {"wozhidaole", "我知道了"},
                {"qingwen", "请问"},
                {"xiawujian", "下午见"},
                {"haode", "好的"},
                {"buyongxie", "不用谢"},
                {"shengrikuaile", "生日快乐"},
                {"gongxifacai", "恭喜发财"},
                {"beijing", "北京"},
                {"shanghai", "上海"},
                {"pengyou", "朋友"},
                {"gongzuo", "工作"},
                {"dianhua", "电话"},
                {"xianzai", "现在"},
                {"keyi", "可以"},
                {"zhidao", "知道"},
        };

        List<String> misses = new ArrayList<>();
        for (String[] row : corpus) {
            List<PinyinCandidate> candidates = composer.candidates(row[0], TOP_N);
            if (!containsText(candidates, row[1])) {
                misses.add(row[0] + " -> wanted " + row[1] + ", got " + describe(candidates));
            }
        }
        assertTrue(
                "Phrases missing from the top " + TOP_N + " (" + misses.size() + "/" + corpus.length + "):\n"
                        + String.join("\n", misses),
                misses.isEmpty()
        );
    }

    /** The single most common readings should not merely appear, but lead. */
    @Test
    public void commonPhrasesRankFirst() {
        String[][] corpus = {
                {"nihao", "你好"},
                {"xiexie", "谢谢"},
                {"zhongguo", "中国"},
                {"womende", "我们的"},
                {"shijian", "时间"},
        };
        for (String[] row : corpus) {
            List<PinyinCandidate> candidates = composer.candidates(row[0], TOP_N);
            assertFalse(row[0] + " produced no candidates", candidates.isEmpty());
            assertEquals(
                    row[0] + " should lead with " + row[1] + ", got " + describe(candidates),
                    row[1],
                    candidates.get(0).text
            );
        }
    }

    /** A single syllable should surface its homophone list, most common first. */
    @Test
    public void singleSyllableOffersHomophones() {
        List<PinyinCandidate> candidates = composer.candidates("yi", 10);
        assertTrue("expected a long homophone list for 'yi'", candidates.size() >= 8);
        for (PinyinCandidate candidate : candidates) {
            if (!candidate.completion) {
                assertEquals("full readings of 'yi' consume both characters", 2, candidate.consumed);
            }
        }
        List<PinyinCandidate> ni = composer.candidates("ni", TOP_N);
        assertEquals("你", ni.get(0).text);
    }

    /** Mid-word input should complete rather than dead-end. */
    @Test
    public void partialInputOffersCompletions() {
        List<PinyinCandidate> candidates = composer.candidates("nih", TOP_N);
        assertTrue("expected completions for 'nih', got " + describe(candidates),
                containsText(candidates, "你好"));
        for (PinyinCandidate candidate : candidates) {
            assertTrue("'nih' has no exact reading, so every candidate is a completion",
                    candidate.completion);
            assertEquals(3, candidate.consumed);
        }
    }

    /**
     * An apostrophe must never break input. It is only a soft separator for now:
     * 西安 and 先 share the key "xian" because keys are stored with syllable
     * spaces removed, so "xi'an" and "xian" are currently indistinguishable.
     * Both readings should be reachable either way. Hard disambiguation needs
     * per-entry syllable boundaries in the asset — deliberately deferred.
     */
    @Test
    public void apostropheIsAcceptedAsSoftSeparator() {
        List<PinyinCandidate> joined = composer.candidates("xian", 12);
        List<PinyinCandidate> split = composer.candidates("xi'an", 12);

        assertFalse("xian should produce candidates", joined.isEmpty());
        assertEquals("an apostrophe should not change the reading today",
                describe(joined), describe(split));
        assertTrue("先 should be reachable from xian, got " + describe(joined),
                containsText(joined, "先"));
        assertTrue("西安 should be reachable from xian, got " + describe(joined),
                containsText(joined, "西安"));
    }

    /** A dangling tail should still let the user commit the good leading part. */
    @Test
    public void trailingGarbageFallsBackToLongestPrefix() {
        List<PinyinCandidate> candidates = composer.candidates("nihaoq", TOP_N);
        assertFalse("expected a partial reading, got nothing", candidates.isEmpty());
        boolean sawPartial = false;
        for (PinyinCandidate candidate : candidates) {
            if ("你好".equals(candidate.text)) {
                assertEquals("你好 covers the leading 5 characters of 'nihaoq'", 5, candidate.consumed);
                sawPartial = true;
            }
        }
        assertTrue("expected 你好 as a partial reading of 'nihaoq', got " + describe(candidates), sawPartial);
    }

    @Test
    public void junkInputIsRejectedCleanly() {
        assertTrue(composer.candidates("", TOP_N).isEmpty());
        assertTrue(composer.candidates(null, TOP_N).isEmpty());
        assertTrue(composer.candidates("zzzz", TOP_N).isEmpty());
        assertTrue(composer.candidates("123!@#", TOP_N).isEmpty());
    }

    @Test
    public void topCandidateTextMatchesFirstCandidate() {
        assertEquals("你好", composer.topCandidateText("nihao"));
        assertNotNull(composer.topCandidateText("ni"));
        assertEquals(null, composer.topCandidateText("zzzz"));
    }

    /** Guards against pathological input locking up the keyboard thread. */
    @Test
    public void longInputStaysFast() {
        String input = "womenjintianwanshangquchifanhaobuhao";
        long start = System.nanoTime();
        List<PinyinCandidate> candidates = composer.candidates(input, 10);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertFalse("expected some reading of a long run", candidates.isEmpty());
        assertTrue("composing took " + elapsedMs + "ms, too slow for keystroke latency", elapsedMs < 100);
    }

    private static boolean containsText(List<PinyinCandidate> candidates, String wanted) {
        for (PinyinCandidate candidate : candidates) {
            if (wanted.equals(candidate.text)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(List<PinyinCandidate> candidates) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(candidates.get(i).text);
        }
        return builder.append("]").toString();
    }
}

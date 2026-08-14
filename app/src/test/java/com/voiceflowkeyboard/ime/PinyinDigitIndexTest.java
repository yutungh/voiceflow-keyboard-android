package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * 9-key (T9) pinyin. Same search as full QWERTY, driven through the digit
 * projection, so these tests are mostly about the extra ambiguity a keypad
 * introduces and about not blowing the keystroke latency budget.
 */
public class PinyinDigitIndexTest {

    private static final int TOP_N = 5;

    private static PinyinDictionary dictionary;
    private static PinyinDigitIndex digits;
    private static PinyinComposer composer;

    @BeforeClass
    public static void loadDictionary() throws IOException {
        File asset = new File("src/main/assets/pinyin_dict.txt");
        assertTrue("run: node scripts/build-pinyin-dict.mjs", asset.isFile());
        try (InputStream stream = new FileInputStream(asset)) {
            dictionary = PinyinDictionary.load(stream);
        }
        digits = PinyinDigitIndex.build(dictionary);
        composer = new PinyinComposer(digits);
    }

    @Test
    public void keypadMappingIsStandard() {
        assertEquals('2', PinyinDigitIndex.digitFor('a'));
        assertEquals('7', PinyinDigitIndex.digitFor('s'));
        assertEquals('9', PinyinDigitIndex.digitFor('z'));
        assertEquals('4', PinyinDigitIndex.digitFor('I'));
        assertEquals(0, PinyinDigitIndex.digitFor('-'));
        assertEquals("64426", PinyinDigitIndex.project("nihao"));
    }

    @Test
    public void projectionCollapsesKeysAsExpected() {
        assertTrue("digit keys should be fewer than letter keys",
                digits.digitKeyCount() < dictionary.keyCount());
        assertTrue(digits.digitKeyCount() > 20000);
        assertEquals(dictionary.totalFrequency(), digits.totalFrequency());
    }

    @Test
    public void normalizeKeepsOnlyKeypadDigits() {
        assertEquals("64426", digits.normalize("64426", 32));
        assertEquals("6442", digits.normalize("6a4#4 2", 32));
        assertEquals("", digits.normalize("0110", 32));
        assertEquals("644", digits.normalize("64426", 3));
    }

    /**
     * The 9-key gate. Deliberately looser than the QWERTY one: a keypad digit is
     * three or four letters, so a unigram model cannot match full pinyin. Agreed
     * bar is 28 of 30 in the top five.
     */
    @Test
    public void everydayPhrasesMostlyRankInTopFive() {
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
            String typed = PinyinDigitIndex.project(row[0]);
            List<PinyinCandidate> candidates = composer.candidates(typed, TOP_N);
            if (!contains(candidates, row[1])) {
                misses.add(row[0] + " (" + typed + ") wanted " + row[1] + ", got " + describe(candidates));
            }
        }
        int hits = corpus.length - misses.size();
        assertTrue(
                "9-key top-" + TOP_N + " hit rate " + hits + "/" + corpus.length + ", below the agreed 28:\n"
                        + String.join("\n", misses),
                hits >= 28
        );
    }

    @Test
    public void veryCommonPhrasesStillLead() {
        assertEquals("你好", composer.candidates(PinyinDigitIndex.project("nihao"), TOP_N).get(0).text);
        assertEquals("中国", composer.candidates(PinyinDigitIndex.project("zhongguo"), TOP_N).get(0).text);
    }

    /**
     * The measured hazard: a single digit covers four letters, so a one-digit
     * prefix spans thousands of keys. Precomputing the best entry per digit key
     * is what keeps this off the parse path.
     */
    @Test
    public void singleDigitPrefixStaysFast() {
        for (char digit = '2'; digit <= '9'; digit++) {
            String typed = String.valueOf(digit);
            long start = System.nanoTime();
            List<PinyinCandidate> candidates = composer.candidates(typed, 8);
            long micros = (System.nanoTime() - start) / 1000;
            assertFalse("digit " + typed + " produced nothing", candidates.isEmpty());
            assertTrue("digit " + typed + " took " + micros + "us, over the keystroke budget",
                    micros < 20000);
        }
    }

    @Test
    public void typingLongRunStaysWithinKeystrokeBudget() {
        String typed = PinyinDigitIndex.project("womenjintianwanshangquchifan");
        long worst = 0;
        for (int i = 1; i <= typed.length(); i++) {
            long start = System.nanoTime();
            composer.candidates(typed.substring(0, i), 8);
            worst = Math.max(worst, (System.nanoTime() - start) / 1000);
        }
        assertTrue("worst 9-key keystroke was " + worst + "us", worst < 30000);
    }

    @Test
    public void junkInputIsRejectedCleanly() {
        assertTrue(composer.candidates("", TOP_N).isEmpty());
        assertTrue(composer.candidates(null, TOP_N).isEmpty());
        assertTrue(composer.candidates("0000", TOP_N).isEmpty());
    }

    private static boolean contains(List<PinyinCandidate> candidates, String wanted) {
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

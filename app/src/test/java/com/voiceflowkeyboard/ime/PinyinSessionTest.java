package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Composition lifecycle. These are the cases that, when wrong, leak half-typed
 * pinyin into whatever app the keyboard is attached to.
 */
public class PinyinSessionTest {

    private static PinyinDictionary dictionary;
    private static PinyinComposer letters;

    private PinyinSession session;

    @BeforeClass
    public static void loadDictionary() throws IOException {
        File asset = new File("src/main/assets/pinyin_dict.txt");
        assertTrue("run: node scripts/build-pinyin-dict.mjs", asset.isFile());
        try (InputStream stream = new FileInputStream(asset)) {
            dictionary = PinyinDictionary.load(stream);
        }
        letters = new PinyinComposer(dictionary);
    }

    @Before
    public void newSession() {
        session = new PinyinSession(letters);
    }

    private void type(String text) {
        for (char c : text.toCharArray()) {
            assertTrue("expected '" + c + "' to be absorbed", session.append(c));
        }
    }

    @Test
    public void freshSessionIsNotComposing() {
        assertFalse(session.isComposing());
        assertEquals("", session.rawText());
        assertTrue(session.candidates().isEmpty());
        assertTrue(session.settle(PinyinSession.SettleReason.ACCEPT_TOP).isNothing());
    }

    @Test
    public void typingBuildsBufferAndCandidates() {
        type("nihao");
        assertTrue(session.isComposing());
        assertEquals("nihao", session.rawText());
        assertEquals("你好", session.candidates().get(0).text);
    }

    @Test
    public void backspaceEatsBufferBeforeText() {
        type("nihao");
        assertTrue(session.backspace());
        assertEquals("niha", session.rawText());
        for (int i = 0; i < 4; i++) {
            assertTrue(session.backspace());
        }
        assertFalse(session.isComposing());
        // Nothing composing: the service must delete real text instead.
        assertFalse(session.backspace());
    }

    @Test
    public void acceptTopCommitsBestReading() {
        type("nihao");
        PinyinSession.Settlement settlement = session.settle(PinyinSession.SettleReason.ACCEPT_TOP);
        assertEquals(PinyinSession.Action.COMMIT, settlement.action);
        assertEquals("你好", settlement.text);
        assertFalse("settling must clear the session", session.isComposing());
    }

    @Test
    public void selectingCandidateCommitsItAndClearsBuffer() {
        type("nihao");
        PinyinSession.Settlement settlement = session.select(0);
        assertEquals(PinyinSession.Action.COMMIT, settlement.action);
        assertEquals("你好", settlement.text);
        assertFalse(session.isComposing());
    }

    /** A partial candidate leaves the tail composing so a run commits piecewise. */
    @Test
    public void selectingPartialCandidateKeepsRemainder() {
        type("nihaoq");
        int partial = -1;
        for (int i = 0; i < session.candidates().size(); i++) {
            if ("你好".equals(session.candidates().get(i).text)) {
                partial = i;
                break;
            }
        }
        assertTrue("expected 你好 as a partial reading of nihaoq", partial >= 0);
        PinyinSession.Settlement settlement = session.select(partial);
        assertEquals("你好", settlement.text);
        assertTrue("the dangling q should still be composing", session.isComposing());
        assertEquals("q", session.rawText());
    }

    @Test
    public void unreadableBufferFallsBackToRawLetters() {
        type("zzzz");
        assertTrue(session.isComposing());
        assertTrue(session.candidates().isEmpty());
        PinyinSession.Settlement settlement = session.settle(PinyinSession.SettleReason.ACCEPT_TOP);
        assertEquals("unreadable input must survive as literal text, not vanish",
                PinyinSession.Action.KEEP_RAW, settlement.action);
        assertEquals("zzzz", settlement.text);
    }

    /** The dangerous one: never write into an editor that has gone away. */
    @Test
    public void editorGoneClearsWithoutWriting() {
        type("nihao");
        PinyinSession.Settlement settlement = session.settle(PinyinSession.SettleReason.EDITOR_GONE);
        assertEquals(PinyinSession.Action.CLEAR_STATE_ONLY, settlement.action);
        assertEquals("", settlement.text);
        assertFalse(session.isComposing());
    }

    @Test
    public void cursorMovedLeavesTypedTextAlone() {
        type("nihao");
        PinyinSession.Settlement settlement = session.settle(PinyinSession.SettleReason.CURSOR_MOVED);
        assertEquals(PinyinSession.Action.KEEP_RAW, settlement.action);
        assertFalse(session.isComposing());
    }

    @Test
    public void cancelDiscardsTheSpan() {
        type("nihao");
        assertEquals(PinyinSession.Action.DISCARD,
                session.settle(PinyinSession.SettleReason.CANCELLED).action);
        assertFalse(session.isComposing());
    }

    @Test
    public void recordingAndModeChangeBothPreserveTheReading() {
        type("nihao");
        assertEquals("你好", session.settle(PinyinSession.SettleReason.RECORDING_STARTED).text);

        type("nihao");
        assertEquals("你好", session.settle(PinyinSession.SettleReason.MODE_CHANGED).text);
    }

    @Test
    public void everySettleReasonClearsTheSession() {
        for (PinyinSession.SettleReason reason : PinyinSession.SettleReason.values()) {
            newSession();
            type("nihao");
            session.settle(reason);
            assertFalse("settle(" + reason + ") left the session composing", session.isComposing());
            assertEquals("settle(" + reason + ") left a stale buffer", "", session.rawText());
            assertTrue("settle(" + reason + ") left stale candidates", session.candidates().isEmpty());
        }
    }

    @Test
    public void bufferIsBoundedAtComposerLimit() {
        StringBuilder typed = new StringBuilder();
        for (int i = 0; i < PinyinComposer.MAX_INPUT_LENGTH; i++) {
            typed.append('a');
        }
        type(typed.toString());
        assertEquals(PinyinComposer.MAX_INPUT_LENGTH, session.rawText().length());
        assertFalse("buffer must refuse to grow past the composer limit", session.append('a'));
        assertEquals(PinyinComposer.MAX_INPUT_LENGTH, session.rawText().length());
    }

    @Test
    public void switchingComposerRescoresTheBuffer() {
        PinyinDigitIndex digitIndex = PinyinDigitIndex.build(dictionary);
        PinyinSession digitSession = new PinyinSession(new PinyinComposer(digitIndex));
        for (char c : "64426".toCharArray()) {
            assertTrue(digitSession.append(c));
        }
        assertEquals("你好", digitSession.candidates().get(0).text);
    }
}

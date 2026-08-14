package com.voiceflowkeyboard.ime;

import android.content.res.AssetManager;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Owns the English lexicon and keeps both loading and correction off the UI
 * thread.
 *
 * <p>Modelled on {@link PinyinEngine}, with one deliberate difference: pinyin
 * loads lazily because most sessions never type Chinese, whereas English is the
 * default mode, so "lazy on first use" would put a 1.3 MB parse on the first
 * keystroke of a session. The service calls {@link #prepare()} when an eligible
 * field opens instead.
 *
 * <p>The executor passed in must be dedicated to typing assistance. The
 * service's general-purpose executor is a single thread shared with cloud
 * transcription and model downloads, and a queued download would head-of-line
 * block every keystroke behind it for minutes.
 *
 * <p>All fields are written on the main thread only. Background work produces
 * an immutable result which is then published back through the handler, so
 * there is no shared mutable state to synchronise. {@link EnglishCorrector} is
 * not thread-safe and is touched only from the background executor, which is
 * why it is created there rather than at construction.
 */
final class EnglishEngine {

    private static final String TAG = "EnglishEngine";
    private static final String ASSET = "english_dict.txt";

    /** Everything one keystroke's worth of background work produced. */
    static final class Suggestions {
        final int generation;
        final String word;
        final EnglishCorrector.Result correction;
        final List<String> completions;

        Suggestions(
                int generation,
                String word,
                EnglishCorrector.Result correction,
                List<String> completions
        ) {
            this.generation = generation;
            this.word = word;
            this.correction = correction;
            this.completions = completions;
        }
    }

    interface ResultListener {
        /** Called on the main thread. The caller must still check the generation. */
        void onEnglishSuggestions(Suggestions suggestions);
    }

    private final AssetManager assets;
    private final Executor background;
    private final Handler mainHandler;
    private final ResultListener listener;

    private EnglishCorrector corrector;
    private boolean loading;
    private boolean failed;

    EnglishEngine(
            AssetManager assets,
            Executor background,
            Handler mainHandler,
            ResultListener listener
    ) {
        this.assets = assets;
        this.background = background;
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    /** True once corrections can actually be produced. */
    boolean isReady() {
        return corrector != null;
    }

    boolean hasFailed() {
        return failed;
    }

    /** Starts loading the lexicon if needed. Safe to call repeatedly. */
    void prepare() {
        if (corrector != null || loading || failed) {
            return;
        }
        loading = true;
        background.execute(() -> {
            EnglishCorrector built = null;
            try (InputStream stream = assets.open(ASSET)) {
                built = new EnglishCorrector(EnglishDictionary.load(stream));
            } catch (IOException | RuntimeException error) {
                Log.e(TAG, "Could not load " + ASSET, error);
            }
            EnglishCorrector result = built;
            mainHandler.post(() -> {
                loading = false;
                if (result == null) {
                    failed = true;
                    return;
                }
                corrector = result;
            });
        });
    }

    /**
     * Ranks corrections and completions for {@code word} off the main thread and
     * publishes them back through the listener, tagged with {@code generation}.
     *
     * <p>Both are computed in one pass because the caller needs completions
     * whenever there is no correction to offer, and a second round trip for that
     * would double the latency of the commonest case.
     */
    void suggest(String word, int limit, int generation) {
        EnglishCorrector snapshot = corrector;
        if (snapshot == null || word == null || word.isEmpty()) {
            return;
        }
        background.execute(() -> {
            EnglishCorrector.Result correction;
            List<String> completions;
            try {
                correction = snapshot.suggest(word, limit);
                completions = snapshot.complete(word, limit);
            } catch (RuntimeException error) {
                // A crash on the typing path would take the keyboard down with
                // it. Losing one keystroke's suggestions is the better failure.
                Log.e(TAG, "Suggestion pass failed for " + word, error);
                correction = EnglishCorrector.Result.NONE;
                completions = Collections.emptyList();
            }
            Suggestions suggestions = new Suggestions(generation, word, correction, completions);
            mainHandler.post(() -> listener.onEnglishSuggestions(suggestions));
        });
    }
}

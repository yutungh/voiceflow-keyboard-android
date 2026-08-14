# Codex research — 2026-07-04 08:23 (local)

## User context
Solo developer (advanced AI-assisted workflow) building "VoiceFlow Keyboard", a NATIVE Android IME (InputMethodService) written in JAVA (not Kotlin, not React Native). minSdk 26, targetSdk/compileSdk 35. The app is MIT-licensed. It is primarily a voice-to-text keyboard (offline transcription via Vosk / sherpa-onnx / Parakeet, plus cloud transcription), but it ALSO has an Apple-style typed letter keyboard layout. It ALREADY has basic autocorrect plumbing: it uses the Android platform text-services API (SpellCheckerSession / SpellCheckerService), tracks a pending auto-correct word + replacement, and renders a suggestion "chip strip" above the keys. The developer wants to UPGRADE this to behave like the Apple iOS keyboard.

## Sources
1. https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main — 2025-02-26 — Current AOSP LatinIME tree head; shows the engine still exists and includes `java/`, `native/`, and `dictionaries/`.  [tag: primary]
2. https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/NOTICE — undated — Defines AOSP LatinIME’s Apache-2.0 terms and, critically, notes separate dictionary attribution/permission.  [tag: primary]
3. https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/ — undated — Shows the bundled offline wordlist assets shipped in the AOSP tree.  [tag: primary]
4. https://github.com/HeliBorg/HeliBoard — 2026-03-29 — Active AOSP-derived GitHub keyboard; shows license, release date, and dictionary/suggestion features.  [tag: github-repo]
5. https://raw.githubusercontent.com/HeliBorg/HeliBoard/main/app/src/main/java/helium314/keyboard/latin/Suggest.kt — undated — Source-level evidence for corrections, completions, next-word prediction, and auto-correct threshold logic.  [tag: github-repo]
6. https://raw.githubusercontent.com/HeliBorg/HeliBoard/main/app/src/main/java/com/android/inputmethod/latin/BinaryDictionary.java — undated — Source-level evidence for full edit distance, proximity info, n-gram probability, auto-commit confidence, and personalization hooks.  [tag: github-repo]
7. https://raw.githubusercontent.com/HeliBorg/HeliBoard/main/app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java — undated — Source-level evidence for separator-triggered auto-correct, backspace revert, auto-cap, auto-space, punctuation handling, and double-space-to-period.  [tag: github-repo]
8. https://github.com/AnySoftKeyboard/AnySoftKeyboard — 2026-02-08 — Active Apache-2.0 Java Android IME with a recent release; best GitHub Apache candidate besides AOSP itself.  [tag: github-repo]
9. https://github.com/AnySoftKeyboard/AnySoftKeyboard/commits/main — 2026-05-17 — Fresh maintenance signal for AnySoftKeyboard.  [tag: github-repo]
10. https://github.com/florisboard/florisboard — 2025-11-28 — Active Apache-2.0 Android keyboard repo, but README says word suggestions/spell checking are not in current releases.  [tag: github-repo]
11. https://github.com/florisboard/florisboard/commits/main — 2026-04-24 — Fresh maintenance signal for FlorisBoard.  [tag: github-repo]
12. https://github.com/openboard-team/openboard — 2022-08-05 — Older AOSP-derived GitHub keyboard; useful lineage/reference, but release is old and GPL-3.0.  [tag: possibly stale]
13. https://github.com/openboard-team/openboard/commits/master — 2022-12-17 — Last visible commit activity for OpenBoard; confirms staleness.  [tag: possibly stale]
14. https://wordlist.aspell.net/ — undated — Official ESDB/SCOWL site; describes the lexicon data and its commonness/dialect metadata.  [tag: primary]
15. https://github.com/en-wl/wordlist — undated — ESDB repo README states the combined work is under an MIT-like license and derived from BSD-compatible sources.  [tag: github-repo]
16. https://www.apache.org/licenses/GPL-compatibility — undated — Apache Software Foundation’s statement on Apache-2.0/GPL compatibility and why ASF avoids GPL linking.  [tag: primary]
17. https://www.apache.org/foundation/license-faq.html — undated — Apache FAQ covering redistribution, NOTICE handling, and distributing modified Apache code.  [tag: primary]
18. https://www.gnu.org/licenses/lgpl-java.html — 2021-12-25 — FSF guidance on LGPL obligations for Java linking/distribution.  [tag: possibly stale]
19. https://www.gnu.org/licenses/gpl-faq.en.html — undated — FSF guidance on GPL/AGPL distribution and network-source obligations.  [tag: primary]

## Findings
There is not a clean, permissive, drop-in GitHub AAR that gives you all four Apple-like behaviors in a native Java IME today. The closest permissive engine is AOSP LatinIME itself; the closest live GitHub turnkey engine is HeliBoard, but HeliBoard is GPL-3.0 and its checked source files carry `Apache-2.0 AND GPL-3.0-only`, so it is a bad code-import target for an MIT app [1][4][5][6][7].

**Ranked packages / repos**

1. **AOSP LatinIME core**. License: Apache-2.0 at the package level [2]. Maintenance: AOSP head visible on 2025-02-26; not as fresh as the most active GitHub repos, but still materially current [1]. Fit: best match for Apple-like offline autocorrect because it already has the exact architecture you want: binary dictionaries, full-edit-distance suggestion generation, touch-proximity scoring, n-gram context, auto-commit confidence, next-word prediction, and rule-based auto-space/caps/punctuation [1][6][7]. How to consume from a Java IME: vendor the engine source, not a dependency. The core pieces to lift are the suggestion stack (`Suggest`, `DictionaryFacilitator`, `WordComposer`, `NgramContext`), the dictionary/native layer (`BinaryDictionary` plus JNI/native code), and the commit/rules path (`InputLogic`) [6][7]. Caveat: do **not** assume the bundled AOSP dictionaries are freely reusable; the NOTICE file says `Includes Dictionaries © Lexiteria LLC. Used by permission.` [2]

2. **AnySoftKeyboard**. License: Apache-2.0 [8]. Maintenance: latest release `v1.13-r1` on 2026-02-08, with commits visible on 2026-05-17 [8][9]. Fit: best GitHub Apache-licensed Java IME base if you want to stay in Java and stay away from GPL. I would treat it as a source-vendoring/forking candidate, not a library. Primary sources confirm it is a current Java IME, but they do not expose an Apple-like standalone correction core as directly as LatinIME does, so I rank it below AOSP for “silent replace + prediction + punctuation intelligence” specifically [8][9]. Consumption path: reuse it as an IME reference/base, or cherry-pick its Java IME plumbing while still preferring LatinIME’s correction architecture.

3. **HeliBoard**. License: GPL-3.0, plus Apache/CC-BY-SA notices for inherited assets [4]. Maintenance: latest release `3.9` on 2026-03-29 [4]. Fit: behaviorally the closest live GitHub proof that AOSP-style offline autocorrect still works well in 2026. Its source shows corrections/completions, next-word prediction when no word is being composed, full-edit-distance suggestion search, n-gram probabilities, auto-correct thresholding, backspace revert, auto-cap, punctuation spacing, and double-space-to-period [5][6][7]. Consumption path: use as a **reference implementation only** unless you are willing to ship GPL-3.0. For an MIT app, do not embed or cherry-pick HeliBoard files.

4. **FlorisBoard**. License: Apache-2.0 [10]. Maintenance: latest release `v0.5.2` on 2025-11-28; commits visible on 2026-04-24 [10][11]. Fit: not suitable for this requirement today. Its own README says `Word suggestions/spell checking are not included in the current releases and are a major goal for the v0.6 milestone` [10]. Good project, wrong current feature set.

5. **OpenBoard**. License: GPL-3.0 [12]. Maintenance: latest release `v1.4.5` on 2022-08-05; latest visible commits on 2022-12-17 [12][13]. Fit: historically useful because it is an AOSP-derived ancestor, but too stale and still GPL. Do not start a 2026 integration here.

**How Apple-like autocorrect actually works**

Apple-like keyboard behavior is not a spellchecker API bolted onto an IME. It is an always-on scoring engine with five layers.

First, a **dictionary layer**. You need a base lexicon, a user/personal dictionary, and optional whitelists/blacklists. In the LatinIME lineage, `BinaryDictionary` already supports unigram lookup plus n-gram entries and dynamic updates for personalization [6].

Second, **candidate generation from the actual tap stream**. The relevant source is not just the typed characters; it is the typed characters plus touch geometry. `getSuggestionsNative(...)` takes `proximityInfo`, `xCoordinates`, `yCoordinates`, `times`, `pointerIds`, and the input code points, and the dictionary can be configured to use full edit distance [6]. That is the “keyboard key-proximity model” piece that plain spellcheck libraries usually lack.

Third, **language-model scoring**. LatinIME-style engines do not score typo candidates only by edit distance. They blend spatial score and language-model score. The same native call carries previous-word arrays and an `inOutWeightOfLangModelVsSpatialModel`, and `BinaryDictionary` exposes `getNgramProbabilityNative(...)` with up to 3 previous words of context [6]. In `Suggest`, when there is no composing word, the engine switches to `getNextWordSuggestions(...)`, which is the next-word prediction path you want for the 3-chip bar [5].

Fourth, **decision and UI policy**. `Suggest` distinguishes corrections and completions, keeps the typed word around so the user can reject the correction, and only marks `hasAutoCorrection` true when the best candidate beats a threshold and context checks [5]. That is the Apple/iOS pattern: silent correction on delimiter, but visible typed-word escape hatch in the strip. For your UI, you do not need all returned candidates; you render the top 3. When auto-correction is pending, keep the typed word in the strip, typically in the middle, so back-tapping it is easy [5].

Fifth, **commit-time smart rules**. `InputLogic` shows the pieces: commit pending auto-correction when a separator arrives; preserve a revertable last-composed word; let backspace revert the correction; compute auto-cap from cursor context; manage phantom/auto spaces; swap or strip punctuation spacing; and turn double-space into sentence-separator-plus-space [7]. This rule engine is as important as the dictionary engine if you want the keyboard to “feel” like Apple.

**How to wire this into your existing `InputMethodService`**

Keep your current chip strip; replace the hot-path engine behind it.

1. Maintain a composing buffer, tap coordinates, and timestamps for each keypress. Feed that into a LatinIME-style `WordComposer`/`ComposedData` adapter instead of relying on `SpellCheckerSession` for primary suggestions [6][7].

2. Build a keyboard-geometry object once per layout and pass touch coordinates as proximity input to the suggestion engine. Without this, you get generic spellcheck, not keyboard autocorrect [6].

3. On every non-separator key, call the suggestion engine with current `NgramContext` from the previous 1-3 words; render the top 3 candidates into your existing chip strip [5][6][7].

4. On space/punctuation, if a word is composing and the top suggestion passes threshold, commit the corrected word plus the separator. `InputLogic`’s separator path is the right model here [7].

5. When no word is composing, request next-word predictions and use the same 3-chip UI for them [5].

6. After every commit, update personalized unigram/ngram data on-device; after manual rejection or backspace revert, demote/unlearn that correction path [6][7].

7. Keep Android `SpellCheckerSession` only as a secondary service for underlines or long-press “replace” actions. It is not the right primary engine for iOS-style keyboard behavior.

**License and offline-data advice**

Apache-2.0 code is compatible with an MIT-licensed app distribution in the practical sense you want: you can ship modified Apache code inside your app, but you must preserve the Apache terms, keep notices, mark modified files, and include any required NOTICE text [2][17]. Your own original app code can still stay MIT; the shipped app just contains third-party Apache-licensed components alongside it [2][17].

GPL-3.0 is the red flag. ASF explicitly says GPL linking is a derivative-work problem from its perspective and that ASF avoids GPL software for that reason [16]. FSF’s position is also that GPL-linked applications are derivative works that must comply with GPL terms [18][19]. For your MIT app, HeliBoard/OpenBoard are therefore bad ship targets.

AGPL is worse if you ever move any of this into a network service: FSF’s FAQ says AGPL requires modified versions used over a network to offer source to network users [19]. Your current question is offline IME code, but the safe recommendation is still: avoid AGPL here.

LGPL is not automatically forbidden, but on Java/Android it is operationally annoying. FSF’s Java guidance says you must allow users to replace the library and reverse-engineer for debugging those replacements [18]. On Android APK packaging, that compliance story is usually not worth the friction unless the dependency is uniquely valuable.

For **dictionary data**, the biggest surprise in this pass is AOSP’s NOTICE: the code is Apache-2.0, but the bundled dictionaries are separately attributed to Lexiteria and “used by permission” [2]. So: use the **engine**, not the AOSP dictionary blobs, unless you separately verify redistribution rights.

For **safe English lexicon data**, ESDB/SCOWL is the strongest source I found in this pass. The site says it includes commonness, dialect, and inflection metadata useful for spellchecker dictionaries [14], and the GitHub README says the combined work is freely available under an **MIT-like** license and derived from BSD-compatible sources [15]. That makes it a strong candidate for your base unigram lexicon, with one caution: check the repo’s `Copyright` file before bundling so you know the exact attribution text [15].

For **word-frequency / n-gram data**, the safest plan is:
- Base lexicon + unigram commonness from ESDB/SCOWL [14][15].
- Static binary dictionary generation with the LatinIME/AOSP toolchain or an equivalent builder.
- Personalized bigrams/trigrams learned entirely on-device through the engine’s update hooks [6].
- If you later want a shipped generic next-word model, use only corpora you own or can redistribute under clearly permissive or attribution-only terms; do not assume third-party Hunspell packs, AOSP blobs, or random GitHub frequency lists are safe without verifying their data license.

## Confidence & caveats
- High confidence: AOSP LatinIME is the best technical fit; HeliBoard is the closest live GitHub behavior reference but GPL-blocked; OpenBoard is stale; FlorisBoard is active but currently lacks shipped word suggestions/spell checking; AOSP bundled dictionaries need separate licensing caution [1][2][4][10][12][13].
- Uncertain: Exact feature parity between AnySoftKeyboard’s current suggestion engine and Apple-style next-word prediction from primary sources alone; exact effort to extract only the minimum LatinIME subset into your existing IME without bringing over more UI/state machinery.
- Couldn't verify: License terms of HeliBoard’s external dictionary packs on Codeberg in this pass; whether Lexiteria separately publishes the exact AOSP dictionary data under a reusable license.
- As-of date of freshest supporting source: 2026-05-17

## Recommendation
Use **AOSP LatinIME core as vendored source**, not as a dependency, and keep your existing Java `InputMethodService` UI. Concretely: preserve your chip strip, replace the current `SpellCheckerSession` hot path with a LatinIME-style engine adapter, vendor the suggestion/dictionary/native/input-logic stack, and feed it your tap coordinates plus preceding-word context [1][6][7]. For bundled data, **do not ship AOSP’s dictionary blobs as-is** because of the Lexiteria notice; generate your own English base dictionary from ESDB/SCOWL and let the engine learn user-specific n-grams entirely on-device [2][14][15].

If you insist on a GitHub-first starting point, the safest Apache choice is **AnySoftKeyboard** as a Java IME base [8][9]. If you insist on the closest live behavior reference, use **HeliBoard only as a reference**, not as shipped code, because GPL-3.0 is the wrong license for your MIT distribution goal [4][16][19].
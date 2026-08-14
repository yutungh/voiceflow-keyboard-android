# Claude research — 2026-07-04 08:23 (local)

## User context
Solo developer (advanced AI-assisted workflow) building **VoiceFlow Keyboard**, a native Android IME (`InputMethodService`) written in **Java** (not Kotlin/RN). minSdk 26, target/compileSdk 35. **App is MIT-licensed.** Primarily a voice-to-text keyboard (offline transcription via Vosk / sherpa-onnx / Parakeet + cloud) that also has an Apple-style typed letter layout. **Already has autocorrect plumbing:** uses Android platform `SpellCheckerSession`/text-services, tracks a pending auto-correct word + replacement, renders a 3-chip suggestion strip.

**Goal:** add fully-**offline**, Apple-like autocorrect — (1) silent typo auto-replace, (2) 3-word suggestion bar, (3) next-word prediction, (4) auto-capitalize + double-space→period/punctuation. Prefers to **drop in an existing GitHub package** over building from scratch. Needs **license advice** (app is MIT). Deliverable: **both** ranked packages **and** the integration architecture.

Research fanned out into three aspects (libraries / architecture / license+data). Freshness: repo-activity signals prioritized to last 12 months; algorithm sources treated as evergreen.

---

## Cross-aspect synthesis (top of funnel)

The single governing constraint is **license, not capability**. The best-quality Apple-like engines (OpenBoard, HeliBoard) are **GPL-3.0**, which would force the entire MIT app to GPL if their code is bundled. The permissive path (safe for an MIT app) is: **AOSP LatinIME engine (Apache-2.0)** and/or **SymSpell Java ports (MIT)**, plus **AnySoftKeyboard (Apache-2.0)** for next-word prediction, fed by **permissive data** (SCOWL/Hunspell en_US word list + a Google-Books-Ngram-derived or self-built frequency/bigram table). Avoid CC-BY-SA frequency data (wordfreq, FrequencyWords, big.txt) verbatim in a closed product.

There is a real tension to resolve for the recommendation: **cleanest drop-in (JSymSpell, MIT, on Maven Central) does typo-correction only** — no next-word prediction, no auto-cap/punctuation; whereas the **best unified engine (AOSP LatinIME)** does everything but is a heavy vendor-and-build-NDK job with no artifact. The pragmatic middle is a **layered build**: JSymSpell (or the AOSP DAWG) for correction + a small Apache-2.0 bigram layer for prediction + your own deterministic auto-cap/punctuation rules, wired into the chip strip you already have.

---

## Sources

**Aspect A — libraries/engines**
1. https://github.com/Helium314/HeliBoard — release v3.9 (2026-03-29) — active OpenBoard successor; ~31% C++ = vendored AOSP engine; GPL-3.0 [github-repo]
2. https://github.com/AnySoftKeyboard/AnySoftKeyboard — release v1.13-r1 (2026-02-08) — Apache-2.0, pure-Java next-word prediction, active [github-repo]
3. https://github.com/rxp90/jsymspell — release v1.1.4 (2026-05-17) — JSymSpell, MIT, Java, Maven Central artifact [github-repo]
4. https://github.com/MighTguY/customized-symspell — last stable v6.6 (2020-12-15) — MIT, adds QWERTY keyboard-distance weighting; stale [github-repo]
5. https://github.com/ayhansalami/Android-Word-Predictor — inactive, no releases — Apache-2.0 Java n-gram next-word predictor (reference only) [github-repo]
6. https://github.com/florisboard/florisboard/discussions/2197 — 2025/2026 — FlorisBoard NLP core: Apache-2.0 C++, not yet shipped [primary]
7. https://github.com/openboard-team/openboard — archived 2022-12-17 — GPL-3.0; dead, superseded by HeliBoard [github-repo]
8. https://gitlab.com/aosp-mirror-1/platform/packages/inputmethods/LatinIME — AOSP mirror — Apache-2.0 native C++ dictionary engine [github-repo]
9. https://github.com/leonardoquevedox/AOSP-LatinIME — undated — AOSP LatinIME repackaged for Android Studio/AndroidX [github-repo, possibly stale]

**Aspect B — architecture**
10. https://github.com/wolfgarbe/SymSpell + https://wolfgarbe.medium.com/1000x-faster-spelling-correction-algorithm-2012-8701fcd87a5f — 2012, evergreen — Symmetric-Delete algorithm [primary]
11. https://norvig.com/spell-correct.html — 2007, evergreen — noisy-channel corrector P(c|w)=P(w)·P(typo|w) [primary]
12. https://arxiv.org/abs/1704.03987 — Google, 2017-04-13 — mobile keyboard FST decoder: Gaussian touch model + LM + confidence gate [primary]
13. https://mjtsai.com/blog/2023/12/22/ios-17-autocorrect/ — 2023 — iOS 17 moved to on-device transformer LM (context; out of scope for a small IME) [secondary]
14. https://android.googlesource.com/platform/packages/inputmethods/LatinIME — AOSP — BinaryDictionary/ProximityInfo/bigram, TYPED_LETTER_MULTIPLIER, MAX_BIGRAMS [primary]
15. https://developer.android.com/reference/android/inputmethodservice/InputMethodService — current — commitText/setComposingText/onUpdateSelection semantics [primary]
16. https://developer.android.com/reference/android/view/textservice/SpellCheckerSession — current — async, third-party-dependent, no next-word/proximity [primary]
17. https://codeberg.org/Helium314/aosp-dictionaries — ongoing — prebuilt AOSP DAWG dictionaries to bundle [primary]

**Aspect C — license + data**
18. https://www.gnu.org/licenses/gpl-faq.html — FSF — GPL "based on the work"/conveying obligations [primary]
19. https://www.gnu.org/licenses/gpl-3.0.en.html — GPLv3 text [primary]
20. https://wiki.qt.io/Licensing-talk-about-mobile-platforms — LGPL relink problem on mobile [secondary]
21. https://github.com/rspeer/wordfreq — 2024 sunset — Apache-2.0 code, CC-BY-SA 4.0 data, ~2021 snapshot [primary]
22. https://github.com/hermitdave/FrequencyWords — MIT LICENSE file but README says data is CC-BY-SA 3.0 [primary]
23. https://wordlist.aspell.net/hunspell-readme/ — en_US Hunspell = SCOWL permissive + BSD affix [primary]
24. https://codeberg.org/Helium314/aosp-dictionaries — AOSP dict tooling Apache-2.0; per-dictionary license follows source corpus (some GPLv2) [primary]
25. https://norvig.com/spell-correct.html — big.txt = Gutenberg(PD)+Wiktionary(CC-BY-SA)+BNC [primary]

---

## Findings

### A. Concrete packages / engines (ranked for an MIT Java IME)

- **JSymSpell (`io.gitlab.rxp90:jsymspell`, MIT, Java, active — v1.1.4 2026-05-17).** The only true clean **Gradle/Maven drop-in**. Symmetric-Delete edit-distance correction + frequency-ranked suggestions, zero deps, Java 8+. **Boundary:** correction + 3-suggestions only — **no next-word prediction, no auto-cap/punctuation** (you build those). Fit: **Strong** for the autocorrect half.
- **AnySoftKeyboard dictionary/prediction module (Apache-2.0, Java, active — v1.13-r1 2026-02-08).** Pure-JVM dictionary + **real next-word prediction** via n-gram word-list packs. Not a standalone artifact — you **vendor/extract** the `Suggest`/dictionary classes from its `/ime` module. Fit: **Strong** as the *prediction* half, license- and language-compatible.
- **AOSP LatinIME engine (Apache-2.0, Java layer + native C++ DAWG).** The genuine "Apple-like" unified engine: proximity-weighted autocorrect + frequency-ranked strip + bigram next-word, from binary `.dict`. **No artifact** — vendor the C++ + JNI + `BinaryDictionary`/`Suggest`/`DictionaryFacilitator` Java classes and build the `.so` with NDK. **HeliBoard has already modernized this native build** to Gradle+NDK — easiest on-ramp to the AOSP engine — **but** you must take only HeliBoard's Apache-2.0 AOSP-derived portions, not its GPL-3.0 original code. Fit: **Strong capability, high integration + legal-separation cost.**
- **customized-symspell (`io.github.mightguy:symspell-lib`, MIT, Java).** Adds weighted Damerau-Levenshtein + **QWERTY keyboard-distance weighting** (valuable for Apple-like fat-finger fixes) + word segmentation. **Stale** (last stable v6.6 2020-12-15; newest features only in a 6.7-SNAPSHOT). Fit: **Medium** — richer than JSymSpell but unmaintained.
- **FlorisBoard nlpcore (Apache-2.0, C++ submodule).** Best *license* fit for a full unified engine, but the suggestion engine is **not production-ready** (word suggestions targeted for v0.6, no firm ETA; open bugs). Fit: **Medium — one to watch, not depend on today.**
- **OpenBoard (GPL-3.0, archived 2022-12-17).** Dead; superseded by HeliBoard. **Weak — do not build on it.**
- **HeliBoard (GPL-3.0, active).** Highest capability, but **GPL-3.0 infects an MIT app** if its code is bundled. **Weak-Medium for MIT** (great as a *reference* and as the modernized AOSP-native build source).
- **Android-Word-Predictor (Apache-2.0, inactive, tiny).** Right idea (offline Java n-gram next-word) but unmaintained + Sugar-ORM dep. **Weak — reference code only.**
- **Platform `SpellCheckerSession` (what you use now).** Free, offline, but async, depends on whatever spell-checker the user installed, **no proximity model, no next-word, no controllable confidence-gated silent replace.** Fine as a fallback; **insufficient alone** for Apple-like feel.
- **Hunspell/nuspell via JNI.** Suggestions only (no next-word), LGPL/GPL/MPL tri-license friction, no turnkey Android AAR. **Weak** for this use case (not deeply verified this pass).

**Shortlist:** (1) **JSymSpell** for typo auto-replace + suggestions; (2) **AnySoftKeyboard's Apache-2.0 Java prediction module** for next-word; (3) **AOSP LatinIME engine (via HeliBoard's modernized native build, Apache-parts only)** if you want the single best unified engine and will pay the integration + license-separation cost.

### B. How Apple-like autocorrect works + integration

**Model:** a **noisy-channel decoder** — `argmax_W P(W|O) ∝ P(O|W)·P(W)`, touch/error model × language model, plus a decision layer.

1. **Lexicon + unigram frequency** in a **DAWG/trie** (per-word frequency byte); O(len) lookup + free prefix completion. Frequency alone (Norvig) already ~80%.
2. **Candidate generation via Damerau-Levenshtein** (includes transposition → "teh"→"the" is *one* edit). Don't brute-force at runtime — use **SymSpell (precomputed deletes)** [recommended for a small Java IME], or **DAWG traversal with a Levenshtein-automaton DP row**, or a BK-tree. Budget k=1 short words, k=2 longer.
3. **Keyboard key-proximity / touch model** — the piece pure spellcheckers miss. Model each touch as a **2-D Gaussian** over the intended key, or (pragmatic, if you only have committed chars) build a **key-adjacency map** and make substitution cost a function of adjacency (~0.5 neighbor vs 1.0 far). This is what makes fat-finger fixes feel Apple-like.
4. **Language model + next-word prediction:** bigram/trigram `P(w|context)` with **stupid backoff**. Score = α·(spatial/edit) + β·(unigram logfreq) + γ·(bigram logprob). Next-word strip = top bigram successors of the last committed word.
5. **Auto-replace decision (confidence gate):** silently replace only when (a) literal isn't a common dictionary word, (b) best candidate beats literal by a margin, (c) edit distance is small. Don't silently replace valid common words (avoids over-correction). **UX contract:** composing underline while typing → on space/punctuation boundary, commit correction + store revert record → **backspace immediately after = restore original** (Apple/AOSP behavior) → tapping the literal chip commits literal + learns it.
6. **Smart typographic rules (deterministic, no ML):** auto-cap sentence starts (honor `EditorInfo`/`getCursorCapsMode`), double-space→". ", auto-apostrophe lookup table (dont→don't, i→I), and **suppress autocorrect/auto-cap in URL/email/password fields** (check `EditorInfo.inputType`).
7. **iOS 17+ note:** Apple moved to an on-device **transformer LM**; out of scope for a small MIT Java IME — the classical DAWG + Damerau-with-proximity + bigram stack (what AOSP shipped for a decade) delivers ~90% of the perceived quality.

**Integration into your existing IME:**
- **Replace `SpellCheckerSession` as the primary engine** (keep it only as optional fallback). It's async, third-party-dependent, and can't do proximity/next-word/controlled silent-replace. Build a **bundled in-process engine** (DAWG + Damerau/proximity + bigram) as the source of truth for the strip and auto-replace.
- **Hook points:** per-keystroke → recompute candidates for current word, update `setComposingText` + push top-3 to your chip strip; at **commit boundaries** (space/punct/newline) → run confidence gate, `commitText(correction)` + store revert record, then populate strip with **next-word predictions**; use `onUpdateSelection` only to *observe* cursor moves and reset state (don't re-trigger correction inside it).
- **Threading:** engine on a **single background HandlerThread/serial executor**; debounce + tag requests with a generation counter, drop stale results, post updates to main looper. Load DAWG once at `onCreate`, memory-mapped.
- **Silent auto-replace + revert:** keep `LastCorrection{original, committed, offset}`; on the immediate backspace, `deleteSurroundingText` + `commitText(original)`; any other key/cursor-move clears it. Persist a small **user unigram/bigram learning store** (SQLite/file) separate from the immutable bundled DAWG so it "stops fighting you."
- **Data to bundle:** AOSP-format **DAWG** (English main dict ready-made at Helium314/aosp-dictionaries) + a **bigram binary** (cap ~60 successors/word) + a runtime-derived key-proximity table (from your Keyboard XML) + a small contraction table. **Budget:** ~5–20 MB resident (mmap'd), per-keystroke compute sub-5 ms, end-to-end < 16 ms (one frame). Debounce/coalesce protects the frame budget under fast typing.

### C. License advice + offline data

**An APK is one distributed binary — there is no dynamic-vs-static-linking escape hatch on Android.** Whatever you bundle (Java, `.so`, `.dict`, `.aar`) is "conveyed" together.

| License of reused code | Verdict for MIT/flexible APK | Action |
|---|---|---|
| Public domain / CC0 / Unlicense | Safe | none |
| MIT / BSD / ISC | Safe | keep notice |
| **Apache-2.0** (AOSP LatinIME) | **Safe — best choice** | keep NOTICE + license; **bonus express patent grant** |
| LGPL-2.1/3 | **Avoid on Android** | relink requirement collides with APK signing |
| MPL-2.0/EPL | Usable, file-level copyleft | modified MPL files stay MPL |
| **GPLv2/GPLv3** (OpenBoard, HeliBoard) | **Incompatible with closed/MIT app** | bundling forces the **whole APK to GPL** + source release |
| AGPLv3 | Same as GPL (+ network clause) | avoid |

**Decision rule:** build only on **Apache-2.0 or MIT/BSD** code. Apache-2.0 is the *strategically smart* permissive pick because it carries an **express patent grant** (MIT/BSD are silent on patents — relevant given keyboard/correction patent density). **Do not copy code from GPL keyboards** (OpenBoard/HeliBoard) — reference their approach only. Ship an "Open-source licenses" screen. Not legal advice; the GPL-linking question is untested in court and CC-BY-SA's reach over derived frequency tables is arguable — get an attorney's final call for a commercial launch.

**Offline data — the recurring trap is CC-BY-SA (ShareAlike) on the *data* even when the *code* is permissive:**

| Source | Gives | License | Bundle in MIT/closed APK? |
|---|---|---|---|
| SCOWL / Hunspell en_US word list | English spelling word list | SCOWL permissive (use/copy/**sell**) + BSD affix | **Yes** — cleanest permissive full-correction dataset |
| AOSP LatinIME `.dict` + `dicttool` | binary word+bigram dict + tooling | tooling Apache-2.0; **each dict inherits its source-corpus license (some GPLv2)** | Tools yes; **data per-language — English generally fine, verify each** |
| Google Books Ngram frequency | n-gram frequency | CC-BY 3.0 (attribution, no SA) | **Yes with attribution** (derive a compact table) |
| wordfreq (rspeer) | word frequency | code Apache-2.0; **data CC-BY-SA 4.0**; sunset ~2021 | Code yes; **data = ShareAlike caveat** |
| hermitdave/FrequencyWords | per-lang frequency | LICENSE=MIT but README says **data CC-BY-SA 3.0** | **Treat data as CC-BY-SA** (ShareAlike caveat) |
| Norvig big.txt | text for freq model | code MIT; big.txt = Gutenberg(PD)+Wiktionary(CC-BY-SA)+BNC | **Regenerate your own**, don't ship verbatim |
| SUBTLEX | subtitle word frequencies | typically CC-BY-NC-ND (non-commercial) | **No for commercial** |
| COCA / BNC | premium frequency | paid / restrictive | **No** |

**Data recommendation:** English engine from **SCOWL/Hunspell en_US word list (permissive, sellable) + a frequency/bigram table derived from Google Books Ngram (CC-BY, attribution) or self-generated**, packed into AOSP's Apache-2.0 `.dict` format. Avoid shipping wordfreq/FrequencyWords/big.txt data verbatim in a closed product (ShareAlike).

---

## Confidence & caveats
- **High confidence (primary-verified):** repo licenses/activity/release dates (HeliBoard v3.9 2026-03-29 GPL-3.0; AnySoftKeyboard v1.13-r1 2026-02-08 Apache-2.0; JSymSpell v1.1.4 2026-05-17 MIT; OpenBoard archived 2022-12-17); SymSpell/Norvig/FST-decoder architecture; AOSP DAWG/ProximityInfo/bigram existence; Android IME API semantics; GPL whole-app copyleft + LGPL Android friction; SCOWL permissive; wordfreq/FrequencyWords CC-BY-SA data.
- **Uncertain / inferred:** exact AOSP confidence-threshold formula and α/β/γ weights (heuristic reconstruction, not literal constants); whether AnySoftKeyboard's `Suggest`/dictionary classes extract cleanly from its module; Hunspell-on-Android tooling state (not fetched); FlorisBoard nlpcore status may have advanced.
- **Needs the dev to verify directly:** the license of each specific `.dict`/frequency file bundled (English fine, some AOSP language dicts GPLv2); FrequencyWords' internal LICENSE-vs-README inconsistency; big.txt provenance; specific SUBTLEX variant terms.
- **As-of date of freshest supporting source:** 2026-05-17 (JSymSpell release). Repo-activity picture current as of 2026-07-04.
- **Possibly stale:** OpenBoard (archived 2022), customized-symspell (2020), leonardoquevedox/AOSP-LatinIME (undated).

## Recommendation
For an **MIT-licensed, Java, offline IME that already has a chip strip**, build a **layered permissive engine**, not a single GPL drop-in:
1. **Correction + suggestions:** **JSymSpell (MIT, Maven)** — true drop-in — optionally borrowing customized-symspell's keyboard-distance idea, plus your own runtime key-adjacency weighting for Apple-like fat-finger fixes.
2. **Next-word prediction:** a small **bigram layer** using **AnySoftKeyboard's Apache-2.0** approach/data.
3. **Auto-cap / double-space→period / apostrophes / field suppression:** your own deterministic rules (cheap, high-impact).
4. **Data:** SCOWL/Hunspell en_US + Google-Books-Ngram-derived frequency, packed permissively.
5. **Wire it in** by replacing `SpellCheckerSession` as the primary engine with the in-process bundled engine, computed off the UI thread, feeding your existing strip + a confidence-gated silent replace with backspace-revert.

If you want the **single best unified engine** and will pay the integration + license-separation cost, take the **AOSP LatinIME engine via HeliBoard's modernized Gradle+NDK build — Apache-2.0 parts only** (never HeliBoard's GPL-3.0 original code). That is the closest to literal Apple/AOSP behavior in one package, at the price of vendoring native code and carefully firewalling GPL from Apache.

# Synthesis — 2026-07-04 08:23 (local)

## Question
"Autocorrect feature from GitHub that we can use as a package to add autocorrect into our keyboard somehow that functions similarly to Apple keyboard." (Offline, all four Apple behaviors, both a ranked package pick and the integration architecture; license advice given the app is MIT.)

## User context (the situation this answer is tailored to)
Solo dev (advanced AI-assisted workflow) building **VoiceFlow Keyboard** — a native Android IME (`InputMethodService`) in **Java**, minSdk 26 / target 35, **MIT-licensed**. Voice-to-text keyboard with an Apple-style typed layout. **Already has autocorrect plumbing:** Android platform `SpellCheckerSession`, a pending auto-correct word/replacement, and a 3-chip suggestion strip. Wants **fully-offline**, Apple-like autocorrect: (1) silent typo auto-replace, (2) 3-word suggestion bar, (3) next-word prediction, (4) auto-capitalize + double-space→period/punctuation. Prefers a **GitHub package drop-in**; needs **license guidance**.

## Answer
**There is no single permissive GitHub package you can `implementation '...'` into Gradle and get all four Apple behaviors — that package does not exist for a Java IME in 2026.** Both agents independently confirmed this. So do one of two things, keeping your existing chip strip and **replacing `SpellCheckerSession` as the primary engine**:

- **Path A — Fast, pragmatic, stays cleanly MIT (recommended to start):** layer permissive libraries — **JSymSpell** (MIT, on Maven Central, a genuine drop-in) for typo auto-replace + suggestions, add your own **key-proximity weighting** and a small **bigram next-word** layer (AnySoftKeyboard's Apache-2.0 approach), plus **deterministic rules** for auto-cap / double-space→period / apostrophes. Ships in days; ~80–90% of the Apple feel.
- **Path B — Highest fidelity, more work:** vendor the **AOSP LatinIME engine** (Apache-2.0) as source — the real thing does proximity-weighted correction + n-gram prediction + all the smart rules in one engine. Best on-ramp is **HeliBoard's already-modernized Gradle+NDK build**, taking **only its Apache-2.0 AOSP-derived code, never its GPL-3.0 original code**.

**Do NOT bundle OpenBoard or HeliBoard code** — they are **GPL-3.0** and would force your entire MIT app to GPL. And **do NOT ship AOSP's bundled dictionary files** — they are licensed from Lexiteria "used by permission," not freely redistributable. Build your dictionary from **SCOWL/ESDB** (permissive) instead.

## Why
- **License is the governing constraint, not capability.** The best turnkey engines (HeliBoard, OpenBoard) are GPL-3.0; an APK is one conveyed binary with no linking-escape hatch, so bundling their code relicenses your whole app as GPL. Apache-2.0 (AOSP LatinIME, AnySoftKeyboard, FlorisBoard) and MIT (JSymSpell) are the only ship-safe options for an MIT app. Apache-2.0 additionally carries an **express patent grant** (MIT/BSD are silent on patents) — a real plus in patent-dense keyboard territory.
- **"Apple-like" is an always-on scoring engine, not a spellchecker.** The essential piece plain spellcheckers lack is the **keyboard key-proximity / touch model** — weighting corrections by physical key distance is what makes fat-finger fixes feel native. Codex confirmed from AOSP/HeliBoard source that the real engine feeds tap coordinates (`xCoordinates`, `yCoordinates`, `times`, `proximityInfo`) into candidate generation, blends a spatial score with an n-gram language-model score, and only auto-commits above a confidence threshold — with the typed word kept in the strip as an escape hatch and backspace-to-revert.
- **`SpellCheckerSession` can't get you there.** It's async, depends on whatever spell-checker the user installed, and has no proximity model, no next-word prediction, and no controllable confidence-gated silent replace. Both agents agree: demote it to a secondary (red-underline / long-press replace) role and make a bundled in-process engine the source of truth.
- **Your plumbing is already right.** You have the chip strip, a pending-correction concept, and commit hooks. The work is swapping in a real correction engine behind them and adding the commit-time smart rules — not rebuilding the keyboard.

## Agreement between Claude and Codex
- No clean permissive drop-in gives all four Apple behaviors today; the closest permissive **engine** is AOSP LatinIME; the closest live **behavioral reference** is HeliBoard (but GPL-blocked).
- **GPL-3.0 (OpenBoard/HeliBoard) = do not bundle** in an MIT app; **Apache-2.0 / MIT = safe**; LGPL is high-friction on Android; AGPL avoid.
- **Replace `SpellCheckerSession`** as the primary hot-path engine; keep it only as a secondary service.
- The **5-layer architecture** is identical across both passes: (1) dictionary/lexicon + frequency, (2) candidate generation from the **tap stream with proximity**, (3) **n-gram language-model** scoring blended with spatial score, (4) confidence-gated **silent auto-replace** with typed-word escape hatch + **backspace revert**, (5) commit-time **smart rules** (auto-cap, double-space→period, punctuation spacing, apostrophes).
- **Next-word prediction** = when no word is composing, feed n-gram successors of the last word(s) into the same 3-chip strip.
- **On-device personalization**: learn user unigrams/bigrams, demote corrections the user rejects.
- **FlorisBoard's** NLP/suggestion engine is Apache-2.0 but **not shipped yet** (v0.6 goal) — one to watch, not depend on.
- **SCOWL/ESDB** is the safe permissive base lexicon.

## Disagreements and resolution
### Point 1: Are AOSP's bundled dictionaries safe to ship? (material)
- **Claude (aspect-C):** implied the English AOSP `.dict` is "generally fine"; flagged only that *some* language dicts are GPLv2.
- **Codex:** read the AOSP `NOTICE` file directly — *"Includes Dictionaries © Lexiteria LLC. Used by permission."* → the dictionary **data** is not freely redistributable even though the **engine code** is Apache-2.0.
- **Resolution:** **Codex is right** (primary-source NOTICE beats an assumption). Use the AOSP **engine**, not its dictionary blobs. Both agents already converge on **SCOWL/ESDB** as the base lexicon, so the recommendation is unchanged and merely sharper: generate your own English dictionary from SCOWL and let the engine learn user n-grams on-device.

### Point 2: Lead with "vendor AOSP LatinIME" or a "layered permissive build"? (emphasis, not contradiction)
- **Codex:** ranks **vendoring AOSP LatinIME core** as the #1 recommendation (best architectural match; did not surface pure spell-correction libraries).
- **Claude:** leads with a **layered JSymSpell + AnySoftKeyboard permissive build** as the pragmatic first move, AOSP as the heavier high-fidelity alternative; surfaced **JSymSpell** (the only true Maven drop-in) which Codex missed.
- **Resolution:** Not a factual conflict — an effort-vs-fidelity tradeoff. Presented as **Path A (fast/layered)** vs **Path B (AOSP, highest fidelity)**. Given the user explicitly prefers a *package drop-in* and wants to ship, **Path A is the recommended starting point**, with a clean upgrade road to Path B if the touch-model fidelity proves insufficient. Claude's JSymSpell find is a genuine value-add; Codex's source-level identification of the exact AOSP classes to lift (`Suggest`, `DictionaryFacilitator`, `WordComposer`, `NgramContext`, `BinaryDictionary`, `InputLogic`) is the value-add for Path B.

## Confidence and recency
**High confidence.** Repo licenses, maintenance dates, and — critically — the AOSP dictionary NOTICE and HeliBoard's source-level behavior were verified against **primary sources** (the repos and license files themselves) as recently as **2026-05-17**, with the repo-activity picture current as of **2026-07-04**. The architecture rests on evergreen sources (SymSpell 2012, Norvig 2007, Google's FST-decoder paper 2017) that remain the correct target for a small offline IME — the only thing that has "moved" is that big vendors (Apple iOS 17+, Gboard) shifted to on-device transformer LMs, which is explicitly out of scope for a lightweight MIT Java keyboard. **What could change the answer in 3–6 months:** FlorisBoard shipping its Apache-2.0 NLP core (v0.6) would create a genuinely permissive full-engine option worth re-evaluating. Two items the developer should verify directly before a commercial ship: the exact license text of any SCOWL/ESDB `Copyright` file bundled, and — if ever tempted — that no Lexiteria/AOSP dictionary blob slips into the APK.

## Consolidated sources
1. https://android.googlesource.com/platform/packages/inputmethods/LatinIME — Apache-2.0 engine (BinaryDictionary, ProximityInfo, n-gram, InputLogic) — recent [Both] [primary]
2. https://android.googlesource.com/.../LatinIME/+/refs/heads/main/NOTICE — **dictionaries © Lexiteria, "used by permission"** — undated [Cx] [primary]
3. https://github.com/HeliBorg/HeliBoard (a.k.a. Helium314/HeliBoard) — v3.9, 2026-03-29, **GPL-3.0** — behavioral reference only — recent [Both] [github-repo]
4. https://github.com/AnySoftKeyboard/AnySoftKeyboard — v1.13-r1, 2026-02-08, **Apache-2.0**, Java, active — recent [Both] [github-repo]
5. https://github.com/rxp90/jsymspell — v1.1.4, 2026-05-17, **MIT**, Maven Central drop-in — recent [C] [github-repo]
6. https://github.com/MighTguY/customized-symspell — v6.6, 2020-12-15, MIT, QWERTY keyboard-distance weighting — aging [C] [github-repo]
7. https://github.com/florisboard/florisboard — v0.5.2, 2025-11-28, Apache-2.0; **word suggestions not shipped (v0.6 goal)** — recent [Both] [github-repo]
8. https://github.com/openboard-team/openboard — archived 2022-12-17, GPL-3.0, dead — possibly stale [Both] [github-repo]
9. https://wordlist.aspell.net/ + https://github.com/en-wl/wordlist — SCOWL/ESDB, **MIT-like/permissive** base lexicon — undated [Both] [primary]
10. https://github.com/wolfgarbe/SymSpell — Symmetric-Delete algorithm (candidate generation) — 2012, evergreen [C] [primary]
11. https://norvig.com/spell-correct.html — noisy-channel corrector; big.txt provenance caveat — 2007, evergreen [C] [primary]
12. https://arxiv.org/abs/1704.03987 — Google FST decoder: Gaussian touch model + LM + confidence gate — 2017-04-13 [C] [primary]
13. https://mjtsai.com/blog/2023/12/22/ios-17-autocorrect/ — iOS 17 transformer LM shift (context, out of scope) — 2023 [C] [secondary]
14. https://developer.android.com/reference/android/inputmethodservice/InputMethodService — commitText/setComposingText/onUpdateSelection — current [C] [primary]
15. https://developer.android.com/reference/android/view/textservice/SpellCheckerSession — async, third-party-dependent — current [Both] [primary]
16. https://codeberg.org/Helium314/aosp-dictionaries — prebuilt AOSP DAWG dicts + tooling; **per-dict source license varies (some GPLv2)** — recent [C] [primary]
17. https://www.gnu.org/licenses/gpl-faq.html — GPL "based on the work"/conveying obligations — current [C] [primary]
18. https://www.apache.org/licenses/GPL-compatibility — Apache-2.0/GPL compatibility direction — undated [Cx] [primary]
19. https://github.com/rspeer/wordfreq — Apache-2.0 code, **CC-BY-SA 4.0 data**, sunset ~2021 — 2024 [C] [primary]
20. https://github.com/hermitdave/FrequencyWords — LICENSE=MIT but data declared **CC-BY-SA 3.0** — undated [C] [primary]
21. https://wiki.qt.io/Licensing-talk-about-mobile-platforms — LGPL relink friction on mobile — undated [C] [secondary]

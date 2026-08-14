# Vendored third-party data

Everything the app ships that we did not write lives here, pinned to an exact
upstream revision and hashed, together with the licence text its terms require
us to retain. Nothing under `app/src/main/assets/` is hand-written — each asset
is generated from a source in this directory by a script in `scripts/`.

VoiceFlow Keyboard is MIT-licensed. That constrains what may be vendored: only
permissive sources, never GPL/AGPL (an APK is one conveyed binary — there is no
dynamic-linking escape hatch on Android), and never ShareAlike *data* even where
the surrounding code is permissive.

Verify a pin with:

```sh
sha256sum third_party/<dir>/<file>
```

---

## rime-pinyin-simp — Chinese pinyin dictionary

| | |
|---|---|
| Upstream | https://github.com/rime/rime-pinyin-simp |
| Pinned at | `0c6861ef` |
| Licence | Apache-2.0 (`LICENSE`, plus `AUTHORS`, both retained) |
| Consumed by | `scripts/build-pinyin-dict.mjs` |
| Ships as | `app/src/main/assets/pinyin_dict.txt` (38,999 keys, 542 KB APK growth) |

Apache-2.0 requires retaining the licence and attribution notices, which is why
`LICENSE` and `AUTHORS` sit beside the data rather than only in the generator
header.

---

## symspell-en-frequency — English word list with frequencies

The unigram prior for English autocorrect. A correction engine without frequency
data cannot rank candidates, and a word list alone cannot supply that ranking.

| | |
|---|---|
| Upstream | https://github.com/wolfgarbe/SymSpell |
| File | `SymSpell/frequency_dictionary_en_82_765.txt` |
| Pinned at | `6b8efd7d5053b68013e0193357a79011aa648ce2` (2021-06-29, the last commit to touch this file) |
| SHA-256 | `c604e1121e398ae7c7fbf777f11e0a0f2fa66eda932cb9fba1321466cf3acd7b` |
| Size | 1,332,881 bytes / 82,834 entries |
| Format | `word<space><count>`, one per line |

### Licence chain — verified against primary sources 2026-08-13

This artifact is a *derived work of two upstream datasets*, so the MIT licence on
the SymSpell repository is **not** by itself sufficient authority to redistribute
it. All three licences below were read directly, and the obligations are the
union of them.

1. **Google Books Ngram** — the frequency counts.
   CC BY 3.0 Unported, per Google's own dataset page: *"This compilation is
   licensed under a Creative Commons Attribution 3.0 Unported License."*
   **No ShareAlike, no NonCommercial.** Commercial redistribution and derivative
   works are permitted with attribution.
   https://storage.googleapis.com/books/ngrams/books/datasetsv3.html

2. **SCOWL / ESDB** — the vocabulary the counts were filtered against.
   Retained verbatim as `LICENSE.SCOWL.txt` (sha256
   `71bffd4b74ad47fff01c8b3c666e77da92737854e568850d8728024e0a53f304`).
   Kevin Atkinson grants permission to *"use, copy, modify, distribute, and sell
   any part of the English Speller Database (ESDB ...), **or word lists created
   from it**"* provided the copyright and permission notice appear in all copies
   and in supporting documentation. That clause is what covers this derived list.
   No component carries a copyleft licence: the sources are 12dicts and ENABLE2K
   (public domain), WordNet (BSD-style), UKACD, and Ispell/VarCon (BSD).
   COCA 3-gram data was used under Atkinson's own NDA, but the grant above
   explicitly extends to word lists created from the database.
   The notice itself states that for an official non-Australian-English speller
   dictionary, the block before its `===` separator is sufficient.

3. **SymSpell** — the intersection work itself.
   MIT, © 2018 Wolf Garbe. Retained as `LICENSE.SymSpell.txt`.
   The README describes the derivation: Google Books Ngram ∩ SCOWL, keeping
   *"only those words which appear in both lists"*, truncated to ~80k entries.
   Note the README gives no explicit licence statement for the bundled data
   files as distinct from the code — which is exactly why items 1 and 2 are
   tracked here independently rather than assumed to be covered by the MIT file.

**Attribution the app must ship** (open-source licences screen): Google Books
Ngram (CC BY 3.0), SCOWL/ESDB © Kevin Atkinson with the notice above, and
SymSpell (MIT).

### Known defects in the upstream data

The 2021 pin appended 67 entries by hand *after* the SCOWL intersection had
already run, so these 67 alone are unvetted. They are identifiable because they
all carry a flat count of `300000` — a value that also breaks the file's
otherwise descending sort. All 67 were reviewed individually:

- 65 are ordinary contractions (`don't`, `we're`, `o'clock`, …) plus `covid` and
  `hi`. Legitimate.
- **`you'v` is a typo.** The correct `you've` is absent from the file entirely,
  so the defect both adds a non-word and loses a real one. The generator must
  drop `you'v` and add `you've`.

Two further consequences of that flat count, for whoever tunes ranking:

- All 67 share one frequency, so they cannot be ranked against each other. The
  value sits far above the file's tail (~12,800), so they are treated as roughly
  as common as the 12,000th word — plausible for contractions, arbitrary for
  `covid` and `hi`.
- The `i`-contractions are stored lowercase (`i'm`, `i'd`, `i'll`, `i've`).
  Correcting `im` must yield `I'm`, not `i'm`, so capitalisation of this group
  cannot be left to the generic case-matching path.

Confirmed absent from the lexicon, and therefore correctly left to the explicit
typo table rather than dictionary lookup: `im`, `teh`, `dont`.

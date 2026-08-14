/**
 * Converts the pinned English frequency list into the compact asset the
 * keyboard ships. Run from the repo root:
 *
 *   node scripts/build-english-dict.mjs
 *
 * Source: SymSpell's frequency_dictionary_en_82_765.txt @ 6b8efd7d, itself the
 * intersection of Google Books Ngram (CC BY 3.0) and SCOWL/ESDB. The upstream
 * file lives in third_party/symspell-en-frequency/ with the three licence texts
 * whose terms require us to retain them; see third_party/README.md for the
 * licence chain and why SymSpell's MIT file is not sufficient authority alone.
 *
 * Output format (UTF-8, LF, '#' header lines first, then one line per word
 * sorted ascending by UTF-16 code unit so Java can binary-search it):
 *
 *   #v1
 *   #words=<int>
 *   #logscale=<int>
 *   #maxword=<int>
 *   <word>\t<scaled log unigram probability>
 *
 * The frequency is stored pre-logged as round(ln(count / total) * logscale),
 * always negative. Two reasons not to ship raw counts: the largest is
 * 23,135,851,162, which does not fit an int and costs 11 characters a line,
 * and the corrector wants log probabilities anyway, so precomputing keeps 82k
 * Math.log calls out of the asset load.
 *
 * Sort order must be plain UTF-16 code-unit ordering, NOT localeCompare —
 * Java's Arrays.binarySearch uses String.compareTo, which compares code units.
 * A locale-aware sort here would silently break lookups on device for exactly
 * the words whose collation differs, notably everything containing "'"
 * (U+0027 sorts before every letter).
 */

import fs from "node:fs";
import path from "node:path";

const SOURCE = path.join(
  "third_party",
  "symspell-en-frequency",
  "frequency_dictionary_en_82_765.txt"
);
const OUTPUT = path.join("app", "src", "main", "assets", "english_dict.txt");
const LOG_SCALE = 1000;

/**
 * Corrections to the 67 entries upstream appended by hand in 2021 AFTER its
 * SCOWL intersection had already run, which is why these alone were never
 * vocabulary-checked. They are identifiable by a flat count of 300000. All 67
 * were reviewed; exactly one is wrong. See third_party/README.md.
 */
const FLOOR_COUNT = 300000;
const DROP = new Set(["you'v"]); // typo for "you've"
const ADD = new Map([["you've", FLOOR_COUNT]]); // absent upstream entirely

/**
 * First-party supplementary vocabulary.
 *
 * The upstream list is Google Books Ngram intersected with SCOWL, so it knows
 * "okay" but not "ok", and nothing at all about texting or software. On a phone
 * keyboard that is not a gap, it is a defect: without these the corrector treats
 * "omg" and "thx" as misspellings and silently rewrites them to "org" and "the".
 *
 * These are NOT frequency data. Each is emitted at the lowest count present in
 * the pinned corpus, which is the weakest prior the format can express: enough
 * to be recognised as a real word, never enough to win a ranking contest against
 * a word that was actually measured. We do not know how common "tbh" is and this
 * file does not pretend to.
 *
 * Every entry below was checked to be genuinely protected at that weight — that
 * is, to have no adjacent-key substitution rival clearing the corrector's
 * real-word override gap. Two candidates failed that check and are deliberately
 * absent: "np" (loses to "no") and "vlog" (loses to "blog"). Including them
 * would have meant inventing a higher weight to defend them, which is exactly
 * the claim this floor convention exists to avoid making.
 *
 * Deliberately excluded for other reasons: single letters like "u" and "r",
 * which the service discards before the corrector ever sees them; and "im" and
 * "dont", which must keep expanding to "I'm" and "don't" through COMMON_TYPOS.
 */
const SUPPLEMENT = [
  // chat and messaging
  "ok", "lol", "omg", "lmao", "rofl", "smh", "nvm", "jk", "brb", "ttyl",
  "idk", "imo", "imho", "tbh", "fyi", "btw", "iirc", "afaik", "tldr",
  "pls", "plz", "thx", "thanx", "ty", "ya", "eh", "ahh", "aww", "hmmm",
  // software and the web
  "api", "uri", "url", "json", "xml", "html", "css", "sms", "mms",
  "gps", "cpu", "gpu", "ssd", "usb", "hdmi", "pdf", "faq", "ios",
  "github", "gitlab", "reddit", "instagram", "tiktok", "whatsapp",
  "spotify", "uber", "airbnb", "macbook", "linkedin",
  // ordinary modern words the corpus predates
  "emoji", "selfie", "hashtag", "lockdown", "signup",
];

function parseSource(raw) {
  const lines = raw.split(/\r?\n/);
  const entries = [];
  const rejected = [];
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      continue;
    }
    const fields = trimmed.split(/\s+/);
    if (fields.length !== 2) {
      throw new Error(`Unexpected row shape: ${JSON.stringify(line)}`);
    }
    const [word, count] = fields;
    if (!/^[0-9]+$/.test(count)) {
      throw new Error(`Non-integer count in: ${JSON.stringify(line)}`);
    }
    // The corrector only ever composes a word from letter and apostrophe keys
    // (see isAutoCorrectWordCharacter in the service), so anything else could
    // never be typed and would only waste scan budget and APK bytes.
    if (!/^[a-z']+$/.test(word)) {
      rejected.push(word);
      continue;
    }
    if (DROP.has(word)) {
      rejected.push(word);
      continue;
    }
    entries.push({ word, count: Number.parseInt(count, 10) });
  }
  return { entries, rejected };
}

function build(entries) {
  const byWord = new Map();
  for (const entry of entries) {
    // Upstream can list a word twice; keep the strongest count rather than
    // emitting a duplicate the binary search would never reach.
    const existing = byWord.get(entry.word);
    if (existing === undefined || entry.count > existing) {
      byWord.set(entry.word, entry.count);
    }
  }
  for (const [word, count] of ADD) {
    if (!byWord.has(word)) {
      byWord.set(word, count);
    }
  }

  // Derived from the data rather than written down, so it cannot drift away
  // from the corpus if the pinned source is ever changed.
  let minimumCount = Infinity;
  for (const count of byWord.values()) {
    if (count < minimumCount) {
      minimumCount = count;
    }
  }
  const supplementAdded = [];
  const supplementSkipped = [];
  for (const word of SUPPLEMENT) {
    if (byWord.has(word)) {
      // Upstream already knows it; never overwrite a measured count with ours.
      supplementSkipped.push(word);
      continue;
    }
    byWord.set(word, minimumCount);
    supplementAdded.push(word);
  }

  let totalCount = 0;
  for (const count of byWord.values()) {
    totalCount += count;
  }
  if (totalCount <= 0) {
    throw new Error("Total count collapsed to zero; refusing to emit an asset.");
  }

  // Plain code-unit sort. See the header note: localeCompare would break the
  // binary search on device.
  const words = [...byWord.keys()].sort();

  let maxWordLength = 0;
  const lines = [];
  for (const word of words) {
    maxWordLength = Math.max(maxWordLength, word.length);
    const logProbability = Math.log(byWord.get(word) / totalCount);
    lines.push(`${word}\t${Math.round(logProbability * LOG_SCALE)}`);
  }

  const header = [
    "#v1",
    `#words=${words.length}`,
    `#logscale=${LOG_SCALE}`,
    `#maxword=${maxWordLength}`,
  ];
  return {
    text: header.concat(lines).join("\n") + "\n",
    wordCount: words.length,
    maxWordLength,
    totalCount,
    minimumCount,
    supplementAdded,
    supplementSkipped,
  };
}

function assertSortedForJava(text) {
  // Cheap insurance against a future edit reintroducing a locale-aware sort:
  // re-read what we are about to write and confirm strict ascending order.
  const rows = text.split("\n").filter((l) => l && !l.startsWith("#"));
  for (let i = 1; i < rows.length; i++) {
    const previous = rows[i - 1].split("\t")[0];
    const current = rows[i].split("\t")[0];
    if (!(previous < current)) {
      throw new Error(
        `Asset is not strictly ascending at line ${i}: ` +
          `${JSON.stringify(previous)} then ${JSON.stringify(current)}`
      );
    }
  }
  return rows.length;
}

const raw = fs.readFileSync(SOURCE, "utf8");
const { entries, rejected } = parseSource(raw);
const {
  text,
  wordCount,
  maxWordLength,
  totalCount,
  minimumCount,
  supplementAdded,
  supplementSkipped,
} = build(entries);
const verifiedRows = assertSortedForJava(text);

fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
fs.writeFileSync(OUTPUT, text, "utf8");

const sourceBytes = fs.statSync(SOURCE).size;
const outputBytes = fs.statSync(OUTPUT).size;
console.log(`source      : ${SOURCE} (${sourceBytes.toLocaleString()} bytes)`);
console.log(`parsed      : ${entries.length.toLocaleString()}`);
// Never let a filter drop rows silently; a shrinking lexicon should be visible.
console.log(`rejected    : ${rejected.length} ${rejected.length ? JSON.stringify(rejected) : ""}`);
console.log(`added       : ${ADD.size} ${ADD.size ? JSON.stringify([...ADD.keys()]) : ""}`);
console.log(`supplement  : ${supplementAdded.length} at the corpus minimum count ${minimumCount.toLocaleString()}`);
if (supplementSkipped.length) {
  // Upstream gaining one of these later should be visible, not silent.
  console.log(`  already upstream, left at their measured counts: ${JSON.stringify(supplementSkipped)}`);
}
console.log(`words       : ${wordCount.toLocaleString()} (sort-verified ${verifiedRows.toLocaleString()})`);
console.log(`max word len: ${maxWordLength}`);
console.log(`total count : ${totalCount.toLocaleString()}`);
console.log(`asset       : ${OUTPUT} (${outputBytes.toLocaleString()} bytes)`);

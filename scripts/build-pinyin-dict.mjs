/**
 * Converts the pinned Rime pinyin_simp dictionary into the compact asset the
 * keyboard ships. Run from the repo root:
 *
 *   node scripts/build-pinyin-dict.mjs
 *
 * Source: https://github.com/rime/rime-pinyin-simp @ 0c6861ef (Apache-2.0).
 * The upstream YAML lives in third_party/rime-pinyin-simp/ along with its
 * LICENSE and AUTHORS, which the Apache-2.0 terms require us to retain.
 *
 * Output format (UTF-8, LF, '#' header lines first, then one line per key
 * sorted ascending so Java can binary-search it):
 *
 *   #v1
 *   #maxkey=<int>
 *   #totalfreq=<int>
 *   #syllables=<space separated>
 *   <key>\t<word>:<freq>,<word>:<freq>,...
 *
 * `totalfreq` is the sum of every entry's weight. The composer needs it to turn
 * raw counts into unigram probabilities; without that normaliser, scoring
 * degenerates into "more segments is always better".
 *
 * `key` is the entry's pinyin with syllable spaces removed, which is exactly
 * what the user types. Words within a key are ordered by descending frequency.
 */

import fs from "node:fs";
import path from "node:path";

const SOURCE = path.join("third_party", "rime-pinyin-simp", "pinyin_simp.dict.yaml");
const OUTPUT = path.join("app", "src", "main", "assets", "pinyin_dict.txt");

function parseSource(raw) {
  // The YAML front matter is terminated by a line containing only "...".
  const parts = raw.split(/^\.\.\.\s*$/m);
  if (parts.length < 2) {
    throw new Error("Could not find the '...' YAML terminator in the source dictionary.");
  }
  const lines = parts[1].split(/\r?\n/);
  const entries = [];
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) {
      continue;
    }
    const fields = line.split("\t");
    if (fields.length < 3) {
      throw new Error(`Unexpected row shape: ${JSON.stringify(line)}`);
    }
    const [word, pinyin, freq] = fields;
    const syllables = pinyin.trim().split(/\s+/);
    if (!syllables.every((s) => /^[a-z]+$/.test(s))) {
      throw new Error(`Non a-z syllable in: ${JSON.stringify(line)}`);
    }
    if (!/^[0-9]+$/.test(freq.trim())) {
      throw new Error(`Non-integer frequency in: ${JSON.stringify(line)}`);
    }
    if (word.includes(":") || word.includes(",") || word.includes("\t")) {
      throw new Error(`Word contains a delimiter character: ${JSON.stringify(line)}`);
    }
    entries.push({
      word,
      key: syllables.join(""),
      syllables,
      freq: Number.parseInt(freq, 10),
    });
  }
  return entries;
}

function build(entries) {
  const syllables = new Set();
  const byKey = new Map();
  let maxKeyLength = 0;

  for (const entry of entries) {
    entry.syllables.forEach((s) => syllables.add(s));
    maxKeyLength = Math.max(maxKeyLength, entry.key.length);
    let bucket = byKey.get(entry.key);
    if (!bucket) {
      bucket = new Map();
      byKey.set(entry.key, bucket);
    }
    // Same word under the same key can appear more than once upstream; keep the
    // strongest frequency rather than emitting a duplicate candidate.
    const existing = bucket.get(entry.word);
    if (existing === undefined || entry.freq > existing) {
      bucket.set(entry.word, entry.freq);
    }
  }

  // Sum the de-duplicated weights, so the normaliser matches what actually ships.
  let totalFrequency = 0;
  for (const bucket of byKey.values()) {
    for (const freq of bucket.values()) {
      totalFrequency += freq;
    }
  }

  const keys = [...byKey.keys()].sort();
  const lines = [
    "#v1",
    `#maxkey=${maxKeyLength}`,
    `#totalfreq=${totalFrequency}`,
    `#syllables=${[...syllables].sort().join(" ")}`,
  ];
  for (const key of keys) {
    const words = [...byKey.get(key).entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
    lines.push(`${key}\t${words.map(([w, f]) => `${w}:${f}`).join(",")}`);
  }
  return {
    text: lines.join("\n") + "\n",
    keyCount: keys.length,
    syllableCount: syllables.size,
    maxKeyLength,
    totalFrequency,
  };
}

const raw = fs.readFileSync(SOURCE, "utf8");
const entries = parseSource(raw);
const { text, keyCount, syllableCount, maxKeyLength, totalFrequency } = build(entries);

fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
fs.writeFileSync(OUTPUT, text, "utf8");

const sourceBytes = fs.statSync(SOURCE).size;
const outputBytes = fs.statSync(OUTPUT).size;
console.log(`source      : ${SOURCE} (${sourceBytes.toLocaleString()} bytes)`);
console.log(`entries     : ${entries.length.toLocaleString()}`);
console.log(`distinct key: ${keyCount.toLocaleString()}`);
console.log(`syllables   : ${syllableCount}`);
console.log(`max key len : ${maxKeyLength}`);
console.log(`total freq  : ${totalFrequency.toLocaleString()} (ln = ${Math.log(totalFrequency).toFixed(3)})`);
console.log(`asset       : ${OUTPUT} (${outputBytes.toLocaleString()} bytes)`);

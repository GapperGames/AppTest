// Tests the shipped chart core. Run: node tools/test.mjs
// Evaluates chart.js directly — no copy of the logic lives here.

import { readFileSync } from "node:fs";
import { strict as assert } from "node:assert";

const g = {};
new Function("globalThis", readFileSync("chart.js", "utf8")).call(g, g);
const CH = g.CH;

let pass = 0;
const cases = [];
function t(name, fn) { cases.push([name, fn]); }

/* ---------- transposition ---------- */

t("shifts a plain triad", () => {
  assert.equal(CH.shiftChord("G", 2, false), "A");
  assert.equal(CH.shiftChord("A", 3, false), "C");
});

t("keeps the quality suffix intact", () => {
  assert.equal(CH.shiftChord("Cadd9", 2, false), "Dadd9");
  assert.equal(CH.shiftChord("F#m7b5", 1, false), "Gm7b5");
  assert.equal(CH.shiftChord("Em7", 5, false), "Am7");
});

t("shifts both halves of a slash chord", () => {
  assert.equal(CH.shiftChord("D/F#", 1, false), "D#/G");
  assert.equal(CH.shiftChord("G/B", 2, false), "A/C#");
});

t("spells flats where the key wants flats", () => {
  assert.equal(CH.shiftChord("G", 3, true), "Bb");
  assert.equal(CH.shiftChord("G", 3, false), "A#");
});

t("wraps around the octave in both directions", () => {
  assert.equal(CH.shiftChord("B", 1, false), "C");
  assert.equal(CH.shiftChord("C", -1, false), "B");
  assert.equal(CH.shiftChord("C", -13, false), "B");
});

t("zero transpose is a no-op", () => {
  assert.equal(CH.shiftChord("F#m7/A#", 0, false), "F#m7/A#");
});

t("leaves things it doesn't understand alone", () => {
  assert.equal(CH.shiftChord("N.C.", 2, false), "N.C.");
});

t("reads the root out of a tonality name", () => {
  assert.equal(CH.keyRoot("Am"), "A");
  assert.equal(CH.keyRoot("Bb"), "Bb");
  assert.equal(CH.keyRoot("F#m"), "F#");
  assert.equal(CH.keyRoot(""), "");
  assert.equal(CH.keyRoot(null), "");
});

/* ---------- parsing ---------- */

t("finds chord columns after stripping markers", () => {
  const [line] = CH.parse("[ch]G[/ch]      [ch]D/F#[/ch]");
  assert.equal(line.t, "chords");
  assert.deepEqual(line.items, [
    { col: 0, chord: "G" },
    { col: 7, chord: "D/F#" },
  ]);
});

t("keeps lyric lines exactly as they came", () => {
  const [line] = CH.parse("  a lyric line  ");
  assert.equal(line.t, "lyric");
  assert.equal(line.text, "  a lyric line  ");
});

t("recognises section headers", () => {
  assert.deepEqual(CH.parse("[Chorus]")[0], { t: "sect", text: "Chorus" });
  assert.deepEqual(CH.parse("  [Verse 2]  ")[0], { t: "sect", text: "Verse 2" });
});

t("a header line that also holds chords is not a header", () => {
  assert.equal(CH.parse("[Intro] [ch]G[/ch]")[0].t, "chords");
});

t("drops [tab] wrappers but keeps their contents", () => {
  const out = CH.parse("[tab][ch]G[/ch]   x[/tab]");
  assert.equal(out[0].t, "chords");
  assert.deepEqual(out[0].items, [{ col: 0, chord: "G" }]);
});

t("blank lines become gaps", () => {
  assert.equal(CH.parse("")[0].t, "gap");
  assert.equal(CH.parse("   ")[0].t, "gap");
});

t("normalises CRLF", () => {
  assert.equal(CH.parse("a\r\nb").length, 2);
});

t("survives an unclosed [ch] marker", () => {
  assert.doesNotThrow(() => CH.parse("[ch]G"));
});

t("handles empty and null input", () => {
  assert.deepEqual(CH.parse(null), [{ t: "gap" }]);
});

/* ---------- alignment ---------- */

t("leaves columns alone when nothing widens", () => {
  const items = [{ col: 0, chord: "G" }, { col: 8, chord: "C" }];
  assert.deepEqual(CH.layout(items, 0, false),
    [{ col: 0, name: "G" }, { col: 8, name: "C" }]);
});

t("pushes a chord that would collide after transposing", () => {
  // G and C sit one apart; G->G# is two chars, so C must move right.
  const items = [{ col: 0, chord: "G" }, { col: 1, chord: "C" }];
  const out = CH.layout(items, 1, false);
  assert.equal(out[0].name, "G#");
  assert.equal(out[0].col, 0);
  assert.equal(out[1].col, 3, "second chord should clear the first");
});

t("never lets two chords overlap, at any transposition", () => {
  const items = [
    { col: 0, chord: "G" }, { col: 2, chord: "D/F#" },
    { col: 8, chord: "Em7" }, { col: 12, chord: "Cadd9" },
  ];
  for (let s = -11; s <= 11; s++) {
    const out = CH.layout(items, s, s % 2 === 0);
    for (let i = 1; i < out.length; i++) {
      const prevEnd = out[i - 1].col + out[i - 1].name.length;
      assert.ok(out[i].col > prevEnd - 1,
        `overlap at semis=${s}: ${JSON.stringify(out)}`);
    }
  }
});

t("preserves chord order", () => {
  const items = [{ col: 0, chord: "B" }, { col: 1, chord: "F#" }, { col: 2, chord: "C" }];
  const out = CH.layout(items, 1, false);
  assert.deepEqual(out.map((o) => o.col), [...out.map((o) => o.col)].sort((a, b) => a - b));
});

/* ---------- round trip ---------- */

t("transposing up then down returns the original", () => {
  for (const c of ["G", "Am7", "D/F#", "Cadd9", "F#m7b5"]) {
    assert.equal(CH.shiftChord(CH.shiftChord(c, 5, false), -5, false), c);
  }
});

/* ---------- run ---------- */

let failed = 0;
for (const [name, fn] of cases) {
  try { fn(); pass++; console.log("  ok   " + name); }
  catch (e) { failed++; console.log("  FAIL " + name + "\n       " + e.message); }
}
console.log("\n" + pass + " passed, " + failed + " failed");
process.exit(failed ? 1 : 0);

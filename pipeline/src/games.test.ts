import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { splitPgn } from "./games.js";

test("splitPgn separates every game in a multi-game file", async () => {
  const path = fileURLToPath(new URL("../../data/sample-games.pgn", import.meta.url));
  const text = await readFile(path, "utf8");
  const games = splitPgn(text);
  assert.equal(games.length, 18);
  for (const g of games) {
    assert.ok(g.startsWith("[Event "), "each chunk starts with headers");
    assert.ok(g.includes("1. e4") || g.includes("1. d4"), "each chunk has movetext");
  }
});

test("splitPgn tolerates a single game and trailing whitespace", () => {
  const one = `[Event "x"]\n[White "a"]\n[Black "b"]\n\n1. e4 e5 2. Nf3 *\n\n`;
  const games = splitPgn(one);
  assert.equal(games.length, 1);
  assert.ok(games[0].includes("1. e4 e5"));
});

test("splitPgn returns empty for blank input", () => {
  assert.deepEqual(splitPgn("   \n\n "), []);
});

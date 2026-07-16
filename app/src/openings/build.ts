import { Chess } from "chess.js";
import type { Color, OpeningsData, TreeNode } from "./types";
import type { RawGame } from "../data/chesscom";

const START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

/**
 * Build the weighted openings tree from raw games, entirely in the browser.
 *
 * This produces the move-frequency structure (which drives opponent replies and
 * the tree visualisation). It does NOT pre-run Stockfish on every position — the
 * trainer judges your moves live with the in-app engine instead, which keeps a
 * "rebuild from my games" tap fast on a phone. `eval`/`isBest`/`lossCp` are left
 * null; the app falls back to live judging when they're absent.
 */
export interface BuildOptions {
  user: string;
  maxPly?: number;
  source?: string;
}

interface ParsedGame {
  color: Color;
  moves: { san: string; uci: string; after: string }[];
}

function parseGame(game: RawGame, handle: string): ParsedGame | null {
  let color: Color | null = null;
  if (game.white.toLowerCase() === handle) color = "w";
  else if (game.black.toLowerCase() === handle) color = "b";
  if (!color) return null;

  const chess = new Chess();
  try {
    chess.loadPgn(game.pgn);
  } catch {
    return null;
  }
  const verbose = chess.history({ verbose: true }) as any[];
  if (verbose.length === 0) return null;
  return {
    color,
    moves: verbose.map((m) => ({ san: m.san, uci: m.lan, after: m.after })),
  };
}

function makeNode(fen: string, ply: number, userColor: Color): TreeNode {
  const turn: Color = ply % 2 === 0 ? "w" : "b";
  return {
    fen,
    ply,
    turn,
    userToMove: turn === userColor,
    gamesReached: 0,
    eval: null,
    children: [],
  };
}

function addGame(root: TreeNode, game: ParsedGame, maxPly: number, userColor: Color): void {
  root.gamesReached++;
  let cur = root;
  const limit = Math.min(maxPly, game.moves.length);
  for (let k = 0; k < limit; k++) {
    const mv = game.moves[k];
    let edge = cur.children.find((e) => e.uci === mv.uci);
    if (!edge) {
      const child = makeNode(mv.after, k + 1, userColor);
      edge = { san: mv.san, uci: mv.uci, count: 0, lossCp: null, isBest: null, child };
      cur.children.push(edge);
    }
    edge.count++;
    edge.child.gamesReached++;
    cur = edge.child;
  }
}

function sortChildren(root: TreeNode): void {
  const stack = [root];
  while (stack.length) {
    const n = stack.pop()!;
    n.children.sort((a, b) => b.count - a.count || a.san.localeCompare(b.san));
    for (const e of n.children) stack.push(e.child);
  }
}

export function buildOpeningsFromGames(games: RawGame[], opts: BuildOptions): OpeningsData {
  const maxPly = opts.maxPly ?? 24;
  const handle = opts.user.trim().toLowerCase();
  const whiteRoot = makeNode(START_FEN, 0, "w");
  const blackRoot = makeNode(START_FEN, 0, "b");

  let used = 0;
  let skipped = 0;
  for (const raw of games) {
    const parsed = parseGame(raw, handle);
    if (!parsed) {
      skipped++;
      continue;
    }
    used++;
    addGame(parsed.color === "w" ? whiteRoot : blackRoot, parsed, maxPly, parsed.color);
  }

  sortChildren(whiteRoot);
  sortChildren(blackRoot);

  return {
    meta: {
      user: opts.user,
      generatedAt: new Date().toISOString(),
      source: opts.source ?? `chess.com:${opts.user}`,
      sourceGameCount: used,
      skippedGameCount: skipped,
      maxPly,
      engineDepth: 12,
      multiPv: 3,
      correctThresholdCp: 30,
    },
    white: whiteRoot,
    black: blackRoot,
  };
}

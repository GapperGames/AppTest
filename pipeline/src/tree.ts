import { Chess } from "chess.js";
import type { Engine } from "./engine.js";
import type { RawGame } from "./games.js";
import type { Color, EngineEval, OpeningsData, PvLine, TreeNode } from "./types.js";

const START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

export interface BuildOptions {
  user: string;
  maxPly: number;
  depth: number;
  multiPv: number;
  correctThresholdCp: number;
  source: string;
  onProgress?: (done: number, total: number, label: string) => void;
}

interface ParsedGame {
  color: Color;
  moves: { san: string; uci: string; before: string; after: string }[];
}

/** Parse a raw game into the user's colour and its move list (verbose). */
function parseGame(game: RawGame, user: string): ParsedGame | null {
  const handle = user.toLowerCase();
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
  const moves = verbose.map((m) => ({
    san: m.san as string,
    uci: m.lan as string,
    before: m.before as string,
    after: m.after as string,
  }));
  return { color, moves };
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

/** Insert a game's moves into the trie for the user's colour. */
function addGameToTree(root: TreeNode, game: ParsedGame, maxPly: number, userColor: Color): void {
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

/** Convert a UCI first move of a PV into SAN using board context. */
function uciToSan(fen: string, uci: string): string {
  try {
    const chess = new Chess(fen);
    const move = chess.move({
      from: uci.slice(0, 2),
      to: uci.slice(2, 4),
      promotion: uci.length > 4 ? uci[4] : undefined,
    });
    return move.san;
  } catch {
    return uci;
  }
}

/** Collapse a PV score to a single comparable centipawn number. */
function scoreToCp(line: PvLine): number {
  if (line.mate !== null) {
    return line.mate > 0 ? 100000 - line.mate * 100 : -100000 - line.mate * 100;
  }
  return line.cp ?? 0;
}

/** Depth-first collection of every node in a trie. */
function collectNodes(root: TreeNode): TreeNode[] {
  const out: TreeNode[] = [];
  const stack = [root];
  while (stack.length) {
    const n = stack.pop()!;
    out.push(n);
    for (const e of n.children) stack.push(e.child);
  }
  return out;
}

/** Annotate every internal node with an engine eval, and every edge with loss. */
async function annotate(
  roots: TreeNode[],
  engine: Engine,
  opts: BuildOptions,
): Promise<void> {
  const cache = new Map<string, EngineEval>();

  const analyse = async (fen: string): Promise<EngineEval> => {
    const hit = cache.get(fen);
    if (hit) return hit;
    const raw = await engine.analyse(fen, opts.depth, opts.multiPv);
    // Fill SAN for each line using the analysed position.
    const lines: PvLine[] = raw.lines.map((l) => ({ ...l, san: uciToSan(fen, l.uci) }));
    const evalResult: EngineEval = { depth: raw.depth, lines };
    cache.set(fen, evalResult);
    return evalResult;
  };

  const allNodes = roots.flatMap(collectNodes);
  const internal = allNodes.filter((n) => n.children.length > 0);
  let done = 0;

  for (const node of internal) {
    node.eval = await analyse(node.fen);
    const bestCp = node.eval.lines.length ? scoreToCp(node.eval.lines[0]) : 0;

    for (const edge of node.children) {
      // Eval of the position AFTER the move, from the mover's perspective.
      let moveCpMover: number | null = null;
      const childEval = edge.child.children.length
        ? await analyse(edge.child.fen) // internal child: reuse/compute its eval
        : await analyse(edge.child.fen); // leaf: analyse once (cached by FEN)
      if (childEval.lines.length) {
        // childEval is from the opponent's perspective; negate for the mover.
        moveCpMover = -scoreToCp(childEval.lines[0]);
      }
      if (moveCpMover !== null) {
        edge.lossCp = Math.max(0, bestCp - moveCpMover);
        edge.isBest = edge.lossCp <= opts.correctThresholdCp;
      }
    }

    done++;
    opts.onProgress?.(done, internal.length, `analysed ply ${node.ply}`);
  }
}

/** Sort every node's children by descending game count (ties: alphabetical). */
function sortChildren(root: TreeNode): void {
  for (const node of collectNodes(root)) {
    node.children.sort((a, b) => b.count - a.count || a.san.localeCompare(b.san));
  }
}

export async function buildOpenings(
  rawGames: RawGame[],
  engine: Engine,
  opts: BuildOptions,
): Promise<OpeningsData> {
  const whiteRoot = makeNode(START_FEN, 0, "w");
  const blackRoot = makeNode(START_FEN, 0, "b");

  let used = 0;
  let skipped = 0;
  for (const raw of rawGames) {
    const parsed = parseGame(raw, opts.user);
    if (!parsed) {
      skipped++;
      continue;
    }
    used++;
    const root = parsed.color === "w" ? whiteRoot : blackRoot;
    addGameToTree(root, parsed, opts.maxPly, parsed.color);
  }

  await annotate([whiteRoot, blackRoot], engine, opts);
  sortChildren(whiteRoot);
  sortChildren(blackRoot);

  return {
    meta: {
      user: opts.user,
      generatedAt: new Date().toISOString(),
      source: opts.source,
      sourceGameCount: used,
      skippedGameCount: skipped,
      maxPly: opts.maxPly,
      engineDepth: opts.depth,
      multiPv: opts.multiPv,
      correctThresholdCp: opts.correctThresholdCp,
    },
    white: whiteRoot,
    black: blackRoot,
  };
}

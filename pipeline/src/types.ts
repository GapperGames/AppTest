/**
 * Shared data contract between the pipeline (producer) and the trainer app
 * (consumer). The app ships its own copy of these types in
 * `app/src/openings/types.ts` — keep the two in sync when editing.
 */

export type Color = "w" | "b";

/** A single engine principal variation for a position. */
export interface PvLine {
  /** Best move of this line in UCI (long algebraic, e.g. "g1f3"). */
  uci: string;
  /** Same move in SAN (e.g. "Nf3"), for display. */
  san: string;
  /** Centipawn score from the side-to-move's perspective (null if mate). */
  cp: number | null;
  /** Moves-to-mate from side-to-move's perspective (positive = we mate). */
  mate: number | null;
}

/** Stockfish assessment of a position (from the side-to-move's perspective). */
export interface EngineEval {
  depth: number;
  /** Ranked best lines (MultiPV), best first. */
  lines: PvLine[];
}

/** An edge = a move actually played by the user (or their opponents) from a node. */
export interface MoveEdge {
  san: string;
  uci: string;
  /** How many of the user's games took this move from the parent position. */
  count: number;
  /**
   * Centipawn loss versus the engine's best move, from the mover's
   * perspective (>= 0). Only meaningful on user-to-move edges. null when the
   * position was not analysed or the move is off the analysed set.
   */
  lossCp: number | null;
  /** True when this move is within the "correct" threshold of the best move. */
  isBest: boolean | null;
  child: TreeNode;
}

/** A position in the openings trie, reached by a specific sequence of moves. */
export interface TreeNode {
  fen: string;
  /** Half-moves from the start position (0 = start). */
  ply: number;
  turn: Color;
  /** Whether it is the trained user's turn to move in this position. */
  userToMove: boolean;
  /** How many of the user's games passed through this exact position. */
  gamesReached: number;
  /** Engine assessment of this position (best moves, eval). null if unanalysed. */
  eval: EngineEval | null;
  /** Moves played from here in the user's games, most common first. */
  children: MoveEdge[];
}

export interface OpeningsMeta {
  user: string;
  generatedAt: string;
  source: string;
  sourceGameCount: number;
  /** Games skipped because the user was not a participant / unparseable. */
  skippedGameCount: number;
  maxPly: number;
  engineDepth: number;
  multiPv: number;
  /** A move within this many centipawns of best counts as "correct". */
  correctThresholdCp: number;
}

/** The full artifact emitted to `openings.json`. */
export interface OpeningsData {
  meta: OpeningsMeta;
  /** Trie for games where the user played White (user moves on even plies). */
  white: TreeNode;
  /** Trie for games where the user played Black (user moves on odd plies). */
  black: TreeNode;
}

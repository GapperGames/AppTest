/**
 * Data contract for `openings.json`. This mirrors `pipeline/src/types.ts`
 * (the producer). Keep the two in sync when editing the schema.
 */

export type Color = "w" | "b";

export interface PvLine {
  uci: string;
  san: string;
  cp: number | null;
  mate: number | null;
}

export interface EngineEval {
  depth: number;
  lines: PvLine[];
}

export interface MoveEdge {
  san: string;
  uci: string;
  count: number;
  lossCp: number | null;
  isBest: boolean | null;
  child: TreeNode;
}

export interface TreeNode {
  fen: string;
  ply: number;
  turn: Color;
  userToMove: boolean;
  gamesReached: number;
  eval: EngineEval | null;
  children: MoveEdge[];
}

export interface OpeningsMeta {
  user: string;
  generatedAt: string;
  source: string;
  sourceGameCount: number;
  skippedGameCount: number;
  maxPly: number;
  engineDepth: number;
  multiPv: number;
  correctThresholdCp: number;
}

export interface OpeningsData {
  meta: OpeningsMeta;
  white: TreeNode;
  black: TreeNode;
}

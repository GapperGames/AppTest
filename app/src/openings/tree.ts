import type { MoveEdge, OpeningsData, TreeNode } from "./types";

/** Find the edge leaving `node` that plays the given UCI move, if any. */
export function findEdge(node: TreeNode, uci: string): MoveEdge | undefined {
  return node.children.find((e) => e.uci === uci);
}

/**
 * Choose the opponent's reply at `node` for a given level.
 *
 * Level N means the opponent may pick any of the N most common replies seen in
 * the user's games (inclusive of previous levels). Among that candidate set the
 * move is chosen weighted by how often it actually occurred, so the most common
 * line still dominates but rarer lines appear as the level rises.
 *
 * Returns `null` when there is no reply data (an off-tree position), in which
 * case the caller should fall back to the engine's best move.
 */
export function pickOpponentMove(
  node: TreeNode,
  level: number,
  rng: () => number = Math.random,
): MoveEdge | null {
  if (node.children.length === 0) return null;
  // children are stored most-common-first by the pipeline.
  const candidates = node.children.slice(0, Math.max(1, level));
  const total = candidates.reduce((s, e) => s + e.count, 0);
  if (total <= 0) return candidates[0];
  let r = rng() * total;
  for (const edge of candidates) {
    r -= edge.count;
    if (r < 0) return edge;
  }
  return candidates[candidates.length - 1];
}

/** Root node for the side the user is training. */
export function rootFor(data: OpeningsData, side: "w" | "b"): TreeNode {
  return side === "w" ? data.white : data.black;
}

/**
 * A sensible number of levels to expose, based on the widest opponent branching
 * actually present in the data (clamped to a friendly range).
 */
export function suggestedLevelCount(data: OpeningsData): number {
  let max = 1;
  const visit = (node: TreeNode) => {
    if (!node.userToMove && node.children.length > max) max = node.children.length;
    for (const e of node.children) visit(e.child);
  };
  visit(data.white);
  visit(data.black);
  return Math.min(6, Math.max(3, max));
}

/** Count games behind each side, for menu display. */
export function sideGameCounts(data: OpeningsData): { white: number; black: number } {
  return { white: data.white.gamesReached, black: data.black.gamesReached };
}

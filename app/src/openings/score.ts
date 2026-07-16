import type { PvLine } from "./types";

const MATE_BASE = 100_000;

/** Collapse a PV score to a single comparable centipawn number (side-to-move). */
export function scoreToCp(line: Pick<PvLine, "cp" | "mate">): number {
  if (line.mate !== null && line.mate !== undefined) {
    return line.mate > 0 ? MATE_BASE - line.mate * 100 : -MATE_BASE - line.mate * 100;
  }
  return line.cp ?? 0;
}

/** Human eval string from the perspective of the side to move. */
export function formatScore(line: Pick<PvLine, "cp" | "mate">): string {
  if (line.mate !== null && line.mate !== undefined) {
    return line.mate > 0 ? `#${line.mate}` : `#-${Math.abs(line.mate)}`;
  }
  const pawns = (line.cp ?? 0) / 100;
  const sign = pawns > 0 ? "+" : pawns < 0 ? "" : "";
  return `${sign}${pawns.toFixed(2)}`;
}

/** Convert a centipawn advantage into a 0..1 white-winning-probability-ish bar. */
export function cpToBar(cpWhite: number): number {
  // Logistic squashing, matching lichess-style eval bars.
  return 1 / (1 + Math.pow(10, -cpWhite / 400));
}

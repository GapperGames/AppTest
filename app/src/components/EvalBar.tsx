import { cpToBar, formatScore } from "../openings/score";

interface EvalBarProps {
  /** White-relative centipawns, or null when unknown. */
  cpWhite: number | null;
  orientation: "white" | "black";
}

/** A slim vertical eval bar, white advantage growing from the player's side. */
export function EvalBar({ cpWhite, orientation }: EvalBarProps) {
  const cp = cpWhite ?? 0;
  const whiteShare = cpToBar(cp); // 0..1, white's portion
  // The bar fills from the bottom with the side the board is oriented to.
  const bottomIsWhite = orientation === "white";
  const bottomShare = bottomIsWhite ? whiteShare : 1 - whiteShare;
  const label = cpWhite === null ? "" : formatScore({ cp, mate: null });

  return (
    <div className="evalbar" title={cpWhite === null ? "eval unavailable" : `${label} (white)`}>
      <div className="evalbar-fill" style={{ height: `${Math.round(bottomShare * 100)}%` }} />
      <span className="evalbar-label">{label}</span>
    </div>
  );
}

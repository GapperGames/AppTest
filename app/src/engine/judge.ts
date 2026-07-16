import type { StockfishEngine } from "./stockfish";
import { scoreToCp } from "../openings/score";

export interface JudgeParams {
  /** Position with the user to move (FEN). */
  fen: string;
  /** The user's move in UCI. */
  userUci: string;
  /** Position after the user's move (FEN), used when the move isn't in MultiPV. */
  fenAfter: string;
  depth: number;
  multiPv: number;
  thresholdCp: number;
}

export interface JudgeResult {
  correct: boolean;
  bestUci: string;
  /** Best-move eval, from the user's perspective (centipawns). */
  bestCp: number;
  /** The user's move eval, from the user's perspective (centipawns). */
  userCp: number;
  /** How many centipawns worse than best (>= 0). */
  lossCp: number;
}

/**
 * Judge the user's move with Stockfish. A move counts as "correct" when it is
 * within `thresholdCp` of the engine's best move — this is deliberately not
 * strict single-best, so genuinely equivalent moves all pass.
 */
export async function judgeUserMove(
  engine: StockfishEngine,
  { fen, userUci, fenAfter, depth, multiPv, thresholdCp }: JudgeParams,
): Promise<JudgeResult> {
  const evalBefore = await engine.analyse(fen, depth, multiPv);
  if (evalBefore.lines.length === 0) {
    // No engine data — be lenient rather than punish.
    return { correct: true, bestUci: userUci, bestCp: 0, userCp: 0, lossCp: 0 };
  }
  const best = evalBefore.lines[0];
  const bestCp = scoreToCp(best);

  const matched = evalBefore.lines.find((l) => l.uci === userUci);
  let userCp: number;
  if (matched) {
    userCp = scoreToCp(matched);
  } else {
    const evalAfter = await engine.analyse(fenAfter, depth, 1);
    // evalAfter is from the opponent's perspective; negate for the user.
    userCp = evalAfter.lines.length ? -scoreToCp(evalAfter.lines[0]) : bestCp - (thresholdCp + 1);
  }

  const lossCp = Math.max(0, bestCp - userCp);
  return {
    correct: lossCp <= thresholdCp,
    bestUci: best.uci,
    bestCp,
    userCp,
    lossCp,
  };
}

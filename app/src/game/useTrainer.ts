import { useCallback, useRef, useState } from "react";
import { Chess } from "chess.js";
import type { Color, OpeningsData, TreeNode } from "../openings/types";
import { findEdge, pickOpponentMove, rootFor } from "../openings/tree";
import { scoreToCp } from "../openings/score";
import type { StockfishEngine } from "../engine/stockfish";
import { judgeUserMove } from "../engine/judge";

export type TrainerStatus =
  | "idle"
  | "opponent"
  | "user-turn"
  | "judging"
  | "reveal"
  | "complete";

export type ResultTint = "correct" | "wrong" | null;

export interface Feedback {
  correct: boolean;
  playedSan: string;
  bestSan: string;
  bestUci: string;
  lossCp: number;
}

export interface HistoryMove {
  san: string;
  by: "user" | "opp";
  ply: number;
  correct?: boolean;
}

export interface RunResult {
  level: number;
  side: Color;
  correct: number;
  total: number;
  accuracy: number;
}

export interface TrainerView {
  status: TrainerStatus;
  side: Color;
  level: number;
  fen: string;
  orientation: "white" | "black";
  lastMove: { from: string; to: string } | null;
  hintMove: { from: string; to: string } | null;
  /** Square currently being checked by the engine (shows a spinner). */
  checkingSquare: string | null;
  /** Colour to tint the last move square: green/red/none. */
  lastResult: ResultTint;
  history: HistoryMove[];
  ply: number;
  maxPly: number;
  decisions: boolean[];
  feedback: Feedback | null;
  offTree: boolean;
  evalWhiteCp: number | null;
  message: string;
  busy: boolean;
}

const START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
const REVEAL_FLASH_MS = 750;
const CORRECT_PAUSE_MS = 380;

const delay = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

function idleView(): TrainerView {
  return {
    status: "idle",
    side: "w",
    level: 1,
    fen: START_FEN,
    orientation: "white",
    lastMove: null,
    hintMove: null,
    checkingSquare: null,
    lastResult: null,
    history: [],
    ply: 0,
    maxPly: 24,
    decisions: [],
    feedback: null,
    offTree: false,
    evalWhiteCp: 0,
    message: "",
    busy: false,
  };
}

function uciParts(uci: string): { from: string; to: string; promotion?: string } {
  return {
    from: uci.slice(0, 2),
    to: uci.slice(2, 4),
    promotion: uci.length > 4 ? uci[4] : undefined,
  };
}

function sanFor(fen: string, uci: string): string {
  try {
    return new Chess(fen).move(uciParts(uci)).san;
  } catch {
    return uci;
  }
}

function orientWhite(node: TreeNode | null): number | null {
  if (!node?.eval?.lines.length) return null;
  const cp = scoreToCp(node.eval.lines[0]);
  return node.turn === "w" ? cp : -cp;
}

export function useTrainer(engine: StockfishEngine | null, data: OpeningsData | null) {
  const [view, setView] = useState<TrainerView>(idleView);

  const chessRef = useRef(new Chess());
  const nodeRef = useRef<TreeNode | null>(null);
  const sessionRef = useRef(0);
  const awaitingCorrectionRef = useRef(false);
  const stateRef = useRef<TrainerView>(idleView());
  const onRunCompleteRef = useRef<((r: RunResult) => void) | null>(null);

  const depth = data?.meta.engineDepth ?? 12;
  const multiPv = data?.meta.multiPv ?? 3;
  const threshold = data?.meta.correctThresholdCp ?? 30;
  const maxPly = data?.meta.maxPly ?? 24;

  const commit = useCallback((patch: Partial<TrainerView>) => {
    stateRef.current = { ...stateRef.current, ...patch };
    setView(stateRef.current);
  }, []);

  const syncBoard = useCallback(
    (extra: Partial<TrainerView> = {}) => {
      const chess = chessRef.current;
      const hist = chess.history({ verbose: true }) as any[];
      const last = hist.length ? hist[hist.length - 1] : null;
      commit({
        fen: chess.fen(),
        ply: hist.length,
        lastMove: last ? { from: last.from, to: last.to } : null,
        evalWhiteCp: orientWhite(nodeRef.current) ?? stateRef.current.evalWhiteCp,
        offTree: nodeRef.current === null,
        ...extra,
      });
    },
    [commit],
  );

  const finishRun = useCallback(() => {
    const decisions = stateRef.current.decisions;
    const total = decisions.length;
    const correct = decisions.filter(Boolean).length;
    const accuracy = total > 0 ? correct / total : 1;
    commit({
      status: "complete",
      busy: false,
      hintMove: null,
      checkingSquare: null,
      lastResult: null,
      message:
        total > 0
          ? `Opening complete — ${correct}/${total} first-try best moves (${Math.round(
              accuracy * 100,
            )}%).`
          : "Opening complete.",
    });
    onRunCompleteRef.current?.({
      level: stateRef.current.level,
      side: stateRef.current.side,
      correct,
      total,
      accuracy,
    });
  }, [commit]);

  const opponentTurn = useCallback(
    async (session: number) => {
      if (!engine) return;
      const chess = chessRef.current;
      if (chess.history().length >= maxPly) {
        finishRun();
        return;
      }
      commit({
        status: "opponent",
        busy: true,
        message: "Opponent is replying…",
        hintMove: null,
        checkingSquare: null,
      });

      let uci: string | null = null;
      const node = nodeRef.current;
      const edge = node ? pickOpponentMove(node, stateRef.current.level) : null;
      if (edge) {
        uci = edge.uci;
        nodeRef.current = edge.child;
      } else {
        const ev = await engine.analyse(chess.fen(), depth, 1);
        if (session !== sessionRef.current) return;
        uci = ev.lines[0]?.uci ?? null;
        nodeRef.current = null;
      }
      if (!uci) {
        finishRun();
        return;
      }
      const move = chess.move(uciParts(uci));
      stateRef.current.history = [
        ...stateRef.current.history,
        { san: move.san, by: "opp", ply: chess.history().length },
      ];

      if (chess.history().length >= maxPly) {
        finishRun();
        return;
      }
      syncBoard({ status: "user-turn", busy: false, message: "Your move.", lastResult: null });
    },
    [engine, commit, syncBoard, finishRun, depth, maxPly],
  );

  const start = useCallback(
    (side: Color, level: number) => {
      if (!data) return;
      const session = ++sessionRef.current;
      awaitingCorrectionRef.current = false;
      chessRef.current = new Chess();
      nodeRef.current = rootFor(data, side);
      stateRef.current = {
        ...idleView(),
        side,
        level,
        maxPly,
        orientation: side === "w" ? "white" : "black",
        evalWhiteCp: orientWhite(nodeRef.current) ?? 0,
      };
      setView(stateRef.current);

      if (side === "b") {
        void opponentTurn(session);
      } else {
        syncBoard({ status: "user-turn", busy: false, message: "Your move." });
      }
    },
    [data, opponentTurn, syncBoard, maxPly],
  );

  const playUserMove = useCallback(
    (from: string, to: string, promotion?: string): boolean => {
      if (!engine || !data) return false;
      const status = stateRef.current.status;
      if (status !== "user-turn" && status !== "reveal") return false;

      const chess = chessRef.current;
      const fenBefore = chess.fen();
      let move: any = null;
      try {
        move = chess.move({ from, to, promotion });
      } catch {
        try {
          move = chess.move({ from, to, promotion: "q" });
        } catch {
          return false;
        }
      }
      if (!move) return false;

      const uci = move.from + move.to + (move.promotion ?? "");
      const fenAfter = chess.fen();
      const session = sessionRef.current;
      const node = nodeRef.current;
      const firstAttempt = !awaitingCorrectionRef.current;

      // Piece lands on the target square immediately; show a spinner there.
      commit({
        fen: fenAfter,
        status: "judging",
        busy: true,
        lastMove: { from: move.from, to: move.to },
        checkingSquare: move.to,
        lastResult: null,
        hintMove: null,
        feedback: null,
        message: "Checking…",
      });

      void (async () => {
        const edge = node ? findEdge(node, uci) : undefined;
        let correct: boolean;
        let bestUci: string;
        let lossCp: number;

        if (edge && edge.isBest !== null && node?.eval?.lines.length) {
          correct = edge.isBest;
          lossCp = edge.lossCp ?? 0;
          bestUci = node.eval.lines[0].uci;
        } else {
          const res = await judgeUserMove(engine, {
            fen: fenBefore,
            userUci: uci,
            fenAfter,
            depth,
            multiPv,
            thresholdCp: threshold,
          });
          if (session !== sessionRef.current) return;
          correct = res.correct;
          bestUci = res.bestUci;
          lossCp = res.lossCp;
        }

        const bestSan = sanFor(fenBefore, bestUci);
        const feedback: Feedback = {
          correct,
          playedSan: move.san,
          bestSan,
          bestUci,
          lossCp: Math.round(lossCp),
        };

        if (firstAttempt) {
          stateRef.current.decisions = [...stateRef.current.decisions, correct];
        }

        if (correct) {
          awaitingCorrectionRef.current = false;
          nodeRef.current = edge ? edge.child : null;
          stateRef.current.history = [
            ...stateRef.current.history,
            { san: move.san, by: "user", ply: chess.history().length, correct: firstAttempt },
          ];
          // Green flash on the played square, then let the opponent reply.
          commit({
            status: "judging",
            busy: true,
            checkingSquare: null,
            lastResult: "correct",
            feedback,
            message: firstAttempt ? "Best move!" : `Good — ${move.san}.`,
          });
          await delay(CORRECT_PAUSE_MS);
          if (session !== sessionRef.current) return;
          await opponentTurn(session);
        } else {
          // Red flash on the wrong square, then revert for a retry.
          commit({
            status: "reveal",
            busy: true,
            checkingSquare: null,
            lastResult: "wrong",
            feedback,
            hintMove: null,
            message: `Not best — you played ${move.san} (−${feedback.lossCp}cp).`,
          });
          await delay(REVEAL_FLASH_MS);
          if (session !== sessionRef.current) return;
          chess.undo();
          awaitingCorrectionRef.current = true;
          const hint = uciParts(bestUci);
          syncBoard({
            status: "reveal",
            busy: false,
            lastResult: null,
            checkingSquare: null,
            feedback,
            hintMove: { from: hint.from, to: hint.to },
            message: `Engine prefers ${bestSan}. Try a best move to continue.`,
          });
        }
      })();

      return true;
    },
    [engine, data, commit, syncBoard, opponentTurn, depth, multiPv, threshold],
  );

  const quit = useCallback(() => {
    sessionRef.current++;
    stateRef.current = idleView();
    setView(idleView());
  }, []);

  const setOnRunComplete = useCallback((fn: ((r: RunResult) => void) | null) => {
    onRunCompleteRef.current = fn;
  }, []);

  return { view, start, playUserMove, quit, setOnRunComplete };
}

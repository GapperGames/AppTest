import { describe, it, expect } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useTrainer } from "./useTrainer";
import type { EngineEval, MoveEdge, OpeningsData, TreeNode } from "../openings/types";
import type { StockfishEngine } from "../engine/stockfish";

// A no-op engine: on-tree play never needs it, and this test stays on-tree.
const fakeEngine = {
  async analyse(): Promise<EngineEval> {
    return { depth: 1, lines: [{ uci: "a2a3", san: "a3", cp: 0, mate: null }] };
  },
  async waitReady() {},
  dispose() {},
} as unknown as StockfishEngine;

function ev(uci: string): EngineEval {
  return { depth: 12, lines: [{ uci, san: uci, cp: 20, mate: null }] };
}

function node(
  turn: "w" | "b",
  userToMove: boolean,
  evalLine: EngineEval | null,
  children: MoveEdge[],
): TreeNode {
  return { fen: "fen", ply: 0, turn, userToMove, gamesReached: 1, eval: evalLine, children };
}

function edge(uci: string, count: number, isBest: boolean, child: TreeNode): MoveEdge {
  return { san: uci, uci, count, lossCp: isBest ? 0 : 120, isBest, child };
}

/**
 * White-side line: 1.e4 c5 2.Nf3 d6.
 * At move 2 the user has a best (Nf3) and a blunder (Qh5) edge to exercise the
 * reveal path. maxPly 4 so the run finishes after the opponent's 2nd move.
 */
function makeData(): OpeningsData {
  const afterNf3 = node("b", false, ev("d7d6"), []);
  const afterQh5 = node("b", false, ev("b8c6"), []); // reached only if user is (wrongly) let through
  const move2 = node("w", true, ev("g1f3"), [
    edge("g1f3", 3, true, node("b", false, ev("d7d6"), [edge("d7d6", 3, false, afterNf3)])),
    edge("d1h5", 1, false, afterQh5),
  ]);
  const afterC5 = node("b", false, ev("c7c5"), [edge("c7c5", 4, false, move2)]);
  const root = node("w", true, ev("e2e4"), [edge("e2e4", 5, true, afterC5)]);

  return {
    meta: {
      user: "T",
      generatedAt: "",
      source: "test",
      sourceGameCount: 1,
      skippedGameCount: 0,
      maxPly: 4,
      engineDepth: 12,
      multiPv: 3,
      correctThresholdCp: 30,
    },
    white: root,
    black: node("b", false, null, []),
  };
}

const tick = () => act(async () => { await new Promise((r) => setTimeout(r, 0)); });

describe("useTrainer", () => {
  it("plays an on-tree line, judging via precomputed data and completing", async () => {
    const data = makeData();
    const { result } = renderHook(() => useTrainer(fakeEngine, data));

    act(() => result.current.start("w", 1));
    expect(result.current.view.status).toBe("user-turn");
    expect(result.current.view.side).toBe("w");

    // 1. e4 — best move, should be accepted.
    act(() => {
      result.current.playUserMove("e2", "e4");
    });
    await tick();
    // opponent replies with the most common move (c5) and it's the user's turn.
    await waitFor(() => expect(result.current.view.status).toBe("user-turn"));
    expect(result.current.view.history.map((m) => m.san)).toEqual(["e4", "c5"]);
    expect(result.current.view.decisions).toEqual([true]);

    // 2. Nf3 — best move, completes the opening at maxPly 4.
    act(() => {
      result.current.playUserMove("g1", "f3");
    });
    await tick();
    await waitFor(() => expect(result.current.view.status).toBe("complete"));
    expect(result.current.view.decisions).toEqual([true, true]);
    expect(result.current.view.history.map((m) => m.san)).toEqual(["e4", "c5", "Nf3", "d6"]);
  });

  it("reveals the best move on a wrong first attempt and counts it wrong", async () => {
    const data = makeData();
    let lastRun: { correct: number; total: number; accuracy: number } | null = null;
    const { result } = renderHook(() => useTrainer(fakeEngine, data));
    act(() => result.current.setOnRunComplete((r) => (lastRun = r)));

    act(() => result.current.start("w", 1));
    act(() => {
      result.current.playUserMove("e2", "e4");
    });
    await tick();
    await waitFor(() => expect(result.current.view.status).toBe("user-turn"));

    // Play the blunder Qh5 (d1h5) — in-tree but flagged not best.
    act(() => {
      result.current.playUserMove("d1", "h5");
    });
    await tick();
    await waitFor(() => expect(result.current.view.status).toBe("reveal"));
    expect(result.current.view.feedback?.correct).toBe(false);
    // The wrong first attempt is recorded immediately.
    expect(result.current.view.decisions).toEqual([true, false]);
    // After the red flash the move is reverted and the best move is revealed.
    await waitFor(() => expect(result.current.view.hintMove).toEqual({ from: "g1", to: "f3" }), {
      timeout: 2500,
    });

    // Now play the best move to continue; it must NOT flip the decision.
    act(() => {
      result.current.playUserMove("g1", "f3");
    });
    await tick();
    await waitFor(() => expect(result.current.view.status).toBe("complete"));
    expect(result.current.view.decisions).toEqual([true, false]);
    expect(lastRun).toEqual({ level: 1, side: "w", correct: 1, total: 2, accuracy: 0.5 });
  });
});

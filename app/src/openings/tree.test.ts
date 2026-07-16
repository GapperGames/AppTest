import { describe, it, expect } from "vitest";
import { findEdge, pickOpponentMove, suggestedLevelCount } from "./tree";
import type { MoveEdge, OpeningsData, TreeNode } from "./types";

function leaf(fen = "x"): TreeNode {
  return { fen, ply: 1, turn: "b", userToMove: false, gamesReached: 0, eval: null, children: [] };
}

function edge(uci: string, count: number): MoveEdge {
  return { san: uci, uci, count, lossCp: null, isBest: null, child: leaf() };
}

function oppNode(edges: MoveEdge[]): TreeNode {
  return {
    fen: "n",
    ply: 1,
    turn: "b",
    userToMove: false,
    gamesReached: edges.reduce((s, e) => s + e.count, 0),
    eval: null,
    children: [...edges].sort((a, b) => b.count - a.count || a.san.localeCompare(b.san)),
  };
}

describe("findEdge", () => {
  it("finds an edge by uci", () => {
    const node = oppNode([edge("e7e5", 3), edge("c7c5", 4)]);
    expect(findEdge(node, "e7e5")?.count).toBe(3);
    expect(findEdge(node, "g8f6")).toBeUndefined();
  });
});

describe("pickOpponentMove", () => {
  it("level 1 always plays the single most common reply", () => {
    const node = oppNode([edge("e7e5", 4), edge("c7c5", 6), edge("e7e6", 1)]);
    for (let i = 0; i < 20; i++) {
      // rng irrelevant at level 1 — only one candidate.
      const pick = pickOpponentMove(node, 1, () => Math.random());
      expect(pick?.uci).toBe("c7c5"); // highest count
    }
  });

  it("higher levels widen the candidate set", () => {
    const node = oppNode([edge("c7c5", 6), edge("e7e5", 4), edge("e7e6", 1)]);
    // rng -> 0 always selects the first candidate within the level window.
    expect(pickOpponentMove(node, 2, () => 0)?.uci).toBe("c7c5");
    // rng -> 0.999 selects the last candidate in the window.
    expect(pickOpponentMove(node, 2, () => 0.999)?.uci).toBe("e7e5");
    // level 2 never reaches the 3rd move.
    for (let i = 0; i < 30; i++) {
      const pick = pickOpponentMove(node, 2, () => i / 30);
      expect(["c7c5", "e7e5"]).toContain(pick?.uci);
    }
  });

  it("returns null when there is no reply data", () => {
    expect(pickOpponentMove(leaf(), 3)).toBeNull();
  });
});

describe("suggestedLevelCount", () => {
  it("scales to the widest opponent branching, clamped 3..6", () => {
    const data = {
      white: oppNode([edge("a", 1), edge("b", 1)]),
      black: leaf(),
    } as unknown as OpeningsData;
    // widest branching is 2 -> clamps up to minimum 3.
    expect(suggestedLevelCount(data)).toBe(3);
  });
});

import { useState } from "react";
import type { Color, MoveEdge, OpeningsData, TreeNode } from "../openings/types";
import { formatScore } from "../openings/score";

interface TreeScreenProps {
  data: OpeningsData;
  side: Color;
  onSide: (s: Color) => void;
  onBack: () => void;
}

function moveNumberLabel(ply: number): string {
  // ply is the half-move index of the position BEFORE this edge's move.
  const moveNo = Math.floor(ply / 2) + 1;
  return ply % 2 === 0 ? `${moveNo}.` : `${moveNo}…`;
}

function qualityClass(edge: MoveEdge, isUserMove: boolean): string {
  if (!isUserMove || edge.lossCp === null) return "";
  if (edge.isBest) return "q-best";
  if (edge.lossCp <= 80) return "q-ok";
  if (edge.lossCp <= 150) return "q-inacc";
  return "q-bad";
}

function EdgeRow({
  edge,
  parent,
  parentTotal,
  depth,
}: {
  edge: MoveEdge;
  parent: TreeNode;
  parentTotal: number;
  depth: number;
}) {
  const [open, setOpen] = useState(depth < 2);
  const isUserMove = parent.userToMove;
  const share = parentTotal > 0 ? edge.count / parentTotal : 0;
  const hasChildren = edge.child.children.length > 0;

  return (
    <div className="tree-branch" style={{ marginLeft: depth === 0 ? 0 : 12 }}>
      <button
        className={"tree-row" + (isUserMove ? " tree-user" : " tree-opp")}
        onClick={() => hasChildren && setOpen((o) => !o)}
      >
        <span className="tree-caret">{hasChildren ? (open ? "▾" : "▸") : "·"}</span>
        <span className="tree-movno muted">{moveNumberLabel(parent.ply)}</span>
        <span className={"tree-san " + qualityClass(edge, isUserMove)}>{edge.san}</span>
        <span className="tree-bar-track">
          <span
            className={"tree-bar " + (isUserMove ? "tree-bar-user" : "tree-bar-opp")}
            style={{ width: `${Math.max(4, Math.round(share * 100))}%` }}
          />
        </span>
        <span className="tree-count muted">{edge.count}</span>
        {isUserMove && edge.lossCp !== null && !edge.isBest && (
          <span className="tree-loss">−{Math.round(edge.lossCp)}</span>
        )}
      </button>
      {open && hasChildren && (
        <ChildEdges node={edge.child} depth={depth + 1} />
      )}
    </div>
  );
}

function ChildEdges({ node, depth }: { node: TreeNode; depth: number }) {
  const total = node.children.reduce((s, e) => s + e.count, 0);
  return (
    <div className="tree-children">
      {node.children.map((e) => (
        <EdgeRow key={e.uci} edge={e} parent={node} parentTotal={total} depth={depth} />
      ))}
    </div>
  );
}

export function TreeScreen({ data, side, onSide, onBack }: TreeScreenProps) {
  const root = side === "w" ? data.white : data.black;

  return (
    <div className="screen tree-screen">
      <header className="sub-head">
        <button className="ghost-btn" onClick={onBack}>
          ‹ Menu
        </button>
        <h2>Openings tree</h2>
        <div className="segmented small-seg">
          <button className={"seg" + (side === "w" ? " seg-on" : "")} onClick={() => onSide("w")}>
            White
          </button>
          <button className={"seg" + (side === "b" ? " seg-on" : "")} onClick={() => onSide("b")}>
            Black
          </button>
        </div>
      </header>

      <div className="tree-legend muted small">
        <span>
          Bars show how often you reached each move. Your replies are tinted by engine quality:
        </span>
        <span className="legend-row">
          <em className="q-best">best</em> · <em className="q-ok">ok</em> ·{" "}
          <em className="q-inacc">inaccuracy</em> · <em className="q-bad">mistake</em>
        </span>
        {root.eval?.lines.length ? (
          <span>Start eval {formatScore(root.eval.lines[0])}</span>
        ) : null}
      </div>

      {root.children.length === 0 ? (
        <p className="warn-note">No games recorded for this colour.</p>
      ) : (
        <div className="tree-root">
          <ChildEdges node={root} depth={0} />
        </div>
      )}
    </div>
  );
}

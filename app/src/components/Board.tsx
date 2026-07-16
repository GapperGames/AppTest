import { useEffect, useMemo, useRef, useState } from "react";
import { Chessboard } from "react-chessboard";
import { Chess } from "chess.js";
import type { ResultTint } from "../game/useTrainer";

interface BoardProps {
  fen: string;
  orientation: "white" | "black";
  draggable: boolean;
  lastMove: { from: string; to: string } | null;
  hintMove: { from: string; to: string } | null;
  checkingSquare: string | null;
  resultTint: ResultTint;
  onDrop: (from: string, to: string, promotion?: string) => boolean;
}

function useContainerWidth(): [React.RefObject<HTMLDivElement>, number] {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(360);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width;
      if (w) setWidth(Math.floor(w));
    });
    ro.observe(el);
    setWidth(Math.floor(el.clientWidth));
    return () => ro.disconnect();
  }, []);
  return [ref, width];
}

const FILES = "abcdefgh";

function squareOffset(square: string, orientation: "white" | "black", size: number) {
  let col = FILES.indexOf(square[0]);
  let row = 8 - Number(square[1]);
  if (orientation === "black") {
    col = 7 - col;
    row = 7 - row;
  }
  return { left: col * size, top: row * size };
}

function promotionFor(fen: string, from: string, to: string): string | undefined {
  try {
    const piece = new Chess(fen).get(from as any);
    const isPawn = piece?.type === "p";
    const lastRank = to[1] === "8" || to[1] === "1";
    return isPawn && lastRank ? "q" : undefined;
  } catch {
    return undefined;
  }
}

export function Board({
  fen,
  orientation,
  draggable,
  lastMove,
  hintMove,
  checkingSquare,
  resultTint,
  onDrop,
}: BoardProps) {
  const [ref, width] = useContainerWidth();
  const squareSize = width / 8;

  // Tap-to-move selection state.
  const [selected, setSelected] = useState<string | null>(null);

  // Clear any selection whenever the position changes (after a move).
  useEffect(() => setSelected(null), [fen]);
  useEffect(() => {
    if (!draggable) setSelected(null);
  }, [draggable]);

  const legalTargets = useMemo(() => {
    if (!selected) return new Map<string, boolean>();
    try {
      const chess = new Chess(fen);
      const moves = chess.moves({ square: selected as any, verbose: true }) as any[];
      const m = new Map<string, boolean>();
      for (const mv of moves) m.set(mv.to, Boolean(mv.captured));
      return m;
    } catch {
      return new Map<string, boolean>();
    }
  }, [selected, fen]);

  const squareStyles: Record<string, React.CSSProperties> = {};
  if (lastMove) {
    const tint =
      resultTint === "wrong"
        ? "rgba(224, 90, 90, 0.55)"
        : resultTint === "correct"
          ? "rgba(87, 180, 95, 0.50)"
          : "rgba(255, 214, 102, 0.38)";
    squareStyles[lastMove.from] = { background: tint };
    squareStyles[lastMove.to] = { background: tint };
  }
  if (selected) {
    squareStyles[selected] = { background: "rgba(255, 214, 102, 0.5)" };
    for (const [sq, isCapture] of legalTargets) {
      squareStyles[sq] = isCapture
        ? { background: "radial-gradient(circle, transparent 58%, rgba(87,180,95,0.55) 60%)" }
        : { background: "radial-gradient(circle, rgba(30,45,28,0.35) 20%, transparent 22%)" };
    }
  }

  function pieceOwnerToMove(square: string): boolean {
    try {
      const chess = new Chess(fen);
      const p = chess.get(square as any);
      return !!p && p.color === chess.turn();
    } catch {
      return false;
    }
  }

  const handleDrop = (source: string, target: string, piece: string): boolean => {
    const isPawn = piece?.[1]?.toLowerCase() === "p";
    const lastRank = target[1] === "8" || target[1] === "1";
    const promotion = isPawn && lastRank ? "q" : undefined;
    const ok = onDrop(source, target, promotion);
    if (ok) setSelected(null);
    return ok;
  };

  const handleSquareClick = (square: string) => {
    if (!draggable) return;
    if (selected) {
      if (square === selected) {
        setSelected(null);
        return;
      }
      if (legalTargets.has(square)) {
        const ok = onDrop(selected, square, promotionFor(fen, selected, square));
        setSelected(ok ? null : selected);
        return;
      }
      // Clicked elsewhere: re-select if it's our piece, otherwise clear.
      setSelected(pieceOwnerToMove(square) ? square : null);
      return;
    }
    if (pieceOwnerToMove(square)) setSelected(square);
  };

  const spinnerPos = checkingSquare ? squareOffset(checkingSquare, orientation, squareSize) : null;

  return (
    <div className="board-wrap" ref={ref} style={{ position: "relative" }}>
      <Chessboard
        position={fen}
        boardWidth={width}
        boardOrientation={orientation}
        arePiecesDraggable={draggable}
        onPieceDrop={handleDrop}
        onSquareClick={handleSquareClick as any}
        animationDuration={170}
        customBoardStyle={{ borderRadius: "10px", boxShadow: "0 8px 30px rgba(0,0,0,0.45)" }}
        customDarkSquareStyle={{ backgroundColor: "#5f7a4b" }}
        customLightSquareStyle={{ backgroundColor: "#e7ecd4" }}
        customSquareStyles={squareStyles as any}
        customArrows={(hintMove ? [[hintMove.from, hintMove.to, "#57b45f"]] : []) as any}
      />
      {spinnerPos && (
        <div
          className="board-spinner-anchor"
          style={{ left: spinnerPos.left + squareSize / 2, top: spinnerPos.top + squareSize / 2 }}
        >
          <div
            className="board-spinner"
            style={{ width: squareSize * 0.5, height: squareSize * 0.5 }}
          />
        </div>
      )}
    </div>
  );
}

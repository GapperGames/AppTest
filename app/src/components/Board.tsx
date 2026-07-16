import { useEffect, useRef, useState } from "react";
import { Chessboard } from "react-chessboard";
import type { ResultTint } from "../game/useTrainer";

interface BoardProps {
  fen: string;
  orientation: "white" | "black";
  draggable: boolean;
  lastMove: { from: string; to: string } | null;
  hintMove: { from: string; to: string } | null;
  /** Square currently being checked — renders a spinner on the piece. */
  checkingSquare: string | null;
  /** Tint for the last move square: correct/wrong/none. */
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

/** Pixel offset (top-left) of a square, respecting board orientation. */
function squareOffset(square: string, orientation: "white" | "black", size: number) {
  let col = FILES.indexOf(square[0]);
  let row = 8 - Number(square[1]);
  if (orientation === "black") {
    col = 7 - col;
    row = 7 - row;
  }
  return { left: col * size, top: row * size };
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

  const handleDrop = (source: string, target: string, piece: string): boolean => {
    const isPawn = piece?.[1]?.toLowerCase() === "p";
    const lastRank = target[1] === "8" || target[1] === "1";
    const promotion = isPawn && lastRank ? "q" : undefined;
    return onDrop(source, target, promotion);
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
        animationDuration={170}
        customBoardStyle={{ borderRadius: "10px", boxShadow: "0 8px 30px rgba(0,0,0,0.45)" }}
        customDarkSquareStyle={{ backgroundColor: "#5f7a4b" }}
        customLightSquareStyle={{ backgroundColor: "#e7ecd4" }}
        customSquareStyles={squareStyles as any}
        customArrows={(hintMove ? [[hintMove.from, hintMove.to, "#57b45f"]] : []) as any}
      />
      {spinnerPos && (
        <div
          className="board-spinner"
          style={{
            left: spinnerPos.left + squareSize / 2,
            top: spinnerPos.top + squareSize / 2,
            width: squareSize * 0.5,
            height: squareSize * 0.5,
          }}
        />
      )}
    </div>
  );
}

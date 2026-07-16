import { useEffect, useRef, useState } from "react";
import { Chessboard } from "react-chessboard";

interface BoardProps {
  fen: string;
  orientation: "white" | "black";
  draggable: boolean;
  lastMove: { from: string; to: string } | null;
  hintMove: { from: string; to: string } | null;
  /** null = neutral, true = last move correct, false = wrong */
  lastCorrect: boolean | null;
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

export function Board({
  fen,
  orientation,
  draggable,
  lastMove,
  hintMove,
  lastCorrect,
  onDrop,
}: BoardProps) {
  const [ref, width] = useContainerWidth();

  const squareStyles: Record<string, React.CSSProperties> = {};
  if (lastMove) {
    const tint =
      lastCorrect === false
        ? "rgba(224, 90, 90, 0.45)"
        : lastCorrect === true
          ? "rgba(123, 201, 111, 0.40)"
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

  return (
    <div className="board-wrap" ref={ref}>
      <Chessboard
        position={fen}
        boardWidth={width}
        boardOrientation={orientation}
        arePiecesDraggable={draggable}
        onPieceDrop={handleDrop}
        animationDuration={180}
        customBoardStyle={{ borderRadius: "10px", boxShadow: "0 8px 30px rgba(0,0,0,0.45)" }}
        customDarkSquareStyle={{ backgroundColor: "#5f7a4b" }}
        customLightSquareStyle={{ backgroundColor: "#e7ecd4" }}
        customSquareStyles={squareStyles as any}
        customArrows={(hintMove ? [[hintMove.from, hintMove.to, "#57b45f"]] : []) as any}
      />
    </div>
  );
}

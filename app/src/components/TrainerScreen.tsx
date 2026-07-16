import { Board } from "./Board";
import { EvalBar } from "./EvalBar";
import type { TrainerView } from "../game/useTrainer";
import { LEVELS } from "../game/levels";

interface TrainerScreenProps {
  view: TrainerView;
  onDrop: (from: string, to: string, promotion?: string) => boolean;
  onQuit: () => void;
  onNext: () => void;
}

export function TrainerScreen({ view, onDrop, onQuit, onNext }: TrainerScreenProps) {
  const lastHist = view.history[view.history.length - 1];
  const lastCorrect = lastHist?.by === "user" ? lastHist.correct ?? null : null;
  const draggable = (view.status === "user-turn" || view.status === "reveal") && !view.busy;
  const level = LEVELS.find((l) => l.n === view.level);

  const bannerClass =
    view.status === "reveal"
      ? "banner banner-wrong"
      : view.feedback?.correct && view.status !== "opponent"
        ? "banner banner-ok"
        : "banner";

  const userDecisions = view.decisions;

  return (
    <div className="screen trainer">
      <header className="trainer-head">
        <button className="ghost-btn" onClick={onQuit} aria-label="Back to menu">
          ‹ Menu
        </button>
        <div className="trainer-title">
          <span className="pill">{view.side === "w" ? "White" : "Black"}</span>
          <span className="pill pill-accent">Level {view.level}</span>
          <span className="muted">{level?.short}</span>
        </div>
        <div className="progress muted">
          {Math.min(view.ply, view.maxPly)}/{view.maxPly}
        </div>
      </header>

      <div className="board-row">
        <EvalBar cpWhite={view.evalWhiteCp} orientation={view.orientation} />
        <Board
          fen={view.fen}
          orientation={view.orientation}
          draggable={draggable}
          lastMove={view.lastMove}
          hintMove={view.hintMove}
          lastCorrect={lastCorrect}
          onDrop={onDrop}
        />
      </div>

      <div className={bannerClass}>
        <span className="banner-text">{view.message || " "}</span>
        {view.feedback && view.status === "reveal" && (
          <span className="banner-sub">
            You played {view.feedback.playedSan} · −{view.feedback.lossCp}cp
          </span>
        )}
      </div>

      <div className="accuracy-dots" aria-label="This run's move results">
        {userDecisions.length === 0 && <span className="muted small">No moves yet</span>}
        {userDecisions.map((ok, i) => (
          <span key={i} className={ok ? "dot dot-ok" : "dot dot-bad"} />
        ))}
        {userDecisions.length > 0 && (
          <span className="muted small dots-count">
            {userDecisions.filter(Boolean).length}/{userDecisions.length}
          </span>
        )}
      </div>

      <div className="movelist">
        {view.history.map((m, i) => (
          <span
            key={i}
            className={
              "move-chip" +
              (m.by === "user" ? " move-user" : " move-opp") +
              (m.by === "user" && m.correct === false ? " move-miss" : "")
            }
          >
            {m.san}
          </span>
        ))}
      </div>

      {view.status === "complete" && (
        <div className="complete-card">
          <div className="complete-score">
            {view.decisions.length > 0
              ? `${Math.round(
                  (view.decisions.filter(Boolean).length / view.decisions.length) * 100,
                )}%`
              : "—"}
          </div>
          <div className="muted">
            {view.decisions.filter(Boolean).length}/{view.decisions.length} best moves on first try
          </div>
          <div className="complete-actions">
            <button className="primary-btn" onClick={onNext}>
              Next opening
            </button>
            <button className="ghost-btn" onClick={onQuit}>
              Menu
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

import type { Color, OpeningsData } from "../openings/types";
import type { LevelDef } from "../game/levels";
import type { RollingStat } from "../stats/store";
import { RebuildPanel } from "./RebuildPanel";

interface HomeScreenProps {
  data: OpeningsData;
  levels: LevelDef[];
  side: Color;
  onSide: (side: Color) => void;
  onStart: (level: number) => void;
  overall: RollingStat;
  perLevel: (n: number) => RollingStat;
  engineReady: boolean;
  isCustom: boolean;
  onRebuilt: (data: OpeningsData) => void;
  onOpenTree: () => void;
  onOpenStats: () => void;
}

function pct(stat: RollingStat): string {
  return stat.avg === null ? "—" : `${Math.round(stat.avg * 100)}%`;
}

export function HomeScreen({
  data,
  levels,
  side,
  onSide,
  onStart,
  overall,
  perLevel,
  engineReady,
  isCustom,
  onRebuilt,
  onOpenTree,
  onOpenStats,
}: HomeScreenProps) {
  const counts = { white: data.white.gamesReached, black: data.black.gamesReached };
  const activeCount = side === "w" ? counts.white : counts.black;

  return (
    <div className="screen home">
      <header className="home-head">
        <div>
          <h1 className="brand">
            Opening<span className="brand-accent">Reflex</span>
          </h1>
          <p className="muted small">
            {data.meta.user} · {data.meta.sourceGameCount} games · first {data.meta.maxPly} half-moves
          </p>
        </div>
        <div className="headline-acc">
          <div className="headline-acc-num">{pct(overall)}</div>
          <div className="muted small">
            {side === "w" ? "White" : "Black"} · last {overall.count || 0}
          </div>
        </div>
      </header>

      <RebuildPanel currentUser={data.meta.user} isCustom={isCustom} onRebuilt={onRebuilt} />

      <div className="segmented" role="tablist" aria-label="Choose your colour">
        <button
          role="tab"
          aria-selected={side === "w"}
          className={"seg" + (side === "w" ? " seg-on" : "")}
          onClick={() => onSide("w")}
        >
          <span className="seg-piece">♔</span> White
          <span className="seg-sub">{counts.white} games</span>
        </button>
        <button
          role="tab"
          aria-selected={side === "b"}
          className={"seg" + (side === "b" ? " seg-on" : "")}
          onClick={() => onSide("b")}
        >
          <span className="seg-piece seg-dark">♚</span> Black
          <span className="seg-sub">{counts.black} games</span>
        </button>
      </div>

      {activeCount === 0 && (
        <p className="warn-note">
          No games found for {side === "w" ? "White" : "Black"} in your data. Try the other colour or
          re-run the pipeline.
        </p>
      )}

      <div className="level-grid">
        {levels.map((lvl) => {
          const stat = perLevel(lvl.n);
          return (
            <button
              key={lvl.n}
              className="level-card"
              disabled={!engineReady || activeCount === 0}
              onClick={() => onStart(lvl.n)}
            >
              <div className="level-card-top">
                <span className="level-n">{lvl.n}</span>
                <span className="level-acc">{pct(stat)}</span>
              </div>
              <div className="level-name">{lvl.name}</div>
              <div className="level-short muted small">{lvl.short}</div>
            </button>
          );
        })}
      </div>

      <div className="home-actions">
        <button className="tile-btn" onClick={onOpenTree}>
          🌳 Openings tree
        </button>
        <button className="tile-btn" onClick={onOpenStats}>
          📈 Stats
        </button>
      </div>

      {!engineReady && <p className="muted small center loading-note">Loading Stockfish engine…</p>}
    </div>
  );
}

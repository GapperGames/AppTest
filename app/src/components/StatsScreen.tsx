import { useState } from "react";
import type { Color } from "../openings/types";
import type { LevelDef } from "../game/levels";
import { rollingAccuracy, type RunRecord, ROLLING_WINDOW } from "../stats/store";

interface StatsScreenProps {
  runs: RunRecord[];
  levels: LevelDef[];
  onBack: () => void;
  onClear: () => void;
}

function pct(v: number | null): string {
  return v === null ? "—" : `${Math.round(v * 100)}%`;
}

export function StatsScreen({ runs, levels, onBack, onClear }: StatsScreenProps) {
  const [side, setSide] = useState<Color>("w");
  const sideRuns = runs.filter((r) => r.side === side);
  const overall = rollingAccuracy(runs, { side });
  const recent = sideRuns.slice(-12).reverse();

  return (
    <div className="screen stats-screen">
      <header className="sub-head">
        <button className="ghost-btn" onClick={onBack}>
          ‹ Menu
        </button>
        <h2>Stats</h2>
        <button className="ghost-btn danger" onClick={onClear} disabled={runs.length === 0}>
          Clear
        </button>
      </header>

      <div className="segmented small-seg">
        <button className={"seg" + (side === "w" ? " seg-on" : "")} onClick={() => setSide("w")}>
          White
        </button>
        <button className={"seg" + (side === "b" ? " seg-on" : "")} onClick={() => setSide("b")}>
          Black
        </button>
      </div>

      <div className="stat-hero">
        <div className="stat-hero-num">{pct(overall.avg)}</div>
        <div className="muted">
          {side === "w" ? "White" : "Black"} accuracy · last {overall.count || 0} plays
        </div>
      </div>

      <h3 className="section-label">By level (last {ROLLING_WINDOW})</h3>
      <div className="level-stats">
        {levels.map((l) => {
          const s = rollingAccuracy(runs, { level: l.n, side });
          return (
            <div key={l.n} className="level-stat-row">
              <span className="level-stat-n">L{l.n}</span>
              <span className="level-stat-name muted">{l.name}</span>
              <span className="level-stat-bar-track">
                <span
                  className="level-stat-bar"
                  style={{ width: s.avg === null ? "0%" : `${Math.round(s.avg * 100)}%` }}
                />
              </span>
              <span className="level-stat-val">{pct(s.avg)}</span>
            </div>
          );
        })}
      </div>

      <h3 className="section-label">Recent {side === "w" ? "White" : "Black"} plays</h3>
      {recent.length === 0 ? (
        <p className="muted small">No plays yet on this colour.</p>
      ) : (
        <ul className="run-list">
          {recent.map((r, i) => (
            <li key={i} className="run-item">
              <span className="pill">L{r.level}</span>
              <span className="run-score">
                {r.correct}/{r.total}
              </span>
              <span
                className={
                  "run-pct " + (r.accuracy >= 0.75 ? "good" : r.accuracy >= 0.5 ? "mid" : "low")
                }
              >
                {Math.round(r.accuracy * 100)}%
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

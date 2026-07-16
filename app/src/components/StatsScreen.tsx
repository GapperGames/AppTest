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
  const overall = rollingAccuracy(runs);
  const recent = runs.slice(-12).reverse();

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

      <div className="stat-hero">
        <div className="stat-hero-num">{pct(overall.avg)}</div>
        <div className="muted">accuracy over last {overall.count || 0} plays</div>
      </div>

      <h3 className="section-label">By level (last {ROLLING_WINDOW})</h3>
      <div className="level-stats">
        {levels.map((l) => {
          const s = rollingAccuracy(runs, { level: l.n });
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

      <h3 className="section-label">Recent plays</h3>
      {recent.length === 0 ? (
        <p className="muted small">No plays yet. Finish an opening to see it here.</p>
      ) : (
        <ul className="run-list">
          {recent.map((r, i) => (
            <li key={i} className="run-item">
              <span className="pill">{r.side === "w" ? "White" : "Black"}</span>
              <span className="muted small">L{r.level}</span>
              <span className="run-score">
                {r.correct}/{r.total}
              </span>
              <span className={"run-pct " + (r.accuracy >= 0.75 ? "good" : r.accuracy >= 0.5 ? "mid" : "low")}>
                {Math.round(r.accuracy * 100)}%
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

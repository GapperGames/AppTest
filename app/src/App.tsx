import { useEffect, useMemo, useState } from "react";
import type { Color } from "./openings/types";
import { useOpenings } from "./data/useOpenings";
import { useEngine } from "./engine/useEngine";
import { useTrainer, type RunResult } from "./game/useTrainer";
import { buildLevels, LEVEL_COUNT } from "./game/levels";
import { loadRuns, recordRun, clearRuns, rollingAccuracy } from "./stats/store";
import { HomeScreen } from "./components/HomeScreen";
import { TrainerScreen } from "./components/TrainerScreen";
import { TreeScreen } from "./components/TreeScreen";
import { StatsScreen } from "./components/StatsScreen";

type Screen = "home" | "trainer" | "tree" | "stats";

export default function App() {
  const { data, error: dataError, isCustom, setData } = useOpenings();
  const { engine, ready: engineReady, error: engineError } = useEngine();
  const { view, start, playUserMove, quit, setOnRunComplete } = useTrainer(engine, data);

  const [screen, setScreen] = useState<Screen>("home");
  const [side, setSide] = useState<Color>("w");
  const [runs, setRuns] = useState(() => loadRuns());

  const levels = useMemo(() => buildLevels(LEVEL_COUNT), []);

  useEffect(() => {
    setOnRunComplete((r: RunResult) => {
      setRuns(recordRun({ ...r, ts: Date.now() }));
    });
    return () => setOnRunComplete(null);
  }, [setOnRunComplete]);

  const startLevel = (level: number) => {
    start(side, level);
    setScreen("trainer");
  };

  const handleQuit = () => {
    quit();
    setScreen("home");
  };

  if (dataError) {
    return (
      <div className="app fatal">
        <h2>Couldn't load your openings</h2>
        <p className="muted">{dataError}</p>
        <p className="muted small">
          Generate <code>app/public/openings.json</code> with the pipeline, then reload.
        </p>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="app center-screen">
        <div className="spinner" />
        <p className="muted">Loading openings…</p>
      </div>
    );
  }

  const overall = rollingAccuracy(runs, { side });

  return (
    <div className="app">
      {engineError && <div className="engine-error">Engine problem: {engineError}</div>}

      {screen === "home" && (
        <HomeScreen
          data={data}
          levels={levels}
          side={side}
          onSide={setSide}
          onStart={startLevel}
          overall={overall}
          perLevel={(n) => rollingAccuracy(runs, { level: n, side })}
          engineReady={engineReady}
          isCustom={isCustom}
          onRebuilt={(d) => setData(d)}
          onOpenTree={() => setScreen("tree")}
          onOpenStats={() => setScreen("stats")}
        />
      )}

      {screen === "trainer" && (
        <TrainerScreen
          view={view}
          onDrop={playUserMove}
          onQuit={handleQuit}
          onNext={() => start(side, view.level)}
        />
      )}

      {screen === "tree" && (
        <TreeScreen data={data} side={side} onSide={setSide} onBack={() => setScreen("home")} />
      )}

      {screen === "stats" && (
        <StatsScreen
          runs={runs}
          levels={levels}
          onBack={() => setScreen("home")}
          onClear={() => {
            clearRuns();
            setRuns([]);
          }}
        />
      )}
    </div>
  );
}

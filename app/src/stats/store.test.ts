import { describe, it, expect, beforeEach } from "vitest";
import { loadRuns, recordRun, clearRuns, rollingAccuracy, type RunRecord } from "./store";

function run(level: number, side: "w" | "b", accuracy: number): RunRecord {
  return { level, side, correct: Math.round(accuracy * 4), total: 4, accuracy, ts: 0 };
}

describe("stats store", () => {
  beforeEach(() => clearRuns());

  it("persists and reloads runs", () => {
    recordRun(run(1, "w", 0.5));
    recordRun(run(2, "b", 1));
    expect(loadRuns()).toHaveLength(2);
  });

  it("rolling accuracy averages the last window", () => {
    for (let i = 0; i < 20; i++) recordRun(run(1, "w", i < 10 ? 0 : 1));
    // last 16 => 6 zeros + 10 ones = 10/16
    const stat = rollingAccuracy(loadRuns(), { window: 16 });
    expect(stat.count).toBe(16);
    expect(stat.avg).toBeCloseTo(10 / 16, 5);
  });

  it("filters by level", () => {
    recordRun(run(1, "w", 1));
    recordRun(run(2, "w", 0));
    recordRun(run(1, "w", 0));
    const l1 = rollingAccuracy(loadRuns(), { level: 1 });
    expect(l1.count).toBe(2);
    expect(l1.avg).toBeCloseTo(0.5, 5);
  });

  it("returns null average with no runs", () => {
    expect(rollingAccuracy([]).avg).toBeNull();
  });
});

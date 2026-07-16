import type { Color } from "../openings/types";

export interface RunRecord {
  level: number;
  side: Color;
  /** User decisions answered correctly on the FIRST attempt. */
  correct: number;
  /** Total user decisions in the run. */
  total: number;
  /** correct / total, 0..1. */
  accuracy: number;
  ts: number;
}

const KEY = "opening-reflex:runs:v1";
const MAX_STORED = 500;

/** The rolling window used for the headline "recent accuracy" figure. */
export const ROLLING_WINDOW = 16;

export function loadRuns(): RunRecord[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as RunRecord[]) : [];
  } catch {
    return [];
  }
}

function save(runs: RunRecord[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(runs.slice(-MAX_STORED)));
  } catch {
    /* storage unavailable — ignore */
  }
}

export function recordRun(run: RunRecord): RunRecord[] {
  const runs = loadRuns();
  runs.push(run);
  const capped = runs.slice(-MAX_STORED);
  save(capped);
  return capped;
}

export function clearRuns(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* ignore */
  }
}

export interface RollingStat {
  /** Mean accuracy (0..1) over the window, or null if no runs. */
  avg: number | null;
  /** Number of runs counted (<= window). */
  count: number;
}

/**
 * Mean accuracy over the last `window` runs, optionally filtered to one level.
 */
export function rollingAccuracy(
  runs: RunRecord[],
  opts: { level?: number; window?: number } = {},
): RollingStat {
  const window = opts.window ?? ROLLING_WINDOW;
  let filtered = runs;
  if (opts.level !== undefined) filtered = runs.filter((r) => r.level === opts.level);
  const recent = filtered.slice(-window);
  if (recent.length === 0) return { avg: null, count: 0 };
  const avg = recent.reduce((s, r) => s + r.accuracy, 0) / recent.length;
  return { avg, count: recent.length };
}

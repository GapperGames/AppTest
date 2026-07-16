export interface LevelDef {
  n: number;
  name: string;
  short: string;
  desc: string;
}

const NAMES = [
  "Reflex",
  "Instinct",
  "Habit",
  "Prepared",
  "Booked",
  "Schooled",
  "Sharp",
  "Seasoned",
  "Theory",
  "Mainline",
];

/** How many levels the app exposes. */
export const LEVEL_COUNT = 10;

/** Build the level table up to `count` levels (each inclusive of the previous). */
export function buildLevels(count: number): LevelDef[] {
  const levels: LevelDef[] = [];
  for (let n = 1; n <= count; n++) {
    levels.push({
      n,
      name: NAMES[n - 1] ?? `Level ${n}`,
      short: n === 1 ? "Top reply only" : `Top ${n} replies`,
      desc:
        n === 1
          ? "The opponent always answers with your single most common line."
          : `The opponent may answer with any of your ${n} most common replies.`,
    });
  }
  return levels;
}

/** Full level table used across the app. */
export const LEVELS: LevelDef[] = buildLevels(LEVEL_COUNT);

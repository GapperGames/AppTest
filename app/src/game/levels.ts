export interface LevelDef {
  n: number;
  name: string;
  short: string;
  desc: string;
}

const NAMES = ["Reflex", "Instinct", "Prepared", "Booked", "Theory", "Mainline"];

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

/** Default table — the app narrows this to the data's real branching. */
export const LEVELS: LevelDef[] = buildLevels(6);

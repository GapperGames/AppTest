import { readFile } from "node:fs/promises";

/** A game as raw PGN plus the two participants' usernames. */
export interface RawGame {
  pgn: string;
  white: string;
  black: string;
  timeClass?: string;
  rules?: string;
}

const UA = "chess-openings-trainer/0.1 (personal opening study tool)";

async function getJson(url: string): Promise<any> {
  const res = await fetch(url, { headers: { "User-Agent": UA, Accept: "application/json" } });
  if (!res.ok) throw new Error(`GET ${url} -> ${res.status} ${res.statusText}`);
  return res.json();
}

/**
 * Fetch up to `max` of a chess.com user's most recent games via the public
 * Published-Data API (https://www.chess.com/news/view/published-data-api).
 * Archives are walked newest-first. Optionally filter by `timeClass`
 * (e.g. "bullet", "blitz") and `rules` (default "chess", i.e. standard).
 */
export async function fetchChessComGames(
  user: string,
  max: number,
  opts: { timeClass?: string; rules?: string } = {},
): Promise<RawGame[]> {
  const rules = opts.rules ?? "chess";
  const handle = user.toLowerCase();
  const { archives } = (await getJson(
    `https://api.chess.com/pub/player/${encodeURIComponent(handle)}/games/archives`,
  )) as { archives: string[] };

  const out: RawGame[] = [];
  // Newest archives last in the list -> iterate in reverse.
  for (let i = archives.length - 1; i >= 0 && out.length < max; i--) {
    const { games } = (await getJson(archives[i])) as { games: any[] };
    // Within a month, newest games are last -> iterate in reverse too.
    for (let g = games.length - 1; g >= 0 && out.length < max; g--) {
      const game = games[g];
      if (!game.pgn) continue;
      if (rules && game.rules && game.rules !== rules) continue;
      if (opts.timeClass && game.time_class !== opts.timeClass) continue;
      out.push({
        pgn: game.pgn,
        white: game.white?.username ?? "",
        black: game.black?.username ?? "",
        timeClass: game.time_class,
        rules: game.rules,
      });
    }
  }
  return out;
}

/** Split a multi-game PGN file into individual games. */
export function splitPgn(text: string): string[] {
  const normalized = text.replace(/\r\n/g, "\n").trim();
  if (!normalized) return [];
  // Games are separated by a blank line followed by the next game's headers.
  // The blank line between a game's own headers and its movetext is followed
  // by movetext (starts with a move number), so this split is unambiguous.
  return normalized
    .split(/\n\s*\n(?=\[Event\b)/)
    .map((g) => g.trim())
    .filter(Boolean);
}

function readHeader(pgn: string, name: string): string {
  const m = pgn.match(new RegExp(`\\[${name}\\s+"([^"]*)"\\]`));
  return m ? m[1] : "";
}

/** Load games from a local PGN file (single- or multi-game). */
export async function loadPgnFile(path: string): Promise<RawGame[]> {
  const text = await readFile(path, "utf8");
  return splitPgn(text).map((pgn) => ({
    pgn,
    white: readHeader(pgn, "White"),
    black: readHeader(pgn, "Black"),
    timeClass: readHeader(pgn, "TimeControl") || undefined,
  }));
}

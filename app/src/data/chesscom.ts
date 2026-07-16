import { Capacitor, CapacitorHttp } from "@capacitor/core";

export interface RawGame {
  pgn: string;
  white: string;
  black: string;
  timeClass?: string;
}

const UA = "opening-reflex/0.1 (personal opening trainer)";

/**
 * Fetch JSON. On a real device we go through CapacitorHttp (native, so the
 * chess.com request isn't subject to browser CORS); in a plain browser we fall
 * back to window.fetch.
 */
async function getJson(url: string): Promise<any> {
  if (Capacitor.isNativePlatform()) {
    const res = await CapacitorHttp.get({ url, headers: { "User-Agent": UA, Accept: "application/json" } });
    if (res.status >= 400) throw new Error(`GET ${url} -> ${res.status}`);
    return typeof res.data === "string" ? JSON.parse(res.data) : res.data;
  }
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`GET ${url} -> ${res.status}`);
  return res.json();
}

export interface FetchOptions {
  timeClass?: string; // bullet | blitz | rapid | daily
  rules?: string; // default "chess"
  onProgress?: (message: string, fetched: number) => void;
  signal?: { aborted: boolean };
}

/**
 * Fetch up to `max` of a chess.com user's most recent games, newest first,
 * via the public Published-Data API.
 */
export async function fetchChessComGames(
  user: string,
  max: number,
  opts: FetchOptions = {},
): Promise<RawGame[]> {
  const rules = opts.rules ?? "chess";
  const handle = user.trim().toLowerCase();
  if (!handle) return [];

  const { archives } = (await getJson(
    `https://api.chess.com/pub/player/${encodeURIComponent(handle)}/games/archives`,
  )) as { archives: string[] };

  const out: RawGame[] = [];
  for (let i = archives.length - 1; i >= 0 && out.length < max; i--) {
    if (opts.signal?.aborted) break;
    opts.onProgress?.(`Reading games (${out.length}/${max})…`, out.length);
    const { games } = (await getJson(archives[i])) as { games: any[] };
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
      });
    }
  }
  return out;
}

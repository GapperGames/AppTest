#!/usr/bin/env -S npx tsx
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { Engine } from "./engine.js";
import { fetchChessComGames, loadPgnFile, type RawGame } from "./games.js";
import { buildOpenings } from "./tree.js";

interface Args {
  user: string;
  pgn?: string;
  max: number;
  out: string;
  depth: number;
  maxPly: number;
  multipv: number;
  threshold: number;
  timeClass?: string;
  rules: string;
}

function parseArgs(argv: string[]): Args {
  const args: Record<string, string> = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next && !next.startsWith("--")) {
        args[key] = next;
        i++;
      } else {
        args[key] = "true";
      }
    }
  }
  if (args.help || args.h) {
    printHelp();
    process.exit(0);
  }
  const user = args.user ?? "";
  if (!user) {
    console.error("error: --user <chess.com username> is required (used to detect your colour).\n");
    printHelp();
    process.exit(1);
  }
  return {
    user,
    pgn: args.pgn,
    max: Number(args.max ?? 200),
    out: args.out ?? "out/openings.json",
    depth: Number(args.depth ?? 12),
    maxPly: Number(args["max-ply"] ?? 24),
    multipv: Number(args.multipv ?? 3),
    threshold: Number(args.threshold ?? 30),
    timeClass: args["time-class"],
    rules: args.rules ?? "chess",
  };
}

function printHelp(): void {
  console.log(`Openings pipeline — build a Stockfish-scored weighted openings tree.

Usage:
  openings-pipeline --user <handle> [options]

Sources (pick one):
  --user <handle>        chess.com username. Also used to detect your colour.
  --pgn <file>           Read games from a local PGN file instead of the API.

Options:
  --max <n>              Max games to fetch from chess.com (default 200).
  --out <file>           Output JSON path (default out/openings.json).
  --depth <n>            Stockfish search depth per position (default 12).
  --max-ply <n>          Opening half-moves to analyse per game (default 24 = 12 moves).
  --multipv <n>          Engine lines kept per position (default 3).
  --threshold <cp>       Centipawns from best still counted "correct" (default 30).
  --time-class <class>   Filter chess.com games: bullet | blitz | rapid | daily.
  --rules <rules>        chess.com variant filter (default "chess" = standard).

Examples:
  openings-pipeline --user hikaru --max 200 --time-class bullet
  openings-pipeline --user me --pgn ../data/sample-games.pgn --out ../app/public/openings.json
`);
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));

  let rawGames: RawGame[];
  let source: string;
  if (args.pgn) {
    source = `local:${args.pgn}`;
    console.log(`Loading games from ${args.pgn} ...`);
    rawGames = await loadPgnFile(resolve(args.pgn));
  } else {
    source = `chess.com:${args.user}`;
    console.log(`Fetching up to ${args.max} games for ${args.user} from chess.com ...`);
    rawGames = await fetchChessComGames(args.user, args.max, {
      timeClass: args.timeClass,
      rules: args.rules,
    });
  }
  console.log(`Got ${rawGames.length} games.`);
  if (rawGames.length === 0) {
    console.error("No games to process. Check the username / PGN path / filters.");
    process.exit(1);
  }

  console.log("Starting Stockfish 16 (single-threaded WASM) ...");
  const engine = await Engine.create();
  await engine.setOption("MultiPV", args.multipv);
  await engine.setOption("Hash", 64);

  const startedAt = Date.now();
  const data = await buildOpenings(rawGames, engine, {
    user: args.user,
    maxPly: args.maxPly,
    depth: args.depth,
    multiPv: args.multipv,
    correctThresholdCp: args.threshold,
    source,
    onProgress: (done, total, label) => {
      if (done === 1 || done === total || done % 10 === 0) {
        process.stdout.write(`\r  analysing positions: ${done}/${total} (${label})   `);
      }
    },
  });
  process.stdout.write("\n");
  engine.quit();

  const outPath = resolve(args.out);
  await mkdir(dirname(outPath), { recursive: true });
  await writeFile(outPath, JSON.stringify(data, null, 2));

  const secs = ((Date.now() - startedAt) / 1000).toFixed(1);
  console.log(
    `\nDone in ${secs}s.\n` +
      `  games used:    ${data.meta.sourceGameCount} (skipped ${data.meta.skippedGameCount})\n` +
      `  white replies: ${data.white.children.length} first moves\n` +
      `  black replies: ${data.black.children.length} first moves\n` +
      `  written to:    ${outPath}`,
  );
}

main().catch((err) => {
  console.error("\nPipeline failed:", err);
  process.exit(1);
});

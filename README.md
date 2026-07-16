# Opening Reflex

A bullet-chess **openings trainer** for Android. It scrapes your recent
chess.com games, uses **Stockfish** to score the opening positions you actually
reach, compiles a **weighted openings tree**, and then drills you: play through
your own opening lines at speed until your instinctive replies *are* the best
moves.

> The idea: in bullet you have no time to calculate, so you fall back on
> pattern recognition. This app finds the exact positions where your reflex move
> isn't the engine's move, and lets you practise them until the good move
> becomes the reflex.

---

## What's in the box

```
├── pipeline/     Node + TypeScript CLI: chess.com → Stockfish → openings.json
├── app/          React + Vite trainer (packaged for Android with Capacitor)
├── data/         Sample PGNs so everything runs offline out of the box
└── app/public/openings.json   Pre-built sample tree the app ships with
```

Two independent pieces:

1. **The pipeline** (run occasionally, e.g. on your laptop) turns your games
   into a `openings.json` data file.
2. **The app** (runs on your phone) loads that file and runs Stockfish
   *in the browser/WebView* to judge your moves live.

---

## Quick start

```bash
npm install

# 1. Build your openings tree from the bundled sample games (offline):
npm run pipeline:sample          # writes app/public/openings.json

# 2. Run the trainer in a browser:
npm run app:dev                  # http://localhost:5173
```

The repo already ships a generated sample `openings.json`, so step 2 works even
before you run the pipeline.

### Build it from *your* chess.com games

```bash
npm run pipeline -- \
  --user your_chesscom_handle \
  --max 200 \
  --time-class bullet \
  --out app/public/openings.json
```

Then `npm run app:dev` (or rebuild the Android app) to train on your own lines.

> **Network note:** the pipeline calls the public chess.com Published-Data API
> (`https://api.chess.com/pub/...`). That host is **blocked by the egress policy
> of the sandbox this repo was built in**, so the pipeline was developed and
> verified against the bundled `data/sample-games.pgn`. On a normal machine the
> chess.com fetch path works directly; if you can't reach the API, export your
> games to a PGN and use `--pgn` (below).

### Build from a local PGN (no network needed)

```bash
npm run pipeline -- --user your_handle --pgn data/sample-games.pgn \
  --out app/public/openings.json
```

---

## How the trainer works

- **Levels** control how your opponent replies, drawn from *your* game history:
  - **Level 1** – opponent always plays your single most common reply.
  - **Level 2** – opponent may play either of your two most common replies.
  - **Level N** – any of your N most common replies (inclusive of lower levels).
  - When a position never occurred in your games, the opponent plays Stockfish's
    best move.
- You play your move; it's judged by Stockfish (a move within
  `--threshold` centipawns of best counts as correct — not strictly the single
  best, so genuinely equal moves all pass). Judgement is independent of whether
  you won or lost the original game.
- **Wrong move?** The best move is revealed (arrow on the board) and you play a
  best move to continue. The miss is still counted against you for that attempt.
- Each opening runs to the first `--max-ply` half-moves (default 12).
- **Accuracy** is the share of first-try best moves, averaged over your last 16
  completed openings (overall and per level), stored on-device.

### Screens

- **Home** – choose White/Black, pick any level any time, see recent accuracy.
- **Trainer** – board, eval bar, live feedback, per-move result dots.
- **Openings tree** – your weighted repertoire; bar length = how often you
  reached a move, your replies tinted by engine quality (best/ok/inaccuracy/
  mistake).
- **Stats** – rolling accuracy overall, per level, and recent plays.

---

## Building the Android app

The web app is packaged with [Capacitor](https://capacitorjs.com/). The native
Android project lives in `app/android/`.

```bash
cd app
npm run build          # produces app/dist
npx cap sync android   # copies web assets + engine into the native project
npx cap open android   # opens Android Studio  (or: cd android && ./gradlew assembleDebug)
```

The Stockfish WASM engine (`app/public/stockfish/`) is bundled as a static asset
and loaded as a Web Worker, so it runs entirely on-device — no server, no
network, no special COOP/COEP headers required in the WebView.

> Building the APK needs the Android SDK (`ANDROID_HOME`). The build sandbox this
> was authored in has the JDK + Gradle but not the Android SDK, so the native
> project is committed but the final `assembleDebug` step must be run where the
> SDK is available (e.g. Android Studio).

---

## The data file (`openings.json`)

Produced by the pipeline, consumed by the app. Two tries — one for games where
you were White, one for Black — each a trie of positions:

```jsonc
{
  "meta": { "user", "sourceGameCount", "maxPly", "engineDepth",
            "multiPv", "correctThresholdCp", ... },
  "white": TreeNode,   // you move on even plies
  "black": TreeNode    // you move on odd plies
}
```

Each `TreeNode` carries the position FEN, whose turn it is, how many of your
games passed through, a Stockfish `eval` (top MultiPV lines), and `children`
(the moves you/your opponents actually played, most common first). Each child
edge records its `count` and — for your moves — its centipawn `lossCp` versus
best and an `isBest` flag. The type is defined once in
`pipeline/src/types.ts` and mirrored in `app/src/openings/types.ts`.

---

## Development

```bash
# Pipeline
npm run test      --workspace @trainer/pipeline
npm run typecheck --workspace @trainer/pipeline

# App
npm run test      --workspace @trainer/app   # vitest (logic + trainer loop)
npm run typecheck --workspace @trainer/app
npm run build     --workspace @trainer/app
```

### Pipeline options

| Flag | Default | Meaning |
| --- | --- | --- |
| `--user <handle>` | – | chess.com username (also detects your colour) |
| `--pgn <file>` | – | read a local PGN instead of the API |
| `--max <n>` | 200 | max games to pull from chess.com |
| `--depth <n>` | 12 | Stockfish depth per position |
| `--max-ply <n>` | 12 | opening half-moves analysed per game |
| `--multipv <n>` | 3 | engine lines kept per position |
| `--threshold <cp>` | 30 | centipawns from best still "correct" |
| `--time-class <c>` | – | `bullet` \| `blitz` \| `rapid` \| `daily` |
| `--out <file>` | `out/openings.json` | output path |

---

## Tech

- **Stockfish 16** (single-threaded WASM, `stockfish` npm) — used in Node for the
  pipeline and in the WebView for live move judging.
- **chess.js** for move generation / PGN parsing.
- **React + Vite + TypeScript**, `react-chessboard` for the board.
- **Capacitor** for the Android wrapper.

## Licence

MIT for this project's code. Stockfish is GPLv3 (bundled unmodified as a WASM
build); `nn-*.nnue` network embedded in the engine.

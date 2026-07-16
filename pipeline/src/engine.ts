import { createRequire } from "node:module";
import type { EngineEval, PvLine } from "./types.js";

const require = createRequire(import.meta.url);

/**
 * Minimal UCI driver around the bundled single-threaded Stockfish 16 WASM
 * build (`stockfish` npm package). Requests are serialised — the engine holds
 * a single search at a time — so callers can `await analyse(...)` freely.
 *
 * The single-threaded build no-ops `postMessage` (it is meant for a Worker);
 * commands must be delivered through `onCustomMessage`, which is what
 * `#send` handles.
 */
export class Engine {
  #engine: any;
  #single = false;
  #listeners: Array<(line: string) => void> = [];
  #queue: Promise<unknown> = Promise.resolve();

  private constructor(engine: any) {
    this.#engine = engine;
    this.#single = !!(engine.__IS_SINGLE_THREADED__ || engine.__IS_NON_NESTED__);
    engine.addMessageListener((line: string) => {
      const s = String(line);
      for (const l of this.#listeners) l(s);
    });
  }

  static async create(): Promise<Engine> {
    const jsPath = require.resolve("stockfish/src/stockfish-nnue-16-single.js");
    const wasmPath = require.resolve("stockfish/src/stockfish-nnue-16-single.wasm");
    const moduleFactory = require(jsPath)();
    const instance = await moduleFactory({
      locateFile: (f: string) => (f.endsWith(".wasm") ? wasmPath : jsPath),
    });
    const engine = new Engine(instance);
    await engine.#handshake();
    return engine;
  }

  #send(cmd: string): void {
    if (this.#single) this.#engine.onCustomMessage(cmd);
    else this.#engine.postMessage(cmd);
  }

  /** Resolve once a line satisfying `pred` is emitted. */
  #await(pred: (line: string) => boolean, timeoutMs = 120_000): Promise<string> {
    return new Promise((resolve, reject) => {
      const listener = (line: string) => {
        if (pred(line)) {
          cleanup();
          resolve(line);
        }
      };
      const timer = setTimeout(() => {
        cleanup();
        reject(new Error("engine timeout waiting for response"));
      }, timeoutMs);
      const cleanup = () => {
        clearTimeout(timer);
        const i = this.#listeners.indexOf(listener);
        if (i >= 0) this.#listeners.splice(i, 1);
      };
      this.#listeners.push(listener);
    });
  }

  async #handshake(): Promise<void> {
    this.#send("uci");
    await this.#await((l) => l.includes("uciok"));
    this.#send("isready");
    await this.#await((l) => l.trim() === "readyok");
  }

  async setOption(name: string, value: string | number): Promise<void> {
    this.#send(`setoption name ${name} value ${value}`);
    this.#send("isready");
    await this.#await((l) => l.trim() === "readyok");
  }

  /**
   * Analyse a position given as a FEN. Returns the top `multiPv` engine lines
   * at the requested search depth. Calls are serialised internally.
   */
  analyse(fen: string, depth: number, multiPv: number): Promise<EngineEval> {
    const run = async (): Promise<EngineEval> => {
      // Track the deepest info line seen per multipv index.
      const best = new Map<number, ParsedInfo>();

      const collector = (line: string) => {
        if (!line.startsWith("info ") || !line.includes(" pv ")) return;
        const parsed = parseInfoLine(line);
        if (!parsed) return;
        const prev = best.get(parsed.multipv);
        if (!prev || parsed.depth >= prev.depth) best.set(parsed.multipv, parsed);
      };
      this.#listeners.push(collector);
      try {
        this.#send("ucinewgame");
        this.#send(`position fen ${fen}`);
        this.#send(`go depth ${depth}`);
        await this.#await((l) => l.startsWith("bestmove"));
      } finally {
        const i = this.#listeners.indexOf(collector);
        if (i >= 0) this.#listeners.splice(i, 1);
      }

      const lines: PvLine[] = [...best.values()]
        .sort((a, b) => a.multipvIndex - b.multipvIndex)
        .slice(0, multiPv)
        .map((p) => ({ uci: p.uci, san: p.san, cp: p.cp, mate: p.mate }));

      return { depth, lines };
    };

    // Chain onto the internal queue so only one search runs at a time.
    const result = this.#queue.then(run, run);
    this.#queue = result.catch(() => undefined);
    return result;
  }

  quit(): void {
    try {
      this.#send("quit");
    } catch {
      /* ignore */
    }
  }
}

interface ParsedInfo extends PvLine {
  multipv: number;
  multipvIndex: number;
  depth: number;
}

/** Parse a UCI `info ... pv <moves>` line. `san` is left as the UCI move. */
function parseInfoLine(line: string): ParsedInfo | null {
  const tokens = line.split(/\s+/);
  let depth = 0;
  let multipv = 1;
  let cp: number | null = null;
  let mate: number | null = null;
  let pvStart = -1;

  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (t === "depth") depth = Number(tokens[i + 1]);
    else if (t === "multipv") multipv = Number(tokens[i + 1]);
    else if (t === "score") {
      const kind = tokens[i + 1];
      const val = Number(tokens[i + 2]);
      if (kind === "cp") cp = val;
      else if (kind === "mate") mate = val;
    } else if (t === "pv") {
      pvStart = i + 1;
      break;
    }
  }
  if (pvStart < 0 || !tokens[pvStart]) return null;
  const firstMove = tokens[pvStart];
  return {
    multipv,
    multipvIndex: multipv,
    depth,
    uci: firstMove,
    san: firstMove, // filled in by the caller which has board context
    cp,
    mate,
  };
}

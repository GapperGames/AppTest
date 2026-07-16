import type { EngineEval, PvLine } from "../openings/types";

/**
 * Browser-side Stockfish 16 driver. Loads the single-threaded WASM build as a
 * classic Web Worker (served from public/stockfish). The worker build routes
 * `worker.postMessage(cmd)` into the engine and posts UCI output lines back,
 * so no SharedArrayBuffer / COOP-COEP headers are required — it runs in a plain
 * Android WebView.
 *
 * Searches are serialised: only one `analyse()` runs at a time.
 */
export class StockfishEngine {
  private worker: Worker;
  private listeners = new Set<(line: string) => void>();
  private queue: Promise<unknown> = Promise.resolve();
  private ready: Promise<void>;
  private multiPv = 0;

  constructor() {
    const jsUrl = new URL("stockfish/stockfish-nnue-16-single.js", document.baseURI).href;
    // The hash tells the worker where to fetch its .wasm from (resolved
    // relative to the worker script URL).
    this.worker = new Worker(`${jsUrl}#stockfish-nnue-16-single.wasm`);
    this.worker.onmessage = (e: MessageEvent) => {
      const line = String(e.data);
      for (const l of this.listeners) l(line);
    };
    this.ready = this.handshake();
  }

  private send(cmd: string): void {
    this.worker.postMessage(cmd);
  }

  private await(pred: (line: string) => boolean, timeoutMs = 60_000): Promise<string> {
    return new Promise((resolve, reject) => {
      const listener = (line: string) => {
        if (pred(line)) {
          cleanup();
          resolve(line);
        }
      };
      const timer = setTimeout(() => {
        cleanup();
        reject(new Error("engine timeout"));
      }, timeoutMs);
      const cleanup = () => {
        clearTimeout(timer);
        this.listeners.delete(listener);
      };
      this.listeners.add(listener);
    });
  }

  private async handshake(): Promise<void> {
    this.send("uci");
    await this.await((l) => l.includes("uciok"));
    this.send("isready");
    await this.await((l) => l.trim() === "readyok");
  }

  async waitReady(): Promise<void> {
    await this.ready;
  }

  private async ensureMultiPv(n: number): Promise<void> {
    if (this.multiPv === n) return;
    this.send(`setoption name MultiPV value ${n}`);
    this.send("isready");
    await this.await((l) => l.trim() === "readyok");
    this.multiPv = n;
  }

  /** Analyse a FEN to the given depth, returning the top `multiPv` lines. */
  analyse(fen: string, depth: number, multiPv = 3): Promise<EngineEval> {
    const run = async (): Promise<EngineEval> => {
      await this.ready;
      await this.ensureMultiPv(multiPv);
      const best = new Map<number, ParsedInfo>();
      const collector = (line: string) => {
        if (!line.startsWith("info ") || !line.includes(" pv ")) return;
        const parsed = parseInfoLine(line);
        if (!parsed) return;
        const prev = best.get(parsed.multipv);
        if (!prev || parsed.depth >= prev.depth) best.set(parsed.multipv, parsed);
      };
      this.listeners.add(collector);
      try {
        this.send("ucinewgame");
        this.send(`position fen ${fen}`);
        this.send(`go depth ${depth}`);
        await this.await((l) => l.startsWith("bestmove"));
      } finally {
        this.listeners.delete(collector);
      }
      const lines: PvLine[] = [...best.values()]
        .sort((a, b) => a.multipv - b.multipv)
        .map((p) => ({ uci: p.uci, san: p.uci, cp: p.cp, mate: p.mate }));
      return { depth, lines };
    };
    const result = this.queue.then(run, run);
    this.queue = result.catch(() => undefined);
    return result;
  }

  dispose(): void {
    try {
      this.send("quit");
    } catch {
      /* ignore */
    }
    this.worker.terminate();
  }
}

interface ParsedInfo {
  multipv: number;
  depth: number;
  uci: string;
  cp: number | null;
  mate: number | null;
}

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
  return { multipv, depth, uci: tokens[pvStart], cp, mate };
}

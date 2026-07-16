import { useEffect, useRef, useState } from "react";
import { StockfishEngine } from "./stockfish";

/** Load a single Stockfish engine for the app lifetime. */
export function useEngine(): { engine: StockfishEngine | null; ready: boolean; error: string | null } {
  const ref = useRef<StockfishEngine | null>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let disposed = false;
    let engine: StockfishEngine;
    try {
      engine = new StockfishEngine();
    } catch (e) {
      setError(`Could not start Stockfish: ${String(e)}`);
      return;
    }
    ref.current = engine;
    engine
      .waitReady()
      .then(() => {
        if (!disposed) setReady(true);
      })
      .catch((e) => {
        if (!disposed) setError(`Engine failed to initialise: ${String(e)}`);
      });
    return () => {
      disposed = true;
      engine.dispose();
      ref.current = null;
    };
  }, []);

  return { engine: ref.current, ready, error };
}

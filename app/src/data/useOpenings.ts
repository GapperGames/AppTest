import { useEffect, useState } from "react";
import type { OpeningsData } from "../openings/types";
import { loadStoredOpenings } from "./openingsStore";

/**
 * Provide the active openings tree: a tree the user rebuilt from their own
 * chess.com games (stored on-device) takes precedence; otherwise the bundled
 * sample shipped in public/openings.json.
 */
export function useOpenings(): {
  data: OpeningsData | null;
  error: string | null;
  isCustom: boolean;
  setData: (d: OpeningsData) => void;
} {
  const [data, setData] = useState<OpeningsData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isCustom, setIsCustom] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const stored = loadStoredOpenings();
    if (stored) {
      setData(stored);
      setIsCustom(true);
      return;
    }
    fetch(`${import.meta.env.BASE_URL}openings.json`)
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then((json: OpeningsData) => {
        if (!cancelled) setData(json);
      })
      .catch((e) => {
        if (!cancelled) setError(`Could not load openings.json: ${String(e)}`);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return {
    data,
    error,
    isCustom,
    setData: (d: OpeningsData) => {
      setData(d);
      setIsCustom(true);
    },
  };
}

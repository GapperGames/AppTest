import { useEffect, useState } from "react";
import type { OpeningsData } from "../openings/types";

/** Load the pre-computed openings tree emitted by the pipeline. */
export function useOpenings(): { data: OpeningsData | null; error: string | null } {
  const [data, setData] = useState<OpeningsData | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
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

  return { data, error };
}

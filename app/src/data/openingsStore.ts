import type { OpeningsData } from "../openings/types";

const KEY = "opening-reflex:openings:v1";

/** Persist a rebuilt openings tree on-device. Returns false on quota failure. */
export function saveOpenings(data: OpeningsData): boolean {
  try {
    localStorage.setItem(KEY, JSON.stringify(data));
    return true;
  } catch {
    return false;
  }
}

export function loadStoredOpenings(): OpeningsData | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    return JSON.parse(raw) as OpeningsData;
  } catch {
    return null;
  }
}

export function clearStoredOpenings(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* ignore */
  }
}

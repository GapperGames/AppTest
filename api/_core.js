// Host-agnostic request handling.
//
// Returns plain {status, body} so the same logic runs behind any host's
// function signature. Netlify and Vercel adapters are thin wrappers over this.

import { search, song, fetchStore, UGError } from "./_ug.js";

function failure(e) {
  if (e instanceof UGError) {
    const status =
      e.code === "blocked" ? 503 :
      e.code === "not_found" ? 404 :
      e.code === "bad_url" ? 400 : 502;
    return { status, body: { error: e.code, message: e.message, detail: e.detail } };
  }
  return {
    status: 500,
    body: {
      error: "internal",
      message: "Something went wrong inside the app.",
      detail: String(e && e.message),
    },
  };
}

export async function handleSearch(params) {
  const q = (params.q || "").trim();
  if (q.length < 2) {
    return { status: 400, body: { error: "short", message: "Type at least two characters." } };
  }
  const page = Math.max(1, parseInt(params.page, 10) || 1);
  try {
    return { status: 200, body: { query: q, page, results: await search(q, page) } };
  } catch (e) {
    return failure(e);
  }
}

export async function handleSong(params) {
  const url = (params.url || "").trim();
  if (!url) {
    return { status: 400, body: { error: "missing", message: "No song link given." } };
  }
  try {
    return { status: 200, body: await song(url) };
  } catch (e) {
    return failure(e);
  }
}

// Self-check. With no laptop and no console, this is the only way to tell
// "Ultimate Guitar is blocking us" apart from "the scraper needs updating".
export async function handleHealth() {
  const started = Date.now();
  const checks = [];

  const t0 = Date.now();
  try {
    const store = await fetchStore(
      "https://www.ultimate-guitar.com/search.php?search_type=title&value=test&type=300"
    );
    const d = store?.store?.page?.data || store?.page?.data;
    const note = !d
      ? "no page data in the store — Ultimate Guitar changed their layout"
      : !Array.isArray(d.results)
      ? "no `results` array — the search page layout changed"
      : null;
    checks.push({
      name: "search page",
      ok: !note,
      ms: Date.now() - t0,
      note: note || "data block found and readable",
    });
  } catch (e) {
    checks.push({
      name: "search page",
      ok: false,
      ms: Date.now() - t0,
      code: e instanceof UGError ? e.code : "internal",
      note: e.message,
      detail: e && e.detail,
    });
  }

  const ok = checks.every((c) => c.ok);
  return {
    status: ok ? 200 : 503,
    body: {
      ok,
      summary: ok
        ? "Ultimate Guitar is reachable and the scraper understands the page."
        : checks.find((c) => !c.ok)?.note || "Something failed.",
      checks,
      tookMs: Date.now() - started,
      node: process.version,
    },
  };
}

export const HANDLERS = {
  "/api/search": handleSearch,
  "/api/song": handleSong,
  "/api/health": handleHealth,
};

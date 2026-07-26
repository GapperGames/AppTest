import { fetchStore, UGError } from "./_ug.js";

// Self-check. With no laptop and no console, this is the only way to tell
// "Ultimate Guitar is blocking us" apart from "the scraper needs updating".
// The app links here from any error screen.
export default async function handler(req, res) {
  res.setHeader("cache-control", "no-store");

  const started = Date.now();
  const checks = [];

  async function probe(name, url, verify) {
    const t0 = Date.now();
    try {
      const store = await fetchStore(url);
      const note = verify ? verify(store) : null;
      checks.push({
        name,
        ok: !note,
        ms: Date.now() - t0,
        note: note || "data block found and readable",
      });
    } catch (e) {
      checks.push({
        name,
        ok: false,
        ms: Date.now() - t0,
        code: e instanceof UGError ? e.code : "internal",
        note: e.message,
        detail: e && e.detail,
      });
    }
  }

  await probe(
    "search page",
    "https://www.ultimate-guitar.com/search.php?search_type=title&value=test&type=300",
    (s) => {
      const d = (s.store?.page?.data) || s.page?.data;
      if (!d) return "no page data in the store — layout changed";
      if (!Array.isArray(d.results)) return "no `results` array — search layout changed";
      return null;
    }
  );

  const ok = checks.every((c) => c.ok);

  res.status(ok ? 200 : 503).json({
    ok,
    summary: ok
      ? "Ultimate Guitar is reachable and the scraper understands the page."
      : checks.find((c) => !c.ok)?.note || "Something failed.",
    checks,
    tookMs: Date.now() - started,
    node: process.version,
  });
}

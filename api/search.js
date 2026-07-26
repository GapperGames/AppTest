import { search, UGError } from "./_ug.js";

export default async function handler(req, res) {
  // Live every time — nothing about a search is stored or reused.
  res.setHeader("cache-control", "no-store");

  const q = (req.query.q || "").trim();
  const page = Math.max(1, parseInt(req.query.page, 10) || 1);

  if (q.length < 2) {
    res.status(400).json({ error: "short", message: "Type at least two characters." });
    return;
  }

  try {
    const results = await search(q, page);
    res.status(200).json({ query: q, page, results });
  } catch (e) {
    if (e instanceof UGError) {
      res.status(e.code === "blocked" ? 503 : 502)
         .json({ error: e.code, message: e.message, detail: e.detail });
      return;
    }
    res.status(500).json({
      error: "internal",
      message: "Something went wrong inside the app.",
      detail: String(e && e.message),
    });
  }
}

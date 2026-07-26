import { song, UGError } from "./_ug.js";

export default async function handler(req, res) {
  // Scraped fresh on every open, by design — no stored copy of anything.
  res.setHeader("cache-control", "no-store");

  const url = (req.query.url || "").trim();
  if (!url) {
    res.status(400).json({ error: "missing", message: "No song link given." });
    return;
  }

  try {
    res.status(200).json(await song(url));
  } catch (e) {
    if (e instanceof UGError) {
      const status =
        e.code === "blocked" ? 503 :
        e.code === "not_found" ? 404 :
        e.code === "bad_url" ? 400 : 502;
      res.status(status).json({ error: e.code, message: e.message, detail: e.detail });
      return;
    }
    res.status(500).json({
      error: "internal",
      message: "Something went wrong inside the app.",
      detail: String(e && e.message),
    });
  }
}

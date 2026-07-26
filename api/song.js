// Vercel adapter.
import { handleSong } from "./_core.js";

export default async function handler(req, res) {
  // Scraped fresh on every open, by design — no stored copy of anything.
  res.setHeader("cache-control", "no-store");
  const { status, body } = await handleSong(req.query || {});
  res.status(status).json(body);
}

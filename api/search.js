// Vercel adapter.
import { handleSearch } from "./_core.js";

export default async function handler(req, res) {
  // Live every time — nothing about a search is stored or reused.
  res.setHeader("cache-control", "no-store");
  const { status, body } = await handleSearch(req.query || {});
  res.status(status).json(body);
}

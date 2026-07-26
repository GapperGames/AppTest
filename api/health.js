// Vercel adapter.
import { handleHealth } from "./_core.js";

export default async function handler(req, res) {
  res.setHeader("cache-control", "no-store");
  const { status, body } = await handleHealth();
  res.status(status).json(body);
}

// Netlify adapter. All three endpoints in one function — Netlify v2 routes by
// the `path` config below, and the real work lives in api/_core.js.

import { HANDLERS } from "../../api/_core.js";

export default async (req) => {
  const url = new URL(req.url);
  const handler = HANDLERS[url.pathname];

  if (!handler) {
    return Response.json({ error: "not_found", message: "No such endpoint." }, { status: 404 });
  }

  const { status, body } = await handler(Object.fromEntries(url.searchParams));

  // Nothing from Ultimate Guitar is ever stored or reused.
  return Response.json(body, {
    status,
    headers: { "cache-control": "no-store" },
  });
};

export const config = {
  path: ["/api/search", "/api/song", "/api/health"],
};

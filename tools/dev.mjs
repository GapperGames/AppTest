// Local dev server — same routes Vercel serves. Run: node tools/dev.mjs
// Only needed if you ever get a computer; the deployed app doesn't use it.

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname } from "node:path";

const PORT = process.env.PORT || 3000;

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".png": "image/png",
};

const ROUTES = { "/api/search": "search", "/api/song": "song", "/api/health": "health" };

createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const path = url.pathname;

  if (ROUTES[path]) {
    const mod = await import("../api/" + ROUTES[path] + ".js");
    // Minimal shim for the bits of Vercel's req/res the handlers use.
    req.query = Object.fromEntries(url.searchParams);
    res.status = (c) => { res.statusCode = c; return res; };
    res.json = (o) => {
      res.setHeader("content-type", "application/json; charset=utf-8");
      res.end(JSON.stringify(o, null, 2));
    };
    try {
      await mod.default(req, res);
    } catch (e) {
      res.statusCode = 500;
      res.end(JSON.stringify({ error: "internal", message: String(e && e.message) }));
    }
    return;
  }

  const file = path === "/" ? "index.html" : path.replace(/^\//, "");
  try {
    const body = await readFile(file);
    res.setHeader("content-type", TYPES[extname(file)] || "application/octet-stream");
    res.end(body);
  } catch {
    res.statusCode = 404;
    res.end("not found");
  }
}).listen(PORT, () => console.log("http://localhost:" + PORT));

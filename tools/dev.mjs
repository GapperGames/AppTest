// Local dev server — same routes either host serves. Run: node tools/dev.mjs
// Only needed if you ever have a computer; the deployed app doesn't use it.

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname } from "node:path";
import { HANDLERS } from "../api/_core.js";

const PORT = process.env.PORT || 3000;

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".png": "image/png",
};

createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const handler = HANDLERS[url.pathname];

  if (handler) {
    const { status, body } = await handler(Object.fromEntries(url.searchParams));
    res.writeHead(status, {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    });
    res.end(JSON.stringify(body, null, 2));
    return;
  }

  const file = url.pathname === "/" ? "index.html" : url.pathname.replace(/^\//, "");
  try {
    const data = await readFile(file);
    res.writeHead(200, { "content-type": TYPES[extname(file)] || "application/octet-stream" });
    res.end(data);
  } catch {
    res.writeHead(404).end("not found");
  }
}).listen(PORT, () => console.log("http://localhost:" + PORT));

// Caches the app shell so the icon opens instantly and survives a dead signal.
//
// It deliberately does NOT cache anything from /api/ — no song, no search
// result, no chords are ever stored on the device. Every song is scraped live.

const SHELL = "shell-v1";
const FILES = ["/", "/index.html", "/manifest.webmanifest", "/icon-192.png", "/icon-512.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);

  // Anything from Ultimate Guitar goes straight to the network, always.
  if (url.pathname.startsWith("/api/")) return;
  if (e.request.method !== "GET" || url.origin !== location.origin) return;

  // Shell: network first so a deploy lands immediately, cache as the fallback.
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(SHELL).then((c) => c.put(e.request, copy)).catch(() => {});
        return res;
      })
      .catch(() => caches.match(e.request).then((hit) => hit || caches.match("/index.html")))
  );
});

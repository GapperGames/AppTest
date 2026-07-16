import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Relative base so the built assets load under Capacitor's file/localhost origin
// as well as from a web host.
export default defineConfig({
  base: "./",
  // Cast avoids a spurious type clash between vitest's bundled vite and the
  // top-level vite when both defineConfig overloads are in scope.
  plugins: [react() as never],
  server: { port: 5173 },
  build: {
    outDir: "dist",
    // The Stockfish WASM engine is served from public/ and loaded as a Worker
    // at runtime, so it never enters the bundle.
    assetsInlineLimit: 0,
  },
  test: {
    environment: "jsdom",
    globals: true,
    include: ["src/**/*.test.{ts,tsx}"],
  },
});

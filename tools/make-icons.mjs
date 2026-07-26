// Generates the PWA icons. Run: node tools/make-icons.mjs
// Motif is a chord chart — amber chord marks sitting above lyric lines.

import { deflateSync } from "node:zlib";
import { writeFileSync, mkdirSync } from "node:fs";

const GROUND = [0x17, 0x14, 0x0f];
const CHORD  = [0xe8, 0xa3, 0x3d];
const LYRIC  = [0x9a, 0x90, 0x81];

const crcTable = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function png(size, px) {
  // RGBA scanlines, filter byte 0 per row.
  const raw = Buffer.alloc(size * (size * 4 + 1));
  let o = 0;
  for (let y = 0; y < size; y++) {
    raw[o++] = 0;
    for (let x = 0; x < size; x++) {
      const [r, g, b, a] = px(x, y);
      raw[o++] = r; raw[o++] = g; raw[o++] = b; raw[o++] = a;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;    // bit depth
  ihdr[9] = 6;    // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

// Chord chart laid out in a 0..1 square: three rows, each an amber chord mark
// above a muted lyric line.
const ROWS = [
  { chords: [[0.20, 0.13], [0.46, 0.10]],              lyric: [0.20, 0.60] },
  { chords: [[0.20, 0.10], [0.40, 0.14], [0.62, 0.09]], lyric: [0.20, 0.52] },
  { chords: [[0.20, 0.14]],                             lyric: [0.20, 0.44] },
];

function motif(u, v, inset) {
  // u,v in 0..1 across the art area
  const top = 0.20, rowH = 0.215, chordH = 0.055, lyricH = 0.048, gap = 0.088;
  for (let i = 0; i < ROWS.length; i++) {
    const y0 = top + i * rowH;
    const row = ROWS[i];
    if (v >= y0 && v <= y0 + chordH) {
      for (const [x, w] of row.chords) if (u >= x && u <= x + w) return CHORD;
    }
    const ly = y0 + gap;
    if (v >= ly && v <= ly + lyricH) {
      const [x, w] = row.lyric;
      if (u >= x && u <= x + w) return LYRIC;
    }
  }
  return null;
}

function rounded(size, radiusFrac, art) {
  const r = size * radiusFrac;
  return (x, y) => {
    const px = x + 0.5, py = y + 0.5;
    // rounded-square coverage
    let inside = true;
    const cx = Math.min(Math.max(px, r), size - r);
    const cy = Math.min(Math.max(py, r), size - r);
    const dx = px - cx, dy = py - cy;
    if (dx || dy) inside = dx * dx + dy * dy <= r * r;
    if (!inside) return [0, 0, 0, 0];
    const c = art(px / size, py / size);
    return [c[0], c[1], c[2], 255];
  };
}

function artFor(inset) {
  return (u, v) => {
    // map the full square into an inset art area
    const a = (u - inset) / (1 - 2 * inset);
    const b = (v - inset) / (1 - 2 * inset);
    if (a < 0 || a > 1 || b < 0 || b > 1) return GROUND;
    return motif(a, b) || GROUND;
  };
}



writeFileSync("icon-192.png", png(192, rounded(192, 0.22, artFor(0.02))));
writeFileSync("icon-512.png", png(512, rounded(512, 0.22, artFor(0.02))));
// Maskable: full bleed, motif pulled into the safe zone so Android can crop it.
writeFileSync("icon-maskable.png", png(512, (x, y) => {
  const c = artFor(0.16)((x + 0.5) / 512, (y + 0.5) / 512);
  return [c[0], c[1], c[2], 255];
}));

console.log("wrote icon-192.png, icon-512.png, icon-maskable.png");

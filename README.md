# Chords

A personal mobile web app for reading song chords and lyrics — in the key you want, with
free auto-scroll and no ads.

**Status:** planning. Tech pass and design pass complete, no implementation yet.

## Documents

- **[docs/tech-pass.md](docs/tech-pass.md)** — what data is actually available, what isn't,
  and the constraints that decide the architecture
- **[docs/design-pass.md](docs/design-pass.md)** — visual direction, layout, interaction rules
- **[docs/mockup/song-view.html](docs/mockup/song-view.html)** — working prototype of the
  song view; transpose, capo-folding, chord diagrams and auto-scroll all run for real

## Headlines

- Chords, lyrics, key, capo, tuning and chord fingerings all come from one Ultimate Guitar
  payload. **BPM and duration do not exist in it** and need a second source.
- Spotify's `/audio-features` — the obvious source for BPM — was deprecated in November 2024
  and is unavailable to new apps. iTunes and Deezer are the replacements.
- UG sits behind Cloudflare, so the phone can't call it directly. A small caching scrape
  service is required, not optional.
- Target is a **PWA**, not native — iOS sideloading needs re-signing every 7 days, and this
  app won't pass App Store review.

## Scope

Private, personal use. Ultimate Guitar's terms prohibit scraping, so this isn't distributed,
published to an app store, or hosted on an open URL.

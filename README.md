# Chords

Search a song, read its chords and lyrics, put it in whatever key you want. No ads.

Personal use. Ultimate Guitar's terms prohibit scraping, so this isn't distributed,
published to an app store, or hosted on a public URL.

---

## Getting it on your phone

You need no computer for this — all of it works in the Android browser. It takes about
three minutes.

**1 — Deploy it**

1. Open **[vercel.com/new](https://vercel.com/new)** in Chrome on your phone
2. Tap **Continue with GitHub** and authorise it
3. Find **GapperGames/AppTest** in the list and tap **Import**
4. Change nothing. Tap **Deploy**
5. Wait for the build, then tap the preview to open your app

You'll get a URL like `apptest-xyz.vercel.app`. That's the app.

**2 — Add it to your home screen**

In Chrome, tap **⋮** → **Add to Home screen**. It then opens fullscreen with no browser
bar, like an installed app.

**3 — Check it works**

Search for a song. If something's wrong, every error screen has a **Run a check** button
that tells you exactly what failed and why. You can also open `/api/health` directly.

### One thing that may need changing

Vercel deploys whatever your **default branch** is. This code is currently on
`claude/music-chords-webapp-dt8dai`. If the import deploys an empty site, either merge that
branch into `master` on github.com (works fine on a phone), or in Vercel go to
**Settings → Git → Production Branch** and set it to `claude/music-chords-webapp-dt8dai`.

### The one real risk

Ultimate Guitar sits behind Cloudflare bot protection. Requests from a data centre are more
likely to be challenged than requests from a phone, so there's a genuine chance Vercel's
servers get blocked where your phone wouldn't be.

**This hasn't been tested against the live site** — the environment this was built in blocks
ultimate-guitar.com, so the scraper has never seen a real UG response. If it is blocked,
`/api/health` will say so in plain words rather than leaving you guessing. The fix is
redeploying the same code to Cloudflare Workers, which tends to fare better.

---

## What it does

- Search by song or artist — chord sheets only, no tabs or Pro versions
- Versions grouped per song, best-rated first, so it's one tap to open
- Title, artist, key, capo, tuning, difficulty
- **Transpose** by semitone, with slash chords handled properly
- **Capo fold** — one tap shows what actually *sounds* rather than the shape you play
- Text size control
- Installs to the home screen, works offline for the app itself

## What it deliberately doesn't do

Cut because they'd have to be faked or bolted on, and a missing feature beats a hacked one:

- **BPM and duration** — not in UG's data. They'd need fuzzy-matching a second music API,
  which silently returns the wrong song often enough to make the number untrustworthy.
- **Auto-scroll** — cut with them.
- **Chord diagrams, fingerings, tablature** — out of scope; this is for reading chords
  and lyrics.

## Nothing is stored

Every search and every song is scraped from Ultimate Guitar live, at the moment you ask
for it. There's no database, no cached copy of any song, and no bundled data. The service
worker caches only the app's own shell — HTML, CSS, icons — so the icon opens instantly;
it explicitly skips everything under `/api/`.

The only thing kept on your phone is your text-size preference.

---

## Layout

```
index.html            the whole UI
chart.js              parsing, transposition, chord alignment (pure, tested)
sw.js                 caches the app shell, never song data
api/_ug.js            the only file that knows what UG's markup looks like
api/search.js         GET /api/search?q=
api/song.js           GET /api/song?url=
api/health.js         GET /api/health — self-diagnosis
tools/test.mjs        node tools/test.mjs
tools/dev.mjs         local server, if you ever have a computer
tools/make-icons.mjs  regenerates the PNG icons
docs/                 tech pass and design pass
```

## Notes for later

- **`api/_ug.js` is the fragile part.** Everything that depends on UG's page structure lives
  there, so when they change their markup it's a one-file fix. `/api/health` reports
  "the scraper is out of date" specifically to distinguish this from being blocked.
- `node tools/test.mjs` runs 22 tests over the transposition and parsing logic.
- Auto-scroll would be roughly 30 lines and needs no external data — it was cut by choice,
  not by constraint, and can come back whenever.

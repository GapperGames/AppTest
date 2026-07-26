# Chords

Search a song, read its chords and lyrics, put it in whatever key you want. No ads.

Personal use. Ultimate Guitar's terms prohibit scraping, so this isn't distributed,
published to an app store, or hosted on a public URL.

---

## Getting it on your phone

No computer needed — all of it works in the Android browser, in about three minutes.
The repo is configured for **Netlify** and **Vercel**; either works from the same code.

### Netlify (recommended)

1. Open **[app.netlify.com/start](https://app.netlify.com/start)** in Chrome
2. Tap **GitHub** and authorise it
3. Pick **GapperGames/AppTest**
4. On the setup screen, set **Branch to deploy** → `claude/music-chords-webapp-dt8dai`
   *(leave build command and publish directory empty — `netlify.toml` handles them)*
5. Tap **Deploy**

You'll get a URL like `random-name-123.netlify.app`. That's the app. You can rename it
under **Site configuration → Change site name** if you want something less silly.

Netlify is the recommendation because signup is a GitHub tap with no card, the free tier is
generous for one person, and you can pick the branch during setup rather than digging
through settings afterwards.

### Vercel

Same idea: **[vercel.com/new](https://vercel.com/new)** → Continue with GitHub → import the
repo → Deploy. It deploys your **default branch** though, so afterwards go to
**Settings → Git → Production Branch**, set it to `claude/music-chords-webapp-dt8dai`, and
redeploy. That extra step is why Netlify is listed first.

### Then: add it to your home screen

In Chrome, tap **⋮** → **Add to Home screen**. It opens fullscreen with no browser bar,
like an installed app.

### Then: check it works

Search for a song. If something's wrong, every error screen has a **Run a check** button
that says exactly what failed and why. You can also open `/api/health` directly.

### The one real risk

Ultimate Guitar sits behind Cloudflare bot protection. Requests from a data centre are more
likely to be challenged than requests from a phone, so there's a genuine chance the host's
servers get blocked where your phone wouldn't be.

**This hasn't been tested against the live site** — the environment it was built in blocks
ultimate-guitar.com, so the scraper has never seen a real UG response. If it is blocked,
`/api/health` says so in plain words rather than leaving you guessing. The fix is
redeploying to a different host; the logic is host-agnostic, so it's a config change and
not a rewrite.

### On safety

Both hosts are mainstream and free at this scale, with no card required. There is nothing
to leak: the app holds no accounts, no passwords, no personal data, and no database. Keep
the URL to yourself — anyone who has it can use it, which is the only real exposure, and
the reason not to put the link anywhere public.

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
index.html              the whole UI
chart.js                parsing, transposition, chord alignment (pure, tested)
sw.js                   caches the app shell, never song data

api/_ug.js              the only file that knows what UG's markup looks like
api/_core.js            the three endpoints, host-agnostic
api/{search,song,health}.js   Vercel adapters (a few lines each)
netlify/functions/api.mjs     Netlify adapter (a few lines)
netlify.toml            Netlify config; Vercel needs none

tools/test.mjs          node tools/test.mjs
tools/dev.mjs           local server, if you ever have a computer
tools/make-icons.mjs    regenerates the PNG icons
docs/                   tech pass and design pass
```

The endpoints are written once in `api/_core.js` and returned as plain
`{status, body}`. Each host adapter just translates that into its own response
type — so moving hosts means adding a five-line adapter, never touching the logic.

## Notes for later

- **`api/_ug.js` is the fragile part.** Everything that depends on UG's page structure lives
  there, so when they change their markup it's a one-file fix. `/api/health` reports
  "the scraper is out of date" specifically to distinguish this from being blocked.
- `node tools/test.mjs` runs 22 tests over the transposition and parsing logic.
- Auto-scroll would be roughly 30 lines and needs no external data — it was cut by choice,
  not by constraint, and can come back whenever.

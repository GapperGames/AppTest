# Tech Pass — Chords app

Goal: a personal mobile app that shows a song's chords + lyrics from Ultimate Guitar,
in the key *you* want, with free auto-scroll and no ads.

This document answers one question first — **what data can we actually get?** — because
two of the four requested metadata fields turn out not to exist where we assumed.

---

## 1. What UG actually returns

UG's page/mobile API returns a single JSON object per tab. Schema below is taken from
the reverse-engineered client type definitions
([Pilfer/ultimate-guitar-scraper](https://github.com/Pilfer/ultimate-guitar-scraper),
`pkg/ultimateguitar/types.go`), cross-checked against the maintained
[`ultimate-guitar`](https://www.npmjs.com/package/ultimate-guitar) npm scraper.

> ⚠️ **Not live-verified.** The sandbox this was researched in blocks
> `ultimate-guitar.com` at the network proxy (403 on CONNECT), so no live payload could
> be fetched. The schema is from the client that talks to that API, not from a response.
> **First implementation step must be to dump one real payload and diff it against this
> table.**

### Available ✅

| Field | JSON path | Notes |
|---|---|---|
| Title | `song_name` | |
| Artist | `artist_name` | |
| **Chords + lyrics** | `content` | Body text with `[ch]G[/ch]` chord markers and `[tab]…[/tab]` blocks. This is the whole song. |
| **Chord diagrams** | `applicature[]` | Per chord: `frets`, `fingers`, `notes`, `noteIndex`, `listCapos`, plus multiple `variations`. Big win — we get renderable fingering charts for free, no separate chord database. |
| Written key | `tonality_name` | The key the *transcription* is in. See §2 — this is not the "original key". |
| Recording key | `recording.tonality_name` | Present sometimes. Closer to the actual record. |
| Capo | `capo` | Integer fret. Critical for key maths, see §2. |
| Tuning | `tuning` | e.g. `E A D G B E` |
| Difficulty | `difficulty` | |
| Transpose offset | `transpose` | UG tracks a semitone offset itself — confirms UG *is* shifting things. |
| Other versions | `versions[]` | Every alternate transcription of the same song, each with its own rating/key/capo. |
| Rating / votes | `rating`, `votes` | Use to auto-select the best version instead of making the user choose. |
| Access type | `tab_access_type` | Flags Pro/paywalled tabs so we can skip them. |
| Contributor | `contributor` | |

### Not available ❌

| Field | Reality |
|---|---|
| **BPM** | Not in the free chords payload at all. UG only has tempo inside Tab Pro playback data. |
| **Duration** | Not present in any form. |
| **"Original key"** | No such field exists. See below. |

**So two of the four requested info fields (BPM, duration) require a second data source,
and the third (original key) requires computing rather than reading.**

---

## 2. The key problem is not the problem you think it is

The brief says UG "displays chords in a guitar-friendly key with no option to transpose."
Half right, and the diagnosis matters because it changes the fix.

UG tabs are **user-submitted transcriptions**. There is no canonical key stored anywhere.
Three different keys are in play:

1. **Written key** (`tonality_name`) — the chord shapes the contributor typed.
2. **Sounding key** — what actually comes out, = written key transposed up by `capo` frets.
3. **Record key** — what the actual recording is in.

Contributors routinely write in easy shapes (G, C, D, Em) and put a capo on to reach the
record. So a tab showing `G` with `capo 3` is really sounding in **B♭**. UG shows you the
G and buries the capo. That's the actual source of the "wrong key" feeling — it's a
*capo display* problem as much as a transposition one.

**This is entirely solvable client-side and needs no external data:**

- `content` marks every chord explicitly with `[ch]…[/ch]`. Parse them, map to
  semitones, shift. Transposition is deterministic and exact — including slash chords
  (`G/B` → both halves shift) which UG's own tooling handles inconsistently.
- Sounding key = written + capo. So we can offer a **"concert pitch" toggle** that
  rewrites the chords to what's actually sounding, and a **transpose stepper** on top.
- Display should show the delta honestly, e.g. `G (capo 3) → sounds in B♭`.

Getting the *record's* key from an external API is a nice-to-have for showing "the record
is in B♭, this tab is in G, tap to match" — but the transpose control alone solves the
user-facing complaint. **Ship transpose first; treat record-key lookup as optional.**

---

## 3. BPM and duration need a second source — and the obvious one is dead

The default answer here would have been Spotify's `/audio-features` (tempo, key,
duration in one call). **That endpoint was deprecated on 27 November 2024.** New apps get
403 immediately; only apps with a quota extension pending before that date still work, and
there is still no official replacement. So it is not an option for a project starting now.

Viable replacements:

| Source | Gives | Auth | Notes |
|---|---|---|---|
| **iTunes Search API** | duration (`trackTimeMillis`), artwork | none | Free, no key, no signup. Best duration source. |
| **Deezer public API** | duration, `bpm`, artwork | none | Free, no key. BPM coverage is patchy but it's free and unauthenticated. |
| **GetSongBPM** | BPM, key | API key | Free, 3000 req/hr, no OAuth. **Requires a visible backlink to getsongbpm.com or they suspend the account** — fine for personal use, just has to actually be in the UI. |
| MusicBrainz / AcoustID | duration, metadata | none (UA required) | Rate-limited to 1 req/sec. Good fallback. |

**Recommendation:** iTunes for duration + artwork (zero friction), Deezer for BPM with
GetSongBPM as fallback. Both lookups are fuzzy artist+title matches, so they will
sometimes miss or mismatch — **the UI must tolerate BPM and duration being absent.** Design
for it rather than treating it as an error state.

---

## 4. The constraint that decides the architecture

**UG is behind Cloudflare bot protection.** The maintained npm scraper had to replace
`axios` with `got-scraping` specifically to get past it, and states bot-protection bypass
as a headline feature.

Consequences, in order of importance:

1. **The phone cannot call UG directly.** A fetch from mobile Safari/Chrome gets
   challenged, and CORS blocks it regardless. There is no way around this client-side.
2. **Therefore a small server component is mandatory**, not a design preference. It
   scrapes, normalizes, and caches.
3. **Cache aggressively.** A personal app that hits UG on every screen view will get IP-
   blocked. Songs are static — cache the parsed tab indefinitely, keyed by tab ID.
4. Scraping is brittle by nature. Isolate all UG-shaped parsing behind one module with a
   normalized output type, so a UG HTML/API change is a one-file fix.

**Legal framing, stated once:** UG's ToS prohibits scraping. This is fine as the private
personal tool described in the brief. It does mean: don't publish it to an app store,
don't distribute it, don't put it on a public URL without auth. That constraint happens to
line up with the platform recommendation below.

---

## 5. Platform recommendation: PWA, not native

Requirement is Android **and** iOS, personal use, no store distribution.

**Recommend a React PWA**, installed to the home screen on both.

Why not React Native / Flutter:
- iOS is the blocker. Personal-use native apps mean sideloading with a free Apple
  developer account, which **expires every 7 days** and needs re-signing from a Mac.
  TestFlight requires App Store review, which this app will not pass.
- A PWA installs to the home screen on both platforms with zero store involvement,
  updates instantly, and is one codebase.

Everything this app needs is available in mobile browsers now:
- **Screen Wake Lock API** (Safari 16.4+, Chrome) — stops the screen sleeping mid-song.
  This is essential and is the one API people assume is missing.
- Smooth `requestAnimationFrame` scrolling.
- Service worker for offline caching — songs you've opened work without signal.

Honest downsides: iOS can evict PWA storage under pressure, and there's no background
execution. Neither matters for this use case. If native is ever wanted, Capacitor wraps
the same web app without a rewrite.

### Shape

```
[ React PWA ]  ──HTTPS──>  [ Node/Fastify service ]  ──> UG (got-scraping)
  home screen                 SQLite cache               iTunes / Deezer
  wake lock                   normalize + parse
  transpose (client)          fuzzy metadata match
  autoscroll (client)
```

Transposition and autoscroll run **entirely client-side** — no round trip when you tap
`+1`. The server is only a scraping/caching proxy.

Server can live on a Raspberry Pi on the home network, or a free tier (Fly.io / Railway).
Put a single shared secret on it so it isn't an open UG proxy.

### Normalized tab type

Everything above collapses to one interface the frontend consumes:

```ts
interface Song {
  id: string;
  title: string;
  artist: string;
  sections: Section[];        // parsed from `content`
  writtenKey: string | null;  // tonality_name
  recordKey: string | null;   // recording.tonality_name — often null
  capo: number;               // 0 = none
  tuning: string | null;
  difficulty: string | null;
  bpm: number | null;         // external, often null
  durationMs: number | null;  // external, often null
  chords: Record<string, ChordShape[]>;  // from applicature
  versions: VersionRef[];
}
```

Note how many fields are `| null`. That is the honest shape of this data and the UI has to
be designed around it, not against it.

---

## 6. Auto-scroll: where we can beat UG outright

UG paywalls auto-scroll and its implementation is a plain fixed-speed scroll. We can do
better cheaply:

- `requestAnimationFrame` with **sub-pixel accumulation** (accumulate fractional pixels,
  scroll on whole ones) — avoids the visible judder of `scrollBy(1)` on an interval.
- Speed in **px/sec**, not an opaque 1–10 dial.
- **Persist speed per song.** You find the right speed once and it's remembered.
- **If duration is known**, offer *fit-to-song*: total scroll distance ÷ track duration, so
  the song ends exactly as the last line reaches the top. This is the genuinely better
  feature, and it's the payoff for bothering with the duration lookup.
- Wake lock held while scrolling, released on pause.

---

## 7. Open items before building

1. **Dump one real UG payload** and diff against §1. Everything downstream depends on it.
   Must be done on a machine that can reach UG — not in this sandbox.
2. Confirm `content` markup covers section headers (`[Verse]`, `[Chorus]`) consistently —
   affects section parsing and jump-to-section.
3. Check `applicature` coverage for unusual chords.
4. Measure how often the iTunes/Deezer fuzzy match is wrong. If it's bad, drop BPM rather
   than show a wrong number.

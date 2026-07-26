# Design Pass — Chords app

Read [`tech-pass.md`](./tech-pass.md) first, including the scope box at the top. This
describes what was actually built.

---

## What the design has to do

One thing, well: **get a chord sheet in front of someone holding a guitar, in the key they
want.** Everything below follows from that posture — you are standing up, an instrument is
in your hands, and you have one thumb and about a second.

---

## Visual direction

The reference is **a chord chart on a music stand under a warm lamp**, not a code editor.

| | |
|---|---|
| **Ground** | `#17140F` — warm brown-black. Dark by default because you play in dim rooms; warm because blue-black reads as "developer tool". |
| **Text** | `#EDE6D8` warm off-white, `#9A9081` muted |
| **Chords** | `#E8A33D` amber — the one saturated colour on the page, spent entirely on the thing your eye hunts for |
| **Errors** | `#D97A6C` — a warm clay red that belongs to the same family rather than a system red dropped in |
| **Light theme** | warm paper `#F7F3EA`, amber darkened to `#9C5F0E` to hold contrast on a light ground |

Both themes follow the OS, and the tokens are redefined for an explicit `data-theme`
override so a viewer toggle wins in either direction.

**Type.** Monospace isn't a style choice, it's structural — chords must sit above the exact
syllable they land on. So the chart *is* the typography and the chrome stays out of its
way. The one deliberate move: the title is heavy uppercase sans while the artist below it
is lowercase mono, inverting the usual hierarchy so the header echoes the chart rather than
competing with it.

No webfonts. A silent fallback in a monospace chart breaks chord alignment — a functional
failure, not a cosmetic one. System stacks only.

---

## Layout

```
┌─────────────────────────────┐
│  ← results          v3 4.9★ │  collapses on scroll
│  PAPER LANTERNS             │  heavy uppercase
│  the hollow coast           │  mono lowercase
│  [Key G] [Capo 3] [Tuning]  │  real UG fields only
├─────────────────────────────┤
│   G          D/F#           │
│   lyric line                │  the chart — the only
│                             │  scrolling region
├─────────────────────────────┤
│  KEY [−][ G ][+] Capo3 ⟳  A±│  thumb zone
└─────────────────────────────┘
```

**Controls dock at the bottom.** The most consequential decision, and it's settled by
posture rather than taste: you reach these with a guitar in your hands. Top-of-screen
controls — where UG puts them — mean letting go of the neck.

**The header collapses.** Once you've scrolled past the first lines the title has done its
job, so it shrinks to a slim bar and gives the screen back to the chart.

---

## The key control

The chip reads `Key G` at rest and `Key G → A` once shifted, so you always know both where
you started and where you are. `Capo 3` beside it is a **toggle**, not a label.

That toggle is the real answer to the original complaint. UG tabs are user transcriptions,
and contributors write capo-friendly shapes — a sheet showing G with capo 3 actually sounds
in B♭, and UG shows you the G. Tapping the capo chip folds it in and shows what genuinely
sounds. With the ± stepper on top you get:

- **as written** — match the sheet, capo on
- **sounding** — what the record is in, capo folded in
- **anywhere else** — ± semitones from either

Transposition handles slash chords on both sides (`D/F#` → `E♭/G`) and picks sharp or flat
spelling from the destination key. It's exact, instant, and entirely client-side.

**Alignment survives it.** `G` → `G#` is a character wider, which shears chord/lyric
alignment in a naive renderer. Chords are absolutely positioned at their source column in
`ch` units, and a left-to-right pass nudges any that would collide. There's a test asserting
no two chords ever overlap at any of the 23 transpositions. Small thing, but it's the
difference between a chart that feels solid and one that feels broken.

---

## Search

One field, debounced, live. Results are **grouped by song, best-rated version first**, and
opening one is a single tap — choosing between nine transcriptions before you can play
anything is a chore UG imposes and this doesn't have to. A quiet `3 versions` badge expands
the alternates for when the top pick has a bad transcription.

Chord sheets only. Tabs, bass, ukulele and Pro versions are filtered out at the API.

---

## Designing for someone with no way to debug

This is the constraint that shaped the error handling, and it's unusual enough to be worth
stating: **the person using this has no computer, no console, and no logs.** If it breaks,
the app itself is the only thing that can explain why.

So every failure is a named, plain-language state rather than a spinner that never resolves:

| What happened | What it says |
|---|---|
| Cloudflare challenge | "Ultimate Guitar blocked us" — and that it usually clears on its own |
| `js-store` missing | "The scraper is out of date" — UG changed their markup, code needs a fix |
| Pro-only tab | "No chords on that page" — it only plays inside UG's own player |
| No signal | "Couldn't reach the app's server" |

Every error screen carries a **Run a check** button that hits `/api/health` and reports what
it found. The distinction that matters is *blocked* versus *scraper broken* — one resolves
itself, the other needs code — and no generic error message can tell those apart.

---

## Other decisions

- **Text size control** in the bar. Arm's length on a stand and close on a couch are
  genuinely different needs.
- **Hash routing**, so the Android back button steps from song to results the way it should
  in an installed app.
- The chart is the only scrolling region; header and bar are fixed. No rubber-banding the
  whole page while you're trying to read.
- `prefers-reduced-motion` disables transitions.
- Nothing about a song is persisted — only your text size.

---

## Not built

Cut deliberately, recorded so the reasoning survives:

- **BPM, duration** — would need fuzzy-matching a second API that returns the wrong song
  often enough to make the number untrustworthy
- **Auto-scroll** — cut alongside them, though it depended on none of that data
- **Chord diagrams, fingerings, tabs** — out of scope

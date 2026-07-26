# Design Pass — Chords app

Read [`tech-pass.md`](./tech-pass.md) first. The design below is shaped by three findings
from it:

1. **BPM and duration are often missing.** They're fuzzy-matched from a second API. So they
   are designed as *present-or-absent*, never as an error.
2. **The key story is three-part** (written key / capo / sounding key), not one number. The
   UI has to tell that story or it repeats UG's mistake in a new font.
3. **Transpose and scroll are client-side and instant.** So they can be direct-manipulation
   controls with live feedback, not forms you submit.

**Working prototype:** [`mockup/song-view.html`](./mockup/song-view.html) — transpose,
capo-folding, chord diagrams and auto-scroll all actually run. Open it on your phone.

---

## Visual direction

The reference point is **a chord chart on a music stand under a warm lamp**, not a code
editor. That drives every choice:

| | |
|---|---|
| **Ground** | `#17140F` — warm brown-black. Dark by default because you play in dim rooms; warm because blue-black reads as "developer tool". |
| **Text** | `#EDE6D8` warm off-white, `#9A9081` muted |
| **Chords** | `#E8A33D` amber — the one saturated colour on the page, spent entirely on the thing your eye hunts for |
| **Running** | `#6FA88C` green — semantic state for "scrolling", deliberately not the accent so it never competes with a chord |
| **Light theme** | warm paper `#F7F3EA`, amber darkened to `#9C5F0E` to hold contrast on a light ground |

**Type.** Monospace isn't a style choice here, it's a structural requirement — chords must
sit above the exact syllable they land on. So the chart *is* the typography, and the chrome
stays out of its way. The one deliberate move: the title is heavy uppercase sans while the
artist below it is lowercase mono — inverting the usual hierarchy so the header echoes the
chart rather than fighting it.

No webfonts. The Artifact CSP blocks font CDNs, and a silent fallback in a monospace chart
would break chord alignment — a real functional failure, not a cosmetic one. System stacks
only.

---

## Layout

```
┌─────────────────────────────┐
│  ← results  · v3 4.9★       │   collapses away on scroll
│  PAPER LANTERNS             │   title, heavy uppercase
│  the hollow coast           │   artist, mono lowercase
│  [Key G] [Capo 3] [BPM 84]… │   info chips
├─────────────────────────────┤
│                             │
│   G          D/F#           │   ← the hero: chart scrolls,
│   lyric line here           │     everything else is fixed
│                             │
├─────────────────────────────┤
│  KEY   [−][ G ][+] Capo3 ⟲  │   thumb zone
│  SCROLL [▶] ──────── Fit    │
└─────────────────────────────┘
```

### Why controls are docked at the bottom

This is the single most consequential layout decision, and it's decided by posture, not
aesthetics: **you operate these controls with a guitar in your hands.** You get one thumb
for one second. Top-of-screen controls — which is where UG puts them — require letting go.
Everything you touch mid-song lives in the bottom third.

The header, by contrast, is pure reference material. It collapses to a slim bar as soon as
you scroll past the first lines: once you're playing, the title has done its job and the
screen belongs to the chart.

---

## The key control

The chip reads `Key: G` at rest and `Key: G → A` once shifted, so you always know both
where you started and where you are. Alongside it, `Capo 3` is a **toggle**, not a label.

That toggle is the actual answer to the original complaint. Pressing it folds the capo into
the chords — G becomes B♭ — showing what genuinely *sounds*. Combined with the ± stepper
you get the full range:

- **as written** — match the tab, capo on
- **sounding pitch** — what the record is in, capo folded in
- **anywhere else** — ± semitones from either of those

The transposition itself is exact and handles slash chords (`D/F#` shifts both halves),
which UG's own tooling does inconsistently.

**One detail worth calling out:** transposing changes chord name *lengths* — `G` → `G#` is
a character wider — which shears the chord/lyric alignment in a naive renderer. The
prototype positions each chord absolutely at its source column in `ch` units and nudges any
chord that would collide with its neighbour. Alignment survives any transposition. This is
a small thing that makes the difference between a chart that feels solid and one that feels
broken.

---

## Auto-scroll

Speed is stated in **px/sec**, not an opaque 1–10 dial, and the motion uses sub-pixel
accumulation so slow speeds glide rather than step.

**Fit** is the feature worth building the metadata pipeline for: it divides the remaining
scroll distance by the track duration so the chart lands exactly as the song ends. You
press play with the record and never touch it again. It's precisely what UG charges for,
done better — and it's the payoff for bothering with the duration lookup.

The button is disabled when duration is unknown. Which leads to the rule below.

### Missing data is a state, not an error

BPM and duration come from fuzzy artist+title matching and will sometimes miss. So:

- Absent values render as a muted italic `unknown` in their chip — present, dimmed,
  not shouting
- No error styling, no red, no retry prompt — nothing went *wrong*
- Features depending on them (Fit) disable quietly rather than appearing broken
- **Never invent a plausible number.** A wrong BPM is worse than no BPM.

---

## Chord diagrams

Tapping any chord opens a sheet with the fingering. UG's `applicature` data gives us real
shapes for the chords as written, free.

Transposed chords have no stored shape, so the prototype derives a movable barre form and
**says so** in the sheet footnote — along with whether the shape assumes the capo. Being
honest about a derived shape costs one line of text and prevents someone learning a wrong
fingering.

---

## Search screen (not yet prototyped)

Deliberately minimal — one field, results grouped by song rather than listing every
version separately.

The design opinion: **auto-pick the best version and don't make it a decision.** UG returns
`rating` and `votes`, so pick the highest-rated non-Pro chords version and open it. Show
`v3 · 4.9★` in the header as a quiet affordance to switch. Choosing between nine
transcriptions before you can play a song is a chore UG imposes and we don't have to.

---

## Interaction rules

- **Wake lock while scrolling.** Non-negotiable — the screen sleeping mid-song is the
  single most annoying failure in this category. Released on pause.
- **Persist per song:** scroll speed, transpose offset, capo mode, font size. You set the
  speed for a song once, ever.
- **Font size control** for the chart — arm's length on a stand vs. close on a couch are
  genuinely different needs.
- Chart is the only scrolling region; header and control bar are fixed. No rubber-banding
  the whole page while you're trying to read.
- `prefers-reduced-motion` disables transitions. Auto-scroll is exempt — it's the feature,
  not decoration.

---

## Build order

1. **Dump one real UG payload** — everything is downstream of confirming §1 of the tech pass
2. Scraper service + normalize + SQLite cache
3. Chart parser and renderer (the alignment work above)
4. Transpose + capo toggle — *this alone resolves the original complaint*
5. Auto-scroll + wake lock
6. Search with auto-version-pick
7. External metadata lookup → unlocks Fit
8. Chord diagram sheet
9. PWA manifest + service worker + offline cache

Steps 1–5 are a usable app. Everything after is upside.

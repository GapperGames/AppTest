# 02 — Implementation Roadmap

How we'll actually build PoolSight, in order, so that **every phase produces
something we can run and check** rather than a big-bang reveal at the end. Each
phase lists its goal, the work, and a concrete "done when…" test.

We are still in the **design phase** — nothing below is built yet. This is the
map we'll follow once you say "start coding."

---

## Guiding principles

1. **Prove the maths without a phone first.** The `geometry` module is pure
   Kotlin — we can build and unit-test the entire aiming brain on a laptop
   before touching a camera. Correctness is settled there.
2. **Each phase is runnable.** Never more than one phase away from something you
   can hold in your hand and judge.
3. **Pool before snooker. Direct before banks. Canvas before Filament.** Ship
   the easy, high-value core; add hard parts behind interfaces.
4. **Test on a real table early and often.** The lab lies; the felt tells the
   truth.

---

## Phase map (at a glance)

```
 P0  Project skeleton + ARCore "hello plane"        ── it runs on the phone
 P1  Table calibration + homography                 ── a drawn grid sits on the cloth
 P2  Ball detection & classification (pool)          ── balls get highlighted live
 P3  Aiming engine — direct shots (pure Kotlin)      ── green unit tests + aim line
 P4  AR overlay polish (ghost ball, contact, path)   ── it looks like the product
 P5  Bank shots + obstruction + difficulty           ── banks draw & get checked
 P6  Snooker mode                                     ── reds handled, colours ID'd
 P7  UX polish, freeze mode, settings, robustness     ── daily-usable prototype
```

Rough shape: P0–P4 gets you a genuinely useful direct-shot pool aid. P5 adds the
banks you asked for. P6–P7 round it out.

---

## Phase 0 — Project skeleton & ARCore hello-world

**Goal:** an installable app that opens the camera and shows ARCore is tracking.

- Android Studio project, Kotlin, min-SDK chosen for ARCore support.
- Add ARCore + OpenCV (or the org.opencv dependency) + CameraX.
- Camera permission flow.
- ARCore session that detects a horizontal plane and draws a debug dot on it.
- Set up the module folders from spec §9 (empty but wired).

**Done when:** you install it, point at the floor/table, and see ARCore lock
onto the surface with a debug marker that stays put as you move.

**Needs from you:** the **phone model** (to confirm ARCore support & pick
min-SDK / camera settings).

---

## Phase 1 — Table calibration

**Goal:** turn "a table in the camera" into a precise top-down coordinate
system.

> **Design refinement (14 Jul 2026):** calibration is done by **tapping the
> four cushion corners in AR** (ARCore hit-tests) rather than felt-colour
> segmentation + homography. Rationale: ARCore hits are metric, so the table's
> real dimensions are *measured* from the taps — no table-size picker, no
> HSV tuning, fewer failure modes. Each corner gets its own ARCore anchor and
> the frame is refitted every frame, so the grid self-corrects as tracking
> refines. Felt auto-detection can return later as a convenience layer.
> The image→table homography returns in Phase 2, derived from the camera pose
> rather than detected corners.

- Guided flow: find surface → tap the 4 inside cushion corners → locked.
- `TableFrame` (geometry module): fits origin/axes/dimensions from 4 corners,
  validates shape (convexity, opposite-edge agreement), maps world ⇄ table mm.
  Unit-tested (rotation/translation invariance, noisy taps, degenerate input).
- Corner pins, table outline, 250 mm grid, and 6 derived pocket markers drawn
  on the cloth; ↺ Reset button to redo the corners.
- **Debug proof:** the rendered grid lies flat along the cloth and rails.

**Done when:** the drawn grid lines lie convincingly along the cloth and rails,
and stay glued as you move the phone.

**Status: code-complete (v0.3), awaiting on-table verification by the owner.**

---

## Phase 2 — Ball detection & classification (pool)

**Goal:** find balls and label them, live.

- Restrict processing to the felt rectangle (ROI).
- Subtract felt colour → candidate blobs; filter by expected size (from `H`)
  and roundness; sub-pixel centroids.
- Map centers through `H` → table-space positions.
- Classify: cue (white), solids, stripes (white-band variance), 8-ball.
- Temporal smoothing across frames; run detection throttled off-thread.
- Overlay a coloured ring + label on each detected ball.

**Done when:** on a real pool table, balls get highlighted with the right
type most of the time, and the highlights sit steadily on the balls without
much jitter.

---

## Phase 3 — Aiming engine: direct shots (pure Kotlin, no device)

**Goal:** the aiming brain, provably correct, before any AR of it.

- `geometry` module, zero Android deps.
- Implement: ghost ball `G`, aim direction, contact spot, cut angle, cut
  fraction, feasibility (ghost reachable, cue/object paths clear, pocket
  approach).
- **Unit tests** with hand-computed cases: straight pot, 30°/45° cuts,
  blocked-path cases, off-table ghost cases.
- Wire selection UI: tap object ball → tap pocket → engine runs → draw a first
  raw aim line and ghost circle via the Phase-1/2 plumbing.

**Done when:** the geometry unit-test suite is green against hand-worked
numbers, **and** tapping a ball + pocket on the real table draws an aim line
that points where your own eyes agree it should.

---

## Phase 4 — AR overlay polish

**Goal:** make it *look* like the product from the overview picture.

- Full overlay set: bold aim line (C→G), dashed object path (O→P), faint ghost
  ball at G, contact-spot dot, selection highlights, floating cut-angle +
  difficulty label.
- Clean visual styling; perspective-correct via project-the-points method.
- Handle "blocked shot" visuals (red X, greyed line).

**Done when:** a stranger glancing at your screen understands the shot the app
is recommending, and it tracks smoothly as you move.

---

## Phase 5 — Bank shots, obstruction, difficulty

**Goal:** the banks you asked for, plus the safety checks that make suggestions
trustworthy.

- Bank engine (spec §5.4): reflect pocket across each cushion → virtual pocket →
  direct-shot solve → validate bounce point on the real cushion segment.
- Render two-segment object path O→B→P with the "geometric estimate" badge.
- Full obstruction checks on both legs; pocket-approach feasibility.
- Difficulty read-out (angle + distance → green/amber/red).
- **Unit tests** for the reflection maths and bounce-point validation.

**Done when:** you pick a ball, toggle banks on, and see a plausible one-cushion
path drawn and checked for obstructions — verified against a few real banks on
the table.

---

## Phase 6 — Snooker mode

**Goal:** add snooker as a mode.

- Snooker table size + 6-pocket layout + snooker ball dimensions.
- Colour classification for the six colours + white; "red vs not" logic.
- **Red-cluster handling:** split touching reds (distance transform / watershed)
  or, pragmatically, treat a red cluster as "aim at the nearest red in the blob"
  with a caveat. Decide based on how bad the clustering is in practice.

**Done when:** on a snooker table the app identifies the colours correctly and
gives usable aim lines for reds that aren't buried in the pack.

---

## Phase 7 — UX polish, freeze mode, settings, robustness

**Goal:** turn the working demo into something you'll actually reach for.

- Freeze mode fully wired (lock solution, stop detection, battery-friendly).
- Phone-stand-friendly layout; big tap targets; minimal controls.
- Settings: default game/table, cloth colour, units, bank on/off.
- Robustness pass: lighting variation, glare, re-calibration prompts on drift,
  graceful "can't see the table" messaging.
- Optional: the **TFLite ball detector** upgrade if classic CV isn't robust
  enough in your usual lighting.

**Done when:** you can walk up to your table, calibrate in a few seconds, and
get trustworthy aim help on real shots without fighting the app.

---

## What we need from you before Phase 0

| Needed | Why | Status |
|--------|-----|--------|
| **Phone model** | Confirm ARCore support; set min-SDK & camera res | ✅ **Samsung Galaxy S22+** — fully ARCore-supported |
| Your usual table (size + cloth colour) | Tune calibration defaults | Open — has defaults |
| A go-ahead to start coding | We're design-only right now | ✅ Given 14 Jul 2026 |

## Build & delivery (no laptop needed)

The owner works phone-only, so:

- **The maths is proven in the cloud dev environment** — the `geometry`
  module's unit tests run there (and in CI) with no device involved. This is
  the "prove the maths without a phone" principle; no laptop required.
- **APKs are built by GitHub Actions** on every push
  (`.github/workflows/android.yml`) and downloaded straight to the phone from
  the repo's Actions tab.
- A debug keystore is committed so every CI build carries the same signature —
  new builds install over old ones without uninstalling.

**Progress note (14 Jul 2026):** Phase 0 is code-complete (ARCore session,
camera background, plane markers, permission/install flows). The Phase 3
geometry engine was pulled forward — ghost ball, cut angle, obstruction and
one-cushion banks are implemented and unit-tested (20 tests green). On-device
verification of P0 happens when the owner installs the first APK.

## Cross-cutting concerns tracked throughout

- **Performance/battery** — throttled detection, freeze mode, ROI (spec §7).
- **Testability** — geometry unit tests; vision tested on saved photos.
- **Interfaces for upgrades** — detector (CV→ML) and renderer (Canvas→Filament)
  behind interfaces from the start, so upgrades don't mean rewrites.
- **Honest labelling** — estimates (banks) and unmodeled effects (spin/throw)
  always surfaced, never hidden.

---

*This roadmap is a living plan; we'll refine phase details as we learn from real
tables. Design docs: [00-overview.md](00-overview.md) ·
[01-design-spec.md](01-design-spec.md).*

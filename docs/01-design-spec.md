# 01 — Design Specification

**Project:** PoolSight — AR Pool & Snooker Aiming Assistant
**Platform:** Android (Kotlin), Google ARCore, OpenCV
**Scope:** v1 = pool + one-cushion banks; snooker and extras later
**Audience:** the person building it (that's us). This is the technical heart.

---

## Table of contents

1. [System at a glance](#1-system-at-a-glance)
2. [The three coordinate systems](#2-the-three-coordinate-systems-the-key-idea)
3. [Stage A — Table detection & calibration](#3-stage-a--table-detection--calibration)
4. [Stage B — Ball detection & classification](#4-stage-b--ball-detection--classification)
5. [Stage C — The aiming engine (the maths)](#5-stage-c--the-aiming-engine-the-maths)
6. [Stage D — AR rendering & overlay](#6-stage-d--ar-rendering--overlay)
7. [The frame pipeline & performance](#7-the-frame-pipeline--performance)
8. [User experience & screen flow](#8-user-experience--screen-flow)
9. [Software architecture (modules)](#9-software-architecture-modules)
10. [Accuracy budget — where error comes from](#10-accuracy-budget--where-error-comes-from)
11. [Risks & open questions](#11-risks--open-questions)
12. [Reference data](#12-reference-data-tables--balls)

---

## 1. System at a glance

The app is a loop that runs on every camera frame (or a throttled subset):

```
 ┌──────────────────────────────────────────────────────────────────────┐
 │  CAMERA FRAME + ARCore camera pose                                     │
 └───────────────┬──────────────────────────────────────────────────────┘
                 │
        ┌────────▼─────────┐   Stage A (mostly done once, at calibration)
        │  Find the table  │   → homography H:  image pixels ⇄ table top-down
        │  + lock geometry │     + ARCore anchor fixing table in the world
        └────────┬─────────┘
                 │
        ┌────────▼─────────┐   Stage B (repeated, throttled)
        │  Find the balls  │   → list of {position in table-space, type, colour}
        └────────┬─────────┘
                 │
        ┌────────▼─────────┐   Stage C (instant, on selection)
        │  Aiming engine   │   → aim line, ghost ball, contact spot,
        │  (pure geometry) │     object path, cut angle, feasibility
        └────────┬─────────┘
                 │
        ┌────────▼─────────┐   Stage D (every frame)
        │  Draw overlay    │   → project table-space results onto the screen
        │  in AR           │     via ARCore, draw lines/markers
        └──────────────────┘
```

The single most important design decision: **all the aiming maths happens in a
flat, top-down "table space," not in the camera image.** The camera view is
distorted by perspective; a top-down view is clean 2D geometry where a ball is
a circle and a cushion is a straight line. Stage A's job is to build the bridge
(a homography) between what the camera sees and that clean top-down space.

---

## 2. The three coordinate systems (the key idea)

Everything in the app is a point living in one of three spaces. Get these
straight and the rest follows.

| Space | What it is | Units | Used for |
|-------|-----------|-------|----------|
| **Image space** | Pixels in the camera frame | pixels (u, v) | Detecting felt & balls (OpenCV works here) |
| **Table space** | A top-down bird's-eye view of the playing surface | real-world mm | ALL the aiming geometry |
| **World space** | ARCore's 3D coordinate system | metres (x, y, z) | Anchoring & drawing the overlay so it sticks |

The transforms between them:

```
                    homography H  (3×3 matrix)
   IMAGE  ────────────────────────────────────►  TABLE
   (pixels)  ◄────────────────────────────────   (top-down mm)
                    homography H⁻¹

   TABLE ──── trivial ────►  WORLD           (table is a flat plane in the world;
   (mm on the plane)         (3D on plane)    a table-space (x,y) maps to a fixed
                                              3D point on the ARCore anchor plane)

   WORLD ──── ARCore view+projection ────►  SCREEN   (for drawing)
```

- **`H` (image ⇄ table)** is what we solve for at calibration. Because we know
  the real dimensions of the playing surface (see §12), four detected corners
  give us a metric top-down map. A ball's pixel center → its real position on
  the table in millimetres.
- **table ⇄ world** is trivial once we drop an ARCore anchor at the table's
  corner and align its axes with the table edges: table point `(x_mm, y_mm)`
  becomes world point `anchor + x·right + y·forward` on the plane.
- **world → screen** is done by ARCore every frame (it hands us a view matrix
  and projection matrix). We use it to draw.

**Why both `H` and ARCore?** `H` gives us *precise, metric, in-plane* geometry
for the maths (ARCore's plane detection alone isn't precise about where the
*rails* are). ARCore gives us *live 3D pose* so the overlay stays glued to the
table as the phone moves. They're complementary.

---

## 3. Stage A — Table detection & calibration

**Goal:** establish `H` (image ⇄ table space) and an ARCore anchor, so we know
exactly where the playing surface and pockets are.

### 3.1 Detecting the playing surface

The playing surface is the coloured cloth *inside* the cushions. Steps:

1. **Colour segmentation.** Convert the frame to HSV. Threshold for the cloth
   colour (green or blue — user picks, or we auto-sample the dominant central
   colour). This yields a mask of "felt pixels."
2. **Largest quadrilateral.** Find contours in the mask, take the largest,
   and fit a 4-sided polygon (`approxPolyDP`). Those four corners are the inner
   corners of the cushions = the playing-surface rectangle.
3. **Order the corners** consistently (top-left, top-right, bottom-right,
   bottom-left) so the homography is oriented correctly.

### 3.2 Manual confirmation (this is what makes it accurate)

Auto-detection gets us ~90% there but corner precision is everything. So
calibration is **assisted, not fully automatic**:

- We show the four detected corners as draggable handles over the live/frozen
  image.
- The user nudges them to sit exactly on the cushion-nose corners.
- This one-time human touch removes the biggest source of aiming error.

The user also confirms **game type** (pool/snooker) and **table size** (see
§12), which sets the real-world dimensions of the rectangle.

### 3.3 Building the homography and placing pockets

- With four image corners ↔ four known table-space corners (e.g. `(0,0)`,
  `(W,0)`, `(W,L)`, `(0,L)` in mm), `getPerspectiveTransform` gives `H`.
- **Pockets** sit at known positions relative to the rectangle (4 corners + 2
  side pockets for pool; 6 for snooker), so once the rectangle is known, the
  pockets are known automatically. We store pocket *centers* and *jaw widths*
  in table space.
- **Cushion lines** are the four edges of the rectangle (used for bank shots).

### 3.4 Anchoring in the world (ARCore)

- Ask ARCore for the horizontal plane under the table and drop an **anchor** at
  the table-space origin corner, with axes aligned to the table edges.
- From now on, any table-space point can be turned into a 3D world point on
  that anchor's plane, and ARCore keeps it glued as the phone moves.
- **Re-calibration trigger:** if ARCore reports we've drifted or lost tracking,
  or the felt rectangle no longer matches, prompt a quick re-confirm.

**Assumption:** the table doesn't move (safe). Balls move; table doesn't. So we
calibrate once per session/rack and only re-check occasionally.

---

## 4. Stage B — Ball detection & classification

**Goal:** produce a list of balls, each with a precise table-space position and
a type (cue / solid / stripe / 8 / a snooker colour).

### 4.1 Why this is tractable here

Two facts make ball-finding much easier than generic object detection:

1. **We already know the felt region** (Stage A) — we only look inside it, and
   we can subtract the felt colour so balls pop out as non-felt blobs.
2. **We know how big a ball should appear.** Ball diameter is fixed (57mm pool,
   52.5mm snooker, see §12). Via `H` we know the pixel-size of a ball at any
   point on the table, so the circle-radius search is tightly constrained. This
   kills most false positives.

### 4.2 Detection

- Within the felt mask, take the "not felt" blobs. Filter by expected size and
  roundness.
- Refine each ball's **center** using the blob centroid (sub-pixel) rather than
  a raw Hough-circle center — center precision directly limits aim accuracy.
- Hough Circle Transform is a fallback/cross-check with radius bounds from `H`.
- Map each pixel center through `H` → **table-space position**.

### 4.3 Classification

Sample the pixels inside each detected ball:

- **Cue ball:** brightest, near-white, low saturation.
- **Pool solids vs stripes:** a stripe has a big white band → high internal
  brightness *variance*; a solid is uniform. Colour = dominant hue.
- **8-ball:** near-black, uniform.
- **Snooker colours:** match mean hue to the known set (yellow, green, brown,
  blue, pink, black) + white cue; reds are "red and not-cue."

> **Snooker's hard case (deferred to snooker phase):** 15 reds bunched together
> read as one blob. Handling that needs cluster-splitting (watershed / distance
> transform) and is exactly why pool ships first.

### 4.4 Robustness path (upgrade, not v1)

v1 uses classic OpenCV (fast to build, easy to debug, no training data). If
lighting/glare proves too much, the upgrade is a small on-device **TFLite**
object detector (e.g. a nano-YOLO) trained on ball photos. The architecture
keeps detection behind an interface so we can swap implementations without
touching the maths.

### 4.5 Temporal smoothing

Balls are static between shots. We keep a short history per ball and smooth the
position (simple moving average / light Kalman) to suppress per-frame jitter.
Detection can run at a reduced rate (e.g. 5–10 fps) while the overlay redraws
every frame from the smoothed positions.

---

## 5. Stage C — The aiming engine (the maths)

This is **pure Kotlin geometry in table space** — no camera, no Android — which
means it's fully **unit-testable offline**. Inputs are table-space points;
outputs are table-space points and angles.

Notation: cue ball center **C**, object ball center **O**, target pocket **P**,
ball radius **r** (so ball diameter = 2r). All are 2D points in table space.
`unit(v)` = v normalised; `·` = dot product.

### 5.1 Direct shots — the ghost-ball method

To pot the object ball, it must set off along the line from **O toward P**. The
cue ball must therefore strike it on the exact opposite side. The **ghost ball**
**G** is where the cue ball's *center* must be at the instant of contact:

```
        G = O + 2r · unit(O − P)
```

i.e. one full ball-diameter beyond the object ball, on the far side from the
pocket. Then:

- **Aim line:** send the cue ball from **C straight toward G**. Direction
  `unit(G − C)`. (This is the line the player sights along.)
- **Contact spot on the object ball** (the "where to hit it" the user asked
  for): the point on the object ball's surface facing the ghost ball,
  `CP = O + r · unit(G − O)` — equivalently `O − r · unit(O − P)`. We draw a dot
  here.
- **Ghost ball overlay:** a faint circle of radius r centered at **G** — the
  most intuitive way to show the player exactly where to "park" the cue ball.

```
   P (pocket)
    \
     \          object ball travels O → P
      O   ●──────────────────────►  (into pocket)
       \ ╱ contact spot
        G   ◌  ghost ball (cue centre at contact)
         ╲
          ╲   aim line  C → G
           C  ○  cue ball
```

### 5.2 Cut angle & difficulty

The **cut angle** θ is the angle between the cue ball's travel (`C→G`) and the
object ball's travel (`O→P`):

```
   cosθ = unit(G − C) · unit(P − O)
```

- θ ≈ 0° → dead-straight pot (easy). θ → 90° → paper-thin cut (near
  impossible). We surface θ and a traffic-light difficulty (green/amber/red)
  from θ plus distance.
- We also compute the **required cut fraction** (full ball, ½ ball, ¼ ball…)
  for players who think that way.

### 5.3 Feasibility & obstruction checks

Before we present a shot as "on," we verify:

1. **Ghost ball reachable** — G is inside the playing area and the cue ball can
   actually get there (it's not buried in a rail).
2. **Cue path clear** — the segment **C→G** doesn't pass through another ball
   (test each other ball's center against the segment, within 2r).
3. **Object path clear** — the segment **O→P** doesn't pass through another
   ball.
4. **Pocket approach sane** — the object ball approaches the pocket from a
   makeable angle (not into the back of a jaw).

If a check fails we mark the shot **blocked/impossible** and (nice-to-have)
suggest an alternative pocket.

### 5.4 Bank shots (one cushion) — the mirror trick

A bank shot bounces the object ball off a cushion and into the pocket. The
clean way to compute it: **reflect the pocket across the cushion line** to get a
"virtual pocket" **P′**, then treat it as a *direct* shot aimed at P′.

```
   real pocket P
        │
   ═════╪═══════════  cushion (mirror line)
        │
   reflected pocket P′   ← P mirrored across the cushion
```

Algorithm, for each of the four cushions:

1. Reflect **P** across that cushion's line → **P′**.
2. Solve the direct-shot ghost ball for sending **O toward P′**:
   `G = O + 2r · unit(O − P′)`.
3. Find the **bounce point B** = where segment **O→P′** crosses the cushion
   line. Require B to lie on the real cushion segment (not off the end).
4. Require the two legs (**O→B** and **B→P**) to be clear of other balls, and
   that the object leaves and returns on the correct sides of the cushion.
5. If valid, the object-ball path we draw is the two-segment poly-line
   **O → B → P**, and the aim line is **C → G** as before.

**Honesty label:** real cushions don't obey perfect "angle in = angle out" —
speed, spin, and cushion rebound shorten or widen the angle. So banks are shown
with a **"geometric estimate"** badge. Good as an aid; not a physics engine.

*(Multi-cushion banks = repeat the mirror trick across several cushions;
deferred beyond v1.)*

### 5.5 What v1 deliberately does NOT model

- **Throw** (friction at contact nudging the object ball ~1–3°, worse on thin
  cuts and with dirty balls).
- **Spin / english / swerve** (curving the cue ball).
- **Cue-ball position after contact** (where the white ends up).
- **Collision-induced throw and cushion physics** beyond ideal reflection.

These are called out so the overlay never over-promises.

---

## 6. Stage D — AR rendering & overlay

**Goal:** draw the engine's table-space results onto the live view so they sit
on the real table.

### 6.1 Recommended approach for v1: projected 2D overlay

We do **not** need a full 3D game engine to draw lines on a flat plane. For
each table-space point we want to draw:

1. Convert table-space → world 3D (on the ARCore anchor plane).
2. Project world → screen using ARCore's view & projection matrices.
3. Draw with a 2D overlay (Canvas / custom `View` or a light OpenGL layer):
   lines, circles (ghost ball), dots (contact spot), text (angle/difficulty).

Because everything lives on one plane, this "project the points, connect the
dots" method looks correctly perspective-anchored while staying simple.

### 6.2 What we draw

| Element | Look |
|---------|------|
| Aim line (C→G) | Bold solid line |
| Object path (O→P, or O→B→P for banks) | Dashed line, different colour |
| Ghost ball | Faint hollow circle radius r at G |
| Contact spot | Small filled dot on the object ball |
| Selected object ball / pocket | Highlight ring / glow |
| Cut angle + difficulty | Small floating label near O |
| Blocked shot | Red X on the offending ball, greyed line |

### 6.3 Upgrade path

Swap the Canvas overlay for **Filament** (Google's renderer, pairs well with
ARCore) if we want nicer visuals: glowing lines, depth occlusion (the line
passing *behind* a real ball), soft shadows for the ghost ball. Kept behind a
`Renderer` interface so it's a drop-in.

---

## 7. The frame pipeline & performance

Running full computer vision on every frame will cook the phone. The plan:

- **Calibrate once, cache `H` and the anchor.** Table doesn't move; don't
  recompute its geometry every frame.
- **Throttle detection.** Ball detection at ~5–10 fps on a **background
  thread**; the AR overlay redraws at display rate from the last known +
  smoothed ball positions.
- **Region of interest.** Only process pixels inside the felt rectangle.
- **Threading:** ARCore + GL/Canvas on the render thread; OpenCV on a worker
  thread; a small state store passes results between them.
- **Freeze mode short-circuits everything** — when frozen, we stop detection
  and just keep drawing the last solution, which is also the battery-friendly
  way to actually line up and play the shot.

**Rough per-frame budget target:** overlay redraw < 16 ms (60 fps); detection
tick may take longer off-thread without blocking drawing.

---

## 8. User experience & screen flow

```
 ┌─────────────┐   ┌──────────────┐   ┌─────────────┐   ┌──────────────┐
 │ 1. Permission│─►│ 2. Calibrate │─►│ 3. Aim mode │─►│ 4. Freeze &  │
 │   (camera)   │   │   table +    │   │  pick ball  │   │   play the   │
 │              │   │   game/size  │   │  + pocket   │   │   shot       │
 └─────────────┘   └──────────────┘   └─────────────┘   └──────────────┘
                          ▲                   │  overlay appears
                          └──── re-calibrate ─┘  (aim, ghost, path, angle)
```

1. **Permission** — camera access, one-time.
2. **Calibrate** — point at table; app finds the felt rectangle; user
   drag-confirms the 4 corners; picks game type + table size. Locks coordinates.
3. **Aim mode** — detected balls are highlighted. User **taps the object ball**;
   available pockets light up; user **taps the target pocket** (or app
   auto-suggests the best). Overlay draws instantly. A **bank-shot toggle**
   shows/hides cushion options.
4. **Freeze & play** — user taps **Freeze**; the solution locks on screen; user
   sets the phone in a stand (or just memorises the line) and plays the shot.

Controls kept minimal: tap-to-select, one toggle for banks, a freeze button, a
re-calibrate button. Settings screen holds game/table defaults and cloth
colour.

### 8.1 The phone-holding reality

You need both hands to cue. The design's answer is the **freeze workflow** plus
support for a **cheap phone stand / clip**, so you analyse, freeze, then shoot.
This is a first-class part of the UX, not an afterthought.

---

## 9. Software architecture (modules)

Clean separation so each piece is testable on its own. The geometry module in
particular has zero Android/OpenCV dependencies and can be developed and tested
entirely on a laptop.

```
 app/                  App glue, navigation, the state machine tying it together
 ├─ capture/           ARCore session, CameraX frames, camera pose
 ├─ vision/            OpenCV: felt detection, homography, ball detect+classify
 │                     (behind interfaces so a TFLite detector can swap in)
 ├─ geometry/          ★ PURE KOTLIN. Ghost ball, banks, cut angle,
 │                       obstruction, feasibility. Fully unit-tested. No Android.
 ├─ model/             Data types: Table, Ball, Pocket, Shot, and the
 │                       coordinate transforms (image⇄table⇄world)
 ├─ ar/                ARCore anchor management, table⇄world, world→screen
 ├─ render/            Overlay drawing (Canvas v1; Filament upgrade behind iface)
 └─ ui/                Calibration screen, aim screen, selection, settings
```

Data flows one way: `capture → vision → model → geometry → ar/render → ui`,
with `ui` sending user selections (object ball, pocket, freeze) back into the
state machine.

**Testing strategy per module:**

- `geometry` — pure unit tests with hand-computed expected values (the maths is
  falsifiable without any device). This is where correctness is proven.
- `vision` — tested against saved photos of tables with known ball layouts.
- `ar`/`render` — verified on-device by eye (does the line sit on the cloth?).
- End-to-end — on a real table, the ultimate test.

---

## 10. Accuracy budget — where error comes from

The "cheat" is only as good as the weakest link. Named so we know where to
spend effort:

| Source of error | Effect | Mitigation |
|-----------------|--------|-----------|
| Corner placement at calibration | Skews the whole homography | Manual drag-confirm; freeze frame while placing |
| Ball center detection | A few px → degrees over table length | Sub-pixel centroid; temporal smoothing |
| Camera lens distortion | Bends straight lines near edges | Use ARCore's camera intrinsics to undistort |
| ARCore tracking drift | Overlay slides off the table | Re-anchor on drift; freeze mode avoids it |
| Unmodeled physics (throw/spin/banks) | Real ball drifts from ideal line | Label estimates; keep v1 to direct + 1-cushion |
| Ball occlusion / glare | Missed or misplaced balls | ROI + size prior; ML detector as upgrade |

**Design principle:** aim errors *amplify with distance*. A 1° aim error is
~9 mm off per 500 mm of object-ball travel. So we invest most in the two things
that set the initial angle: **corner precision** and **ball-center precision.**

---

## 11. Risks & open questions

- **Detection robustness in real venue lighting.** Biggest technical risk.
  Mitigation: classic CV first, ML detector as a known upgrade path; test early
  on real tables.
- **Homography precision from hand-placed corners.** Mitigation: freeze-frame
  calibration, possibly sub-pixel corner snapping to detected cushion lines.
- **ARCore support on the target phone.** *Open — need the phone model to
  confirm ARCore compatibility and camera resolution.*
- **Banks' real-world accuracy.** Accepted as "estimate"; revisit with a simple
  empirically-tuned rebound fudge factor if needed.
- **Whether a full 3D renderer is worth it.** Start with Canvas; upgrade only
  if the flat overlay looks wrong or we want occlusion.

*Open question for you:* **which Android phone is this for?** It determines
ARCore support and how much camera resolution we have to play with. Not blocking
the design, but needed before Phase 0.

---

## 12. Reference data (tables & balls)

Approximate real-world dimensions used to build table space. Final values
confirmed at calibration (user picks table size).

**Playing surface (inside the cushions):**

| Table | Playing area (approx) |
|-------|----------------------|
| 7ft pool (bar) | 1980 × 990 mm |
| 8ft pool | 2240 × 1120 mm |
| 9ft pool (pro) | 2540 × 1270 mm |
| 12ft snooker (full) | 3570 × 1780 mm |

**Balls:**

| Ball | Diameter | Radius r |
|------|----------|----------|
| Pool (US) | 57.15 mm | 28.6 mm |
| Snooker | 52.5 mm | 26.25 mm |

**Pockets:** 6 total — 4 corner, 2 side (long-rail midpoints). Positions derive
from the playing-surface rectangle; jaw widths stored per game type for the
"makeable angle" feasibility check.

---

*End of design spec. Build plan in [02-implementation-roadmap.md](02-implementation-roadmap.md).*

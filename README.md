# PoolSight — AR Pool & Snooker Aiming Assistant

An Android augmented-reality app that helps you aim. Point your phone at a
pool or snooker table, pick the ball you want to pot and the pocket you want
it to go in, and the app overlays the correct aim line, the "ghost ball"
position, and the exact spot to strike — directly onto the live camera view
of the table.

> **Status: Design phase.** No application code yet. This repository currently
> contains the design specification and the implementation roadmap. See
> [`docs/`](docs/).

## What it does (in one picture)

```
   You point the phone            The app figures out              You see the answer
   at the table                   the geometry                     drawn on the table
   ┌────────────────┐             ┌────────────────┐               ┌────────────────┐
   │   •white        │            │  ghost-ball     │              │   •white        │
   │      ○8   ▢     │    ─────▶   │  + cut angle    │   ─────▶     │   ╲  ○8 ────▢   │
   │                 │            │  + bank paths   │              │    ╲aim  path   │
   └────────────────┘             └────────────────┘               └────────────────┘
   camera sees balls              maths in table-space             AR overlay on screen
```

## Documents

| Doc | What's inside |
|-----|---------------|
| [docs/00-overview.md](docs/00-overview.md) | The product in plain English: what it is, who it's for, the honest limits. Read this first. |
| [docs/01-design-spec.md](docs/01-design-spec.md) | The full design spec: how table detection, ball recognition, the aiming maths, and the AR overlay all work. The detailed one. |
| [docs/02-implementation-roadmap.md](docs/02-implementation-roadmap.md) | How we'll actually build it — phase by phase, module by module, with what "done" looks like at each step. |

## Decisions locked in (14 Jul 2026)

- **Games:** Pool *and* snooker. Pool ships first (easier to recognize); snooker is a later mode.
- **Platform:** Native Android (Kotlin) + Google ARCore + OpenCV. No game engine.
- **Shots (v1):** Direct pots **and** bank shots (one cushion). Spin/english is out of scope for v1.
- **Goal:** A working prototype for personal use — genuinely useful accuracy, not app-store polish.

## A note on the word "cheat"

This is an aiming *aid*, the same idea as a training app. It's great for
practice and learning angles. Most leagues and many venues forbid aiming
devices in competitive play — so keep it for practice and casual games.

# PoolSight — AR Pool & Snooker Aiming Assistant

An Android augmented-reality app that helps you aim. Point your phone at a
pool or snooker table, pick the ball you want to pot and the pocket you want
it to go in, and the app overlays the correct aim line, the "ghost ball"
position, and the exact spot to strike — directly onto the live camera view
of the table.

> **Status: Phase 0 built** (ARCore foundation) — plus the Phase 3 aiming
> maths, pulled forward and fully unit-tested. Target device: Samsung Galaxy
> S22+. Design docs in [`docs/`](docs/).

## Getting the app on your phone (no computer needed)

Every push to this branch makes GitHub build the app automatically:

1. On your phone, open this repository on **github.com** → **Actions** tab.
2. Open the newest run with a green tick → scroll to **Artifacts**.
3. Download **PoolSight-debug-apk**, unzip it, and tap the APK to install.
   (Android will ask you to allow installs from your browser — that's normal
   for apps outside the Play Store.)

## Project layout

| Module | What it is |
|--------|------------|
| `geometry/` | The aiming brain: ghost-ball, bank-shot and obstruction maths. Pure Kotlin, fully unit-tested — no phone needed. |
| `app/` | The Android app. Phase 0: camera + ARCore surface tracking with on-screen status. |

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

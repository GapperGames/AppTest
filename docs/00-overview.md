# 00 — Overview (the plain-English version)

This document explains what we're building and why, with as little jargon as
possible. The detailed engineering is in
[01-design-spec.md](01-design-spec.md); the build plan is in
[02-implementation-roadmap.md](02-implementation-roadmap.md).

---

## 1. The idea

You're standing at a pool or snooker table with your phone. You open the app
and point the camera at the table. On screen you see the real table with the
balls. You tap the ball you want to sink, then tap the pocket you want it to
go in. The app instantly draws, right on top of the real table:

- a **line showing where to aim the cue ball**,
- a faint **"ghost ball"** — a picture of where the cue ball needs to *end up*
  to make contact,
- the **exact spot on the object ball** the cue ball should strike,
- the **path the object ball will travel** into the pocket (including a bounce
  off a cushion, for bank shots),
- a small **difficulty read-out** (the cut angle, and whether anything is in
  the way).

That's the whole product. Everything in the spec exists to make those overlays
appear in the right place and be correct.

## 2. Why this is harder than it looks (said honestly up front)

The maths of "where do I aim" is actually the *easy* part — it's schoolbook
geometry (see the "ghost ball" method in the spec). The hard parts are:

1. **Knowing exactly where the table is.** The phone has to work out the
   table's position and angle in 3D from a 2D camera image, precisely enough
   that a line drawn on screen lands on the real cloth.
2. **Finding the balls precisely.** Being off by a few pixels on where a ball's
   center is can turn into several degrees of aiming error by the time the ball
   reaches the far pocket. Glare, shadows, and balls touching each other all
   fight us.
3. **Doing it fast enough** to feel live, on a phone, without the battery
   melting.

None of these are dealbreakers, but they're why we phase the work and why the
honest goal is "a genuinely useful aid," not "a robot that never misses."

## 3. Who it's for and how it's actually used

It's for you — practice and casual play. One real-world subtlety worth stating
now, because it shapes the design:

> **You can't hold the phone and take the shot at the same time.**

So the app supports a **"freeze" workflow**: line up the shot, tap to freeze
the analysis on screen, then put the phone down (or in a small stand) and play
the shot you just saw. The design assumes this, rather than pretending you'll
cue one-handed while filming.

## 4. What's in and what's out for the first version

**In scope (v1):**

- Pool (8-ball / 9-ball) recognition and aiming.
- Direct pots using the ghost-ball method.
- One-cushion bank shots (geometric estimate).
- Table calibration with manual corner adjustment for accuracy.
- The full AR overlay described above.
- Freeze-frame workflow.

**Explicitly out of scope for v1 (planned later):**

- Snooker mode (added after pool works — the cluster of identical red balls is
  a recognition challenge of its own).
- Spin / english / swerve (curving the cue ball). Very hard to estimate from a
  camera; would give false confidence if done badly.
- Multi-cushion banks, combination shots, kick shots.
- Predicting where the *cue ball* ends up after contact (position play).

We can revisit any of these once the core works.

## 5. The honest limitations (set expectations before we build)

- **It's an aid, not a guarantee.** It computes the ideal geometric line;
  you still have to deliver a straight, clean stroke.
- **Bank shots are approximations.** Real cushions behave differently at
  different speeds and with spin. We use "angle in = angle out," clearly
  labelled as an estimate.
- **Contact "throw" is ignored in v1.** When balls collide there's a tiny
  friction effect that nudges the object ball a fraction off the ideal line.
  We model the ideal; it's close but not perfect on thin cuts.
- **Lighting and setup matter.** Bright, even light and a clear view of cue
  ball + object ball + pocket give the best results.
- **Fairness.** Aiming aids are banned in most competitive play. Practice only.

## 6. The four decisions that shaped everything

| Decision | Choice | Why it matters |
|----------|--------|----------------|
| Which game first | **Both**, pool first | Pool's distinct colors are far easier for the camera than snooker's 15 identical reds. |
| How we build it | **Native Android (Kotlin)** + ARCore + OpenCV | Lean, full control, no game-engine overhead; the standard toolkit for exactly this job. |
| Which shots | **Direct + bank** | Banks were requested; we scope them to one cushion and label them as estimates. |
| Ambition | **Working prototype** | Sets accuracy targets that are useful and achievable, not perfectionist. |

Next: read [01-design-spec.md](01-design-spec.md) for how each piece works.

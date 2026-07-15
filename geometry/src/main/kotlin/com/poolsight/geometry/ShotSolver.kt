package com.poolsight.geometry

/**
 * The aiming brain (spec §5). Pure geometry in table space — no Android,
 * no camera. Everything here is unit-tested offline.
 *
 * Direct shots use the ghost-ball method:  G = O + 2r · unit(O − P)
 * Bank shots reflect the pocket across a cushion (offset inward by one ball
 * radius, since a ball's *centre* rebounds one radius short of the nose) and
 * then solve as a direct shot at the virtual pocket.
 */

/** Why a shot can't be played. */
enum class ShotProblem {
    GHOST_UNREACHABLE,     // ghost-ball position is off the playing surface
    CUE_PATH_BLOCKED,      // another ball sits on the cue ball's path to the ghost
    OBJECT_PATH_BLOCKED,   // another ball sits on the object ball's path to the pocket
    CUT_TOO_THIN,          // cut angle ≥ MAX_CUT_DEGREES — geometrically near-impossible
    BANK_NO_BOUNCE,        // reflected path never crosses the chosen cushion segment
    BANK_WRONG_SIDE,       // object ball travels away from the cushion, not into it
}

enum class Difficulty { EASY, MEDIUM, HARD, VERY_HARD }

sealed class ShotResult {
    /**
     * A playable shot.
     *
     * @param ghost      cue-ball centre position at contact (draw a faint circle here)
     * @param contactPoint the spot on the object ball's surface to strike
     * @param aimDirection unit vector the cue ball must travel (cue → ghost)
     * @param objectPath polyline the object ball follows: [O, P] direct,
     *                   [O, bounce, P] for a bank
     * @param cutAngleDegrees 0 = dead straight, →90 = paper-thin
     * @param cutFraction 1.0 = full ball, 0.5 = half ball, … (cos of cut angle)
     * @param isBank     true when this solution bounces off a cushion (estimate)
     */
    data class Solution(
        val ghost: Vec2,
        val contactPoint: Vec2,
        val aimDirection: Vec2,
        val objectPath: List<Vec2>,
        val cutAngleDegrees: Double,
        val cutFraction: Double,
        val difficulty: Difficulty,
        val isBank: Boolean,
        val bankCushion: CushionId? = null,
    ) : ShotResult()

    data class Impossible(val problem: ShotProblem, val blockingBallId: String? = null) : ShotResult()
}

/**
 * @param cushionRestitution normal coefficient of restitution for bank shots
 *   (0<e≤1). At e=1 the cushion is a perfect mirror. Below 1, the rebound is
 *   "long": a naturally rolling ball loses some of its into-cushion speed but
 *   keeps its along-cushion speed, so it comes off flatter than the mirror
 *   angle. This is a first-order, speed-independent approximation of cushion
 *   bounce/spin — good as an aid, not a physics engine.
 */
class ShotSolver(
    private val table: Table,
    private val cushionRestitution: Double = DEFAULT_CUSHION_RESTITUTION,
) {

    private val r = table.ballRadius

    /**
     * Solve a direct pot: cue ball [cue] strikes object ball [objectBall]
     * so it rolls into pocket [pocket]. [otherBalls] are checked as obstacles
     * (the cue and object balls themselves are excluded automatically).
     */
    fun solveDirect(
        cue: Ball,
        objectBall: Ball,
        pocket: Pocket,
        otherBalls: List<Ball> = emptyList(),
    ): ShotResult = solveTowards(cue, objectBall, pocket.center, otherBalls, isBank = false, cushion = null)

    /**
     * Solve a one-cushion bank into [pocket] off [cushion].
     * The mirror line is the cushion offset inward by one ball radius,
     * because the ball's centre rebounds one radius short of the nose.
     * The bounce point accounts for [cushionRestitution] (bounce/spin).
     */
    fun solveBank(
        cue: Ball,
        objectBall: Ball,
        pocket: Pocket,
        cushion: Cushion,
        otherBalls: List<Ball> = emptyList(),
    ): ShotResult {
        val offset = cushion.inwardNormal * r
        val mirrorA = cushion.a + offset
        val mirrorB = cushion.b + offset

        val bounce = bankBouncePoint(objectBall.center, pocket.center, mirrorA, mirrorB, cushion.inwardNormal)
            ?: return ShotResult.Impossible(ShotProblem.BANK_WRONG_SIDE)

        // The bounce must land on the real cushion segment (not in a pocket jaw).
        val along = (mirrorB - mirrorA).normalized()
        val t = (bounce - mirrorA) dot along
        if (t < 0.0 || t > mirrorA.distanceTo(mirrorB)) {
            return ShotResult.Impossible(ShotProblem.BANK_NO_BOUNCE)
        }

        // The cue sends the object ball toward the bounce point (first leg).
        val solved = solveTowards(cue, objectBall, bounce, otherBalls, isBank = true, cushion = cushion.id)
        if (solved !is ShotResult.Solution) return solved

        // Check the second leg (bounce → real pocket) for obstructions too.
        firstBallOnPath(bounce, pocket.center, otherBalls, exclude = setOf(cue.id, objectBall.id))?.let {
            return ShotResult.Impossible(ShotProblem.OBJECT_PATH_BLOCKED, it.id)
        }

        return solved.copy(objectPath = listOf(objectBall.center, bounce, pocket.center))
    }

    /**
     * The point on the mirror line where the object ball should strike the
     * cushion so that, after a bounce with normal restitution
     * [cushionRestitution], it reaches [target].
     *
     * Working in cushion-local coordinates (x along the rail, y = distance
     * inward from the rail): the along-rail speed is preserved and the
     * into-rail speed is scaled by e, so the along-rail travel splits as
     *   Δx_out = D·y_target / (y_target + e·y_object)
     * where D is the total along-rail span. At e=1 this is the exact mirror
     * bounce; below 1 the ball comes off flatter (rebounds long).
     *
     * Returns null when the object or target is on/behind the rail line
     * (no valid bank).
     */
    private fun bankBouncePoint(
        obj: Vec2,
        target: Vec2,
        mirrorA: Vec2,
        mirrorB: Vec2,
        inwardNormal: Vec2,
    ): Vec2? {
        val along = (mirrorB - mirrorA).normalized()
        val yObj = (obj - mirrorA) dot inwardNormal   // inward distance of the object
        val yTgt = (target - mirrorA) dot inwardNormal // inward distance of the pocket
        if (yObj <= Vec2.EPSILON || yTgt <= Vec2.EPSILON) return null // wrong side of rail

        val xObj = (obj - mirrorA) dot along
        val xTgt = (target - mirrorA) dot along
        val span = xTgt - xObj

        val dxOut = span * yTgt / (yTgt + cushionRestitution * yObj)
        val xBounce = xTgt - dxOut
        return mirrorA + along * xBounce
    }

    /**
     * Try every shot for [objectBall] into [pocket]: the direct pot first,
     * then a bank off each cushion. Returns all playable solutions,
     * easiest first (direct shots sort before banks at equal difficulty).
     */
    fun solveAll(
        cue: Ball,
        objectBall: Ball,
        pocket: Pocket,
        otherBalls: List<Ball> = emptyList(),
    ): List<ShotResult.Solution> {
        val solutions = mutableListOf<ShotResult.Solution>()
        (solveDirect(cue, objectBall, pocket, otherBalls) as? ShotResult.Solution)?.let { solutions += it }
        for (cushion in table.cushions) {
            (solveBank(cue, objectBall, pocket, cushion, otherBalls) as? ShotResult.Solution)?.let { solutions += it }
        }
        return solutions.sortedWith(compareBy({ it.difficulty }, { it.isBank }, { it.cutAngleDegrees }))
    }

    // ---- internals ---------------------------------------------------------

    /** Ghost-ball solve for sending the object ball toward [target]. */
    private fun solveTowards(
        cue: Ball,
        objectBall: Ball,
        target: Vec2,
        otherBalls: List<Ball>,
        isBank: Boolean,
        cushion: CushionId?,
    ): ShotResult {
        val o = objectBall.center
        val away = (o - target).normalized()

        val ghost = o + away * (2.0 * r)                       // G = O + 2r·unit(O−P)
        val contact = o + away * r                              // strike point on the ball's surface

        if (!table.isReachableByBallCenter(ghost)) {
            return ShotResult.Impossible(ShotProblem.GHOST_UNREACHABLE)
        }

        val cueToGhost = ghost - cue.center
        if (cueToGhost.length() < Vec2.EPSILON) {
            return ShotResult.Impossible(ShotProblem.GHOST_UNREACHABLE)
        }
        val aim = cueToGhost.normalized()

        // A cut is only physically possible when the cue ball approaches from
        // the correct side: the aim direction must push the object ball
        // toward the target, not drag it backwards.
        val cutAngle = Vec2.angleBetweenDegrees(aim, target - o)
        if (cutAngle >= MAX_CUT_DEGREES) {
            return ShotResult.Impossible(ShotProblem.CUT_TOO_THIN)
        }

        val exclude = setOf(cue.id, objectBall.id)
        firstBallOnPath(cue.center, ghost, otherBalls, exclude)?.let {
            return ShotResult.Impossible(ShotProblem.CUE_PATH_BLOCKED, it.id)
        }
        firstBallOnPath(o, target, otherBalls, exclude)?.let {
            return ShotResult.Impossible(ShotProblem.OBJECT_PATH_BLOCKED, it.id)
        }

        val cutFraction = kotlin.math.cos(Math.toRadians(cutAngle))
        val distance = cue.center.distanceTo(ghost) + o.distanceTo(target)

        return ShotResult.Solution(
            ghost = ghost,
            contactPoint = contact,
            aimDirection = aim,
            objectPath = listOf(o, target),
            cutAngleDegrees = cutAngle,
            cutFraction = cutFraction,
            difficulty = rateDifficulty(cutAngle, distance, isBank),
            isBank = isBank,
            bankCushion = cushion,
        )
    }

    /** First ball whose centre lies within 2r of segment [from]→[to]. */
    private fun firstBallOnPath(from: Vec2, to: Vec2, balls: List<Ball>, exclude: Set<String>): Ball? =
        balls.asSequence()
            .filter { it.id !in exclude }
            .filter { distancePointToSegment(it.center, from, to) < 2.0 * r - CLEARANCE_TOLERANCE }
            .minByOrNull { it.center.distanceTo(from) }

    private fun rateDifficulty(cutAngleDeg: Double, totalDistanceMm: Double, isBank: Boolean): Difficulty {
        // Score blends cut thinness with distance; banks bump one level.
        var score = cutAngleDeg / 15.0 + totalDistanceMm / 800.0
        if (isBank) score += 2.0
        return when {
            score < 2.0 -> Difficulty.EASY
            score < 4.0 -> Difficulty.MEDIUM
            score < 6.0 -> Difficulty.HARD
            else -> Difficulty.VERY_HARD
        }
    }

    companion object {
        /** Cuts at/beyond this are treated as unplayable rather than shown. */
        const val MAX_CUT_DEGREES = 85.0

        /**
         * Default cushion restitution for banks. Empirical: a rolling ball
         * rebounds slightly "long" (flatter than the mirror), so a value just
         * below 1 nudges the aim that way without over-committing. Tunable.
         */
        const val DEFAULT_CUSHION_RESTITUTION = 0.85

        /** Small tolerance so a ball exactly 2r away (touching path) doesn't flag. */
        private const val CLEARANCE_TOLERANCE = 0.5 // mm
    }
}

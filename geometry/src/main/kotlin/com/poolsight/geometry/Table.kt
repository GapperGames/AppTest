package com.poolsight.geometry

/**
 * Table-space model of the playing surface: a W×L rectangle (mm) with
 * pockets and cushions at known positions. Origin is the bottom-left corner
 * looking top-down; x spans the width (short rail), y the length (long rail).
 */

enum class GameType(val ballRadiusMm: Double) {
    POOL(28.575),   // 57.15 mm US pool ball
    SNOOKER(26.25), // 52.5 mm snooker ball
}

data class Pocket(val id: PocketId, val center: Vec2)

enum class PocketId {
    BOTTOM_LEFT, BOTTOM_RIGHT,
    MIDDLE_LEFT, MIDDLE_RIGHT,
    TOP_LEFT, TOP_RIGHT,
}

/**
 * A cushion is one straight rail segment between two pockets, described by
 * its endpoints on the playing-surface boundary and its inward normal.
 * The four cushion *sides* are split into six segments on a real table, but
 * for bank geometry the four boundary lines are what matter; jaw gaps are
 * handled by requiring the bounce point to be inside the segment.
 */
data class Cushion(val id: CushionId, val a: Vec2, val b: Vec2, val inwardNormal: Vec2)

enum class CushionId { BOTTOM, TOP, LEFT_LOWER, LEFT_UPPER, RIGHT_LOWER, RIGHT_UPPER }

class Table(
    val widthMm: Double,
    val lengthMm: Double,
    val game: GameType,
) {
    val ballRadius: Double get() = game.ballRadiusMm

    /** Corner pockets at the corners; middle pockets at long-rail midpoints. */
    val pockets: List<Pocket> = listOf(
        Pocket(PocketId.BOTTOM_LEFT, Vec2(0.0, 0.0)),
        Pocket(PocketId.BOTTOM_RIGHT, Vec2(widthMm, 0.0)),
        Pocket(PocketId.MIDDLE_LEFT, Vec2(0.0, lengthMm / 2)),
        Pocket(PocketId.MIDDLE_RIGHT, Vec2(widthMm, lengthMm / 2)),
        Pocket(PocketId.TOP_LEFT, Vec2(0.0, lengthMm)),
        Pocket(PocketId.TOP_RIGHT, Vec2(widthMm, lengthMm)),
    )

    /**
     * Rail segments between pockets. A small jaw margin is trimmed from each
     * end so bank bounce points aimed into a pocket mouth are rejected.
     */
    val cushions: List<Cushion> = run {
        val jaw = 2.5 * ballRadius // approximate half-mouth trimmed off each segment end
        val w = widthMm
        val l = lengthMm
        listOf(
            Cushion(CushionId.BOTTOM, Vec2(jaw, 0.0), Vec2(w - jaw, 0.0), Vec2(0.0, 1.0)),
            Cushion(CushionId.TOP, Vec2(jaw, l), Vec2(w - jaw, l), Vec2(0.0, -1.0)),
            Cushion(CushionId.LEFT_LOWER, Vec2(0.0, jaw), Vec2(0.0, l / 2 - jaw), Vec2(1.0, 0.0)),
            Cushion(CushionId.LEFT_UPPER, Vec2(0.0, l / 2 + jaw), Vec2(0.0, l - jaw), Vec2(1.0, 0.0)),
            Cushion(CushionId.RIGHT_LOWER, Vec2(w, jaw), Vec2(w, l / 2 - jaw), Vec2(-1.0, 0.0)),
            Cushion(CushionId.RIGHT_UPPER, Vec2(w, l / 2 + jaw), Vec2(w, l - jaw), Vec2(-1.0, 0.0)),
        )
    }

    /** Can a ball centre sit here? (inside the boundary, allowing for radius) */
    fun isReachableByBallCenter(p: Vec2): Boolean =
        p.x >= ballRadius - Vec2.EPSILON && p.x <= widthMm - ballRadius + Vec2.EPSILON &&
            p.y >= ballRadius - Vec2.EPSILON && p.y <= lengthMm - ballRadius + Vec2.EPSILON

    companion object {
        /** Common playing-surface sizes (inside the cushions), mm. Spec §12. */
        fun poolSevenFoot() = Table(990.0, 1980.0, GameType.POOL)
        fun poolEightFoot() = Table(1120.0, 2240.0, GameType.POOL)
        fun poolNineFoot() = Table(1270.0, 2540.0, GameType.POOL)
        fun snookerTwelveFoot() = Table(1780.0, 3570.0, GameType.SNOOKER)
    }
}

/** A ball on the table: identity is up to the caller (cue, 8-ball, red #3…). */
data class Ball(val id: String, val center: Vec2)

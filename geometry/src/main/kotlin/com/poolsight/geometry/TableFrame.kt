package com.poolsight.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * The bridge between AR world space (3D, metres) and table space (2D, mm).
 *
 * Built from the four corners of the playing surface, tapped in order around
 * the table (any starting corner, either direction). Because ARCore is metric,
 * the tapped corners also give us the table's real dimensions — no manual
 * size entry needed.
 *
 * Convention matches [Table]: origin at the first tapped corner, x along the
 * SHORT rail (width), y along the LONG rail (length).
 */
class TableFrame(
    val origin: Vec3,
    val xAxis: Vec3, // unit, along the width
    val yAxis: Vec3, // unit, along the length
    val widthMm: Double,
    val lengthMm: Double,
) {

    /** World (metres) → table (mm). Projects onto the table plane. */
    fun toTable(world: Vec3): Vec2 {
        val d = world - origin
        return Vec2((d dot xAxis) * 1000.0, (d dot yAxis) * 1000.0)
    }

    /** Table (mm) → world (metres), on the table plane. */
    fun toWorld(table: Vec2): Vec3 =
        origin + xAxis * (table.x / 1000.0) + yAxis * (table.y / 1000.0)

    /** The geometry-engine table model for these measured dimensions. */
    fun table(game: GameType): Table = Table(widthMm, lengthMm, game)

    /** Unit normal of the table plane, pointing up (away from the floor). */
    val upNormal: Vec3 by lazy {
        val n = (xAxis cross yAxis).normalizedOrNull() ?: Vec3(0.0, 1.0, 0.0)
        if (n.y < 0.0) n * -1.0 else n
    }

    /**
     * Where is the ball whose image lies along this camera ray?
     *
     * The ray from the camera through a ball's pixel centroid intersects a
     * plane ONE BALL RADIUS above the cloth (we sight the ball's centre, not
     * its contact point — intersecting the cloth itself would smear the ball
     * away from the camera by parallax). Returns the ball's table-space
     * position, or null when the ray misses (parallel/behind) or lands
     * outside the playing surface by more than [marginMm].
     */
    fun ballCenterFromRay(
        rayOrigin: Vec3,
        rayDirection: Vec3,
        ballRadiusMm: Double,
        marginMm: Double = 50.0,
    ): Vec2? {
        val planePoint = origin + upNormal * (ballRadiusMm / 1000.0)
        val denom = rayDirection dot upNormal
        if (kotlin.math.abs(denom) < Vec2.EPSILON) return null
        val t = ((planePoint - rayOrigin) dot upNormal) / denom
        if (t <= 0.0) return null
        val hit = rayOrigin + rayDirection * t
        val table = toTable(hit)
        if (table.x < -marginMm || table.x > widthMm + marginMm) return null
        if (table.y < -marginMm || table.y > lengthMm + marginMm) return null
        return table
    }

    companion object {
        /** Corners closer than this are suspicious double-taps / bad rectangles. */
        const val MIN_EDGE_METRES = 0.3

        /** Opposite edges may differ at most by this factor before we reject. */
        const val MAX_OPPOSITE_EDGE_RATIO = 1.5

        /**
         * Fit a table frame from four corners tapped in order around the
         * playing surface. Returns null when the points don't form a
         * plausible table rectangle (too small, wildly uneven opposite
         * sides, non-convex, or degenerate).
         */
        fun fitFromCorners(corners: List<Vec3>): TableFrame? {
            if (corners.size != 4) return null

            // Best-fit plane normal from the diagonals; flatten the quad onto
            // the plane through the centroid so slight tap-height noise
            // doesn't skew edge lengths.
            val normal = ((corners[2] - corners[0]) cross (corners[3] - corners[1]))
                .normalizedOrNull() ?: return null
            val centroid = Vec3(
                corners.sumOf { it.x } / 4.0,
                corners.sumOf { it.y } / 4.0,
                corners.sumOf { it.z } / 4.0,
            )
            val p = corners.map { c -> c - normal * ((c - centroid) dot normal) }

            val edges = List(4) { i -> p[(i + 1) % 4] - p[i] }
            val lengths = edges.map { it.length() }
            if (lengths.any { it < MIN_EDGE_METRES }) return null

            // Opposite edges of a rectangle viewed as a quad must roughly match.
            if (!oppositesMatch(lengths[0], lengths[2])) return null
            if (!oppositesMatch(lengths[1], lengths[3])) return null

            // Convexity: turning direction must be consistent all the way round.
            var sign = 0.0
            for (i in 0 until 4) {
                val turn = (edges[i] cross edges[(i + 1) % 4]) dot normal
                if (turn == 0.0) return null
                if (sign == 0.0) sign = turn
                if (turn * sign < 0.0) return null
            }

            // Side lengths: edges 0/2 run one way round the table, 1/3 the other.
            val side01 = (lengths[0] + lengths[2]) / 2.0
            val side12 = (lengths[1] + lengths[3]) / 2.0

            // At the first tapped corner, one adjacent edge is the width and
            // the other the length. Pick so x = short (width), y = long.
            val adjNext = p[1] - p[0] // belongs to side01
            val adjPrev = p[3] - p[0] // belongs to side12
            val (xEdge, width, yEdge, length) =
                if (side01 <= side12) Fit(adjNext, side01, adjPrev, side12)
                else Fit(adjPrev, side12, adjNext, side01)

            val xAxis = xEdge.normalizedOrNull() ?: return null
            // Orthogonalize the length axis against the width axis so the
            // frame is exactly rectangular even if the taps weren't.
            val yAxis = (yEdge - xAxis * (yEdge dot xAxis)).normalizedOrNull() ?: return null

            return TableFrame(
                origin = p[0],
                xAxis = xAxis,
                yAxis = yAxis,
                widthMm = width * 1000.0,
                lengthMm = length * 1000.0,
            )
        }

        private fun oppositesMatch(a: Double, b: Double): Boolean =
            max(a, b) / min(a, b) <= MAX_OPPOSITE_EDGE_RATIO

        private data class Fit(val xEdge: Vec3, val width: Double, val yEdge: Vec3, val length: Double)

        /** Plausible corner-to-corner diagonals: 6ft pool (~1.87m) to 12ft snooker (~4m). */
        const val MIN_DIAGONAL_M = 1.5
        const val MAX_DIAGONAL_M = 4.5

        /**
         * Fit a table from just TWO taps: diagonally opposite playing-surface
         * corners. Pool and snooker playing surfaces are 2:1 (length = 2 ×
         * width), so the diagonal plus that ratio pins the rectangle down to
         * exactly TWO candidates — mirror images across the diagonal.
         * [mirrored] selects between them (a "flip" button in the UI).
         * Tables are level, so the plane is the horizontal one through the
         * taps (world +Y is gravity-up in ARCore).
         *
         * More reliable than four taps in practice: half the tap error, and
         * the rectangle is perfect by construction.
         */
        fun fitFromDiagonal(a: Vec3, c: Vec3, mirrored: Boolean = false): TableFrame? {
            // Flatten both taps onto a horizontal plane at their mean height.
            val y = (a.y + c.y) / 2.0
            val pa = Vec3(a.x, y, a.z)
            val pc = Vec3(c.x, y, c.z)

            val diagonal = pc - pa
            val dLen = diagonal.length()
            if (dLen < MIN_DIAGONAL_M || dLen > MAX_DIAGONAL_M) return null

            // For a 2:1 rectangle the centre→corner vectors of adjacent
            // corners are 2θ apart with cos2θ = 3/5, sin2θ = 4/5 — exactly.
            // Rotating centre→A by ±2θ about the vertical gives corner B of
            // the two mirror candidates; D is B reflected through the centre.
            val center = pa + diagonal * 0.5
            val toA = pa - center
            val sin = if (mirrored) 0.8 else -0.8
            val toB = Vec3(
                toA.x * 0.6 + toA.z * sin,
                toA.y,
                -toA.x * sin + toA.z * 0.6,
            )
            val b = center + toB
            val d = center - toB

            // A→B→C→D walks around the rectangle; reuse the 4-corner fit for
            // axis conventions and final validation.
            return fitFromCorners(listOf(pa, b, pc, d))
        }
    }
}

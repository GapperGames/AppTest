package com.poolsight.geometry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * A 2D point/vector in table space. Units are millimetres.
 *
 * Table space is the top-down view of the playing surface: origin at one
 * corner, x along the short rail (width W), y along the long rail (length L).
 * See docs/01-design-spec.md §2.
 */
data class Vec2(val x: Double, val y: Double) {

    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun unaryMinus() = Vec2(-x, -y)

    infix fun dot(o: Vec2): Double = x * o.x + y * o.y

    /** 2D cross product (z-component). Sign tells left/right of a direction. */
    infix fun cross(o: Vec2): Double = x * o.y - y * o.x

    fun length(): Double = sqrt(x * x + y * y)

    fun distanceTo(o: Vec2): Double = (this - o).length()

    /** Unit vector in this direction. Throws for the zero vector. */
    fun normalized(): Vec2 {
        val len = length()
        require(len > EPSILON) { "Cannot normalize a zero-length vector" }
        return Vec2(x / len, y / len)
    }

    companion object {
        const val EPSILON = 1e-9

        /** Angle in degrees between two directions (0..180). */
        fun angleBetweenDegrees(a: Vec2, b: Vec2): Double {
            val cos = (a.normalized() dot b.normalized()).coerceIn(-1.0, 1.0)
            return Math.toDegrees(acos(cos))
        }
    }
}

/**
 * Shortest distance from point [p] to the line segment [a]–[b].
 * Used for obstruction checks: a ball blocks a rolling ball's path when its
 * centre is closer than two radii to the path segment.
 */
fun distancePointToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
    val ab = b - a
    val lenSq = ab dot ab
    if (lenSq < Vec2.EPSILON) return p.distanceTo(a)
    val t = ((p - a) dot ab) / lenSq
    val clamped = t.coerceIn(0.0, 1.0)
    val closest = a + ab * clamped
    return p.distanceTo(closest)
}

/**
 * Reflect point [p] across the infinite line through [a] and [b].
 * This is the "mirror trick" used for bank shots (spec §5.4).
 */
fun reflectPointAcrossLine(p: Vec2, a: Vec2, b: Vec2): Vec2 {
    val dir = (b - a).normalized()
    val ap = p - a
    val along = dir * (ap dot dir)
    val perpendicular = ap - along
    return p - perpendicular * 2.0
}

/**
 * Intersection of segment [p1]–[p2] with the infinite line through [a]–[b],
 * or null when the segment is parallel to (or does not reach) the line.
 */
fun segmentLineIntersection(p1: Vec2, p2: Vec2, a: Vec2, b: Vec2): Vec2? {
    val d = p2 - p1
    val lineDir = b - a
    val denom = d cross lineDir
    if (abs(denom) < Vec2.EPSILON) return null // parallel
    val t = ((a - p1) cross lineDir) / denom
    if (t < -Vec2.EPSILON || t > 1.0 + Vec2.EPSILON) return null // beyond segment ends
    return p1 + d * t.coerceIn(0.0, 1.0)
}

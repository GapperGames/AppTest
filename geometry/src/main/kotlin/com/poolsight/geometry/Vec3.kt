package com.poolsight.geometry

import kotlin.math.sqrt

/**
 * A 3D point/vector in world space (ARCore's coordinate system). Units are
 * metres, matching ARCore. Table space stays 2D millimetres (Vec2); TableFrame
 * bridges the two.
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    fun length(): Double = sqrt(x * x + y * y + z * z)

    fun distanceTo(o: Vec3): Double = (this - o).length()

    /** Unit vector, or null for a (near-)zero vector. */
    fun normalizedOrNull(): Vec3? {
        val len = length()
        if (len < Vec2.EPSILON) return null
        return Vec3(x / len, y / len, z / len)
    }
}

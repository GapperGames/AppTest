package com.poolsight.geometry

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Corner-fit calibration maths. World space is metres (y up, table on the
 * x/z plane like ARCore); table space is millimetres.
 */
class TableFrameTest {

    // A 1m × 2m playing surface, axis-aligned on the floor (y = 0).
    private val p0 = Vec3(0.0, 0.0, 0.0)
    private val p1 = Vec3(1.0, 0.0, 0.0)
    private val p2 = Vec3(1.0, 0.0, 2.0)
    private val p3 = Vec3(0.0, 0.0, 2.0)

    @Test
    fun `axis-aligned rectangle - measured dimensions and corner mapping`() {
        val frame = assertNotNull(TableFrame.fitFromCorners(listOf(p0, p1, p2, p3)))

        assertEquals(1000.0, frame.widthMm, 1e-9)
        assertEquals(2000.0, frame.lengthMm, 1e-9)

        // Corners map to the table-space rectangle in mm.
        assertVec2(Vec2(0.0, 0.0), frame.toTable(p0))
        assertVec2(Vec2(1000.0, 0.0), frame.toTable(p1))
        assertVec2(Vec2(1000.0, 2000.0), frame.toTable(p2))
        assertVec2(Vec2(0.0, 2000.0), frame.toTable(p3))
    }

    @Test
    fun `tapping the long edge first still puts x on the short side`() {
        // Same rectangle, walked the other way round: p0 → p3 is the long edge.
        val frame = assertNotNull(TableFrame.fitFromCorners(listOf(p0, p3, p2, p1)))

        assertEquals(1000.0, frame.widthMm, 1e-9)
        assertEquals(2000.0, frame.lengthMm, 1e-9)
        assertVec2(Vec2(0.0, 2000.0), frame.toTable(p3))
        assertVec2(Vec2(1000.0, 0.0), frame.toTable(p1))
    }

    @Test
    fun `rotated and translated table - round trip is identity`() {
        val deg = 37.0
        val rad = Math.toRadians(deg)
        val offset = Vec3(5.0, 1.2, -3.0)
        fun place(p: Vec3) = Vec3(
            p.x * cos(rad) + p.z * sin(rad),
            p.y,
            -p.x * sin(rad) + p.z * cos(rad),
        ) + offset

        val world = listOf(p0, p1, p2, p3).map(::place)
        val frame = assertNotNull(TableFrame.fitFromCorners(world))

        assertEquals(1000.0, frame.widthMm, 1e-6)
        assertEquals(2000.0, frame.lengthMm, 1e-6)

        // toWorld(toTable(p)) returns the point (corners and an interior point).
        val samples = world + place(Vec3(0.25, 0.0, 1.5))
        for (w in samples) {
            val back = frame.toWorld(frame.toTable(w))
            assertTrue(back.distanceTo(w) < 1e-9, "round trip drifted: $w -> $back")
        }

        // Interior point lands at the expected table coordinates.
        assertVec2(Vec2(250.0, 1500.0), frame.toTable(place(Vec3(0.25, 0.0, 1.5))))
    }

    @Test
    fun `noisy taps - dimensions averaged from opposite edges`() {
        // Corners off by a centimetre or two, and slightly off-plane.
        val noisy = listOf(
            Vec3(0.01, 0.005, -0.01),
            Vec3(0.99, -0.004, 0.015),
            Vec3(1.02, 0.006, 1.99),
            Vec3(-0.015, 0.0, 2.01),
        )
        val frame = assertNotNull(TableFrame.fitFromCorners(noisy))
        assertEquals(1000.0, frame.widthMm, 40.0)   // within a few cm
        assertEquals(2000.0, frame.lengthMm, 40.0)
    }

    @Test
    fun `axes are unit length and perpendicular`() {
        val frame = assertNotNull(TableFrame.fitFromCorners(listOf(p0, p1, p2, p3)))
        assertEquals(1.0, frame.xAxis.length(), 1e-12)
        assertEquals(1.0, frame.yAxis.length(), 1e-12)
        assertEquals(0.0, frame.xAxis dot frame.yAxis, 1e-12)
    }

    @Test
    fun `degenerate and implausible corner sets are rejected`() {
        // Collinear third corner.
        assertNull(TableFrame.fitFromCorners(listOf(p0, p1, Vec3(2.0, 0.0, 0.0), p3)))
        // Double-tap: two corners nearly coincide (edge below minimum).
        assertNull(TableFrame.fitFromCorners(listOf(p0, Vec3(0.05, 0.0, 0.0), p2, p3)))
        // Non-convex (bow-tie) ordering.
        assertNull(TableFrame.fitFromCorners(listOf(p0, p1, p3, p2)))
        // Wildly uneven opposite edges.
        assertNull(TableFrame.fitFromCorners(listOf(p0, p1, Vec3(1.0, 0.0, 0.5), Vec3(0.0, 0.0, 2.0))))
        // Wrong count.
        assertNull(TableFrame.fitFromCorners(listOf(p0, p1, p2)))
    }

    @Test
    fun `measured frame produces a working shot table`() {
        // End-to-end: calibrate from corners, then pot a straight ball using
        // the measured table. Ties Phase 1 output to the Phase 3 engine.
        val frame = assertNotNull(TableFrame.fitFromCorners(listOf(p0, p1, p2, p3)))
        val table = frame.table(GameType.POOL)
        val solver = ShotSolver(table)

        val pocket = table.pockets.first { it.id == PocketId.MIDDLE_LEFT }
        val cue = Ball("cue", Vec2(900.0, 1000.0))
        val obj = Ball("8", Vec2(500.0, 1000.0))
        val shot = solver.solveDirect(cue, obj, pocket)
        assertTrue(shot is ShotResult.Solution && !shot.isBank)
    }

    private fun assertVec2(expected: Vec2, actual: Vec2, tol: Double = 1e-9) {
        assertEquals(expected.x, actual.x, tol, "x of $actual")
        assertEquals(expected.y, actual.y, tol, "y of $actual")
    }
}

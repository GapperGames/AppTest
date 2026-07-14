package com.poolsight.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val TOL = 1e-9

class Vec2Test {

    @Test
    fun `basic vector arithmetic`() {
        val a = Vec2(3.0, 4.0)
        assertEquals(5.0, a.length(), TOL)
        assertEquals(Vec2(0.6, 0.8), a.normalized())
        assertEquals(5.0, Vec2(0.0, 0.0).distanceTo(a), TOL)
        assertEquals(Vec2(6.0, 8.0), a * 2.0)
        assertEquals(11.0, a dot Vec2(1.0, 2.0), TOL)
    }

    @Test
    fun `angle between perpendicular vectors is 90 degrees`() {
        assertEquals(90.0, Vec2.angleBetweenDegrees(Vec2(1.0, 0.0), Vec2(0.0, 5.0)), TOL)
        assertEquals(0.0, Vec2.angleBetweenDegrees(Vec2(2.0, 0.0), Vec2(7.0, 0.0)), TOL)
        assertEquals(180.0, Vec2.angleBetweenDegrees(Vec2(1.0, 0.0), Vec2(-3.0, 0.0)), TOL)
        assertEquals(45.0, Vec2.angleBetweenDegrees(Vec2(1.0, 0.0), Vec2(1.0, 1.0)), 1e-6)
    }

    @Test
    fun `distance from point to segment - perpendicular foot inside segment`() {
        // Point above the middle of a horizontal segment: plain perpendicular distance.
        val d = distancePointToSegment(Vec2(5.0, 3.0), Vec2(0.0, 0.0), Vec2(10.0, 0.0))
        assertEquals(3.0, d, TOL)
    }

    @Test
    fun `distance from point to segment - beyond an end uses the endpoint`() {
        // Point past the left end: distance is to the endpoint (3-4-5 triangle).
        val d = distancePointToSegment(Vec2(-4.0, 3.0), Vec2(0.0, 0.0), Vec2(10.0, 0.0))
        assertEquals(5.0, d, TOL)
    }

    @Test
    fun `reflection across a horizontal line flips y`() {
        val reflected = reflectPointAcrossLine(Vec2(2.0, 3.0), Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        assertEquals(Vec2(2.0, -3.0), reflected)
    }

    @Test
    fun `reflection across a vertical line mirrors x`() {
        // Line x = 5; point at x = 2 lands at x = 8.
        val reflected = reflectPointAcrossLine(Vec2(2.0, 3.0), Vec2(5.0, 0.0), Vec2(5.0, 1.0))
        assertEquals(8.0, reflected.x, TOL)
        assertEquals(3.0, reflected.y, TOL)
    }

    @Test
    fun `reflection is an involution - reflecting twice returns the point`() {
        val p = Vec2(123.4, -56.7)
        val a = Vec2(10.0, 20.0)
        val b = Vec2(-3.0, 7.5)
        val twice = reflectPointAcrossLine(reflectPointAcrossLine(p, a, b), a, b)
        assertEquals(p.x, twice.x, 1e-9)
        assertEquals(p.y, twice.y, 1e-9)
    }

    @Test
    fun `segment-line intersection at the expected point`() {
        // Segment rising diagonally crosses the horizontal line y=2 at (2,2).
        val hit = segmentLineIntersection(Vec2(1.0, 1.0), Vec2(3.0, 3.0), Vec2(0.0, 2.0), Vec2(10.0, 2.0))
        assertNotNull(hit)
        assertEquals(2.0, hit.x, TOL)
        assertEquals(2.0, hit.y, TOL)
    }

    @Test
    fun `segment parallel to line does not intersect`() {
        assertNull(segmentLineIntersection(Vec2(0.0, 1.0), Vec2(5.0, 1.0), Vec2(0.0, 2.0), Vec2(10.0, 2.0)))
    }

    @Test
    fun `segment stopping short of the line does not intersect`() {
        assertNull(segmentLineIntersection(Vec2(1.0, 0.0), Vec2(1.0, 1.5), Vec2(0.0, 2.0), Vec2(10.0, 2.0)))
    }
}

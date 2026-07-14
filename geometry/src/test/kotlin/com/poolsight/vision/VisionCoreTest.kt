package com.poolsight.vision

import com.poolsight.geometry.TableFrame
import com.poolsight.geometry.Vec2
import com.poolsight.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorMathTest {

    private val hsv = FloatArray(3)

    @Test
    fun `primary colours convert to the expected hues`() {
        ColorMath.rgbToHsv(255, 0, 0, hsv)
        assertEquals(0f, hsv[0], 0.5f)
        ColorMath.rgbToHsv(0, 255, 0, hsv)
        assertEquals(120f, hsv[0], 0.5f)
        ColorMath.rgbToHsv(0, 0, 255, hsv)
        assertEquals(240f, hsv[0], 0.5f)
    }

    @Test
    fun `white is bright and unsaturated, black is dark`() {
        ColorMath.rgbToHsv(250, 250, 245, hsv)
        assertTrue(hsv[1] < 0.05f && hsv[2] > 0.9f)
        ColorMath.rgbToHsv(15, 15, 18, hsv)
        assertTrue(hsv[2] < 0.1f)
    }

    @Test
    fun `hue distance wraps around 360`() {
        assertEquals(20f, ColorMath.hueDistance(350f, 10f), 1e-4f)
        assertEquals(180f, ColorMath.hueDistance(0f, 180f), 1e-4f)
    }

    @Test
    fun `hsv to rgb round-trips a saturated green`() {
        val rgb = FloatArray(3)
        ColorMath.hsvToRgb(120f, 1f, 1f, rgb)
        assertEquals(0f, rgb[0], 1e-4f)
        assertEquals(1f, rgb[1], 1e-4f)
        assertEquals(0f, rgb[2], 1e-4f)
    }
}

class FeltModelTest {

    @Test
    fun `learns cloth colour despite ball pixels in the sample`() {
        // 200 green cloth pixels + 40 assorted ball pixels.
        val n = 240
        val h = FloatArray(n)
        val s = FloatArray(n)
        val v = FloatArray(n)
        for (i in 0 until 200) { h[i] = 130f + (i % 10); s[i] = 0.7f; v[i] = 0.5f }
        for (i in 200 until 240) { h[i] = (i * 37f) % 360f; s[i] = 0.9f; v[i] = 0.8f }

        val felt = assertNotNull(FeltModel.learn(h, s, v, n))
        assertTrue(ColorMath.hueDistance(felt.hue, 134f) < 8f, "median hue ~cloth, got ${felt.hue}")
        assertTrue(felt.isFelt(132f, 0.65f, 0.48f))          // cloth pixel
        assertTrue(!felt.isFelt(30f, 0.9f, 0.7f))            // orange ball
        assertTrue(!felt.isFelt(132f, 0.05f, 0.95f))         // white ball (unsaturated)
    }

    @Test
    fun `refuses to learn from chaos`() {
        val n = 120
        val h = FloatArray(n) { (it * 53f) % 360f } // no dominant hue
        val s = FloatArray(n) { 0.8f }
        val v = FloatArray(n) { 0.6f }
        assertNull(FeltModel.learn(h, s, v, n))
    }
}

class BlobFinderTest {

    @Test
    fun `finds two round blobs with correct centroids`() {
        val w = 40
        val h = 30
        val mask = BooleanArray(w * h)
        fun disc(cx: Int, cy: Int, r: Int) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) mask[y * w + x] = true
            }
        }
        disc(10, 10, 4)
        disc(30, 20, 5)

        val blobs = BlobFinder.findBlobs(mask, w, h, minArea = 10, maxArea = 500)
        assertEquals(2, blobs.size)
        val first = blobs.minByOrNull { it.centroidX }!!
        val second = blobs.maxByOrNull { it.centroidX }!!
        assertEquals(10.0, first.centroidX, 0.01)
        assertEquals(10.0, first.centroidY, 0.01)
        assertEquals(30.0, second.centroidX, 0.01)
        assertEquals(20.0, second.centroidY, 0.01)
        assertTrue(first.roundness > 0.6)
    }

    @Test
    fun `size filter rejects specks and floods`() {
        val w = 30
        val h = 30
        val mask = BooleanArray(w * h)
        mask[5 * w + 5] = true // single-pixel speck
        for (y in 10 until 30) for (x in 0 until 30) mask[y * w + x] = true // huge flood

        val blobs = BlobFinder.findBlobs(mask, w, h, minArea = 4, maxArea = 200)
        assertEquals(0, blobs.size)
    }

    @Test
    fun `thin streaks are rejected as non-round`() {
        val w = 40
        val h = 20
        val mask = BooleanArray(w * h)
        for (x in 2 until 38) mask[8 * w + x] = true // 36×1 glare streak
        for (x in 2 until 38) mask[9 * w + x] = true

        val blobs = BlobFinder.findBlobs(mask, w, h, minArea = 10, maxArea = 500)
        assertEquals(0, blobs.size)
    }
}

class BallClassifierTest {

    private fun uniform(n: Int, h: Float, s: Float, v: Float) =
        Triple(FloatArray(n) { h }, FloatArray(n) { s }, FloatArray(n) { v })

    @Test
    fun `bright unsaturated blob is the cue ball`() {
        val (h, s, v) = uniform(100, 40f, 0.08f, 0.9f)
        assertEquals(BallClass.CUE, BallClassifier.classify(h, s, v, 100).ballClass)
    }

    @Test
    fun `dark blob is the black ball`() {
        val (h, s, v) = uniform(100, 0f, 0.3f, 0.08f)
        assertEquals(BallClass.BLACK, BallClassifier.classify(h, s, v, 100).ballClass)
    }

    @Test
    fun `saturated colour with little white is a solid`() {
        val (h, s, v) = uniform(100, 25f, 0.85f, 0.75f)
        val result = BallClassifier.classify(h, s, v, 100)
        assertEquals(BallClass.SOLID, result.ballClass)
        assertEquals(25f, result.hue, 1f)
    }

    @Test
    fun `colour plus a white band is a stripe`() {
        val n = 100
        val h = FloatArray(n)
        val s = FloatArray(n)
        val v = FloatArray(n)
        for (i in 0 until 60) { h[i] = 220f; s[i] = 0.8f; v[i] = 0.7f }  // blue
        for (i in 60 until n) { h[i] = 0f; s[i] = 0.05f; v[i] = 0.9f }   // white band
        val result = BallClassifier.classify(h, s, v, n)
        assertEquals(BallClass.STRIPE, result.ballClass)
        assertEquals(220f, result.hue, 2f)
    }
}

class BallTrackerTest {

    private val cueLook = BallAppearance(BallClass.CUE, Float.NaN, floatArrayOf(1f, 1f, 1f))

    @Test
    fun `ball is confirmed after repeated sightings and smoothed toward observations`() {
        val tracker = BallTracker(confirmAfterSightings = 3)

        assertTrue(tracker.update(0, listOf(BallTracker.Detection(Vec2(500.0, 500.0), cueLook))).isEmpty())
        assertTrue(tracker.update(100, listOf(BallTracker.Detection(Vec2(510.0, 500.0), cueLook))).isEmpty())
        val confirmed = tracker.update(200, listOf(BallTracker.Detection(Vec2(505.0, 500.0), cueLook)))

        assertEquals(1, confirmed.size)
        val pos = confirmed.single().position
        assertTrue(pos.distanceTo(Vec2(505.0, 500.0)) < 10.0, "smoothed position near observations, got $pos")
    }

    @Test
    fun `jitter is damped - single outlier moves the ball only fractionally`() {
        val tracker = BallTracker(confirmAfterSightings = 1, smoothing = 0.35)
        tracker.update(0, listOf(BallTracker.Detection(Vec2(500.0, 500.0), cueLook)))
        val after = tracker.update(100, listOf(BallTracker.Detection(Vec2(540.0, 500.0), cueLook)))
        assertEquals(514.0, after.single().position.x, 0.01) // 500 + 0.35×40
    }

    @Test
    fun `vanished ball expires`() {
        val tracker = BallTracker(confirmAfterSightings = 1, expireAfterMs = 1000)
        tracker.update(0, listOf(BallTracker.Detection(Vec2(500.0, 500.0), cueLook)))
        val later = tracker.update(2000, emptyList())
        assertTrue(later.isEmpty())
    }

    @Test
    fun `two distant detections are two balls, close ones are one`() {
        val tracker = BallTracker(confirmAfterSightings = 1, matchRadiusMm = 60.0)
        tracker.update(0, listOf(BallTracker.Detection(Vec2(500.0, 500.0), cueLook)))
        tracker.update(
            100,
            listOf(
                BallTracker.Detection(Vec2(520.0, 500.0), cueLook),   // same ball (within 60mm)
                BallTracker.Detection(Vec2(900.0, 1200.0), cueLook),  // a different ball
            ),
        )
        val balls = tracker.confirmed()
        assertEquals(2, balls.size)
        // The matched ball kept its identity (two sightings); the far one is new.
        assertEquals(1, balls.count { it.sightings == 2 })
        assertEquals(1, balls.count { it.sightings == 1 })
    }
}

class BallRayMappingTest {

    // 1m × 2m table on the floor: origin (0,0,0), x → (1,0,0), y → (0,0,1).
    private val frame = assertNotNull(
        TableFrame.fitFromCorners(
            listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(1.0, 0.0, 2.0), Vec3(0.0, 0.0, 2.0)),
        ),
    )

    @Test
    fun `looking straight down, the ball is exactly under the camera`() {
        val camera = Vec3(0.5, 1.5, 1.0) // above table point (500, 1000)
        val ball = assertNotNull(frame.ballCenterFromRay(camera, Vec3(0.0, -1.0, 0.0), ballRadiusMm = 28.575))
        assertEquals(500.0, ball.x, 1e-9)
        assertEquals(1000.0, ball.y, 1e-9)
    }

    @Test
    fun `oblique view - ball-height correction pulls the position toward the camera`() {
        // Camera 1m up at the origin corner, looking 45° down along +x:
        // the ray hits the CLOTH at x = 1m, but a ball centre plane sits
        // r = 28.575mm up, so the hit is at x = 1 − 0.028575 m.
        val camera = Vec3(0.0, 1.0, 1.0) // above table point (0, 1000)
        val dir = Vec3(1.0, -1.0, 0.0)
        val r = 28.575

        val ball = assertNotNull(frame.ballCenterFromRay(camera, dir, ballRadiusMm = r))
        assertEquals(1000.0 - r, ball.x, 1e-6)
        assertEquals(1000.0, ball.y, 1e-6)
    }

    @Test
    fun `rays that miss the table are rejected`() {
        val camera = Vec3(0.5, 1.0, 1.0)
        // Upward ray: no intersection.
        assertNull(frame.ballCenterFromRay(camera, Vec3(0.0, 1.0, 0.0), 28.575))
        // Ray landing far off the playing surface.
        assertNull(frame.ballCenterFromRay(camera, Vec3(5.0, -0.5, 0.0), 28.575))
    }
}

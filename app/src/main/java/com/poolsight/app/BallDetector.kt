package com.poolsight.app

import com.poolsight.vision.BallAppearance
import com.poolsight.vision.BallClassifier
import com.poolsight.vision.BlobFinder
import com.poolsight.vision.ColorMath
import com.poolsight.vision.FeltModel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Background ball detection (spec §4, on downsampled frames):
 * inside the projected table polygon, learn the cloth colour, mask everything
 * that isn't cloth, find round ball-sized blobs, classify them by colour.
 *
 * One frame in flight at a time; the renderer polls [takeResult] and maps
 * the pixel centroids to table space with the camera geometry it owns.
 */
class BallDetector {

    /** A detected ball, in FULL camera-image pixel coordinates. */
    class ImageBall(
        val pixelX: Float,
        val pixelY: Float,
        val appearance: BallAppearance,
    )

    class Result(val balls: List<ImageBall>, val feltLearnt: Boolean)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "poolsight-vision").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val busy = AtomicBoolean(false)
    private val pendingResult = AtomicReference<Result?>(null)

    val isIdle: Boolean get() = !busy.get()

    /** Latest finished detection, or null. Clears on read. */
    fun takeResult(): Result? = pendingResult.getAndSet(null)

    /**
     * Submit a frame. [polygon] is the table's outline in small-frame pixels
     * (x0,y0, x1,y1, …), [expectedBallRadiusPx] the ball radius at table
     * distance in small-frame pixels.
     */
    fun submit(frame: YuvDownsampler.Frame, polygon: FloatArray, expectedBallRadiusPx: Float) {
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                pendingResult.set(detect(frame, polygon, expectedBallRadiusPx))
            } catch (_: Throwable) {
                // A bad frame must never take the detector down; skip it.
            } finally {
                busy.set(false)
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun detect(
        frame: YuvDownsampler.Frame,
        polygon: FloatArray,
        expectedBallRadiusPx: Float,
    ): Result {
        val w = frame.width
        val h = frame.height
        val hsv = FloatArray(3)

        // Bounding box of the polygon clamped to the frame.
        var minX = w - 1f
        var minY = h - 1f
        var maxX = 0f
        var maxY = 0f
        for (i in polygon.indices step 2) {
            if (polygon[i] < minX) minX = polygon[i]
            if (polygon[i] > maxX) maxX = polygon[i]
            if (polygon[i + 1] < minY) minY = polygon[i + 1]
            if (polygon[i + 1] > maxY) maxY = polygon[i + 1]
        }
        val x0 = minX.roundToInt().coerceIn(0, w - 1)
        val x1 = maxX.roundToInt().coerceIn(0, w - 1)
        val y0 = minY.roundToInt().coerceIn(0, h - 1)
        val y1 = maxY.roundToInt().coerceIn(0, h - 1)
        if (x1 - x0 < 8 || y1 - y0 < 8) return Result(emptyList(), feltLearnt = false)

        // Learn the cloth colour from a sparse sample of in-polygon pixels.
        val sampleH = FloatArray(4096)
        val sampleS = FloatArray(4096)
        val sampleV = FloatArray(4096)
        var samples = 0
        val step = maxOf(1, ((x1 - x0) * (y1 - y0)) / 3500)
        var cursor = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (cursor++ % step != 0) continue
                if (!pointInPolygon(x + 0.5f, y + 0.5f, polygon)) continue
                if (samples >= sampleH.size) break
                val c = frame.rgb[y * w + x]
                ColorMath.rgbToHsv(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, hsv)
                sampleH[samples] = hsv[0]
                sampleS[samples] = hsv[1]
                sampleV[samples] = hsv[2]
                samples++
            }
        }
        val felt = FeltModel.learn(sampleH, sampleS, sampleV, samples)
            ?: return Result(emptyList(), feltLearnt = false)

        // Mask: inside polygon AND not cloth.
        val roiW = x1 - x0 + 1
        val roiH = y1 - y0 + 1
        val mask = BooleanArray(roiW * roiH)
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (!pointInPolygon(x + 0.5f, y + 0.5f, polygon)) continue
                val c = frame.rgb[y * w + x]
                ColorMath.rgbToHsv(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, hsv)
                if (!felt.isFelt(hsv[0], hsv[1], hsv[2])) {
                    mask[(y - y0) * roiW + (x - x0)] = true
                }
            }
        }

        // Ball-sized, round blobs only.
        val expectedArea = Math.PI * expectedBallRadiusPx * expectedBallRadiusPx
        val blobs = BlobFinder.findBlobs(
            mask, roiW, roiH,
            minArea = (expectedArea * 0.25).roundToInt().coerceAtLeast(6),
            maxArea = (expectedArea * 5.0).roundToInt(),
        )

        val balls = blobs.map { blob ->
            // Classify from the blob's own pixels.
            var n = 0
            val bh = FloatArray(blob.area)
            val bs = FloatArray(blob.area)
            val bv = FloatArray(blob.area)
            for (y in blob.minY..blob.maxY) {
                for (x in blob.minX..blob.maxX) {
                    if (!mask[y * roiW + x]) continue
                    val c = frame.rgb[(y + y0) * w + (x + x0)]
                    ColorMath.rgbToHsv(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, hsv)
                    bh[n] = hsv[0]; bs[n] = hsv[1]; bv[n] = hsv[2]
                    n++
                }
            }
            ImageBall(
                pixelX = (blob.centroidX.toFloat() + x0) * frame.scaleToFull,
                pixelY = (blob.centroidY.toFloat() + y0) * frame.scaleToFull,
                appearance = BallClassifier.classify(bh, bs, bv, n),
            )
        }
        return Result(balls, feltLearnt = true)
    }

    /** Ray-casting point-in-polygon (polygon as x,y pairs). */
    private fun pointInPolygon(px: Float, py: Float, poly: FloatArray): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            if ((yi > py) != (yj > py) &&
                px < (xj - xi) * (py - yi) / (yj - yi) + xi
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}

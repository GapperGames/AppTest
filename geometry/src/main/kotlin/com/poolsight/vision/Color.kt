package com.poolsight.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal colour maths for ball/felt classification. Pure Kotlin so the
 * whole vision core is unit-testable off-device.
 *
 * HSV convention: hue 0..360 (degrees), saturation 0..1, value 0..1.
 */
object ColorMath {

    /** Packs h into [out][0], s into [out][1], v into [out][2]. */
    fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val delta = maxC - minC

        val h = when {
            delta < 1e-6f -> 0f
            maxC == rf -> 60f * (((gf - bf) / delta + 6f) % 6f)
            maxC == gf -> 60f * ((bf - rf) / delta + 2f)
            else -> 60f * ((rf - gf) / delta + 4f)
        }
        out[0] = h
        out[1] = if (maxC < 1e-6f) 0f else delta / maxC
        out[2] = maxC
    }

    /** Smallest angular distance between two hues, in degrees (0..180). */
    fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** HSV → packed 0..1 RGB floats (for GL marker colours). */
    fun hsvToRgb(h: Float, s: Float, v: Float, out: FloatArray) {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        out[0] = r + m
        out[1] = g + m
        out[2] = b + m
    }
}

/**
 * A model of the cloth colour, learnt from pixels sampled inside the
 * calibrated table area. A pixel "is felt" when its hue sits near the
 * cloth's median hue with plausible saturation/brightness — everything
 * else inside the table polygon is ball, cushion shadow, or glare.
 */
class FeltModel private constructor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
) {

    fun isFelt(h: Float, s: Float, v: Float): Boolean {
        if (s < MIN_FELT_SATURATION) return false // white/grey is never cloth
        if (ColorMath.hueDistance(h, hue) > HUE_TOLERANCE_DEG) return false
        if (s < saturation * 0.45f) return false
        if (v < value * 0.35f || v > value * 1.9f) return false
        return true
    }

    companion object {
        const val HUE_TOLERANCE_DEG = 22f
        const val MIN_FELT_SATURATION = 0.15f

        /**
         * Learn the cloth colour from sampled HSV triples (parallel arrays).
         * Uses medians so balls inside the sample region don't skew it —
         * cloth dominates the playing surface. Null when there's no clear
         * dominant colour (too few samples).
         */
        fun learn(hues: FloatArray, sats: FloatArray, vals: FloatArray, count: Int): FeltModel? {
            if (count < 32) return null
            val idx = (0 until count).sortedBy { hues[it] }
            val medianHue = hues[idx[count / 2]]

            // Median s/v over pixels whose hue agrees with the median hue.
            val agreeing = (0 until count).filter {
                ColorMath.hueDistance(hues[it], medianHue) <= HUE_TOLERANCE_DEG
            }
            if (agreeing.size < count / 3) return null // no dominant cloth colour
            val s = agreeing.map { sats[it] }.sorted()[agreeing.size / 2]
            val v = agreeing.map { vals[it] }.sorted()[agreeing.size / 2]
            return FeltModel(medianHue, s, v)
        }
    }
}

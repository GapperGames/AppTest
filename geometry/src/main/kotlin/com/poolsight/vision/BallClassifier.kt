package com.poolsight.vision

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** What kind of ball a blob looks like, from its colour statistics. */
enum class BallClass { CUE, BLACK, SOLID, STRIPE, UNKNOWN }

data class BallAppearance(
    val ballClass: BallClass,
    /** Circular-mean hue of the coloured pixels, 0..360 (NaN if none). */
    val hue: Float,
    /** Display colour (0..1 RGB) for markers/overlays. */
    val displayRgb: FloatArray,
    /** Mean saturation over ALL pixels (0..1) — for relative whiteness. */
    val meanSaturation: Float = 0f,
    /** Mean brightness over ALL pixels (0..1) — for relative whiteness. */
    val meanValue: Float = 0f,
) {
    /**
     * How "white" this ball looks RELATIVE to others: bright and unsaturated
     * scores high. Absolute white thresholds fail under warm lighting (a
     * white ball under tungsten light reads cream); comparing balls against
     * each other doesn't.
     */
    val whitenessScore: Float get() = meanValue * (1f - meanSaturation)
}

/**
 * Classifies a ball blob from the HSV values of its pixels. Heuristics from
 * the design spec §4.3:
 *  - cue ball: overwhelmingly bright + unsaturated (white)
 *  - 8-ball / black: overwhelmingly dark
 *  - stripe: a colour AND a big white band (white fraction in a middle range)
 *  - solid: a colour with little white
 */
object BallClassifier {

    fun classify(hues: FloatArray, sats: FloatArray, vals: FloatArray, count: Int): BallAppearance {
        if (count == 0) return BallAppearance(BallClass.UNKNOWN, Float.NaN, floatArrayOf(0.6f, 0.6f, 0.6f))

        var white = 0
        var dark = 0
        var sumSin = 0.0
        var sumCos = 0.0
        var colored = 0
        var sumS = 0f
        var sumV = 0f
        var sumAllS = 0f
        var sumAllV = 0f

        for (i in 0 until count) {
            val h = hues[i]
            val s = sats[i]
            val v = vals[i]
            sumAllS += s
            sumAllV += v
            when {
                v < DARK_MAX_VALUE -> dark++
                s < WHITE_MAX_SATURATION && v > WHITE_MIN_VALUE -> white++
                else -> {
                    val rad = Math.toRadians(h.toDouble())
                    sumSin += sin(rad)
                    sumCos += cos(rad)
                    sumS += s
                    sumV += v
                    colored++
                }
            }
        }
        val meanAllS = sumAllS / count
        val meanAllV = sumAllV / count

        val whiteFrac = white.toFloat() / count
        val darkFrac = dark.toFloat() / count
        val meanHue = if (colored > 0) {
            ((Math.toDegrees(atan2(sumSin, sumCos)).toFloat()) + 360f) % 360f
        } else Float.NaN

        val ballClass = when {
            whiteFrac > 0.75f -> BallClass.CUE
            darkFrac > 0.70f -> BallClass.BLACK
            colored < count / 8 -> BallClass.UNKNOWN
            whiteFrac > 0.22f -> BallClass.STRIPE
            else -> BallClass.SOLID
        }

        val rgb = FloatArray(3)
        when (ballClass) {
            BallClass.CUE -> { rgb[0] = 1f; rgb[1] = 1f; rgb[2] = 0.95f }
            BallClass.BLACK, BallClass.UNKNOWN -> { rgb[0] = 0.25f; rgb[1] = 0.25f; rgb[2] = 0.25f }
            else -> ColorMath.hsvToRgb(
                meanHue,
                (sumS / colored).coerceIn(0.5f, 1f),
                (sumV / colored).coerceIn(0.6f, 1f),
                rgb,
            )
        }
        return BallAppearance(ballClass, meanHue, rgb, meanAllS, meanAllV)
    }

    const val WHITE_MAX_SATURATION = 0.28f
    const val WHITE_MIN_VALUE = 0.62f
    const val DARK_MAX_VALUE = 0.22f
}

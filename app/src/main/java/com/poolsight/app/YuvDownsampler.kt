package com.poolsight.app

import android.media.Image

/**
 * Downsamples an ARCore CPU image (YUV_420_888) straight to a small RGB
 * buffer for the vision pipeline. Nearest-neighbour sampling — detection
 * doesn't need interpolation, it needs speed. Runs on the GL thread right
 * after acquisition (a few ms at ~360px wide), so the Image can be closed
 * immediately.
 */
object YuvDownsampler {

    class Frame(
        val rgb: IntArray, // 0xRRGGBB
        val width: Int,
        val height: Int,
        /** Multiply small-frame coords by this to get full-image pixels. */
        val scaleToFull: Float,
    )

    fun downsample(image: Image, targetWidth: Int): Frame {
        val srcW = image.width
        val srcH = image.height
        val scale = maxOf(1, srcW / targetWidth)
        val outW = srcW / scale
        val outH = srcH / scale

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val rgb = IntArray(outW * outH)
        var i = 0
        for (oy in 0 until outH) {
            val sy = oy * scale
            val yRow = sy * yPlane.rowStride
            val uvRow = (sy / 2) * uPlane.rowStride
            for (ox in 0 until outW) {
                val sx = ox * scale
                val yVal = yBuf.get(yRow + sx * yPlane.pixelStride).toInt() and 0xFF
                val uVal = (uBuf.get(uvRow + (sx / 2) * uPlane.pixelStride).toInt() and 0xFF) - 128
                val vVal = (vBuf.get(uvRow + (sx / 2) * vPlane.pixelStride).toInt() and 0xFF) - 128

                // BT.601 full-range YUV → RGB.
                var r = yVal + (1.402f * vVal).toInt()
                var g = yVal - (0.344f * uVal).toInt() - (0.714f * vVal).toInt()
                var b = yVal + (1.772f * uVal).toInt()
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                rgb[i++] = (r shl 16) or (g shl 8) or b
            }
        }
        return Frame(rgb, outW, outH, scale.toFloat())
    }
}

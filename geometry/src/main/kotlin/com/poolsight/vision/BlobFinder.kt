package com.poolsight.vision

/**
 * Connected-component blob finding on a boolean mask ("not felt" pixels).
 * 4-connected flood fill with an explicit stack (no recursion). Pure Kotlin.
 */
object BlobFinder {

    data class Blob(
        val centroidX: Double,
        val centroidY: Double,
        val area: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1

        /** Balls are round: bounding box near-square and reasonably filled. */
        val roundness: Double
            get() {
                val w = width.toDouble()
                val h = height.toDouble()
                val aspect = if (w > h) h / w else w / h
                val fill = area / (w * h)
                return aspect * fill // circle in a square: 1.0 × π/4 ≈ 0.785
            }
    }

    /**
     * Find blobs of `true` pixels in [mask] (row-major, [width]×[height]).
     * Blobs outside [minArea]..[maxArea] are discarded, as are decidedly
     * non-round ones (cushion shadows, glare streaks, arms).
     */
    fun findBlobs(
        mask: BooleanArray,
        width: Int,
        height: Int,
        minArea: Int,
        maxArea: Int,
        minRoundness: Double = 0.35,
    ): List<Blob> {
        require(mask.size >= width * height) { "mask smaller than width*height" }
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val blobs = mutableListOf<Blob>()

        for (start in 0 until width * height) {
            if (!mask[start] || visited[start]) continue

            var stackSize = 0
            stack[stackSize++] = start
            visited[start] = true

            var area = 0
            var sumX = 0L
            var sumY = 0L
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE

            while (stackSize > 0) {
                val p = stack[--stackSize]
                val x = p % width
                val y = p / width
                area++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y

                // 4-neighbours.
                if (x > 0 && mask[p - 1] && !visited[p - 1]) {
                    visited[p - 1] = true; stack[stackSize++] = p - 1
                }
                if (x < width - 1 && mask[p + 1] && !visited[p + 1]) {
                    visited[p + 1] = true; stack[stackSize++] = p + 1
                }
                if (y > 0 && mask[p - width] && !visited[p - width]) {
                    visited[p - width] = true; stack[stackSize++] = p - width
                }
                if (y < height - 1 && mask[p + width] && !visited[p + width]) {
                    visited[p + width] = true; stack[stackSize++] = p + width
                }
            }

            if (area in minArea..maxArea) {
                val blob = Blob(
                    centroidX = sumX.toDouble() / area,
                    centroidY = sumY.toDouble() / area,
                    area = area,
                    minX = minX, minY = minY, maxX = maxX, maxY = maxY,
                )
                if (blob.roundness >= minRoundness) blobs += blob
            }
        }
        return blobs
    }
}

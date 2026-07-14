package com.poolsight.vision

import com.poolsight.geometry.Vec2

/**
 * Temporal smoothing over per-frame detections (spec §4.5). Balls are static
 * between shots, so we match detections to known balls by proximity, smooth
 * positions with an exponential moving average to kill jitter, and expire
 * balls that vanish (potted, occluded too long, or false positives).
 *
 * Time is injected (millis) so this is fully testable.
 */
class BallTracker(
    private val matchRadiusMm: Double = 60.0,
    private val smoothing: Double = 0.35, // weight of the NEW observation
    private val expireAfterMs: Long = 2500,
    private val confirmAfterSightings: Int = 3,
) {

    data class Detection(val position: Vec2, val appearance: BallAppearance)

    data class TrackedBall(
        val id: Int,
        val position: Vec2,
        val appearance: BallAppearance,
        val sightings: Int,
        val lastSeenMs: Long,
    ) {
        /** Only balls seen several times are shown — kills one-frame ghosts. */
        fun isConfirmed(confirmAfter: Int): Boolean = sightings >= confirmAfter
    }

    private val balls = mutableListOf<TrackedBall>()
    private var nextId = 1

    /** Feed one detection batch; returns the confirmed balls to display. */
    fun update(nowMs: Long, detections: List<Detection>): List<TrackedBall> {
        val unmatched = detections.toMutableList()

        for (i in balls.indices) {
            val ball = balls[i]
            val nearest = unmatched.minByOrNull { it.position.distanceTo(ball.position) }
            if (nearest != null && nearest.position.distanceTo(ball.position) <= matchRadiusMm) {
                unmatched.remove(nearest)
                balls[i] = ball.copy(
                    position = ball.position * (1.0 - smoothing) + nearest.position * smoothing,
                    appearance = nearest.appearance,
                    sightings = ball.sightings + 1,
                    lastSeenMs = nowMs,
                )
            }
        }

        for (d in unmatched) {
            balls += TrackedBall(nextId++, d.position, d.appearance, sightings = 1, lastSeenMs = nowMs)
        }

        balls.removeAll { nowMs - it.lastSeenMs > expireAfterMs }
        return confirmed()
    }

    fun confirmed(): List<TrackedBall> = balls.filter { it.isConfirmed(confirmAfterSightings) }

    fun clear() {
        balls.clear()
    }
}

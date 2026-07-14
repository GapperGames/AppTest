package com.poolsight.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.poolsight.geometry.Ball
import com.poolsight.geometry.GameType
import com.poolsight.geometry.ShotProblem
import com.poolsight.geometry.ShotResult
import com.poolsight.geometry.ShotSolver
import com.poolsight.geometry.Table
import com.poolsight.geometry.TableFrame
import com.poolsight.geometry.Vec2
import com.poolsight.geometry.Vec3
import com.poolsight.vision.BallClass
import com.poolsight.vision.BallTracker
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.sin
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GL renderer, Phase 1: camera background + tap-to-calibrate table setup.
 *
 * Flow: find a horizontal surface (Phase 0) → user taps the four inside
 * corners of the cushions → a [TableFrame] is fitted from the tapped points
 * (metric, thanks to ARCore) → the table outline, a grid, and the six pocket
 * positions are drawn hugging the cloth. The grid lying flat on the real
 * table is the Phase 1 acceptance test.
 */
class ArRenderer(
    context: Context,
    private val onStatus: (String) -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    val displayRotationHelper = DisplayRotationHelper(context)

    private val appContext = context.applicationContext
    private val background = BackgroundRenderer()
    private val points = PointRenderer()
    private val lines = LineRenderer()

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)

    // --- calibration state (GL thread only, except the queues/flags) ---
    private val pendingTaps = ConcurrentLinkedQueue<FloatArray>()
    @Volatile
    private var resetRequested = false

    private val cornerAnchors = mutableListOf<Anchor>()
    private var tableFrame: TableFrame? = null
    private var gameType = GameType.POOL

    // --- ball detection (Phase 2) ---
    private val detector = BallDetector()
    private val tracker = BallTracker()
    private var trackedBalls: List<BallTracker.TrackedBall> = emptyList()
    private var lastDetectionSubmitMs = 0L
    private var viewportWidth = 1
    private var viewportHeight = 1
    private val inverseViewProjection = FloatArray(16)
    private val scratch4 = FloatArray(4)
    private val scratch4b = FloatArray(4)

    // --- shot selection & freeze (Phases 3+4) ---
    private var selectedBallId: Int? = null
    private var selectedPocketIndex: Int? = null
    @Volatile
    var frozen = false

    private var lastStatus = ""

    /** Screen tap from the UI thread; consumed on the GL thread. */
    fun onTap(x: Float, y: Float) {
        pendingTaps.add(floatArrayOf(x, y))
    }

    /** Restart corner calibration (UI thread). */
    fun requestReset() {
        resetRequested = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        background.createOnGlThread()
        points.createOnGlThread()
        lines.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return

        try {
            displayRotationHelper.updateSessionIfNeeded(session)
            session.setCameraTextureName(background.textureId)
            val frame = session.update()
            background.draw(frame)

            if (resetRequested) {
                resetRequested = false
                clearCalibration()
            }

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                pendingTaps.clear()
                report(appContext.getString(R.string.status_move_phone))
                return
            }

            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, NEAR_CLIP, FAR_CLIP)
            android.opengl.Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0)

            handleTaps(frame)
            refitTableIfReady()

            val planesTracked = session.getAllTrackables(Plane::class.java)
                .any { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }

            drawCornerMarkers()
            val frameNow = tableFrame
            if (frameNow != null) {
                android.opengl.Matrix.invertM(inverseViewProjection, 0, viewProjection, 0)
                if (!frozen) {
                    ingestDetections(frame, frameNow)
                    maybeSubmitDetection(frame, frameNow)
                }

                drawTable(frameNow)
                drawBalls(frameNow)
                val shotStatus = solveAndDrawShot(frameNow)

                val dims = String.format("%.2f m × %.2f m", frameNow.widthMm / 1000.0, frameNow.lengthMm / 1000.0)
                val base = shotStatus ?: if (trackedBalls.isEmpty()) {
                    appContext.getString(R.string.status_table_locked, dims)
                } else {
                    appContext.getString(R.string.status_balls_seen, dims, trackedBalls.size)
                }
                report(if (frozen) appContext.getString(R.string.frozen_status, base) else base)
            } else if (!planesTracked) {
                report(appContext.getString(R.string.status_searching))
            } else {
                report(appContext.getString(R.string.status_tap_corner, cornerAnchors.size + 1))
            }
        } catch (e: CameraNotAvailableException) {
            report(appContext.getString(R.string.status_camera_unavailable))
        } catch (e: Throwable) {
            // Never let a per-frame hiccup kill the render thread.
            report(appContext.getString(R.string.status_frame_error, e.javaClass.simpleName))
        }
    }

    // ---- calibration ---------------------------------------------------------

    private fun handleTaps(frame: Frame) {
        while (true) {
            val tap = pendingTaps.poll() ?: return
            if (cornerAnchors.size >= 4) {
                // Calibrated: taps select the ball to pot, then the pocket.
                if (!frozen) tableFrame?.let { handleSelectionTap(tap, it) }
                continue
            }

            val hit = frame.hitTest(tap[0], tap[1]).firstOrNull { h ->
                val plane = h.trackable as? Plane ?: return@firstOrNull false
                plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    plane.trackingState == TrackingState.TRACKING &&
                    plane.isPoseInPolygon(h.hitPose)
            } ?: continue

            val position = hit.hitPose.toVec3()
            // Ignore accidental double-taps on an existing corner.
            if (cornerAnchors.any { it.pose.toVec3().distanceTo(position) < MIN_CORNER_SPACING_M }) continue

            cornerAnchors.add(hit.createAnchor())
        }
    }

    /**
     * (Re)fit the table frame from the corner anchors every frame — anchors
     * drift-correct as ARCore refines its map, and refitting keeps the grid
     * glued to the improved positions. Cheap: four points of vector maths.
     */
    private fun refitTableIfReady() {
        if (cornerAnchors.size < 4) {
            tableFrame = null
            return
        }
        val corners = cornerAnchors.map { it.pose.toVec3() }
        val fitted = TableFrame.fitFromCorners(corners)
        if (fitted == null) {
            // Corners don't form a plausible rectangle: start over.
            clearCalibration()
            report(appContext.getString(R.string.status_bad_rectangle))
        } else {
            tableFrame = fitted
        }
    }

    private fun clearCalibration() {
        cornerAnchors.forEach { it.detach() }
        cornerAnchors.clear()
        tableFrame = null
        tracker.clear()
        trackedBalls = emptyList()
        selectedBallId = null
        selectedPocketIndex = null
        frozen = false
    }

    // ---- shot selection & aiming overlay (Phases 3+4) --------------------------

    /** Tap on the calibrated table: pick the object ball, then the pocket. */
    private fun handleSelectionTap(tap: FloatArray, table: TableFrame) {
        val ndcX = 2f * tap[0] / viewportWidth - 1f
        val ndcY = 1f - 2f * tap[1] / viewportHeight
        val (origin, dir) = unprojectRay(ndcX, ndcY) ?: return
        val tapPos = table.ballCenterFromRay(origin, dir, gameType.ballRadiusMm, marginMm = 250.0) ?: return

        // A tracked ball near the tap → select it as the object ball.
        val ball = trackedBalls.minByOrNull { it.position.distanceTo(tapPos) }
        if (ball != null && ball.position.distanceTo(tapPos) < BALL_PICK_MM) {
            if (ball.appearance.ballClass == BallClass.CUE) {
                transientStatus = appContext.getString(R.string.status_thats_cue)
            } else {
                selectedBallId = ball.id
                selectedPocketIndex = null
            }
            return
        }

        // A pocket near the tap (with a ball already chosen) → set the target.
        if (selectedBallId != null) {
            val pockets = table.table(gameType).pockets
            val nearest = pockets.withIndex().minByOrNull { it.value.center.distanceTo(tapPos) }
            if (nearest != null && nearest.value.center.distanceTo(tapPos) < POCKET_PICK_MM) {
                selectedPocketIndex = nearest.index
                return
            }
        }

        // Empty felt: clear the shot.
        selectedBallId = null
        selectedPocketIndex = null
    }

    /** One-shot status hint (e.g. "that's the cue ball"), shown for a moment. */
    private var transientStatus: String? = null
    private var transientStatusUntilMs = 0L

    /**
     * Solve the selected shot against current ball positions and draw the
     * overlay. Returns the status line to show, or null when no shot is
     * in progress.
     */
    private fun solveAndDrawShot(table: TableFrame): String? {
        val now = SystemClock.uptimeMillis()
        transientStatus?.let {
            if (transientStatusUntilMs == 0L) transientStatusUntilMs = now + 2500
            if (now < transientStatusUntilMs) return it
            transientStatus = null
            transientStatusUntilMs = 0L
        }

        val objectId = selectedBallId ?: return null
        val objectBall = trackedBalls.firstOrNull { it.id == objectId }
        if (objectBall == null) {
            selectedBallId = null
            selectedPocketIndex = null
            return null
        }

        // Highlight the selected ball.
        drawMarkerRing(table, objectBall.position, 1f, 0.85f, 0.2f, 30.0)

        val pocketIdx = selectedPocketIndex ?: return appContext.getString(R.string.status_select_pocket)

        val cue = trackedBalls.firstOrNull { it.appearance.ballClass == BallClass.CUE }
            ?: return appContext.getString(R.string.status_no_cue)

        val gameTable = table.table(gameType)
        val pocket = gameTable.pockets[pocketIdx]
        val solver = ShotSolver(gameTable)

        val cueBall = Ball("cue", cue.position)
        val objBall = Ball("obj", objectBall.position)
        val others = trackedBalls
            .filter { it.id != objectId && it.id != cue.id }
            .map { Ball(it.id.toString(), it.position) }

        // Direct first; fall back to the best bank.
        val direct = solver.solveDirect(cueBall, objBall, pocket, others)
        val solution = direct as? ShotResult.Solution
            ?: solver.solveAll(cueBall, objBall, pocket, others).firstOrNull()

        if (solution == null) {
            return when ((direct as? ShotResult.Impossible)?.problem) {
                ShotProblem.CUE_PATH_BLOCKED -> appContext.getString(R.string.shot_cue_blocked)
                ShotProblem.OBJECT_PATH_BLOCKED -> appContext.getString(R.string.shot_object_blocked)
                else -> appContext.getString(R.string.shot_impossible)
            }
        }

        drawShot(table, cueBall, solution)

        val cutDeg = solution.cutAngleDegrees.toInt()
        val difficulty = solution.difficulty.name.replace('_', ' ')
        return if (solution.isBank) {
            appContext.getString(R.string.shot_bank, cutDeg, difficulty)
        } else {
            appContext.getString(R.string.shot_direct, cutDeg, difficulty)
        }
    }

    /** Aim line, ghost ball, contact spot, object path — the actual cheat. */
    private fun drawShot(table: TableFrame, cue: Ball, shot: ShotResult.Solution) {
        // Aim line: cue centre → ghost centre. Bold white.
        drawTableLines(table, listOf(cue.center to shot.ghost), 1f, 1f, 1f, 0.95f, 8f)

        // Object path (dashed amber): O → P, or O → bounce → P for banks.
        val dashes = mutableListOf<Pair<Vec2, Vec2>>()
        for (i in 0 until shot.objectPath.size - 1) {
            dashes += dashSegments(shot.objectPath[i], shot.objectPath[i + 1])
        }
        drawTableLines(table, dashes, 1f, 0.75f, 0.25f, 0.95f, 6f)

        // Ghost ball: faint circle where the cue ball must arrive.
        drawCircle(table, shot.ghost, gameType.ballRadiusMm, 1f, 1f, 1f, 0.8f)

        // Contact spot on the object ball.
        drawMarkerDot(table, shot.contactPoint, 1f, 0.3f, 0.25f, 16f)
    }

    private fun dashSegments(a: Vec2, b: Vec2, dashMm: Double = 60.0, gapMm: Double = 45.0): List<Pair<Vec2, Vec2>> {
        val total = a.distanceTo(b)
        if (total < 1.0) return emptyList()
        val dir = (b - a).normalized()
        val out = mutableListOf<Pair<Vec2, Vec2>>()
        var d = 0.0
        while (d < total) {
            val end = minOf(d + dashMm, total)
            out += (a + dir * d) to (a + dir * end)
            d = end + gapMm
        }
        return out
    }

    private fun drawTableLines(
        table: TableFrame,
        segments: List<Pair<Vec2, Vec2>>,
        r: Float, g: Float, b: Float, alpha: Float, widthPx: Float,
    ) {
        if (segments.isEmpty()) return
        val lift = gameType.ballRadiusMm / 1000.0
        val up = table.upNormal
        val vertices = FloatArray(segments.size * 6)
        segments.forEachIndexed { i, (p, q) ->
            (table.toWorld(p) + up * lift).into(vertices, i * 6)
            (table.toWorld(q) + up * lift).into(vertices, i * 6 + 3)
        }
        lines.draw(viewProjection, vertices, segments.size * 2, r, g, b, alpha, widthPx)
    }

    private fun drawCircle(
        table: TableFrame,
        center: Vec2,
        radiusMm: Double,
        r: Float, g: Float, b: Float, alpha: Float,
        segments: Int = 24,
    ) {
        val pts = mutableListOf<Pair<Vec2, Vec2>>()
        var prev = center + Vec2(radiusMm, 0.0)
        for (i in 1..segments) {
            val angle = 2.0 * Math.PI * i / segments
            val next = center + Vec2(radiusMm * cos(angle), radiusMm * sin(angle))
            pts += prev to next
            prev = next
        }
        drawTableLines(table, pts, r, g, b, alpha, 5f)
    }

    private fun drawMarkerDot(table: TableFrame, pos: Vec2, r: Float, g: Float, b: Float, sizePx: Float) {
        val world = table.toWorld(pos) + table.upNormal * (gameType.ballRadiusMm / 1000.0)
        val v = FloatArray(3)
        world.into(v, 0)
        points.draw(viewProjection, v, 1, red = r, green = g, blue = b, pointSizePx = sizePx)
    }

    private fun drawMarkerRing(table: TableFrame, pos: Vec2, r: Float, g: Float, b: Float, extraRadiusMm: Double) {
        drawCircle(table, pos, gameType.ballRadiusMm + extraRadiusMm, r, g, b, 0.9f)
    }

    // ---- ball detection (Phase 2) ---------------------------------------------

    /**
     * Map any finished detection from image pixels to table space and feed
     * the tracker. Mapping: image px → view px (ARCore's display transform)
     * → NDC → unproject through the inverse view-projection → world ray →
     * intersect the ball-centre plane (one radius above the cloth).
     */
    private fun ingestDetections(frame: Frame, table: TableFrame) {
        val result = detector.takeResult() ?: return
        if (result.balls.isEmpty()) {
            trackedBalls = tracker.update(SystemClock.uptimeMillis(), emptyList())
            return
        }

        val imagePx = FloatArray(result.balls.size * 2)
        result.balls.forEachIndexed { i, b ->
            imagePx[i * 2] = b.pixelX
            imagePx[i * 2 + 1] = b.pixelY
        }
        val viewPx = FloatArray(imagePx.size)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS, imagePx,
            Coordinates2d.VIEW, viewPx,
        )

        val ballRadiusMm = gameType.ballRadiusMm
        val detections = mutableListOf<BallTracker.Detection>()
        result.balls.forEachIndexed { i, ball ->
            val ndcX = 2f * viewPx[i * 2] / viewportWidth - 1f
            val ndcY = 1f - 2f * viewPx[i * 2 + 1] / viewportHeight
            val (origin, dir) = unprojectRay(ndcX, ndcY) ?: return@forEachIndexed
            val tablePos = table.ballCenterFromRay(origin, dir, ballRadiusMm) ?: return@forEachIndexed
            detections += BallTracker.Detection(tablePos, ball.appearance)
        }
        trackedBalls = tracker.update(SystemClock.uptimeMillis(), detections)
    }

    /** Screen-NDC point → world-space ray, inverse of the rendering transform. */
    private fun unprojectRay(ndcX: Float, ndcY: Float): Pair<Vec3, Vec3>? {
        fun unproject(z: Float, out: FloatArray): Vec3? {
            scratch4[0] = ndcX; scratch4[1] = ndcY; scratch4[2] = z; scratch4[3] = 1f
            android.opengl.Matrix.multiplyMV(out, 0, inverseViewProjection, 0, scratch4, 0)
            if (kotlin.math.abs(out[3]) < 1e-9f) return null
            return Vec3(
                (out[0] / out[3]).toDouble(),
                (out[1] / out[3]).toDouble(),
                (out[2] / out[3]).toDouble(),
            )
        }
        val near = unproject(-1f, scratch4b) ?: return null
        val far = unproject(1f, scratch4b) ?: return null
        val dir = (far - near).normalizedOrNull() ?: return null
        return near to dir
    }

    /** Grab + downsample a CPU frame and hand it to the background detector. */
    private fun maybeSubmitDetection(frame: Frame, table: TableFrame) {
        val now = SystemClock.uptimeMillis()
        if (!detector.isIdle || now - lastDetectionSubmitMs < DETECTION_INTERVAL_MS) return

        // Table polygon (slightly expanded) in view px, then image px.
        val w = table.widthMm
        val l = table.lengthMm
        val cx = w / 2.0
        val cy = l / 2.0
        val corners = listOf(Vec2(0.0, 0.0), Vec2(w, 0.0), Vec2(w, l), Vec2(0.0, l)).map {
            Vec2(cx + (it.x - cx) * 1.05, cy + (it.y - cy) * 1.05)
        }
        val viewPoly = FloatArray(8)
        for (i in corners.indices) {
            val world = table.toWorld(corners[i])
            scratch4[0] = world.x.toFloat(); scratch4[1] = world.y.toFloat()
            scratch4[2] = world.z.toFloat(); scratch4[3] = 1f
            android.opengl.Matrix.multiplyMV(scratch4b, 0, viewProjection, 0, scratch4, 0)
            if (scratch4b[3] <= 0f) return // corner behind the camera: skip this frame
            val ndcX = scratch4b[0] / scratch4b[3]
            val ndcY = scratch4b[1] / scratch4b[3]
            viewPoly[i * 2] = (ndcX * 0.5f + 0.5f) * viewportWidth
            viewPoly[i * 2 + 1] = (1f - (ndcY * 0.5f + 0.5f)) * viewportHeight
        }
        val imagePoly = FloatArray(8)
        frame.transformCoordinates2d(
            Coordinates2d.VIEW, viewPoly,
            Coordinates2d.IMAGE_PIXELS, imagePoly,
        )

        // Expected ball radius in full-image pixels: fx · r / distance.
        val camera = frame.camera
        val camPos = camera.pose.toVec3()
        val distance = camPos.distanceTo(table.toWorld(Vec2(cx, cy))).coerceAtLeast(0.2)
        val fx = camera.imageIntrinsics.focalLength[0]
        val radiusFullPx = (fx * (gameType.ballRadiusMm / 1000.0) / distance).toFloat()

        val image = try {
            frame.acquireCameraImage()
        } catch (_: Exception) {
            return // not yet available / resource pressure: try next frame
        }
        val small = try {
            YuvDownsampler.downsample(image, targetWidth = 360)
        } finally {
            image.close()
        }

        val smallPoly = FloatArray(8) { imagePoly[it] / small.scaleToFull }
        detector.submit(small, smallPoly, radiusFullPx / small.scaleToFull)
        lastDetectionSubmitMs = now
    }

    /** Colour-coded dots hovering at each tracked ball's centre. */
    private fun drawBalls(table: TableFrame) {
        val balls = trackedBalls
        if (balls.isEmpty()) return
        val up = table.upNormal
        val lift = gameType.ballRadiusMm / 1000.0

        val position = FloatArray(3)
        for (ball in balls) {
            val world = table.toWorld(ball.position) + up * lift
            position[0] = world.x.toFloat()
            position[1] = world.y.toFloat()
            position[2] = world.z.toFloat()
            val rgb = ball.appearance.displayRgb
            points.draw(
                viewProjection, position, 1,
                red = rgb[0], green = rgb[1], blue = rgb[2],
                pointSizePx = 38f,
            )
        }
    }

    // ---- drawing -------------------------------------------------------------

    private fun drawCornerMarkers() {
        if (cornerAnchors.isEmpty()) return
        val positions = FloatArray(cornerAnchors.size * 3)
        cornerAnchors.forEachIndexed { i, anchor ->
            val t = anchor.pose.translation
            positions[i * 3] = t[0]
            positions[i * 3 + 1] = t[1]
            positions[i * 3 + 2] = t[2]
        }
        // Amber corner pins.
        points.draw(viewProjection, positions, cornerAnchors.size, red = 0.95f, green = 0.7f, blue = 0.25f, pointSizePx = 44f)
    }

    private fun drawTable(frame: TableFrame) {
        val w = frame.widthMm
        val l = frame.lengthMm

        // Outline + grid lines in table space (mm), lifted to world space.
        val segments = mutableListOf<Pair<Vec2, Vec2>>()
        segments += Vec2(0.0, 0.0) to Vec2(w, 0.0)
        segments += Vec2(w, 0.0) to Vec2(w, l)
        segments += Vec2(w, l) to Vec2(0.0, l)
        segments += Vec2(0.0, l) to Vec2(0.0, 0.0)

        var x = GRID_STEP_MM
        while (x < w) {
            segments += Vec2(x, 0.0) to Vec2(x, l)
            x += GRID_STEP_MM
        }
        var y = GRID_STEP_MM
        while (y < l) {
            segments += Vec2(0.0, y) to Vec2(w, y)
            y += GRID_STEP_MM
        }

        val vertices = FloatArray(segments.size * 2 * 3)
        segments.forEachIndexed { i, (a, b) ->
            frame.toWorld(a).into(vertices, i * 6)
            frame.toWorld(b).into(vertices, i * 6 + 3)
        }

        // Chalk-blue grid, slightly transparent; bold outline drawn on top.
        lines.draw(viewProjection, vertices, segments.size * 2, red = 0.35f, green = 0.75f, blue = 0.85f, alpha = 0.55f, widthPx = 4f)
        lines.draw(viewProjection, vertices, 8, red = 0.35f, green = 0.85f, blue = 0.95f, alpha = 0.95f, widthPx = 8f)

        // Pocket markers from the geometry model (positions derive from the rectangle).
        val table: Table = frame.table(gameType)
        val pockets = FloatArray(table.pockets.size * 3)
        table.pockets.forEachIndexed { i, pocket ->
            frame.toWorld(pocket.center).into(pockets, i * 3)
        }
        points.draw(viewProjection, pockets, table.pockets.size, red = 0.1f, green = 0.1f, blue = 0.1f, pointSizePx = 52f)
    }

    private fun report(status: String) {
        if (status != lastStatus) {
            lastStatus = status
            onStatus(status)
        }
    }

    private fun com.google.ar.core.Pose.toVec3(): Vec3 =
        Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble())

    private fun Vec3.into(target: FloatArray, offset: Int) {
        target[offset] = x.toFloat()
        target[offset + 1] = y.toFloat()
        target[offset + 2] = z.toFloat()
    }

    private companion object {
        const val NEAR_CLIP = 0.1f
        const val FAR_CLIP = 100f
        const val GRID_STEP_MM = 250.0
        const val MIN_CORNER_SPACING_M = 0.25
        const val DETECTION_INTERVAL_MS = 250L
        const val BALL_PICK_MM = 80.0
        const val POCKET_PICK_MM = 220.0
    }
}

package com.poolsight.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.poolsight.geometry.Ball
import com.poolsight.geometry.GameType
import com.poolsight.geometry.ShotProblem
import com.poolsight.geometry.ShotResult
import com.poolsight.geometry.ShotSolver
import com.poolsight.geometry.TableFrame
import com.poolsight.geometry.Vec2
import com.poolsight.geometry.Vec3
import com.poolsight.vision.BallTracker
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.sin

/** Which tab the user is on. TABLE = set up the table; PLAY = aim shots. */
enum class UiMode { TABLE, PLAY }

/**
 * GL renderer for the whole PoolSight flow.
 *
 * TABLE tab: tap two diagonally opposite pocket corners; the 2:1 playing
 * surface is reconstructed from the diagonal (⇄ Flip resolves the mirror
 * ambiguity). Grid + pockets drawn as proof of lock.
 *
 * PLAY tab: balls detected and marked, tap a ball + a pocket, press
 * "Create shot" to draw the aim line / ghost ball / contact spot / path.
 * Freeze locks the overlay for taking the shot.
 */
/** The guided pick sequence in PLAY: white ball → coloured ball → pocket. */
enum class SelectStep { CUE, OBJECT, POCKET, READY }

class ArRenderer(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onCalibrated: () -> Unit,
    /** Called (GL thread) when a tap lands on empty felt: offer a manual ball. */
    private val onManualBallOffer: (stepLabel: String) -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    @Volatile
    var uiMode = UiMode.TABLE

    @Volatile
    var frozen = false

    val displayRotationHelper = DisplayRotationHelper(context)

    private val appContext = context.applicationContext
    private val background = BackgroundRenderer()
    private val points = PointRenderer()
    private val lines = LineRenderer()

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val inverseViewProjection = FloatArray(16)
    private val scratch4 = FloatArray(4)
    private val scratch4b = FloatArray(4)
    private var viewportWidth = 1
    private var viewportHeight = 1

    // --- cross-thread requests (UI thread writes, GL thread consumes) ---
    private val pendingTaps = ConcurrentLinkedQueue<FloatArray>()
    @Volatile
    private var resetRequested = false
    @Volatile
    private var flipRequested = false
    @Volatile
    private var shotRequestPending = false
    @Volatile
    private var clearPicksRequested = false

    // --- calibration state (GL thread) ---
    private val cornerAnchors = mutableListOf<Anchor>() // max 2: diagonal corners
    private var diagonalMirrored = false
    private var tableFrame: TableFrame? = null
    private var everCalibrated = false
    @Volatile private var gameType = GameType.POOL
    @Volatile private var gameTypeRequest: GameType? = null

    // --- ball detection ---
    private val detector = BallDetector()
    private val tracker = BallTracker()
    private var trackedBalls: List<BallTracker.TrackedBall> = emptyList()
    private var lastDetectionSubmitMs = 0L

    // --- shot state (guided: white → coloured → pocket) ---
    private var cuePick: BallPick? = null
    private var objPick: BallPick? = null
    private var selectedPocketIndex: Int? = null
    private var shotActive = false

    // --- manual-ball fallback (tap empty felt) ---
    @Volatile private var manualConfirmRequested = false
    @Volatile private var manualCancelRequested = false
    private var awaitingManualResponse = false
    private var pendingManualPos: Vec2? = null
    private var pendingManualStep: SelectStep? = null

    /** A chosen ball: a live tracked ball, or a fixed spot the user vouched for. */
    private sealed class BallPick {
        data class Tracked(val id: Int) : BallPick()
        data class Manual(val pos: Vec2) : BallPick()
    }

    private var lastStatus = ""
    private var transientStatus: String? = null
    private var transientStatusUntilMs = 0L

    // ---- UI-thread API ---------------------------------------------------------

    fun onTap(x: Float, y: Float) {
        pendingTaps.add(floatArrayOf(x, y))
    }

    fun requestReset() {
        resetRequested = true
    }

    fun requestFlip() {
        flipRequested = true
    }

    fun requestShot() {
        shotRequestPending = true
    }

    /** Start the shot picks over (white → coloured → pocket). */
    fun requestClearPicks() {
        clearPicksRequested = true
    }

    /** Switch pool ⇄ snooker (changes ball size; re-detects). */
    fun requestGameType(type: GameType) {
        gameTypeRequest = type
    }

    val gameTypeSetting: GameType get() = gameType

    /** User answered the "use this spot anyway?" dialog. */
    fun confirmManualBall() {
        manualConfirmRequested = true
    }

    fun cancelManualBall() {
        manualCancelRequested = true
    }

    // ---- GLSurfaceView.Renderer -------------------------------------------------

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        background.createOnGlThread()
        points.createOnGlThread()
        lines.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
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
            if (flipRequested) {
                flipRequested = false
                diagonalMirrored = !diagonalMirrored
            }
            if (clearPicksRequested) {
                clearPicksRequested = false
                clearPicks()
            }
            gameTypeRequest?.let { req ->
                gameTypeRequest = null
                if (req != gameType) {
                    // Ball size changed → previous detections are invalid.
                    gameType = req
                    tracker.clear()
                    trackedBalls = emptyList()
                    clearPicks()
                }
            }
            applyManualResponse()

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                pendingTaps.clear()
                report(
                    when (camera.trackingFailureReason) {
                        TrackingFailureReason.INSUFFICIENT_LIGHT ->
                            appContext.getString(R.string.status_tracking_dark)
                        TrackingFailureReason.EXCESSIVE_MOTION ->
                            appContext.getString(R.string.status_tracking_motion)
                        TrackingFailureReason.INSUFFICIENT_FEATURES ->
                            appContext.getString(R.string.status_tracking_features)
                        else -> appContext.getString(R.string.status_move_phone)
                    },
                )
                return
            }

            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, NEAR_CLIP, FAR_CLIP)
            android.opengl.Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0)

            handleTaps(frame)
            refitTableIfReady()

            drawCornerMarkers()
            val frameNow = tableFrame
            if (frameNow != null) {
                android.opengl.Matrix.invertM(inverseViewProjection, 0, viewProjection, 0)

                drawTable(frameNow)
                drawPockets(frameNow)

                if (uiMode == UiMode.PLAY) {
                    if (!frozen) {
                        ingestDetections(frame, frameNow)
                        maybeSubmitDetection(frame, frameNow)
                    }
                    drawBalls(frameNow)
                    val playStatus = drawPlayOverlays(frameNow)
                    val base = currentTransient() ?: playStatus
                    report(if (frozen) appContext.getString(R.string.frozen_status, base) else base)
                } else {
                    val dims = dimsText(frameNow)
                    report(
                        currentTransient()
                            ?: appContext.getString(R.string.status_table_locked_table_tab, dims),
                    )
                }
            } else {
                report(
                    currentTransient() ?: when {
                        uiMode == UiMode.PLAY -> appContext.getString(R.string.status_play_not_calibrated)
                        cornerAnchors.isEmpty() -> appContext.getString(R.string.status_diag_first)
                        else -> appContext.getString(R.string.status_diag_second)
                    },
                )
            }
        } catch (e: CameraNotAvailableException) {
            report(appContext.getString(R.string.status_camera_unavailable))
        } catch (e: Throwable) {
            // Never let a per-frame hiccup kill the render thread.
            report(appContext.getString(R.string.status_frame_error, e.javaClass.simpleName))
        }
    }

    // ---- taps -------------------------------------------------------------------

    private fun handleTaps(frame: Frame) {
        while (true) {
            val tap = pendingTaps.poll() ?: return
            when (uiMode) {
                UiMode.TABLE -> handleCornerTap(tap, frame)
                UiMode.PLAY -> {
                    val table = tableFrame ?: continue
                    if (!frozen) handleSelectionTap(tap, table)
                }
            }
        }
    }

    private fun handleCornerTap(tap: FloatArray, frame: Frame) {
        if (cornerAnchors.size >= 2) {
            setTransient(appContext.getString(R.string.status_table_locked_hint))
            return
        }

        // Prefer a proper plane hit, but pool cloth is often too featureless
        // for ARCore to ever produce a Plane — depth points (the S22+ has
        // depth support) and feature points work fine, since the diagonal
        // fit validates the geometry itself.
        val hits = frame.hitTest(tap[0], tap[1])
        val hit = hits.firstOrNull { h ->
            val plane = h.trackable as? Plane ?: return@firstOrNull false
            plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                plane.trackingState == TrackingState.TRACKING &&
                plane.isPoseInPolygon(h.hitPose)
        }
            ?: hits.firstOrNull { it.trackable is DepthPoint }
            ?: hits.firstOrNull { it.trackable is Point }

        if (hit == null) {
            setTransient(appContext.getString(R.string.status_tap_missed))
            return
        }

        val position = hit.hitPose.toVec3()
        if (cornerAnchors.any { it.pose.toVec3().distanceTo(position) < MIN_CORNER_SPACING_M }) {
            setTransient(appContext.getString(R.string.status_diag_second))
            return
        }
        cornerAnchors.add(hit.createAnchor())
    }

    /**
     * Tap on the calibrated table (PLAY). Guided sequence:
     *   CUE   → tap the white ball  (or empty felt → offer a manual spot)
     *   OBJECT→ tap the ball to pot (or empty felt → offer a manual spot)
     *   POCKET→ tap the target pocket
     * Explicit selection means the app never has to guess which ball is
     * white — it just believes the user, so warm lighting can't fool it.
     */
    private fun handleSelectionTap(tap: FloatArray, table: TableFrame) {
        if (awaitingManualResponse) return // a dialog is open; ignore taps
        val ndcX = 2f * tap[0] / viewportWidth - 1f
        val ndcY = 1f - 2f * tap[1] / viewportHeight
        val (origin, dir) = unprojectRay(ndcX, ndcY) ?: return
        val tapPos = table.ballCenterFromRay(origin, dir, gameType.ballRadiusMm, marginMm = 250.0) ?: return

        shotActive = false
        when (currentStep()) {
            SelectStep.CUE -> pickBallOrOffer(table, tapPos, SelectStep.CUE)
            SelectStep.OBJECT -> pickBallOrOffer(table, tapPos, SelectStep.OBJECT)
            SelectStep.POCKET -> pickPocket(table, tapPos)
            SelectStep.READY -> {
                // Everything chosen: let a pocket re-tap change the target.
                pickPocket(table, tapPos)
            }
        }
    }

    private fun currentStep(): SelectStep = when {
        cuePick == null -> SelectStep.CUE
        objPick == null -> SelectStep.OBJECT
        selectedPocketIndex == null -> SelectStep.POCKET
        else -> SelectStep.READY
    }

    private fun pickBallOrOffer(table: TableFrame, tapPos: Vec2, step: SelectStep) {
        val ball = trackedBalls.minByOrNull { it.position.distanceTo(tapPos) }
        if (ball != null && ball.position.distanceTo(tapPos) < BALL_PICK_MM) {
            assignPick(step, BallPick.Tracked(ball.id))
            return
        }
        // Empty felt within the table: offer a manual ball at this spot.
        pendingManualPos = tapPos
        pendingManualStep = step
        awaitingManualResponse = true
        onManualBallOffer(
            appContext.getString(
                if (step == SelectStep.CUE) R.string.ball_white else R.string.ball_coloured,
            ),
        )
    }

    private fun pickPocket(table: TableFrame, tapPos: Vec2) {
        val pockets = table.table(gameType).pockets
        val nearest = pockets.withIndex().minByOrNull { it.value.center.distanceTo(tapPos) } ?: return
        if (nearest.value.center.distanceTo(tapPos) < POCKET_PICK_MM) {
            selectedPocketIndex = nearest.index
        }
    }

    private fun assignPick(step: SelectStep, pick: BallPick) {
        when (step) {
            SelectStep.CUE -> cuePick = pick
            SelectStep.OBJECT -> objPick = pick
            else -> {}
        }
    }

    /** Apply the user's answer to the "use this spot anyway?" dialog. */
    private fun applyManualResponse() {
        if (manualConfirmRequested) {
            manualConfirmRequested = false
            val pos = pendingManualPos
            val step = pendingManualStep
            if (pos != null && step != null) assignPick(step, BallPick.Manual(pos))
            clearPending()
        }
        if (manualCancelRequested) {
            manualCancelRequested = false
            clearPending()
        }
    }

    private fun clearPending() {
        awaitingManualResponse = false
        pendingManualPos = null
        pendingManualStep = null
    }

    private fun clearPicks() {
        cuePick = null
        objPick = null
        selectedPocketIndex = null
        shotActive = false
        clearPending()
    }

    /** Resolve a pick to a current table-space position, or null if it's gone. */
    private fun resolvePick(pick: BallPick?): Vec2? = when (pick) {
        is BallPick.Tracked -> trackedBalls.firstOrNull { it.id == pick.id }?.position
        is BallPick.Manual -> pick.pos
        null -> null
    }

    // ---- calibration --------------------------------------------------------------

    /**
     * (Re)fit the table from the two diagonal anchors every frame — anchors
     * drift-correct as ARCore refines its map, and the mirror flag is applied
     * live so ⇄ Flip takes effect instantly.
     */
    private fun refitTableIfReady() {
        if (cornerAnchors.size < 2) {
            tableFrame = null
            return
        }
        val fitted = TableFrame.fitFromDiagonal(
            cornerAnchors[0].pose.toVec3(),
            cornerAnchors[1].pose.toVec3(),
            diagonalMirrored,
        )
        if (fitted == null) {
            clearCalibration()
            setTransient(appContext.getString(R.string.status_bad_diagonal))
        } else {
            val first = tableFrame == null && !everCalibrated
            tableFrame = fitted
            if (first) {
                everCalibrated = true
                onCalibrated()
            }
        }
    }

    private fun clearCalibration() {
        cornerAnchors.forEach { it.detach() }
        cornerAnchors.clear()
        tableFrame = null
        diagonalMirrored = false
        tracker.clear()
        trackedBalls = emptyList()
        clearPicks()
        frozen = false
    }

    // ---- PLAY overlays --------------------------------------------------------------

    /**
     * Selection outlines, shot solving/drawing. Returns the status line.
     */
    private fun drawPlayOverlays(table: TableFrame): String {
        // Consume a "Create shot" press.
        if (shotRequestPending) {
            shotRequestPending = false
            if (currentStep() == SelectStep.READY) {
                shotActive = true
            } else {
                setTransient(appContext.getString(R.string.hint_need_selection))
            }
        }

        // A tracked pick that vanished (potted/lost) drops back to that step.
        if (cuePick is BallPick.Tracked && resolvePick(cuePick) == null) {
            cuePick = null; shotActive = false
        }
        if (objPick is BallPick.Tracked && resolvePick(objPick) == null) {
            objPick = null; shotActive = false
        }

        val cuePos = resolvePick(cuePick)
        val objPos = resolvePick(objPick)

        // Selection outlines: white ring = cue, amber ring = object ball.
        cuePos?.let { drawCircle(table, it, gameType.ballRadiusMm + 22.0, 1f, 1f, 1f, 0.95f) }
        objPos?.let { drawCircle(table, it, gameType.ballRadiusMm + 22.0, 1f, 0.85f, 0.2f, 0.95f) }

        if (awaitingManualResponse) {
            return appContext.getString(R.string.status_manual_pending)
        }

        if (!shotActive) {
            return when (currentStep()) {
                SelectStep.CUE ->
                    if (trackedBalls.isEmpty()) appContext.getString(R.string.status_pick_white_none)
                    else appContext.getString(R.string.status_pick_white)
                SelectStep.OBJECT -> appContext.getString(R.string.status_pick_object)
                SelectStep.POCKET -> appContext.getString(R.string.status_pick_pocket)
                SelectStep.READY -> appContext.getString(R.string.status_press_create)
            }
        }

        // --- shot is active: solve against current positions and draw ---
        if (cuePos == null || objPos == null) return appContext.getString(R.string.status_pick_white)
        val pocketIdx = selectedPocketIndex ?: return appContext.getString(R.string.status_pick_pocket)

        val gameTable = table.table(gameType)
        val solver = ShotSolver(gameTable)
        val pocket = gameTable.pockets[pocketIdx]

        val cueBall = Ball("cue", cuePos)
        val objBall = Ball("obj", objPos)
        // Every other tracked ball is an obstacle, minus whichever ones we're
        // using as cue/object (matched by proximity, since picks may be manual).
        val others = trackedBalls
            .filter { it.position.distanceTo(cuePos) > 1.0 && it.position.distanceTo(objPos) > 1.0 }
            .map { Ball(it.id.toString(), it.position) }

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
        // Cue-sight line: the line to lay the cue along, extended BEHIND the
        // cue ball (opposite the aim direction), clipped to the rail. Chalk
        // blue so it reads as "line up your cue here", distinct from the aim.
        val back = shot.aimDirection * -1.0
        val backLen = distanceToRail(table, cue.center, back, CUE_SIGHT_MAX_MM)
        if (backLen > 1.0) {
            drawTableLines(table, listOf(cue.center to (cue.center + back * backLen)), 0.42f, 0.78f, 0.9f, 0.9f, 6f)
        }

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

    // ---- drawing helpers -----------------------------------------------------------

    private fun drawCornerMarkers() {
        if (cornerAnchors.isEmpty()) return
        val positions = FloatArray(cornerAnchors.size * 3)
        cornerAnchors.forEachIndexed { i, anchor ->
            val t = anchor.pose.translation
            positions[i * 3] = t[0]
            positions[i * 3 + 1] = t[1]
            positions[i * 3 + 2] = t[2]
        }
        points.draw(viewProjection, positions, cornerAnchors.size, red = 0.95f, green = 0.7f, blue = 0.25f, pointSizePx = 44f)
    }

    private fun drawTable(frame: TableFrame) {
        val w = frame.widthMm
        val l = frame.lengthMm

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
        lines.draw(viewProjection, vertices, segments.size * 2, red = 0.35f, green = 0.75f, blue = 0.85f, alpha = 0.45f, widthPx = 4f)
        lines.draw(viewProjection, vertices, 8, red = 0.35f, green = 0.85f, blue = 0.95f, alpha = 0.95f, widthPx = 8f)
    }

    /** Pocket rings at the six derived positions; selected pocket highlighted. */
    private fun drawPockets(frame: TableFrame) {
        val pockets = frame.table(gameType).pockets
        pockets.forEachIndexed { i, pocket ->
            val selected = uiMode == UiMode.PLAY && i == selectedPocketIndex
            if (selected) {
                drawCircle(frame, pocket.center, POCKET_RING_MM + 15.0, 1f, 0.85f, 0.2f, 1f)
            }
            drawCircle(frame, pocket.center, POCKET_RING_MM, 0.95f, 0.95f, 0.95f, if (selected) 0.9f else 0.55f)
            drawMarkerDot(frame, pocket.center, 0.08f, 0.08f, 0.08f, 30f)
        }
    }

    /** Colour-coded dot per tracked ball; a dashed hint marks the likely cue. */
    private fun drawBalls(table: TableFrame) {
        val balls = trackedBalls
        if (balls.isEmpty()) return
        val up = table.upNormal
        val lift = gameType.ballRadiusMm / 1000.0

        // Relative whiteness: the brightest, least-saturated ball is most
        // likely the cue — lighting-independent, unlike an absolute threshold.
        // Only a hint (the user still explicitly picks white).
        val likelyCue = if (cuePick == null) {
            balls.maxByOrNull { it.appearance.whitenessScore }
        } else null

        val position = FloatArray(3)
        for (ball in balls) {
            val world = table.toWorld(ball.position) + up * lift
            world.into(position, 0)
            val rgb = ball.appearance.displayRgb
            points.draw(
                viewProjection, position, 1,
                red = rgb[0], green = rgb[1], blue = rgb[2],
                pointSizePx = 38f,
            )
            if (ball === likelyCue) {
                drawCircle(table, ball.position, gameType.ballRadiusMm + 10.0, 0.9f, 0.9f, 0.9f, 0.7f)
            }
        }
    }

    /**
     * Distance from [from] along unit [dir] until leaving the table rectangle,
     * capped at [cap]. [from] is assumed inside the playing surface.
     */
    private fun distanceToRail(table: TableFrame, from: Vec2, dir: Vec2, cap: Double): Double {
        var t = cap
        val eps = 1e-6
        if (dir.x > eps) t = minOf(t, (table.widthMm - from.x) / dir.x)
        else if (dir.x < -eps) t = minOf(t, (0.0 - from.x) / dir.x)
        if (dir.y > eps) t = minOf(t, (table.lengthMm - from.y) / dir.y)
        else if (dir.y < -eps) t = minOf(t, (0.0 - from.y) / dir.y)
        return t.coerceAtLeast(0.0)
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

    // ---- ball detection pipeline -----------------------------------------------------

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

    // ---- misc ------------------------------------------------------------------------

    private fun dimsText(frame: TableFrame): String =
        String.format("%.2f m × %.2f m", frame.widthMm / 1000.0, frame.lengthMm / 1000.0)

    private fun setTransient(message: String) {
        transientStatus = message
        transientStatusUntilMs = SystemClock.uptimeMillis() + 2500
    }

    private fun currentTransient(): String? {
        if (SystemClock.uptimeMillis() >= transientStatusUntilMs) return null
        return transientStatus
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
        const val POCKET_RING_MM = 70.0
        const val CUE_SIGHT_MAX_MM = 700.0
    }
}

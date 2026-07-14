package com.poolsight.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.poolsight.geometry.GameType
import com.poolsight.geometry.Table
import com.poolsight.geometry.TableFrame
import com.poolsight.geometry.Vec2
import com.poolsight.geometry.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
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
                drawTable(frameNow)
                report(
                    appContext.getString(
                        R.string.status_table_locked,
                        String.format("%.2f m × %.2f m", frameNow.widthMm / 1000.0, frameNow.lengthMm / 1000.0),
                    ),
                )
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
            if (cornerAnchors.size >= 4) continue // already calibrated; taps ignored until reset

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
    }
}

package com.poolsight.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GL renderer for Phase 0: camera background + a marker on every tracked
 * horizontal plane. Reports human-readable status via [onStatus].
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
    private val planeMarkers = PointRenderer()

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)

    private var lastStatus = ""

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        background.createOnGlThread()
        planeMarkers.createOnGlThread()
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

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                report(appContext.getString(R.string.status_move_phone))
                return
            }

            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, NEAR_CLIP, FAR_CLIP)
            android.opengl.Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0)

            // One marker per tracked horizontal plane (its centre point).
            val planes = session.getAllTrackables(Plane::class.java)
                .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }

            if (planes.isEmpty()) {
                report(appContext.getString(R.string.status_searching))
            } else {
                val centers = FloatArray(planes.size * 3)
                planes.forEachIndexed { i, plane ->
                    val t = plane.centerPose.translation
                    centers[i * 3] = t[0]
                    centers[i * 3 + 1] = t[1]
                    centers[i * 3 + 2] = t[2]
                }
                planeMarkers.draw(viewProjection, centers, planes.size)
                report(appContext.getString(R.string.status_locked, planes.size))
            }
        } catch (e: CameraNotAvailableException) {
            report(appContext.getString(R.string.status_camera_unavailable))
        } catch (e: Throwable) {
            // Never let a per-frame hiccup kill the render thread.
            report(appContext.getString(R.string.status_frame_error, e.javaClass.simpleName))
        }
    }

    private fun report(status: String) {
        if (status != lastStatus) {
            lastStatus = status
            onStatus(status)
        }
    }

    private companion object {
        const val NEAR_CLIP = 0.1f
        const val FAR_CLIP = 100f
    }
}

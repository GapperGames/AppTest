package com.poolsight.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

/**
 * Phase 0: prove the AR foundation. Opens the camera through ARCore, detects
 * horizontal surfaces, and drops a marker on each — the marker staying glued
 * as the phone moves is the proof that world tracking works (roadmap P0).
 */
class MainActivity : Activity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private val renderer = ArRenderer(this) { status -> setStatus(status) }

    private var session: Session? = null
    private var arCoreInstallRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        statusText = TextView(this).apply {
            text = getString(R.string.status_starting)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xAA14603F.toInt())
            textSize = 16f
            setPadding(40, 24, 40, 24)
        }

        val root = FrameLayout(this)
        root.addView(surfaceView)
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = 80 },
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            return
        }
        if (session == null && !createSession()) return

        try {
            session?.resume()
            renderer.session = session
            surfaceView.onResume()
            renderer.displayRotationHelper.onResume()
        } catch (e: CameraNotAvailableException) {
            setStatus(getString(R.string.status_camera_unavailable))
            session = null
        }
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            renderer.displayRotationHelper.onPause()
            surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    /** Create the ARCore session, walking the Play-Services-for-AR install flow. */
    private fun createSession(): Boolean {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arCoreInstallRequested = true
                    return false // resumes again after the install dialog
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            val newSession = Session(this)
            newSession.configure(
                Config(newSession).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                },
            )
            session = newSession
            return true
        } catch (e: Exception) {
            val message = when (e) {
                is UnavailableArcoreNotInstalledException,
                is UnavailableUserDeclinedInstallationException,
                -> getString(R.string.error_arcore_not_installed)
                is UnavailableApkTooOldException -> getString(R.string.error_arcore_too_old)
                is UnavailableSdkTooOldException -> getString(R.string.error_app_too_old)
                is UnavailableDeviceNotCompatibleException -> getString(R.string.error_device_unsupported)
                else -> getString(R.string.error_session_failed, e.localizedMessage ?: e.javaClass.simpleName)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            setStatus(message)
            return false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == CAMERA_PERMISSION_CODE && !hasCameraPermission()) {
            Toast.makeText(this, getString(R.string.error_camera_permission), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(status: String) {
        runOnUiThread {
            if (statusText.text != status) statusText.text = status
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_CODE = 1
    }
}

package com.poolsight.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * PoolSight main screen: a fullscreen AR view with two tabs.
 *
 * TABLE — set up (tap two diagonal pocket corners; ⇄ Flip if mirrored).
 * PLAY  — tap ball + pocket, press 🎯 Create shot, ❄ Freeze to take it.
 */
class MainActivity : Activity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var renderer: ArRenderer

    private lateinit var tableTab: TextView
    private lateinit var playTab: TextView
    private lateinit var tableButtons: LinearLayout
    private lateinit var playButtons: LinearLayout
    private lateinit var freezeButton: TextView

    private var session: Session? = null
    private var arCoreInstallRequested = false

    /** True when we're showing the crash screen instead of the AR view. */
    private var showingCrash = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash safety net: record any uncaught error to disk, so that if the
        // app dies we can show its cause on the next launch (phone-only
        // debugging — no logcat needed).
        installCrashCatcher()
        if (showLastCrashIfAny()) return

        try {
            buildUi()
        } catch (t: Throwable) {
            saveCrash(t)
            showCrashScreen(t.stackTraceToString())
        }
    }

    private fun buildUi() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Built inside onCreate (not as a field initializer): it queries a
        // system service, which needs a ready Activity context.
        renderer = ArRenderer(
            this,
            onStatus = { status -> setStatus(status) },
            onCalibrated = { runOnUiThread { switchMode(UiMode.PLAY) } },
        )

        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnClickListener { }
            setOnTouchListener { v, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    renderer.onTap(event.x, event.y)
                    v.performClick()
                }
                true
            }
        }

        statusText = TextView(this).apply {
            text = getString(R.string.status_starting)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xAA14603F.toInt())
            textSize = 15f
            setPadding(36, 20, 36, 20)
        }

        // --- tabs ---
        tableTab = tabView(getString(R.string.tab_table)) { switchMode(UiMode.TABLE) }
        playTab = tabView(getString(R.string.tab_play)) { switchMode(UiMode.PLAY) }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tableTab)
            addView(spacer(8))
            addView(playTab)
        }

        // --- per-tab button rows ---
        tableButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pillButton(getString(R.string.btn_reset_corners)) { renderer.requestReset() })
            addView(spacer(40))
            addView(pillButton(getString(R.string.btn_flip)) { renderer.requestFlip() })
        }
        freezeButton = pillButton(getString(R.string.btn_freeze)) {
            renderer.frozen = !renderer.frozen
            freezeButton.text =
                getString(if (renderer.frozen) R.string.btn_resume else R.string.btn_freeze)
        }
        playButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pillButton(getString(R.string.btn_create_shot)) { renderer.requestShot() })
            addView(spacer(40))
            addView(freezeButton)
        }

        val root = FrameLayout(this)
        root.addView(surfaceView)
        root.addView(
            tabs,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = 40 },
        )
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = 165 },
        )
        root.addView(
            tableButtons,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 70 },
        )
        root.addView(
            playButtons,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 70 },
        )
        setContentView(root)

        switchMode(UiMode.TABLE)
    }

    private fun switchMode(mode: UiMode) {
        renderer.uiMode = mode
        val on = 0xEE1E7A50.toInt()
        val off = 0x8014603F.toInt()
        tableTab.setBackgroundColor(if (mode == UiMode.TABLE) on else off)
        playTab.setBackgroundColor(if (mode == UiMode.PLAY) on else off)
        tableButtons.visibility = if (mode == UiMode.TABLE) android.view.View.VISIBLE else android.view.View.GONE
        playButtons.visibility = if (mode == UiMode.PLAY) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun tabView(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 17f
        setPadding(70, 24, 70, 24)
        setOnClickListener { onClick() }
    }

    private fun pillButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xCC14603F.toInt())
        textSize = 16f
        setPadding(56, 28, 56, 28)
        setOnClickListener { onClick() }
    }

    private fun spacer(widthPx: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(widthPx, 1)
    }

    override fun onResume() {
        super.onResume()
        if (showingCrash) return
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
        if (showingCrash) return
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
                    // Pool cloth is nearly featureless, so plane detection can
                    // fail outright. Depth (supported on the S22+) lets corner
                    // taps land on the cloth without a detected plane.
                    if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }
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
        if (!::statusText.isInitialized) return
        runOnUiThread {
            if (statusText.text != status) statusText.text = status
        }
    }

    // ---- crash safety net --------------------------------------------------

    /** Persist any uncaught exception (from any thread) for next-launch display. */
    private fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrash(throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(t: Throwable) {
        try {
            getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(CRASH_KEY, t.stackTraceToString())
                .commit()
        } catch (_: Throwable) {
            // Never let crash reporting itself crash.
        }
    }

    /** If a crash was recorded last run, show it and return true. */
    private fun showLastCrashIfAny(): Boolean {
        val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
        val last = prefs.getString(CRASH_KEY, null) ?: return false
        prefs.edit().remove(CRASH_KEY).apply()
        showCrashScreen(last)
        return true
    }

    private fun showCrashScreen(details: String) {
        showingCrash = true
        val message = TextView(this).apply {
            text = "PoolSight hit an error last time.\n\n" +
                "Please screenshot this and send it back — it tells me exactly " +
                "what to fix.\n\n----\n$details"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14603F.toInt())
            textSize = 13f
            setPadding(40, 80, 40, 40)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF14603F.toInt())
            addView(message)
        }
        setContentView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    companion object {
        private const val CAMERA_PERMISSION_CODE = 1
        private const val CRASH_PREFS = "poolsight_crash"
        private const val CRASH_KEY = "last_crash"
    }
}

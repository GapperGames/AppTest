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
import com.poolsight.geometry.GameType
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
            onManualBallOffer = { label -> runOnUiThread { showManualBallDialog(label) } },
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
            setTextColor(0xFFF6F1E2.toInt())
            setBackgroundResource(R.drawable.bg_status)
            textSize = 15f
            gravity = Gravity.CENTER
            letterSpacing = 0.01f
            maxWidth = dp(560f).toInt()
            setPadding(dp(22f).toInt(), dp(13f).toInt(), dp(22f).toInt(), dp(13f).toInt())
            setShadowLayer(dp(4f), 0f, dp(1f), 0xCC000000.toInt())
            elevation = dp(6f)
        }

        // --- tabs (segmented control) ---
        tableTab = tabView(getString(R.string.tab_table)) { switchMode(UiMode.TABLE) }
        playTab = tabView(getString(R.string.tab_play)) { switchMode(UiMode.PLAY) }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_tabbar)
            val p = dp(5f).toInt()
            setPadding(p, p, p, p)
            elevation = dp(8f)
            addView(tableTab)
            addView(spacer(dp(5f).toInt()))
            addView(playTab)
        }

        // --- per-tab button rows ---
        val gap = dp(16f).toInt()
        lateinit var gameButton: TextView
        gameButton = pillButton(gameLabel(renderer.gameTypeSetting)) {
            val next = if (renderer.gameTypeSetting == GameType.POOL) GameType.SNOOKER else GameType.POOL
            renderer.requestGameType(next)
            gameButton.text = gameLabel(next)
        }
        tableButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(gameButton)
            addView(spacer(gap))
            addView(pillButton(getString(R.string.btn_reset_corners)) { renderer.requestReset() })
            addView(spacer(gap))
            addView(pillButton(getString(R.string.btn_flip)) { renderer.requestFlip() })
        }
        freezeButton = pillButton(getString(R.string.btn_freeze)) {
            renderer.frozen = !renderer.frozen
            freezeButton.text =
                getString(if (renderer.frozen) R.string.btn_resume else R.string.btn_freeze)
        }
        playButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pillButton(getString(R.string.btn_clear_picks)) { renderer.requestClearPicks() })
            addView(spacer(gap))
            addView(pillButton(getString(R.string.btn_create_shot), primary = true) { renderer.requestShot() })
            addView(spacer(gap))
            addView(freezeButton)
        }

        // --- scrims: fade the camera behind the top and bottom controls ---
        val scrimTop = android.view.View(this).apply { setBackgroundResource(R.drawable.scrim_top) }
        val scrimBottom = android.view.View(this).apply { setBackgroundResource(R.drawable.scrim_bottom) }

        val root = FrameLayout(this)
        root.addView(surfaceView)
        root.addView(
            scrimTop,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(150f).toInt(), Gravity.TOP),
        )
        root.addView(
            scrimBottom,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(140f).toInt(), Gravity.BOTTOM),
        )
        root.addView(
            tabs,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(16f).toInt() },
        )
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(80f).toInt() },
        )
        root.addView(
            tableButtons,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(26f).toInt() },
        )
        root.addView(
            playButtons,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(26f).toInt() },
        )
        setContentView(root)

        switchMode(UiMode.TABLE)
    }

    private fun switchMode(mode: UiMode) {
        renderer.uiMode = mode
        styleTab(tableTab, mode == UiMode.TABLE)
        styleTab(playTab, mode == UiMode.PLAY)
        tableButtons.visibility = if (mode == UiMode.TABLE) android.view.View.VISIBLE else android.view.View.GONE
        playButtons.visibility = if (mode == UiMode.PLAY) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
        tab.setTextColor(if (selected) 0xFFF6F1E2.toInt() else 0xB3F6F1E2.toInt())
    }

    private fun tabView(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(0xFFF6F1E2.toInt())
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.02f
        gravity = Gravity.CENTER
        setPadding(dp(30f).toInt(), dp(11f).toInt(), dp(30f).toInt(), dp(11f).toInt())
        setShadowLayer(dp(3f), 0f, dp(1f), 0xB3000000.toInt())
        setOnClickListener { onClick() }
    }

    private fun pillButton(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(0xFFF6F1E2.toInt())
        setBackgroundResource(if (primary) R.drawable.bg_pill_primary else R.drawable.bg_pill)
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.02f
        gravity = Gravity.CENTER
        setPadding(dp(26f).toInt(), dp(15f).toInt(), dp(26f).toInt(), dp(15f).toInt())
        setShadowLayer(dp(4f), 0f, dp(1f), 0xCC000000.toInt())
        elevation = dp(8f)
        // Coloured shadow gives the pill a soft glow (chalk for primary,
        // felt for the rest). setOutlineSpotShadowColor is API 28+; minSdk is
        // 26, so guard it (the S22+ is API 33 and gets the glow).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val glow = if (primary) 0xFF3FA6C4.toInt() else 0xFF1E7A50.toInt()
            outlineSpotShadowColor = glow
            outlineAmbientShadowColor = glow
        }
        setOnClickListener { onClick() }
    }

    private fun spacer(widthPx: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(widthPx, 1)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun gameLabel(type: GameType): String = getString(
        if (type == GameType.SNOOKER) R.string.btn_game_snooker else R.string.btn_game_pool,
    )

    /** Fallback: user tapped empty felt — offer to use that spot as the ball. */
    private fun showManualBallDialog(ballLabel: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_manual_title))
            .setMessage(getString(R.string.dialog_manual_msg, ballLabel))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ -> renderer.confirmManualBall() }
            .setNegativeButton(getString(R.string.dialog_no)) { _, _ -> renderer.cancelManualBall() }
            .show()
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

package com.poolsight.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.google.ar.core.Session

/**
 * Keeps the ARCore session's display geometry (rotation + viewport size) in
 * sync with the actual display, so the camera image is never sideways or
 * stretched. Compact version of the helper from Google's ARCore samples.
 */
class DisplayRotationHelper(private val context: Context) : DisplayManager.DisplayListener {

    private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val display: Display?
        get() = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    fun onResume() {
        displayManager.registerDisplayListener(this, null)
    }

    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    fun updateSessionIfNeeded(session: Session) {
        if (viewportChanged) {
            val rotation = display?.rotation ?: 0
            session.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
        viewportChanged = true
    }
}

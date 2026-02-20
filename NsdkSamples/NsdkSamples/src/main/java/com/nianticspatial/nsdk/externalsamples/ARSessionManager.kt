// Copyright 2025 Niantic.
package com.nianticspatial.nsdk.externalsamples

import android.app.Activity
import android.hardware.display.DisplayManager
import android.location.Location
import android.view.Display
import android.view.Surface
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.nianticspatial.nsdk.Orientation

/**
 * Simplified ARSessionManager for SceneView era.
 *
 * SceneView's ARScene handles all the AR session lifecycle and rendering.
 * This manager just tracks state and provides access to the session/frames
 * for other components that need them (like NSDK features).
 */
class ARSessionManager(
    private val activity: Activity
) : DefaultLifecycleObserver, LocationHelper.OnUpdateListener {

    // AR session provided by SceneView
    private var session: Session? = null

    // Current frame from SceneView
    var currentFrame: Frame? = null
        private set

    // Location tracking
    private var locationHelper: LocationHelper? = null
    private var _lastLocation: Location? = null
    val lastLocation: Location? get() = _lastLocation

    // UI state for showing/hiding AR view
    private val _enabled = mutableStateOf(true)
    val enabled: State<Boolean> get() = _enabled

    // Display and viewport management
    private var frameUpdateListener: OnFrameUpdateListener? = null

    private var displayRotation = 0
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportDirty = false

    var lastImageOrientation: Orientation = Orientation.PORTRAIT
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            updateDisplayRotation()
        }

        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    init {
        // Initialize location tracking if we have permissions
        if (Utils.hasLocationPermissions(activity)) {
            locationHelper = LocationHelper(activity, this)
            locationHelper?.startLocationUpdates()
        }

        val displayManager = activity.getSystemService(Activity.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
        updateDisplayRotation()
    }

    /**
     * Called by ARSceneView when the AR session is created.
     */
    fun setSession(arSession: Session) {
        session = arSession
    }

    /**
     * Set the camera texture name for ARCore.
     */
    fun setCameraTextureId(textureId: Int) {
        synchronized(this) {
            session?.setCameraTextureName(textureId)
        }
    }

    /**
     * Called by ARSceneView on each frame update.
     */
    fun updateFrame(frame: Frame) {

        synchronized(this) {
            if (viewportDirty) {
                session?.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                viewportDirty = false
            }

            currentFrame = frame
        }

        frameUpdateListener?.onFrameUpdate(frame)
    }

    /**
     * Enable or disable the AR view.
     */
    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }

    /**
     * Set the frame update listener for business logic.
     * This listener will be called with each frame for processing.
     */
    fun setFrameUpdateListener(listener: OnFrameUpdateListener?) {
        frameUpdateListener = listener
    }

    /**
     * Called by renderer when viewport size changes.
     */
    fun onViewportChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportDirty = true
    }

    private fun updateDisplayRotation() {
        val display: Display = activity.windowManager.defaultDisplay
        displayRotation = display.rotation
        viewportDirty = true

        lastImageOrientation = when (display.rotation) {
            Surface.ROTATION_0 -> Orientation.PORTRAIT
            Surface.ROTATION_90 -> Orientation.LANDSCAPE_LEFT
            Surface.ROTATION_180 -> Orientation.PORTRAIT_UPSIDE_DOWN
            Surface.ROTATION_270 -> Orientation.LANDSCAPE_RIGHT
            else -> Orientation.PORTRAIT
        }
    }

    /**
     * Get the current AR session (if needed by other components).
     */
    fun getSession(): Session? = session

    // Location update callback
    override fun onLocationUpdate(location: Location) {
        _lastLocation = location
    }

    override fun onDestroy(owner: LifecycleOwner) {
        locationHelper?.stopLocationUpdates()

        val displayManager = activity.getSystemService(Activity.DISPLAY_SERVICE) as DisplayManager
        displayManager.unregisterDisplayListener(displayListener)

        // Don't close the session - SceneView manages it
    }
}

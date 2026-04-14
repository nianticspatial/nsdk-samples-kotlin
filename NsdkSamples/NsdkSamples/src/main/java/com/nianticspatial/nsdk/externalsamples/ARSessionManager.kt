// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.app.Activity
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.nianticspatial.nsdk.NsdkFrame
import com.nianticspatial.nsdk.Orientation
import com.nianticspatial.nsdk.playback.AssetPlaybackDatasetLoader
import com.nianticspatial.nsdk.playback.PlaybackDataset
import com.nianticspatial.nsdk.playback.PlaybackFrame
import com.nianticspatial.nsdk.playback.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session mode: live AR (ARCore) or playback (dataset).
 */
enum class SessionMode { LIVE, PLAYBACK }

/**
 * Manages the active AR session — either live (ARCore) or playback (pre-recorded dataset).
 *
 * Drives the unified frame pipeline: [updateFrame] is called by ARSceneView each live frame,
 * and [PlaybackSession] delivers playback frames via its frame listener. Both paths notify
 * [frameUpdateListeners] so downstream consumers (e.g. [NSDKSessionManager]) see every frame
 * regardless of mode.
 *
 * When [active] is false (e.g. on the feature selector screen), live AR is hidden and playback
 * is paused. Call [setEnabled] to toggle.
 */
class ARSessionManager(
    private val activity: Activity,
    playbackDatasetPath: String = ""
) : DefaultLifecycleObserver {

    /** Whether the AR/playback view is currently active. False while on the feature selector. */
    var active: Boolean by mutableStateOf(false)
        private set

    /** Current session mode. Starts as [SessionMode.LIVE]; switches to [SessionMode.PLAYBACK] when a dataset path is provided. */
    var mode: SessionMode by mutableStateOf(SessionMode.LIVE)
        private set

    private var session: Session? = null
    private var playbackSession: PlaybackSession? = null

    /** True while the playback dataset is being loaded from assets in the background. */
    var isPlaybackLoading: Boolean by mutableStateOf(false)
        private set

    /** True when the current dataset includes depth data. Always false in live mode. */
    var depthAvailability: Boolean by mutableStateOf(false)
        private set

    /** Current device display orientation, updated on every display rotation change. */
    var deviceOrientation: Orientation by mutableStateOf(Orientation.PORTRAIT)
        private set

    /** Most recent ARCore frame, set on each [updateFrame] call. Null until the first live frame. */
    var currentLiveFrame: Frame? = null
        private set

    /** Most recent playback frame, set by the [PlaybackSession] frame listener. Null until playback begins. */
    var currentPlaybackFrame: PlaybackFrame? by mutableStateOf(null)
        private set

    /**
     * The current frame wrapped as [NsdkFrame], or null if no frame is available yet.
     * Returns a [NsdkFrame.Live] or [NsdkFrame.Playback] depending on [mode].
     */
    val currentFrame: NsdkFrame?
        get() = when (mode) {
            SessionMode.PLAYBACK -> currentPlaybackFrame?.let { NsdkFrame.Playback(it) }
            SessionMode.LIVE -> currentLiveFrame?.let { NsdkFrame.Live(it) }
        }

    private val frameUpdateListeners = mutableListOf<OnFrameUpdateListener>()

    /** Register a listener to be notified on every frame update (live or playback). */
    fun addFrameUpdateListener(listener: OnFrameUpdateListener) {
        frameUpdateListeners.add(listener)
    }

    /** Unregister a previously added frame update listener. */
    fun removeFrameUpdateListener(listener: OnFrameUpdateListener) {
        frameUpdateListeners.remove(listener)
    }

    /** Coroutine scope for background work (dataset loading, frame dispatch). Cancelled in [onDestroy]. */
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) { updateDisplayRotation() }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    init {
        val displayManager = activity.getSystemService(Activity.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
        updateDisplayRotation()

        // When a playback path is set, start in PLAYBACK so ARCore is not used. Load on background; if load fails, switch to LIVE.
        if (playbackDatasetPath.isNotEmpty()) {
            mode = SessionMode.PLAYBACK
            isPlaybackLoading = true
            val path = playbackDatasetPath
            val act = activity
            playbackScope.launch {
                val dataset = AssetPlaybackDatasetLoader(act, path).loadDataset()
                withContext(Dispatchers.Main.immediate) {
                    if (!act.isFinishing && !act.isDestroyed && dataset != null) {
                        setPlaybackDataset(dataset)
                    } else {
                        mode = SessionMode.LIVE
                    }
                    isPlaybackLoading = false
                }
            }
        }
    }

    /** Called by ARSceneView when the ARCore session is first created. */
    fun setSession(arSession: Session) {
        session = arSession
    }

    /**
     * Called by ARSceneView on each frame update (live mode only).
     * Only invoked when [mode] is LIVE and ARScene is mounted; not used during playback or loading.
     */
    fun updateFrame(frame: Frame) {
        synchronized(this) {
            // Guard currentLiveFrame so data sources reading it on a background thread
            // always see a fully-written Frame reference.
            currentLiveFrame = frame
        }
        if (active) {
            frameUpdateListeners.forEach { it.onFrameUpdate(NsdkFrame.Live(frame)) }
        }
    }

    /**
     * Enable or disable the AR/playback view. When false, playback is paused and view hidden.
     * When switching to true in playback mode, resets to the first frame and starts playback.
     */
    fun setEnabled(value: Boolean) {
        val wasActive = active
        active = value
        if (wasActive != value && mode == SessionMode.PLAYBACK) {
            if (value) {
                playbackSession?.seekToStart()
                playbackSession?.play()
            } else {
                playbackSession?.pause()
            }
        }
    }

    /**
     * Switch to playback mode and load [dataset]. Creates a [PlaybackSession] but does not start
     * it; playback begins when [setEnabled] is called with `true`. Pass `null` to tear down the
     * current dataset and revert to live mode.
     */
    private fun setPlaybackDataset(dataset: PlaybackDataset?) {
        playbackSession?.dispose()
        playbackSession = null
        currentPlaybackFrame = null

        if (dataset == null) {
            mode = SessionMode.LIVE
            depthAvailability = false
            return
        }

        mode = SessionMode.PLAYBACK
        depthAvailability = dataset.hasDepth()

        playbackSession = PlaybackSession(dataset)
        playbackSession?.setOnFrameListener { frame ->
            playbackScope.launch {
                withContext(Dispatchers.Main.immediate) {
                    currentPlaybackFrame = frame
                    if (active) {
                        frameUpdateListeners.forEach { it.onFrameUpdate(NsdkFrame.Playback(frame)) }
                    }
                }
            }
        }

        if (active) {
            playbackSession?.seekToStart()
            playbackSession?.play()
        }
    }

    /** Reads the current display rotation and updates [deviceOrientation]. */
    private fun updateDisplayRotation() {
        val display: Display = activity.windowManager.defaultDisplay
        deviceOrientation = when (display.rotation) {
            Surface.ROTATION_0 -> Orientation.PORTRAIT
            Surface.ROTATION_90 -> Orientation.LANDSCAPE_LEFT
            Surface.ROTATION_180 -> Orientation.PORTRAIT_UPSIDE_DOWN
            Surface.ROTATION_270 -> Orientation.LANDSCAPE_RIGHT
            else -> Orientation.PORTRAIT
        }
    }

    /** Cancels all background work, disposes the playback session, and unregisters the display listener. */
    override fun onDestroy(owner: LifecycleOwner) {
        val displayManager = activity.getSystemService(Activity.DISPLAY_SERVICE) as DisplayManager
        displayManager.unregisterDisplayListener(displayListener)

        playbackScope.cancel()
        playbackSession?.dispose()
        playbackSession = null
        currentPlaybackFrame = null
    }
}

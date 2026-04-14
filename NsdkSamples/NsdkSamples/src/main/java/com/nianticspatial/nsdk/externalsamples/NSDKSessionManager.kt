// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.NsdkFrame
import com.nianticspatial.nsdk.NsdkSessionDataSource
import com.nianticspatial.nsdk.Orientation
import com.nianticspatial.nsdk.helpers.createLiveDataSource
import com.nianticspatial.nsdk.helpers.createPlaybackDataSource

/**
 * Bridges [ARSessionManager] frame output to [NSDKSession]. Each frame is processed in two
 * steps:
 *
 * 1. [NsdkSessionDataSource.prepareFrame] runs on the main thread to snapshot the camera
 *    image and metadata.
 * 2. [NSDKSession.update] runs on a background thread, keeping the main thread free for
 *    smooth camera preview rendering.
 *
 * If the previous frame is still being processed when a new one arrives, the new frame is
 * dropped to keep memory bounded.
 *
 * @param context Used to initialise the live data source (location, sensors).
 * @param session The NSDK session to update each frame.
 * @param arManager Source of frame events and session mode.
 */
class NSDKSessionManager(
    context: Context,
    val session: NSDKSession,
    val arManager: ARSessionManager
) : DefaultLifecycleObserver, OnFrameUpdateListener {

    companion object {
        private const val TAG = "NSDKSessionManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val updateExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NSDK-Frame-Update").apply { isDaemon = true }
    }
    private val updateDispatcher = updateExecutor.asCoroutineDispatcher()

    private val updateInFlight = AtomicBoolean(false)

    private val liveDataSource: NsdkSessionDataSource by lazy {
        createLiveDataSource(
            context = context,
            frameProvider = { arManager.currentLiveFrame },
            orientationProvider = { arManager.deviceOrientation }
        )
    }

    private val playbackDataSource: NsdkSessionDataSource by lazy {
        createPlaybackDataSource(
            frameProvider = { arManager.currentPlaybackFrame },
            orientationProvider = { arManager.deviceOrientation }
        )
    }

    private var activeMode: SessionMode? = null

    val currentFrame: NsdkFrame?
        get() = arManager.currentFrame

    val currentImageOrientation: Orientation
        get() = arManager.deviceOrientation

    val dataSource: NsdkSessionDataSource?
        get() = session.dataSource

    init {
        arManager.addFrameUpdateListener(this)
    }

    override fun onFrameUpdate(frame: NsdkFrame) {
        if (!updateInFlight.compareAndSet(false, true)) return

        val mode = arManager.mode
        val dataSource: NsdkSessionDataSource = when (mode) {
            SessionMode.LIVE -> liveDataSource
            SessionMode.PLAYBACK -> playbackDataSource
        }

        if (activeMode != mode) {
            session.dataSource = dataSource
            activeMode = mode
        }

        scope.launch {
            try {
                val prepared = dataSource.prepareFrame()
                if (prepared) {
                    withContext(updateDispatcher) {
                        session.update()
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Frame update failed", e)
            } finally {
                updateInFlight.set(false)
            }
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        (liveDataSource as? DefaultLifecycleObserver)?.onResume(owner)
    }

    override fun onPause(owner: LifecycleOwner) {
        (liveDataSource as? DefaultLifecycleObserver)?.onPause(owner)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        arManager.removeFrameUpdateListener(this)
        scope.cancel()
        updateExecutor.shutdown()
        // Wait for any in-flight session.update() to finish before tearing down the data
        // sources — onDestroy closes retained Images whose planes may still be read by native code.
        updateExecutor.awaitTermination(5, TimeUnit.SECONDS)
        (liveDataSource as? DefaultLifecycleObserver)?.onDestroy(owner)
        (playbackDataSource as? DefaultLifecycleObserver)?.onDestroy(owner)
    }
}

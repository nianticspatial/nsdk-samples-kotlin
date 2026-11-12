// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.depth

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.NSDKResult
import com.nianticspatial.nsdk.AwarenessFeatureMode
import com.nianticspatial.nsdk.DepthConfig
import com.nianticspatial.nsdk.depth.DepthSession
import com.nianticspatial.nsdk.DepthBuffer
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DepthManager(
    private val session: DepthSession,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : FeatureManager() {
    val TAG = "DepthManager"

    val logMessages = mutableStateListOf<String>()
    var isRunning by mutableStateOf(false)
        private set
    var currentDepthBuffer by mutableStateOf<DepthBuffer?>(null)
        private set

    private val depthSession: DepthSession get() = session
    private var pollJob: Job? = null

    val depthFrameRate = 30

    init {
        depthSession.configure(
          DepthConfig(
            framerate = depthFrameRate,
            featureMode = AwarenessFeatureMode.UNSPECIFIED
          )
        )
        log("Configured depth session")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        val wasRunning = isRunning
        isRunning = false
        val jobSnapshot = pollJob
        pollJob = null
        CoroutineScope(Dispatchers.Default).launch {
            jobSnapshot?.cancelAndJoin()
            if (wasRunning) depthSession.stop()
            try {
                depthSession.close()
                withContext(Dispatchers.Main) { log("Closed depth session") }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing depth session", e)
            }
        }
        coroutineScope.cancel()
    }

    fun startDepth() {
        if (isRunning) return
        val status = depthSession.start()
        isRunning = true
        log("Started depth session (native status=$status)")

        // depthUpdates polls the session at its own interval and emits each depth frame once.
        pollJob = coroutineScope.launch {
            depthSession.depthUpdates.collect { latestDepth ->
                when (latestDepth) {
                    is NSDKResult.Success -> currentDepthBuffer = latestDepth.value
                    is NSDKResult.Error -> log("No depth frame received: ${latestDepth.code}")
                }
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        pollJob?.cancel()
        pollJob = null
        depthSession.stop()
        isRunning = false
        currentDepthBuffer = null
        log("Stopped depth session")
    }


    private fun log(message: String) {
        Log.i(TAG, message)
        logMessages.add(message)
    }
}

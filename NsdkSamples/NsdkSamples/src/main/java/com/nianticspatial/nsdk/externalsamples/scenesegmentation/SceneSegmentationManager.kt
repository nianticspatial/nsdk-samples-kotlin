// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.scenesegmentation

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.NSDKResult
import com.nianticspatial.nsdk.AwarenessFeatureMode
import com.nianticspatial.nsdk.AwarenessStatus
import com.nianticspatial.nsdk.awareness.scenesegmentation.SceneSegmentationChannel
import com.nianticspatial.nsdk.awareness.scenesegmentation.SceneSegmentationConfig
import com.nianticspatial.nsdk.awareness.scenesegmentation.SceneSegmentationResult
import com.nianticspatial.nsdk.awareness.scenesegmentation.SceneSegmentationSession
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transient, non-fatal issues that the UI can surface without stopping the session.
 */
sealed interface SceneSegmentationWarningEvent {
    data class ChannelQueryFailed(val cause: Throwable) : SceneSegmentationWarningEvent
    data class ChannelQueryError(val status: AwarenessStatus) : SceneSegmentationWarningEvent
    data object ChannelsUnavailable : SceneSegmentationWarningEvent

    data class LatestConfidenceResultError(val channelIndex: Int, val status: AwarenessStatus) :
        SceneSegmentationWarningEvent

    data class StopFailed(val cause: Throwable) : SceneSegmentationWarningEvent
    data object Cleared : SceneSegmentationWarningEvent
}

/**
 * Long-lived lifecycle description for the Scene Segmentation session.
 */
sealed interface SceneSegmentationSessionState {
    data object Idle : SceneSegmentationSessionState
    data object LoadingChannels : SceneSegmentationSessionState
    data class Streaming(val channel: SceneSegmentationChannel?) : SceneSegmentationSessionState
    data object Stopping : SceneSegmentationSessionState
    data class Failed(val cause: Throwable?) : SceneSegmentationSessionState
}

class SceneSegmentationManager(
    nsdkSessionManager: NSDKSessionManager,
    parentScope: CoroutineScope? = null,
) : FeatureManager() {

    companion object {
        private const val TAG = "SemanticsManager"
        private const val SCENESEGMENTATION_FRAME_RATE = 20
    }

    private val sceneSegmentationSession: SceneSegmentationSession = nsdkSessionManager.session.sceneSegmentation.acquire()

    private val scope: CoroutineScope = parentScope?.let { parent ->
        val parentJob = parent.coroutineContext[Job]
        CoroutineScope(parent.coroutineContext + SupervisorJob(parentJob))
    } ?: CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var resultJob: Job? = null

    private val _channels = MutableStateFlow<Array<SceneSegmentationChannel>>(emptyArray())
    val channels: StateFlow<Array<SceneSegmentationChannel>> = _channels.asStateFlow()

    private val _selectedChannelIndex = MutableStateFlow(-1)
    val selectedChannelIndex: StateFlow<Int> = _selectedChannelIndex.asStateFlow()

    private val _sessionState =
        MutableStateFlow<SceneSegmentationSessionState>(SceneSegmentationSessionState.Idle)
    val sessionState: StateFlow<SceneSegmentationSessionState> = _sessionState.asStateFlow()

    private val _warnings = MutableSharedFlow<SceneSegmentationWarningEvent>(replay = 0)
    val warnings: SharedFlow<SceneSegmentationWarningEvent> = _warnings.asSharedFlow()

    private val _latestResult = MutableStateFlow<SceneSegmentationResult?>(null)
    val latestResult: StateFlow<SceneSegmentationResult?> = _latestResult.asStateFlow()

    private var isPaused = false

    suspend fun start() {
        val current = _sessionState.value
        if (
            current is SceneSegmentationSessionState.LoadingChannels ||
            current is SceneSegmentationSessionState.Streaming ||
            current is SceneSegmentationSessionState.Stopping
        ) {
            return
        }

        _sessionState.value = SceneSegmentationSessionState.LoadingChannels

        runCatching { configureSession() }
            .onFailure { error ->
                Log.e(TAG, "Failed to configure Scene Segmentation", error)
                _sessionState.value = SceneSegmentationSessionState.Failed(error)
                return
            }

        runCatching {
            withContext(Dispatchers.Default) {
                sceneSegmentationSession.start()
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to start Scene Segmentation", error)
            _sessionState.value = SceneSegmentationSessionState.Failed(error)
            return
        }

        withContext(Dispatchers.Default) {
            val channelNames = sceneSegmentationSession.channelNames()
            withContext(Dispatchers.Main.immediate) {
                _channels.value = channelNames
                if (channelNames.isNotEmpty()) {
                    val defaultIndex =
                        channelNames.indexOfFirst { it.toString().contains("ground", ignoreCase = true) }
                    val resolvedIndex = if (defaultIndex >= 0) defaultIndex else 0
                    _selectedChannelIndex.value = resolvedIndex
                    sceneSegmentationSession.confidenceChannel = channelNames[resolvedIndex]
                    updateStreamingState()
                    clearWarning()
                    startShowingResults()
                } else {
                    emitWarning(SceneSegmentationWarningEvent.ChannelsUnavailable)
                }
            }
        }
    }

    fun stop() {
        when (_sessionState.value) {
            is SceneSegmentationSessionState.Idle,
            is SceneSegmentationSessionState.Failed,
            SceneSegmentationSessionState.Stopping -> return
            else -> Unit
        }

        _sessionState.value = SceneSegmentationSessionState.Stopping

        resultJob?.cancel()
        resultJob = null

        runCatching {
            sceneSegmentationSession.stop()
        }.onFailure { error ->
            Log.e(TAG, "Failed to stop Scene Segmentation", error)
            emitWarning(SceneSegmentationWarningEvent.StopFailed(error))
        }

        _channels.value = emptyArray()
        _selectedChannelIndex.value = -1
        _latestResult.value = null
        _sessionState.value = SceneSegmentationSessionState.Idle
    }

    fun selectChannel(index: Int) {
        if (index == _selectedChannelIndex.value) return
        if (index < 0 || index >= _channels.value.size) return

        _selectedChannelIndex.value = index
        // Update the confidence channel for the Flow
        sceneSegmentationSession.confidenceChannel = _channels.value[index]
        _latestResult.value = null
        updateStreamingState()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        val running = isSessionRunning()
        isPaused = running
        if (running) {
            stop()
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        if (isPaused) {
            scope.launch {
                start()
            }
            isPaused = false
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        stop()
        scope.cancel()
        runCatching { sceneSegmentationSession.close() }
            .onFailure { error -> Log.e(TAG, "Failed to close Scene Segmentation session", error) }
    }

    private suspend fun configureSession() {
        withContext(Dispatchers.Default) {
            val config = SceneSegmentationConfig().apply {
                frameRate = SCENESEGMENTATION_FRAME_RATE
                mode = AwarenessFeatureMode.UNSPECIFIED
            }
            sceneSegmentationSession.configure(config)
        }
    }

    private fun startShowingResults() {
        resultJob?.cancel()
        resultJob = scope.launch(Dispatchers.Default) {
            sceneSegmentationSession.confidenceUpdates.collect { result ->
                when (result) {
                    is NSDKResult.Success -> {
                        withContext(Dispatchers.Main.immediate) {
                            _latestResult.value = result.value
                        }
                        clearWarning()
                    }
                    is NSDKResult.Error -> {
                        val index = _selectedChannelIndex.value
                        Log.e(TAG, "Scene Segmentation error: ${result.code}")
                        emitWarning(
                            SceneSegmentationWarningEvent.LatestConfidenceResultError(index, result.code)
                        )
                    }
                }
            }
        }
    }

    private fun updateStreamingState() {
        if (!isSessionRunning()) return
        val channel = _channels.value.getOrNull(_selectedChannelIndex.value)
        _sessionState.value = SceneSegmentationSessionState.Streaming(channel)
    }

    private fun emitWarning(event: SceneSegmentationWarningEvent) {
        if (!_warnings.tryEmit(event)) {
            scope.launch { _warnings.emit(event) }
        }
    }

    private fun clearWarning() {
        emitWarning(SceneSegmentationWarningEvent.Cleared)
    }

    private fun isSessionRunning(): Boolean =
        when (_sessionState.value) {
            SceneSegmentationSessionState.LoadingChannels,
            is SceneSegmentationSessionState.Streaming -> true
            else -> false
        }
}

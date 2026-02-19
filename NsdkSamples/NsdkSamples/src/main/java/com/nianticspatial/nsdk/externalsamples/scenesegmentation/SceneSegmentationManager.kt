package com.nianticspatial.nsdk.externalsamples.scenesegmentation

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.NSDKResult
import com.nianticspatial.nsdk.AwarenessFeatureMode
import com.nianticspatial.nsdk.AwarenessStatus
import com.nianticspatial.nsdk.awareness.semantics.SemanticsChannel
import com.nianticspatial.nsdk.awareness.semantics.SemanticsConfig
import com.nianticspatial.nsdk.awareness.semantics.SemanticsResult
import com.nianticspatial.nsdk.awareness.semantics.SemanticsSession
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transient, non-fatal issues that the UI can surface without stopping the session.
 */
sealed interface SemanticsWarningEvent {
    data class ChannelQueryFailed(val cause: Throwable) : SemanticsWarningEvent
    data class ChannelQueryError(val status: AwarenessStatus) : SemanticsWarningEvent
    data object ChannelsUnavailable : SemanticsWarningEvent

    data class LatestConfidenceResultError(val channelIndex: Int, val status: AwarenessStatus) :
        SemanticsWarningEvent

    data class StopFailed(val cause: Throwable) : SemanticsWarningEvent
    data object Cleared : SemanticsWarningEvent
}

/**
 * Long-lived lifecycle description for the semantics session.
 */
sealed interface SemanticsSessionState {
    data object Idle : SemanticsSessionState
    data object LoadingChannels : SemanticsSessionState
    data class Streaming(val channel: SemanticsChannel?) : SemanticsSessionState
    data object Stopping : SemanticsSessionState
    data class Failed(val cause: Throwable?) : SemanticsSessionState
}

class SceneSegmentationManager(
    nsdkSessionManager: NSDKSessionManager,
    parentScope: CoroutineScope? = null,
) : FeatureManager() {

    companion object {
        private const val TAG = "SemanticsManager"
        private const val RESULT_POLL_DELAY_MS = 16L
        private const val SEMANTICS_FRAME_RATE = 20
    }

    private val semanticsSession: SemanticsSession = nsdkSessionManager.session.semantics.acquire()

    private val scope: CoroutineScope = parentScope?.let { parent ->
        val parentJob = parent.coroutineContext[Job]
        CoroutineScope(parent.coroutineContext + SupervisorJob(parentJob))
    } ?: CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var resultJob: Job? = null

    private val _channels = MutableStateFlow<Array<SemanticsChannel>>(emptyArray())
    val channels: StateFlow<Array<SemanticsChannel>> = _channels.asStateFlow()

    private val _selectedChannelIndex = MutableStateFlow(-1)
    val selectedChannelIndex: StateFlow<Int> = _selectedChannelIndex.asStateFlow()

    private val _sessionState =
        MutableStateFlow<SemanticsSessionState>(SemanticsSessionState.Idle)
    val sessionState: StateFlow<SemanticsSessionState> = _sessionState.asStateFlow()

    private val _warnings = MutableSharedFlow<SemanticsWarningEvent>(replay = 0)
    val warnings: SharedFlow<SemanticsWarningEvent> = _warnings.asSharedFlow()

    private val _latestResult = MutableStateFlow<SemanticsResult?>(null)
    val latestResult: StateFlow<SemanticsResult?> = _latestResult.asStateFlow()

    private var isPaused = false

    suspend fun start() {
        val current = _sessionState.value
        if (
            current is SemanticsSessionState.LoadingChannels ||
            current is SemanticsSessionState.Streaming ||
            current is SemanticsSessionState.Stopping
        ) {
            return
        }

        _sessionState.value = SemanticsSessionState.LoadingChannels

        runCatching { configureSession() }
            .onFailure { error ->
                Log.e(TAG, "Failed to configure semantics", error)
                _sessionState.value = SemanticsSessionState.Failed(error)
                return
            }

        runCatching {
            withContext(Dispatchers.Default) {
                semanticsSession.start()
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to start semantics", error)
            _sessionState.value = SemanticsSessionState.Failed(error)
            return
        }

        withContext(Dispatchers.Default) {
            val channelNames = semanticsSession.channelNames()
            withContext(Dispatchers.Main.immediate) {
                _channels.value = channelNames
                if (channelNames.isNotEmpty()) {
                    val defaultIndex =
                        channelNames.indexOfFirst { it.toString().contains("ground", ignoreCase = true) }
                    _selectedChannelIndex.value = if (defaultIndex >= 0) defaultIndex else 0
                    updateStreamingState()
                    clearWarning()
                    startResultPolling()
                } else {
                    emitWarning(SemanticsWarningEvent.ChannelsUnavailable)
                }
            }
        }
    }

    fun stop() {
        when (_sessionState.value) {
            is SemanticsSessionState.Idle,
            is SemanticsSessionState.Failed,
            SemanticsSessionState.Stopping -> return
            else -> Unit
        }

        _sessionState.value = SemanticsSessionState.Stopping

        resultJob?.cancel()
        resultJob = null

        runCatching {
            semanticsSession.stop()
        }.onFailure { error ->
            Log.e(TAG, "Failed to stop semantics", error)
            emitWarning(SemanticsWarningEvent.StopFailed(error))
        }

        _channels.value = emptyArray()
        _selectedChannelIndex.value = -1
        _latestResult.value = null
        _sessionState.value = SemanticsSessionState.Idle
    }

    fun selectChannel(index: Int) {
        if (index == _selectedChannelIndex.value) return
        if (index < 0 || index >= _channels.value.size) return

        _selectedChannelIndex.value = index
        // Update the confidence channel for the Flow
        semanticsSession.confidenceChannel = _channels.value[index]
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
        runCatching { semanticsSession.close() }
            .onFailure { error -> Log.e(TAG, "Failed to close semantics session", error) }
    }

    private suspend fun configureSession() {
        withContext(Dispatchers.Default) {
            val config = SemanticsConfig().apply {
                frameRate = SEMANTICS_FRAME_RATE
                mode = AwarenessFeatureMode.UNSPECIFIED
            }
            semanticsSession.configure(config)
        }
    }

    private fun startResultPolling() {
        resultJob?.cancel()
        resultJob = scope.launch(Dispatchers.Default) {
            var lastTimestamp = 0L
            var previousIndex = -1
            while (isActive && isSessionRunning()) {
                val index = _selectedChannelIndex.value
                if (index != previousIndex) {
                    previousIndex = index
                    lastTimestamp = 0L
                }
                if (index >= 0 && index < _channels.value.size) {
                    val channel = _channels.value[index]
                    val result = try {
                        semanticsSession.latestConfidence(channel)
                    } catch (error: Exception) {
                        Log.e(TAG, "latestConfidence threw", error)
                        delay(RESULT_POLL_DELAY_MS)
                        continue
                    }
                    when (result) {
                        is NSDKResult.Success -> {
                            val confidence = result.value
                            if (confidence.timestampMs > lastTimestamp) {
                                lastTimestamp = confidence.timestampMs
                                withContext(Dispatchers.Main.immediate) {
                                    _latestResult.value = confidence
                                }
                                clearWarning()
                            }
                        }
                        is NSDKResult.Error -> {
                            Log.e(TAG, "Semantics error: ${result.code}")
                            emitWarning(
                                SemanticsWarningEvent.LatestConfidenceResultError(
                                    index,
                                    result.code
                                )
                            )
                        }
                    }
                }
                delay(RESULT_POLL_DELAY_MS)
            }
        }
    }

    private fun updateStreamingState() {
        if (!isSessionRunning()) return
        val channel = _channels.value.getOrNull(_selectedChannelIndex.value)
        _sessionState.value = SemanticsSessionState.Streaming(channel)
    }

    private fun emitWarning(event: SemanticsWarningEvent) {
        if (!_warnings.tryEmit(event)) {
            scope.launch { _warnings.emit(event) }
        }
    }

    private fun clearWarning() {
        emitWarning(SemanticsWarningEvent.Cleared)
    }

    private fun isSessionRunning(): Boolean =
        when (_sessionState.value) {
            SemanticsSessionState.LoadingChannels,
            is SemanticsSessionState.Streaming -> true
            else -> false
        }
}

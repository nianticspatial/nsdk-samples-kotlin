package com.nianticspatial.nsdk.externalsamples.vps

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.NSDKResult
import com.nianticspatial.nsdk.AnchorTrackingState
import com.nianticspatial.nsdk.NsdkStatusException
import com.nianticspatial.nsdk.UUID
import com.nianticspatial.nsdk.VPSConfig
import com.nianticspatial.nsdk.vps.VPSSession
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.google.ar.core.Pose

/**
 * Emits transient warnings that the UI can surface (toast, snackbar, log, etc.).
 * These notifications do not necessarily mean the session is unusable.
 */
sealed interface VpsWarningEvent {
    data class AnchorTrackFailed(val payload: String, val cause: Throwable) : VpsWarningEvent
    data class AnchorUpdateFailed(val anchorId: UUID, val cause: Throwable) : VpsWarningEvent
    data class AnchorPayloadFailed(val anchorId: UUID, val cause: Throwable) : VpsWarningEvent
    data class SessionIdQueryFailed(val cause: Throwable) : VpsWarningEvent
    data class SessionStopFailed(val cause: Throwable) : VpsWarningEvent
}

/**
 * Long-lived state describing where the VPS session currently sits in its lifecycle.
 * UI should drive its persistent rendering directly from this sealed type.
 */
sealed interface VpsSessionState {
    data object Ready : VpsSessionState
    data class Localizing(val sessionId: String?) : VpsSessionState
    data class Tracking(
        val sessionId: String,
        val anchorConfidence: Float?,
        val anchorStates: Map<UUID, AnchorTrackingState>
    ) : VpsSessionState

    data object Stopping : VpsSessionState
    data class Failed(val cause: Throwable?) : VpsSessionState
}

class VPSManager(
    private val nsdkManager: NSDKSessionManager,
    private val session: VPSSession,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : FeatureManager() {

    companion object {
        private const val TAG = "VPSManager"

        // Max number of managed anchors
        private const val MAX_TRACKED_ANCHORS = 6

        // Anchor update polling interval
        private const val ANCHOR_UPDATE_DELAY_MS = 500L
    }

    val vpsSession: VPSSession get() = session
    val maxTrackedAnchors: Int = MAX_TRACKED_ANCHORS

    private val _sessionState = MutableStateFlow<VpsSessionState>(VpsSessionState.Ready)
    val sessionState: StateFlow<VpsSessionState> = _sessionState.asStateFlow()

    private val _warnings = MutableSharedFlow<VpsWarningEvent>(replay = 0)
    val warnings: SharedFlow<VpsWarningEvent> = _warnings

    private val _targetAnchorId = MutableStateFlow<UUID?>(null)
    val targetAnchorId: StateFlow<UUID?> = _targetAnchorId.asStateFlow()

    private val _targetAnchorPayload = MutableStateFlow<String?>(null)
    val targetAnchorPayload: StateFlow<String?> = _targetAnchorPayload.asStateFlow()

    private val _targetAnchorState = MutableStateFlow(AnchorTrackingState.NOT_TRACKED)
    val targetAnchorState: StateFlow<AnchorTrackingState> = _targetAnchorState.asStateFlow()

    private val _targetAnchorConfidence = MutableStateFlow<Float?>(null)
    val targetAnchorConfidence: StateFlow<Float?> = _targetAnchorConfidence.asStateFlow()

    private val _targetAnchorPose = MutableStateFlow<Pose?>(null)
    val targetAnchorPose: StateFlow<Pose?> = _targetAnchorPose.asStateFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _geolocationInfo = MutableStateFlow("No geolocation data yet")
    val geolocationInfo: StateFlow<String> = _geolocationInfo.asStateFlow()

    private val localAnchorPayloadCache = mutableMapOf<UUID, String?>()
    private val localAnchorStateCache = mutableMapOf<UUID, AnchorTrackingState>()
    private val localAnchorPoseCache = mutableMapOf<UUID, Pose>()

    private val _localAnchorPayloads = MutableStateFlow<Map<UUID, String?>>(emptyMap())
    val localAnchorPayloads: StateFlow<Map<UUID, String?>> = _localAnchorPayloads.asStateFlow()

    private val _localAnchorStates = MutableStateFlow<Map<UUID, AnchorTrackingState>>(emptyMap())
    val localAnchorStatesFlow: StateFlow<Map<UUID, AnchorTrackingState>> =
        _localAnchorStates.asStateFlow()

    private val _localAnchorPoses = MutableStateFlow<Map<UUID, Pose>>(emptyMap())
    val localAnchorPoses: StateFlow<Map<UUID, Pose>> = _localAnchorPoses.asStateFlow()

    private var pollingJob: Job? = null

    init {
        configureVPSSession(
            VPSConfig(
                enableContinuousLocalization = true,
                enableTemporalFusion = true,
                cloudContinuousRequestsPerSecond = 1f
            )
        )
    }

    fun configureVPSSession(config: VPSConfig) {
        session.configure(config)
    }

    /**
     * Called when the user taps the screen to create a local anchor.
     */
    fun createAnchor() {
        val trackedCount =
            localAnchorPayloadCache.size + if (_targetAnchorId.value != null) 1 else 0
        if (trackedCount >= MAX_TRACKED_ANCHORS) return

        nsdkManager.currentFrame?.let { frame ->
            runCatching {
                val cameraPose = frame.camera.getDisplayOrientedPose()
                val anchorId = session.createAnchor(cameraPose)
                localAnchorPayloadCache[anchorId] = null
                localAnchorStateCache[anchorId] = AnchorTrackingState.NOT_TRACKED
                publishAnchorCollections()
                Log.i(TAG, "Created anchor: $anchorId")
            }.onFailure { error ->
                Log.e(TAG, "Failed to create anchor", error)
            }
        }
    }

    /**
     * Starts VPS tracking for the supplied payload.
     */
    fun startTracking(payload: String) {
        if (_sessionState.value !is VpsSessionState.Ready) return

        cancelPolling()
        resetTargetAnchor()
        _sessionId.value = null
        _geolocationInfo.value = "No geolocation data yet"
        _sessionState.value = VpsSessionState.Localizing(sessionId = null)

        try {
            session.start()
            val anchorId = session.trackAnchor(payload)
            _targetAnchorId.value = anchorId
            _targetAnchorPayload.value = payload
            localAnchorPayloadCache.remove(anchorId) // ensure no clash with local anchor maps
            publishAnchorCollections()
            Log.i(TAG, "Started tracking anchor: $anchorId")

            pollingJob = scope.launch {
                while (isActive) {
                    delay(ANCHOR_UPDATE_DELAY_MS)
                    _sessionId.value = querySessionId()
                    updateTargetAnchor(anchorId)
                    updateLocalAnchors()
                    updateGeolocation()

                    val currentId = _sessionId.value
                    _sessionState.value =
                        if (!isActive) {
                            VpsSessionState.Ready
                        } else if (currentId != null && _targetAnchorState.value == AnchorTrackingState.TRACKED) {
                            VpsSessionState.Tracking(
                                sessionId = currentId,
                                anchorConfidence = _targetAnchorConfidence.value,
                                anchorStates = _localAnchorStates.value
                            )
                        } else {
                            VpsSessionState.Localizing(sessionId = currentId)
                        }
                }
            }
        } catch (error: NsdkStatusException) {
            Log.e(TAG, "Failed to start tracking", error)
            _sessionState.value = VpsSessionState.Failed(error)
            emitWarning(VpsWarningEvent.AnchorTrackFailed(payload, error))
            runCatching { session.stop() }
        }
    }

    /**
     * Stops VPS tracking and clears sample state.
     */
    fun stopTracking() {
        val current = _sessionState.value
        if (current is VpsSessionState.Ready || current is VpsSessionState.Stopping) return

        _sessionState.value = VpsSessionState.Stopping
        cancelPolling()

        runCatching { session.stop() }
            .onFailure { error ->
                Log.e(TAG, "Error stopping VPS session", error)
                emitWarning(VpsWarningEvent.SessionStopFailed(error))
            }

        resetTargetAnchor()
        _sessionId.value = null
        _geolocationInfo.value = "No geolocation data yet"
        localAnchorPayloadCache.clear()
        localAnchorStateCache.clear()
        localAnchorPoseCache.clear()
        publishAnchorCollections()

        _sessionState.value = VpsSessionState.Ready
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopTracking()
        runCatching { session.close() }
            .onFailure { error -> Log.e(TAG, "Error closing VPS session", error) }
        scope.cancel()
    }

    private fun cancelPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun resetTargetAnchor() {
        _targetAnchorId.value = null
        _targetAnchorPayload.value = null
        _targetAnchorState.value = AnchorTrackingState.NOT_TRACKED
        _targetAnchorConfidence.value = null
        _targetAnchorPose.value = null
    }

    private fun querySessionId(): String? =
        try {
            session.getSessionId()?.joinToString("") { "%02x".format(it) }
        } catch (error: Exception) {
            Log.e(TAG, "Error getting session ID", error)
            emitWarning(VpsWarningEvent.SessionIdQueryFailed(error))
            null
        }

    private fun updateTargetAnchor(anchorId: UUID) {
        runCatching { session.getAnchorUpdate(anchorId) }
            .onSuccess { update ->
                if (_targetAnchorState.value != update.trackingState) {
                    Log.i(TAG, "Tracking state changed to ${update.trackingState}")
                }
                _targetAnchorState.value = update.trackingState
                _targetAnchorConfidence.value = update.trackingConfidence
                if (update.trackingState == AnchorTrackingState.TRACKED) {
                    _targetAnchorPose.value = update.anchorToLocalTrackingTransform
                } else {
                    _targetAnchorPose.value = null
                }
            }
            .onFailure { error ->
                Log.e(TAG, "Error getting anchor update [$anchorId]", error)
                emitWarning(VpsWarningEvent.AnchorUpdateFailed(anchorId, error))
            }
    }

    private fun updateLocalAnchors() {
        localAnchorPayloadCache.keys.toList().forEach { anchorId ->
            runCatching { session.getAnchorUpdate(anchorId) }
                .onSuccess { update ->
                    localAnchorStateCache[anchorId] = update.trackingState
                    if (update.trackingState == AnchorTrackingState.TRACKED) {
                        localAnchorPoseCache[anchorId] = update.anchorToLocalTrackingTransform
                    } else {
                        localAnchorPoseCache.remove(anchorId)
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "Error getting anchor update [$anchorId]", error)
                    emitWarning(VpsWarningEvent.AnchorUpdateFailed(anchorId, error))
                }
        }

        localAnchorPayloadCache.entries.toList().forEach { (anchorId, payload) ->
            if (payload != null) return@forEach
            if (localAnchorStateCache[anchorId] != AnchorTrackingState.TRACKED) return@forEach

            runCatching { session.getAnchorPayload(anchorId) }
                .onSuccess { retrieved ->
                    if (retrieved != null) {
                        localAnchorPayloadCache[anchorId] = retrieved
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "Error getting anchor payload [$anchorId]", error)
                    emitWarning(VpsWarningEvent.AnchorPayloadFailed(anchorId, error))
                }
        }

        publishAnchorCollections()
    }

    private fun updateGeolocation() {
        nsdkManager.currentFrame?.camera?.let { camera ->
            when (val result = session.getDevicePoseAsGeolocation(camera.pose)) {
                is NSDKResult.Success -> {
                    _geolocationInfo.value = "Lat: %.3f, Lon: %.3f, Heading: %.3f, Alt: %.3f"
                        .format(
                            result.value.latitude, result.value.longitude,
                            result.value.heading, result.value.altitude
                        )
                    Log.i(TAG, "VPS geolocation: ${result.value}")
                }

                is NSDKResult.Error -> {
                    _geolocationInfo.value = "Error: ${result.code}"
                    Log.e(TAG, "Error when getting VPS device geolocation: ${result.code}")
                }
            }
        }
    }

    private fun publishAnchorCollections() {
        _localAnchorPayloads.value = localAnchorPayloadCache.toMap()
        _localAnchorStates.value = localAnchorStateCache.toMap()
        _localAnchorPoses.value = localAnchorPoseCache.toMap()
    }

    private fun emitWarning(event: VpsWarningEvent) {
        if (!_warnings.tryEmit(event)) {
            scope.launch { _warnings.emit(event) }
        }
    }
}

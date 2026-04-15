// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.vps2

import android.opengl.Matrix
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.AnchorTrackingState
import com.nianticspatial.nsdk.GeolocationData
import com.nianticspatial.nsdk.AsyncResult
import com.nianticspatial.nsdk.MeshDownloaderData
import com.nianticspatial.nsdk.NsdkInvalidArgumentStatusException
import com.nianticspatial.nsdk.NsdkStatusException
import com.nianticspatial.nsdk.UUIDKey
import com.nianticspatial.nsdk.toKey
import com.nianticspatial.nsdk.Vps2Config
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import com.nianticspatial.nsdk.externalsamples.MeshRenderer
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.auth.AuthRetryHelper
import com.nianticspatial.nsdk.externalsamples.auth.AuthRetryException
import com.nianticspatial.nsdk.mesh.MeshDownloaderSession
import com.nianticspatial.nsdk.vps2.Vps2LocalizationRequestStatus
import com.nianticspatial.nsdk.vps2.Vps2Session
import com.nianticspatial.nsdk.vps2.HeadingMode
import com.nianticspatial.nsdk.vps2.Vps2TrackingState
import com.nianticspatial.nsdk.vps2.Vps2Localization
import com.google.ar.core.Pose
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VPS2Manager(
    private val nsdkManager: NSDKSessionManager,
    private val vps2Session: Vps2Session,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : FeatureManager() {

    companion object {
        private const val TAG = "VPS2Manager"
        private const val TOAST_COOLDOWN_MS = 5000L

        object MeshDownload {
            const val MAX_SIZE_KB = 10240
            const val TIMEOUT_MS = 15000L
        }

    }

    enum class MeshDownloadStatus {
        READY,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        TIMEOUT,
    }

    private val meshDownloaderSession: MeshDownloaderSession = nsdkManager.session.meshDownload.acquire()
    private val authRetryHelper = AuthRetryHelper(nsdkManager.session)
    private val _meshDownloadStatus = MutableStateFlow<MeshDownloadStatus>(MeshDownloadStatus.READY)
    val meshDownloadStatus: StateFlow<MeshDownloadStatus> = _meshDownloadStatus

    private val downloadedMeshesByAnchor = ConcurrentHashMap<UUIDKey, Array<MeshDownloaderData>>()
    private var renderedMeshAnchors: Set<UUIDKey> = emptySet()

    // Callback for renderable mesh chunks
    var onMeshDataReady: ((List<MeshRenderer.RenderableMeshChunk>, List<Long>) -> Unit)? = null

    private val lastToastTimeByMessage = ConcurrentHashMap<String, Long>()
    private var trackingCollectorsJob: Job? = null

    var trackingStarted by mutableStateOf(false)
        private set

    var localizationTrackingState by mutableStateOf(Vps2TrackingState.UNAVAILABLE)
        private set

    // Lat/Lng/Heading from latest localization conversion (pose -> geolocation)
    var vps2Latitude by mutableStateOf(0.0)
        private set
    var vps2Longitude by mutableStateOf(0.0)
        private set
    var vps2HeadingDeg by mutableStateOf(0.0)
        private set

    // Device GPS + compass heading
    var gpsLatitude by mutableStateOf(0.0)
        private set
    var gpsLongitude by mutableStateOf(0.0)
        private set
    var deviceHeadingDeg by mutableStateOf(0.0)
        private set

    // Track all anchors and payloads
    val anchors = mutableStateMapOf<UUIDKey, String?>()
    val anchorStates = mutableStateMapOf<UUIDKey, AnchorTrackingState>()

    // Anchor transforms for rendering (from anchor updates)
    val anchorTransforms = mutableStateMapOf<UUIDKey, FloatArray>()

    // Real-time Flow for anchor transforms (bypasses snapshotFlow recomposition delay)
    // Only transforms need real-time updates for rendering; states are accessed directly
    private val _anchorTransformsFlow = MutableStateFlow<Map<UUIDKey, FloatArray>>(emptyMap())
    val anchorTransformsFlow: StateFlow<Map<UUIDKey, FloatArray>> = _anchorTransformsFlow.asStateFlow()

    // Anchor geolocations from anchor updates
    val anchorGeolocations = mutableStateMapOf<UUIDKey, GeolocationData>()

    private val _anchorGeolocationsFlow = MutableStateFlow<Map<UUIDKey, GeolocationData>>(emptyMap())
    val anchorGeolocationsFlow: StateFlow<Map<UUIDKey, GeolocationData>> = _anchorGeolocationsFlow.asStateFlow()

    private var configured = false

    fun configure(config: Vps2Config = Vps2Config()) {
        vps2Session.configure(config)
        configured = true
    }

    fun startTracking(payloads: List<String>) {
        if (trackingStarted) return

        val sanitizedPayloads = payloads.map { it.trim() }.filter { it.isNotEmpty() }
        if (sanitizedPayloads.isEmpty()) {
            coroutineScope.launch { _toasts.emit("No anchor payloads provided") }
            return
        }

        try {
            // Configure the demo with universal and VPS-map localization enabled at bandwidth-friendly rates:
            // - universal: 1.0 rps, initial VPS: 1.0 rps, continuous VPS: 0.2 rps
            if (!configured) {
                configure(
                    Vps2Config(
                        enableUniversalLocalization = true,
                        universalLocalizationRequestsPerSecond = 1.0f,
                        enableVpsMapLocalization = true,
                        initialVpsRequestsPerSecond = 1.0f,
                        continuousVpsRequestsPerSecond = 0.2f,
                    )
                )
            }

            vps2Session.start()

            // Track all requested anchors.
            sanitizedPayloads.forEach { payload ->
                coroutineScope.launch {
                    // Invalid or malformed payloads cause trackAnchor to throw; handle here to avoid crash and reset UI.
                    try {
                        val anchorId = authRetryHelper.withRetry {
                            vps2Session.trackAnchor(payload)
                        }

                        val anchorKey = anchorId.toKey()
                        anchors[anchorKey] = payload
                        anchorStates[anchorKey] = AnchorTrackingState.NOT_TRACKED

                        // Automatically start downloading mesh for this location payload.
                        startMeshDownload(anchorKey = anchorKey, payload = payload)
                    } catch (e: NsdkInvalidArgumentStatusException) {
                        Log.e(TAG, "Invalid payload: $payload", e)
                        _toasts.emit("Invalid Payload")
                        stopTracking()
                    } catch (e: AuthRetryException) {
                        Log.e(TAG, "Authorization timed out while tracking anchor", e)
                        _toasts.emit("Authorization timed out")
                        stopTracking()
                    }
                }
            }

            // (Re)start collectors for session-driven flows
            trackingCollectorsJob?.cancel()
            val collectorsJob = SupervisorJob()
            trackingCollectorsJob = collectorsJob
            val trackingScope = CoroutineScope(coroutineScope.coroutineContext + collectorsJob)

            vps2Session.anchorUpdates
                .filter { trackingStarted }
                .filter { update -> anchors.containsKey(update.uuid.toKey()) }
                .catch { e -> Log.e(TAG, "Error in anchor updates flow", e) }
                .onEach { update ->
                    val anchorIdUpdate = update.uuid
                    val anchorKey = anchorIdUpdate.toKey()

                    anchorStates[anchorKey] = update.trackingState

                    // Store transform from anchor update (works for LIMITED/TRACKED states).
                    if (update.trackingState == AnchorTrackingState.NOT_TRACKED) {
                        anchorTransforms.remove(anchorKey)
                    } else {
                        // Create a fresh FloatArray and copy the transform to ensure no accumulation
                        val matrix = FloatArray(16)
                        update.anchorToLocalTrackingTransform.toMatrix(matrix, 0)
                        anchorTransforms[anchorKey] = matrix
                    }

                    // Emit real-time transform updates via Flow
                    _anchorTransformsFlow.value = anchorTransforms.toMap()

                    // Update geolocation from anchor update
                    if (update.trackingState == AnchorTrackingState.NOT_TRACKED) {
                        anchorGeolocations.remove(anchorKey)
                    } else {
                        update.geolocation?.let { anchorGeolocations[anchorKey] = it }
                    }
                    _anchorGeolocationsFlow.value = anchorGeolocations.toMap()

                    // Update mesh rendering
                    updateMesh()
                }
                .launchIn(trackingScope)

            vps2Session.localizationUpdates
                .filter { trackingStarted }
                .catch { e -> Log.e(TAG, "Error in localization updates flow", e) }
                .onEach { t: Vps2Localization ->
                    // device GPS + compass
                    val gps = nsdkManager.dataSource?.latestGpsSample()
                    gpsLatitude = gps?.latitude ?: gpsLatitude
                    gpsLongitude = gps?.longitude ?: gpsLongitude
                    val compass = nsdkManager.dataSource?.latestCompassSample()
                    deviceHeadingDeg = compass?.trueHeading?.toDouble() ?: deviceHeadingDeg

                    // VPS2 localization + geolocation
                    runCatching {
                        localizationTrackingState = t.trackingState

                        val geo = vps2Session.getDeviceGeolocation(HeadingMode.DEVICE_TOP)
                        if (geo != null) {
                            vps2Latitude = geo.latitude
                            vps2Longitude = geo.longitude
                            vps2HeadingDeg = geo.heading
                        }
                    }.onFailure { e ->
                        localizationTrackingState = Vps2TrackingState.UNAVAILABLE
                        when (e) {
                            is NsdkStatusException -> Log.e(TAG, "NsdkStatusException in localization update", e)
                            else -> Log.e(TAG, "Exception in localization update", e)
                        }
                    }
                }
                .launchIn(trackingScope)

            vps2Session.localizationRequestRecords
                .filter { trackingStarted }
                .filter { it.status == Vps2LocalizationRequestStatus.FAILED ||
                          it.status == Vps2LocalizationRequestStatus.FRAME_REJECTED }
                .catch { e -> Log.e(TAG, "Error in localization request records flow", e) }
                .onEach { record ->
                    val message = when (record.status) {
                        Vps2LocalizationRequestStatus.FAILED ->
                            "Localization request failed: ${record.error}"
                        Vps2LocalizationRequestStatus.FRAME_REJECTED ->
                            "Frame rejected: ${record.error}"
                        else -> return@onEach
                    }
                    val now = System.currentTimeMillis()
                    val lastTime = lastToastTimeByMessage[message] ?: 0L
                    if (now - lastTime >= TOAST_COOLDOWN_MS) {
                        lastToastTimeByMessage[message] = now
                        _toasts.emit(message)
                    }
                }
                .launchIn(trackingScope)

            trackingStarted = true
        } catch (e: NsdkStatusException) {
            trackingStarted = false
            runCatching { vps2Session.stop() }
            trackingCollectorsJob?.cancel()
            coroutineScope.launch { _toasts.emit("Unable to start VPS2 tracking") }
        }
    }


    private fun startMeshDownload(anchorKey: UUIDKey, payload: String) {
        if (downloadedMeshesByAnchor.containsKey(anchorKey)) {
            return
        }

        if (_meshDownloadStatus.value == MeshDownloadStatus.IN_PROGRESS) {
            return
        }

        _meshDownloadStatus.value = MeshDownloadStatus.IN_PROGRESS
        coroutineScope.launch {
            val asyncResult = try {
                authRetryHelper.withRetry {
                    withContext(Dispatchers.IO) {
                        meshDownloaderSession.download(
                            payload = payload,
                            getTexture = true,
                            maxDownloadSizeKb = MeshDownload.MAX_SIZE_KB,
                            timeoutMillis = MeshDownload.TIMEOUT_MS
                        )
                    }
                }
            } catch (e: AuthRetryException) {
                Log.e(TAG, "Authorization timed out while downloading mesh", e)
                _meshDownloadStatus.value = MeshDownloadStatus.FAILED
                _toasts.emit("Authorization timed out")
                return@launch
            }

            when (asyncResult) {
                is AsyncResult.Success -> {
                    val meshes = asyncResult.value
                    if (meshes.isNotEmpty()) {
                        Log.d(TAG, "startMeshDownload: Mesh download successful for anchor, ${meshes.size} chunks")
                        downloadedMeshesByAnchor[anchorKey] = meshes
                        _meshDownloadStatus.value = MeshDownloadStatus.COMPLETED
                        // Update mesh rendering
                        updateMesh()
                    } else {
                        Log.w(TAG, "startMeshDownload: Mesh download returned no meshes")
                        _meshDownloadStatus.value = MeshDownloadStatus.FAILED
                        _toasts.emit("Mesh download returned no meshes")
                    }
                }
                is AsyncResult.Timeout -> {
                    _meshDownloadStatus.value = MeshDownloadStatus.TIMEOUT
                    _toasts.emit("Mesh download timed out")
                }
                is AsyncResult.Error -> {
                    _meshDownloadStatus.value = MeshDownloadStatus.FAILED
                    _toasts.emit("Mesh download failed (${asyncResult.code})")
                }
            }
        }
    }

    /**
     * Returns the set of anchors that are currently in full tracked mode
     * and eligible for mesh rendering.
     */
    private fun getFullyTrackedAnchors(): Set<UUIDKey> {
        return anchors.keys.filter { key ->
            val state = anchorStates[key] ?: AnchorTrackingState.NOT_TRACKED
            val hasMesh = downloadedMeshesByAnchor.containsKey(key)
            val hasTransform = anchorTransforms.containsKey(key)
            (state == AnchorTrackingState.TRACKED) && hasMesh && hasTransform
        }.toSet()
    }

    /**
     * Determines which meshes should be rendered based on anchor states and update types,
     * composes transforms, and notifies the view layer via callback.
     * Mesh is only visible in precise mode (REFINED updateType), not in coarse mode.
     */
    private fun updateMesh() {
        if (!trackingStarted) {
            return
        }

        // Only render meshes for anchors that are TRACKED or LIMITED with REFINED updateType (precise mode)
        val eligibleAnchors = getFullyTrackedAnchors().toList()

        // Only update if the set of eligible anchors has changed
        val newEligibleSet = eligibleAnchors.toSet()
        if (renderedMeshAnchors == newEligibleSet) {
            return // No change, skip expensive mesh composition
        }

        // If no eligible anchors, clear meshes (hide them)
        if (eligibleAnchors.isEmpty()) {
            onMeshDataReady?.invoke(emptyList(), emptyList())
            renderedMeshAnchors = emptySet()
            return
        }

        // Combine meshes from all eligible anchors with composed transforms
        val combinedMeshes = mutableListOf<MeshRenderer.RenderableMeshChunk>()
        val allMeshIds = mutableListOf<Long>()

        eligibleAnchors.forEach { anchorKey ->
            val anchorPoseMatrix = anchorTransforms[anchorKey] ?: return@forEach
            val meshes = downloadedMeshesByAnchor[anchorKey] ?: return@forEach

            // Compose each mesh chunk's transform with the anchor's transform
            meshes.forEach { chunk ->
                val composed = FloatArray(16)
                Matrix.multiplyMM(composed, 0, anchorPoseMatrix, 0, chunk.transform, 0)
                val chunkId = chunk.meshData.hashCode().toLong()
                allMeshIds.add(chunkId)
                combinedMeshes.add(
                    MeshRenderer.RenderableMeshChunk(
                        chunkId = chunkId,
                        meshData = chunk.meshData,
                        modelMatrix = composed,
                        textureData = chunk.imageData,
                    )
                )
            }
        }

        Log.d(TAG, "updateMesh: Updating mesh rendering with ${combinedMeshes.size} chunks from ${eligibleAnchors.size} anchors")
        onMeshDataReady?.invoke(combinedMeshes, allMeshIds)
        renderedMeshAnchors = newEligibleSet
    }

    /** Called when the user taps the screen to create an anchor 1m in front of the camera */
    fun createAnchor() {
        if (!trackingStarted) return

        nsdkManager.currentFrame?.let {
            runCatching {
                val cameraPose = it.camera.getDisplayOrientedPose()
                // Place anchor 1m in front of the camera (-Z is forward in ARCore)
                val anchorPose = cameraPose.compose(Pose.makeTranslation(0f, 0f, -1f))
                    .extractTranslation()
                val anchorId = vps2Session.createAnchor(anchorPose)
                val anchorKey = anchorId.toKey()
                anchors[anchorKey] = null
                anchorStates[anchorKey] = AnchorTrackingState.NOT_TRACKED
            }.onFailure { e ->
                Log.w(TAG, "createAnchor: Could not create VPS2 anchor", e)
            }
        }
    }

    fun stopTracking() {
        trackingStarted = false

        // Clear meshes immediately when stopping tracking
        onMeshDataReady?.invoke(emptyList(), emptyList())

        // Remove all tracked anchors from the native module before stopping
        anchors.keys.forEach { anchorKey ->
            runCatching {
                vps2Session.removeAnchor(anchorKey.bytes)
            }.onFailure { e ->
                Log.w(TAG, "stopTracking(): failed to remove anchor id=${anchorKey.bytes.toString(Charsets.UTF_8)}: ${e.message}")
            }
        }

        runCatching { vps2Session.stop() }

        trackingCollectorsJob?.cancel()
        trackingCollectorsJob = null
        lastToastTimeByMessage.clear()

        localizationTrackingState = Vps2TrackingState.UNAVAILABLE

        anchors.clear()
        anchorStates.clear()
        anchorTransforms.clear()
        downloadedMeshesByAnchor.clear()
        renderedMeshAnchors = emptySet()
        _meshDownloadStatus.value = MeshDownloadStatus.READY

        // Clear anchor transforms flow
        _anchorTransformsFlow.value = emptyMap()

        anchorGeolocations.clear()
        _anchorGeolocationsFlow.value = emptyMap()
    }

    override fun onPause(owner: LifecycleOwner) {
        stopTracking()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopTracking()
        coroutineScope.cancel()
        runCatching { vps2Session.close() }
        runCatching { meshDownloaderSession.close() }
    }
}

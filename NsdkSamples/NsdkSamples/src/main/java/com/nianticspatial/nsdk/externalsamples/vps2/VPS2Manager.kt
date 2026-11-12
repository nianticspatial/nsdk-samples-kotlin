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
import com.nianticspatial.nsdk.MeshDownloaderError
import com.nianticspatial.nsdk.NSDKResult
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
import com.nianticspatial.nsdk.sites.AssetDeploymentType
import com.nianticspatial.nsdk.sites.AssetInfo
import com.nianticspatial.nsdk.sites.SitesSession
import com.nianticspatial.nsdk.vps2.Vps2LocalizationError
import com.nianticspatial.nsdk.vps2.Vps2LocalizationRequestStatus
import com.nianticspatial.nsdk.vps2.Vps2LocalizationRequestType
import com.nianticspatial.nsdk.vps2.Vps2LocalizedAsset
import com.nianticspatial.nsdk.vps2.Vps2Session
import com.nianticspatial.nsdk.vps2.HeadingMode
import com.nianticspatial.nsdk.vps2.Vps2TrackingState
import com.nianticspatial.nsdk.vps2.Vps2Localization
import com.nianticspatial.nsdk.vps2.Vps2GeolocationData
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.nianticspatial.nsdk.MeshDownloaderConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VPS2Manager(
    private val nsdkManager: NSDKSessionManager,
    private val vps2Session: Vps2Session,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val vpsMapAssetId: String? = null,
    // Whether Switch Asset offers non-production assets too, rather than production only.
    private val allAssets: Boolean = false,
    private val siteId: String? = null,
    private val siteLatitude: Double = 0.0,
    private val siteLongitude: Double = 0.0,
) : FeatureManager() {

    companion object {
        private const val TAG = "VPS2Manager"
        private const val TOAST_COOLDOWN_MS = 5000L
        // How far in front of the camera a long-press geotags.
        private const val GEOTAG_DISTANCE_M = 2f

        object MeshDownload {
            const val MAX_SIZE_KB = 10240
            const val TIMEOUT_MS = 15000L

            // How many areas of the localized asset have their mesh drawn at once: the current
            // area plus the most recently visited ones. Every area is still downloaded; drawing
            // all of a large asset at once runs the device out of memory.
            const val MAX_RENDERED_AREAS = 1

            // On-disk budget for downloaded meshes, so revisiting a site loads from the device
            // instead of re-downloading. Least recently used meshes are evicted past this.
            const val CACHE_MAX_MB = 5000
        }

    }

    /** Where to look for VPS sites in the "Localize to Nearby Site Automatically" mode. */
    data class NearbySitesRequest(
        val latitude: Double,
        val longitude: Double,
        /** 0.0 uses the SDK's own default radius (100m). */
        val radiusMeters: Double = 0.0,
    )

    enum class MeshDownloadStatus {
        READY,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        TIMEOUT,
    }

    // Configured once, straight after acquire, so every download path sees the same cache and no
    // download can be in flight when the cache is set up.
    private val meshDownloaderSession: MeshDownloaderSession =
        nsdkManager.session.meshDownload.acquire().also { session ->
            // The cache only saves bandwidth, so failing to enable it must not stop the sample.
            runCatching {
                session.configure(
                    MeshDownloaderConfig(
                        cacheEnabled = true,
                        cacheMaxMb = MeshDownload.CACHE_MAX_MB,
                    )
                )
            }.onFailure { e -> Log.w(TAG, "Mesh cache not enabled; meshes will be re-downloaded", e) }
        }
    private val authRetryHelper = AuthRetryHelper(nsdkManager.session)

    // The component to get the mesh asset from.
    // Acquire from the main thread only (NSDK native library requirement).
    private var sitesSession: SitesSession? = null

    private fun acquireSitesSession(): SitesSession =
        sitesSession ?: nsdkManager.session.sites.acquire().also { sitesSession = it }

    // Resolved lazily from siteId when vpsMapAssetId is not provided upfront.
    private var resolvedVpsMapAssetId: String? = vpsMapAssetId
    private val _meshDownloadStatus = MutableStateFlow<MeshDownloadStatus>(MeshDownloadStatus.READY)
    val meshDownloadStatus: StateFlow<MeshDownloadStatus> = _meshDownloadStatus

    private val downloadedMeshesByAnchor = ConcurrentHashMap<UUIDKey, MutableList<MeshDownloaderData>>()
    private var renderedMeshAnchors: Set<UUIDKey> = emptySet()

    // Asset-anchored mesh, used when there is no app-visible anchor to hang it off (the
    // nearby-sites mode). Chunks stream in once we know the localized asset; the transform comes
    // from Vps2Session.getAssetTrackingData and is only served while the asset is fully tracked.
    // Written by the download coroutine, read by the localization-update collector on another
    // thread, hence the concurrent collection and volatiles.
    private val downloadedAssetMeshes = CopyOnWriteArrayList<MeshDownloaderData>()
    @Volatile private var assetMeshDownloadStarted = false
    /** The asset [downloadedAssetMeshes] belongs to; also the asset whose pose we poll. */
    @Volatile private var meshAssetId: String? = null

    // Areas of meshAssetId whose mesh is drawn, least recently localized first. An asset without
    // areas counts as one area with an empty id, which is what its chunks carry.
    private val renderedAreaIds = LinkedHashSet<String>()
    // Guards renderedAreaIds, the chunk list and the calls into the renderer, so the renderer sees
    // chunk arrivals and area changes in the order they happened.
    private val assetMeshLock = Any()

    // Callback for renderable mesh chunks
    var onMeshDataReady: ((List<MeshRenderer.RenderableMeshChunk>, List<Long>) -> Unit)? = null

    // Pose of the node the downloaded mesh hangs off, or null to hide the mesh. The asset path
    // moves it with the asset, as the Swift sample does with its asset anchor, so losing tracking
    // hides the built mesh instead of destroying it and it reappears at once. The anchor path
    // bakes its poses into the chunks and leaves this at identity.
    private val _meshRootPose = MutableStateFlow<FloatArray?>(identityMatrix())
    val meshRootPose: StateFlow<FloatArray?> = _meshRootPose.asStateFlow()

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

    // The asset the SDK actually localized against, reported on every localization update once a
    // visual localization succeeds. Only the nearby-sites mode surfaces this today; ids arrive
    // first, names follow one portal round-trip later (VPS2 itself knows ids only).
    var localizedSiteId by mutableStateOf<String?>(null)
        private set
    var localizedAssetId by mutableStateOf<String?>(null)
        private set
    var localizedSiteName by mutableStateOf<String?>(null)
        private set
    var localizedAssetName by mutableStateOf<String?>(null)
        private set

    // How many areas the localized asset is split into. Surfaced only above 1: a scan too large
    // for one mapping run produces several, each with its own anchor and extent, and VPS2
    // localizes against whichever one the device is standing in.
    var localizedAssetAreaCount by mutableStateOf<Int?>(null)
        private set

    // The site startTracking localizes to, and which of its assets: null for its production ones.
    // Switch Asset offers the site's other VPS assets in allAssets' scope; empty until they load.
    var targetSiteId by mutableStateOf<String?>(null)
        private set
    var targetAssetId by mutableStateOf<String?>(null)
        private set
    var siteVpsAssets by mutableStateOf<List<AssetInfo>>(emptyList())
        private set

    // The areas whose mesh is drawn, most recently localized first, so the first is the area the
    // device is in. Empty when the asset has no areas.
    var meshAreaIds by mutableStateOf<List<String>>(emptyList())
        private set

    // Track all anchors and payloads. A null payload marks an anchor the user created by tapping
    // the screen; the rest were tracked from a payload by startTracking.
    val anchors = mutableStateMapOf<UUIDKey, String?>()
    val anchorStates = mutableStateMapOf<UUIDKey, AnchorTrackingState>()

    /**
     * How many anchors the user created by tapping the screen, as opposed to the payload-tracked
     * ones the sample sets up itself. Lets the UI offer [clearAll] only when there is something of
     * the user's own to clear.
     */
    val createdAnchorCount: Int
        get() = anchors.count { (_, payload) -> payload == null }

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

    // --- Map-tap pose placement ---------------------------------------------------------------
    // A pose the user dropped by tapping the map, kept as a durable lat/lng/altitude + colour. The
    // AR transform is re-resolved from VPS2's latest localization every frame in
    // updatePlacedMarkers, so a marker tracks localization refinements instead of freezing at its
    // first-resolved pose.
    private data class Placement(
        val id: Long,
        val lat: Double,
        val lng: Double,
        // Captured once at tap time so an existing marker doesn't slide vertically as the device's
        // own altitude changes later (walking up a floor, raising the phone).
        val altitude: Double,
        val colorIndex: Int,
    )

    private val placementsLock = Any()
    private val placements = mutableListOf<Placement>()
    private var nextPlacementId = 0L

    // Device altitude (WGS84 ellipsoidal) from the latest localization — the datum getPose expects,
    // so a new map-placed marker is seeded at the device's current height. Written on the
    // localization thread, read on the UI thread by placeMarker, hence @Volatile.
    @Volatile private var latestDeviceAltitude: Double? = null

    // The localization every placed marker is resolved against, so a single refresh converts the
    // whole batch with one consistent snapshot rather than each marker picking up whatever the
    // latest localization happens to be mid-loop. Vps2Localization is an immutable snapshot, so
    // holding a reference is safe. Written on the localization thread, read by placeMarker on the
    // UI thread, hence @Volatile.
    @Volatile private var latestLocalization: Vps2Localization? = null

    // Whether the last pose-resolution pass failed, so a persistent failure logs once instead of at
    // frame rate (updatePlacedMarkers runs on every localization update).
    private var poseResolutionFailed = false

    // Markers exposed to the UI: the same list drives both the map dots and the AR cubes.
    var placedMarkers by mutableStateOf<List<VPS2PlacedMarker>>(emptyList())
        private set

    private var configured = false

    fun configure(config: Vps2Config = Vps2Config()) {
        vps2Session.configure(config)
        configured = true
    }

    /**
     * Starts VPS2 and localizes against [siteId] (its VPS asset [assetId], production or not, or
     * all of its production assets when that is null or blank), against every site near
     * [nearbySites], against the anchor [payloads], or any combination. At least one of the three
     * must be supplied.
     */
    fun startTracking(
        payloads: List<String> = emptyList(),
        siteId: String? = null,
        assetId: String? = null,
        nearbySites: NearbySitesRequest? = null,
    ) {
        if (trackingStarted) return

        val sanitizedPayloads = payloads.map { it.trim() }.filter { it.isNotEmpty() }
        val sanitizedSiteId = siteId?.trim()?.takeIf { it.isNotEmpty() }
        val sanitizedAssetId = assetId?.trim()?.takeIf { it.isNotEmpty() }
        if (sanitizedPayloads.isEmpty() && sanitizedSiteId == null && nearbySites == null) {
            val message = when {
                siteId != null -> "No site ID provided"
                else -> "No anchor payloads provided"
            }
            coroutineScope.launch { _toasts.emit(message) }
            return
        }

        try {
            // Configure the demo with VPS-map localization at bandwidth-friendly rates:
            // - initial VPS: 1.0 rps, continuous VPS: 0.2 rps
            // - universal localization (OSMT/BEV) off, matching the SDK default
            // - advisory blur check on, so too-blurry frames surface a "hold steady" warning
            if (!configured) {
                configure(
                    Vps2Config(
                        enableUniversalLocalization = false,
                        enableVpsMapLocalization = true,
                        initialVpsRequestsPerSecond = 1.0f,
                        continuousVpsRequestsPerSecond = 0.2f,
                        enableBlurDetection = true,
                        minSharpnessScore = 1000f,
                        enableVpsDebugger = true,
                    )
                )
            }

            vps2Session.start()

            // Fetch the VPS map asset ID for mesh download in parallel with localization, when the
            // caller gave us a site instead of an asset. Mesh comes from the same site we localize
            // to: either the one supplied up front by the site-browsing flow, or the site ID this
            // call is localizing to.
            val meshSiteId = this@VPS2Manager.siteId ?: sanitizedSiteId
            if (vpsMapAssetId == null && meshSiteId != null) {
                coroutineScope.launch {
                    try {
                        val assets = authRetryHelper.withRetry {
                            acquireSitesSession().requestAssetsForSite(meshSiteId)
                        }
                        resolvedVpsMapAssetId = assets.assets.firstOrNull { asset ->
                            val vps = asset.vpsData
                            vps != null &&
                                vps.anchorPayload.isNotBlank() &&
                                asset.deployment == AssetDeploymentType.PRODUCTION
                        }?.id
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch VPS map asset for site $meshSiteId: ${e.message}")
                    }
                }
            }

            // Localize to a Scaniverse site
            if (sanitizedSiteId != null) {
                targetSiteId = sanitizedSiteId
                localizeToSite(sanitizedSiteId, sanitizedAssetId)
                loadSiteVpsAssets(sanitizedSiteId)
            }

            // Localize against every VPS site near the given coordinate — the coordinate overload of
            // It's fire-and-forget, and which asset matched is only known once a localization succeeds
            // via Vps2Localization.localizedAsset below.
            if (nearbySites != null) {
                coroutineScope.launch {
                    try {
                        authRetryHelper.withRetry {
                            vps2Session.localize(
                                latitude = nearbySites.latitude,
                                longitude = nearbySites.longitude,
                                radiusMeters = nearbySites.radiusMeters,
                            )
                        }
                    } catch (e: NsdkInvalidArgumentStatusException) {
                        Log.e(TAG, "Invalid nearby-sites request: $nearbySites", e)
                        _toasts.emit("Invalid location for nearby sites")
                        stopTracking()
                    } catch (e: AuthRetryException) {
                        Log.e(TAG, "Authorization timed out while localizing to nearby sites", e)
                        _toasts.emit("Authorization timed out")
                        stopTracking()
                    }
                }
            }

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

                    // Which asset we are localized to, if any. Independent of the tracking state:
                    // an asset with no georeference reports here while the state stays coarse.
                    onLocalizedAsset(t.localizedAsset)
                    pollAssetTrackingData()

                    // VPS2 localization + geolocation
                    runCatching {
                        localizationTrackingState = t.trackingState
                        latestLocalization = t

                        val geo = vps2Session.getDeviceGeolocation(HeadingMode.DEVICE_TOP)
                        if (geo != null) {
                            vps2Latitude = geo.latitude
                            vps2Longitude = geo.longitude
                            vps2HeadingDeg = geo.heading
                            latestDeviceAltitude = geo.altitude
                        }
                    }.onFailure { e ->
                        localizationTrackingState = Vps2TrackingState.UNAVAILABLE
                        when (e) {
                            is NsdkStatusException -> Log.e(TAG, "NsdkStatusException in localization update", e)
                            else -> Log.e(TAG, "Exception in localization update", e)
                        }
                    }

                    // Re-resolve map-placed markers against the refreshed localization each frame.
                    updatePlacedMarkers()
                }
                .launchIn(trackingScope)

            vps2Session.localizationRequestRecords
                .filter { trackingStarted }
                .filter { it.status == Vps2LocalizationRequestStatus.FAILED ||
                          it.status == Vps2LocalizationRequestStatus.FRAME_REJECTED ||
                          it.status == Vps2LocalizationRequestStatus.FRAME_FLAGGED }
                .catch { e -> Log.e(TAG, "Error in localization request records flow", e) }
                .onEach { record ->
                    val message = when (record.status) {
                        // ASSET_INFO records come from localize()'s site/asset lookup, so
                        // report them as a site problem rather than a localization failure.
                        Vps2LocalizationRequestStatus.FAILED ->
                            if (record.type == Vps2LocalizationRequestType.ASSET_INFO)
                                when (record.error) {
                                    Vps2LocalizationError.SITE_NOT_FOUND -> "Site not found"
                                    Vps2LocalizationError.ASSETS_NOT_FOUND ->
                                        "No localizable assets in site"
                                    // A fetch that stopLocalize cancelled (Switch Asset), not a
                                    // failure to report.
                                    Vps2LocalizationError.UNKNOWN -> return@onEach
                                    else -> "Site request failed: ${record.error}"
                                }
                            else
                                "Localization request failed: ${record.error}"
                        Vps2LocalizationRequestStatus.FRAME_REJECTED ->
                            "Frame rejected: ${record.error}"
                        // Advisory quality flag: the frame is still sent, so coach the user
                        // instead of reporting an error.
                        Vps2LocalizationRequestStatus.FRAME_FLAGGED ->
                            if (record.error == Vps2LocalizationError.IMAGE_TOO_BLURRY)
                                "Image too blurry - hold the device steady"
                            else return@onEach
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


    /** Localizes to [assetId] of [siteId], or to the site's production assets when it is null. */
    private fun localizeToSite(siteId: String, assetId: String?) {
        targetAssetId = assetId
        coroutineScope.launch {
            try {
                authRetryHelper.withRetry {
                    vps2Session.localize(siteId = siteId, assetId = assetId.orEmpty())
                }
            } catch (e: NsdkInvalidArgumentStatusException) {
                Log.e(TAG, "Invalid site or asset ID: $siteId / $assetId", e)
                _toasts.emit(if (assetId == null) "Invalid site ID" else "Invalid site or asset ID")
                stopTracking()
            } catch (e: AuthRetryException) {
                Log.e(TAG, "Authorization timed out while localizing to site", e)
                _toasts.emit("Authorization timed out")
                stopTracking()
            }
        }
    }

    /** Lists [siteId]'s VPS assets, production only unless [allAssets], for [switchAsset]. */
    private fun loadSiteVpsAssets(siteId: String) {
        val sites = acquireSitesSession()
        coroutineScope.launch {
            try {
                // This endpoint returns every asset, production or not.
                val assets = authRetryHelper.withRetry { sites.requestAssetsForSite(siteId) }.assets
                // Tracking may have stopped, or moved to another site, while this was in flight.
                if (targetSiteId == siteId) {
                    siteVpsAssets = assets.filter {
                        it.vpsData?.anchorPayload?.isNotBlank() == true &&
                            (allAssets || it.deployment == AssetDeploymentType.PRODUCTION)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to list VPS assets for site $siteId: ${e.message}")
            }
        }
    }

    /**
     * Moves site localization to [assetId], another VPS asset of the target site. Localizing to a
     * second asset of a site that is already a target would add it rather than replace the first,
     * so the site is stopped first. That clears the localized asset, which drops the old mesh.
     */
    fun switchAsset(assetId: String) {
        val siteId = targetSiteId ?: return
        runCatching { vps2Session.stopLocalize(siteId) }
            .onFailure { e -> Log.w(TAG, "switchAsset(): stopLocalize failed: ${e.message}") }
        localizeToSite(siteId, assetId)
    }

    /**
     * Records the asset the SDK localized against and, the first time we see one, kicks off the
     * name lookup and the asset-anchored mesh download. Then shows the mesh of the area the device
     * localized in. Called on every localization update, so it must stay cheap and idempotent for
     * an unchanged asset and area.
     */
    private fun onLocalizedAsset(asset: Vps2LocalizedAsset?) {
        if (asset == null) {
            // Stopping localization to the site clears it (Switch Asset does). Drop the old
            // asset's mesh, so it is not left on screen and localizing to it again re-downloads it.
            if (localizedAssetId != null) clearLocalizedAsset()
            return
        }
        if (asset.assetId != localizedAssetId) onNewLocalizedAsset(asset)
        // The area changes as the device walks through a split asset, while the asset stays the same.
        showAreaMesh(asset.areaId.orEmpty())
    }

    private fun onNewLocalizedAsset(asset: Vps2LocalizedAsset) {
        localizedSiteId = asset.siteId
        localizedAssetId = asset.assetId
        // Ids are all VPS2 has; names cost a portal round-trip each.
        localizedSiteName = null
        localizedAssetName = null
        localizedAssetAreaCount = null

        // The asset id is all the mesh download needs, so it does not wait on the name lookup.
        startAssetMeshDownload(asset.assetId)

        coroutineScope.launch {
            try {
                val sites = authRetryHelper.withRetry {
                    acquireSitesSession().requestSiteInfo(asset.siteId)
                }
                localizedSiteName = sites.sites.firstOrNull()?.name

                val assetInfo = authRetryHelper.withRetry {
                    acquireSitesSession().requestAssetInfo(asset.assetId)
                }.assets.firstOrNull()
                localizedAssetName = assetInfo?.name
                localizedAssetAreaCount = assetInfo?.areas?.size
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve asset info for site ${asset.siteId}: ${e.message}")
            }
        }
    }

    /** Forgets the localized asset: its ids and names, and its mesh, drawn and downloaded. */
    private fun clearLocalizedAsset() {
        synchronized(assetMeshLock) {
            downloadedAssetMeshes.clear()
            renderedAreaIds.clear()
            meshAssetId = null
        }
        assetMeshDownloadStarted = false
        _meshDownloadStatus.value = MeshDownloadStatus.READY
        onMeshDataReady?.invoke(emptyList(), emptyList())
        localizedSiteId = null
        localizedAssetId = null
        localizedSiteName = null
        localizedAssetName = null
        localizedAssetAreaCount = null
        meshAreaIds = emptyList()
    }

    /**
     * Streams the asset's mesh and places it with [pollAssetTrackingData]'s transform.
     *
     * Every chunk of the asset is downloaded and kept, but only the chunks of the areas in
     * [renderedAreaIds] are drawn; see [showAreaMesh].
     *
     * Mesh geometry is expressed in the asset's coordinate frame: downloadByAsset returns each
     * chunk relative to the asset's root node, and getAssetTrackingData returns the pose of that
     * same root in AR tracking space, which [meshRootPose] applies to every chunk at once.
     *
     * The device can relocalize onto a different asset of the site mid-session, so this restarts
     * for the new asset rather than leaving the previous asset's mesh on screen at the new asset's
     * origin.
     */
    private fun startAssetMeshDownload(assetId: String) {
        if (meshAssetId == assetId) return

        synchronized(assetMeshLock) {
            meshAssetId = assetId
            downloadedAssetMeshes.clear()
            renderedAreaIds.clear()
        }
        meshAreaIds = emptyList()
        assetMeshDownloadStarted = true
        // Hidden until the new asset is tracked.
        _meshRootPose.value = null
        _meshDownloadStatus.value = MeshDownloadStatus.IN_PROGRESS
        onMeshDataReady?.invoke(emptyList(), emptyList())

        coroutineScope.launch {
            // Orders chunks closest-first only; placement comes from the chunk transforms.
            val gps = nsdkManager.dataSource?.latestGpsSample()
            val lat = gps?.latitude?.takeIf { it != 0.0 } ?: siteLatitude
            val lng = gps?.longitude?.takeIf { it != 0.0 } ?: siteLongitude

            var terminalError: MeshDownloaderError? = null

            meshDownloaderSession.downloadByAsset(
                vpsMapAssetId = assetId,
                latitude = lat,
                longitude = lng,
                getTexture = true,
            ).collect { result ->
                when (result) {
                    is NSDKResult.Success -> synchronized(assetMeshLock) {
                        // The stream is long-lived, so re-check inside it: a download superseded
                        // by a newer localized asset must not contribute to it.
                        if (meshAssetId != assetId) return@collect

                        val chunk = result.value
                        downloadedAssetMeshes.add(chunk)
                        Log.d(TAG, "startAssetMeshDownload: chunk received, total=${downloadedAssetMeshes.size}")
                        if (chunk.areaId in renderedAreaIds) renderAssetMesh(listOf(chunk))
                    }
                    is NSDKResult.Error -> terminalError = result.code
                }
            }

            if (meshAssetId != assetId) return@launch

            when (val error = terminalError) {
                null, MeshDownloaderError.NONE -> {
                    Log.d(TAG, "startAssetMeshDownload: stream complete, ${downloadedAssetMeshes.size} chunks")
                    _meshDownloadStatus.value = MeshDownloadStatus.COMPLETED
                }
                MeshDownloaderError.SIZE_LIMIT_REACHED -> {
                    Log.d(TAG, "startAssetMeshDownload: size limit reached, treating as completion")
                    _meshDownloadStatus.value = MeshDownloadStatus.COMPLETED
                }
                else -> {
                    Log.e(TAG, "startAssetMeshDownload: download failed ($error)")
                    _meshDownloadStatus.value = MeshDownloadStatus.FAILED
                    _toasts.emit("Mesh download failed ($error)")
                }
            }
        }
    }

    /**
     * Refreshes the localized asset's pose in AR tracking space. The SDK returns null while the
     * asset is not fully tracked. The mesh is world-locked, so it keeps its last pose while AR
     * tracking holds, and is hidden only when AR tracking is lost too.
     */
    private fun pollAssetTrackingData() {
        // Poll the asset the mesh belongs to by id, not the implicit "currently localized" asset:
        // if the device relocalizes onto another asset mid-download the two disagree, and pairing
        // one asset's mesh with another's pose would place it in the wrong spot.
        val assetId = meshAssetId ?: return

        val tracking = runCatching { vps2Session.getAssetTrackingData(assetId) }
            .onFailure { e -> Log.w(TAG, "getAssetTrackingData failed: ${e.message}") }
            .getOrNull()

        // Same conversion the anchor path does: the SDK hands back an ARCore Pose, and the
        // renderer wants a column-major matrix. Moving one node is cheap, so this follows every
        // refinement rather than only tracking changes.
        if (tracking != null) {
            _meshRootPose.value =
                FloatArray(16).also { tracking.assetToLocalTrackingTransform.toMatrix(it, 0) }
            return
        }

        val isArTracking = runCatching {
            nsdkManager.currentFrame?.camera?.trackingState == TrackingState.TRACKING
        }.getOrDefault(false)
        if (!isArTracking) _meshRootPose.value = null
    }

    /**
     * Makes [areaId] the most recently localized area and draws its mesh. Once more than
     * [MeshDownload.MAX_RENDERED_AREAS] areas are drawn, the least recently localized one is
     * removed from the screen; its chunks stay downloaded, so coming back to it redraws it without
     * a new download.
     */
    private fun showAreaMesh(areaId: String) = synchronized(assetMeshLock) {
        if (renderedAreaIds.lastOrNull() == areaId) return@synchronized

        // Re-adding moves the area to the most recent end.
        val alreadyDrawn = renderedAreaIds.remove(areaId)
        renderedAreaIds.add(areaId)
        while (renderedAreaIds.size > MeshDownload.MAX_RENDERED_AREAS) {
            renderedAreaIds.remove(renderedAreaIds.first())
        }
        meshAreaIds = renderedAreaIds.toList().asReversed().filter { it.isNotEmpty() }
        // Already on screen, so nothing is added or evicted.
        if (alreadyDrawn) return@synchronized

        // Chunks of this area that have not arrived yet are drawn as they arrive.
        renderAssetMesh(downloadedAssetMeshes.filter { it.areaId == areaId })
    }

    /**
     * Hands the renderer [added], placed relative to the asset's root, and removes every drawn
     * chunk outside [renderedAreaIds]. Pass only chunks that are not drawn yet: the renderer
     * rebuilds every chunk it is handed. Builds them even while the asset is untracked: the mesh
     * is hidden then, and ready to show the moment tracking returns.
     *
     * Call with [assetMeshLock] held.
     */
    private fun renderAssetMesh(added: List<MeshDownloaderData>) {
        if (!trackingStarted) return

        val addedMeshes = added.map { chunk ->
            MeshRenderer.RenderableMeshChunk(
                chunkId = chunkId(chunk),
                meshData = chunk.meshData,
                modelMatrix = chunk.transform,
                textureData = chunk.imageData,
            )
        }

        val visibleIds = downloadedAssetMeshes.filter { it.areaId in renderedAreaIds }.map(::chunkId)
        onMeshDataReady?.invoke(addedMeshes, visibleIds)
    }

    private fun chunkId(chunk: MeshDownloaderData): Long = chunk.meshData.hashCode().toLong()

    private fun identityMatrix(): FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private fun startMeshDownload(anchorKey: UUIDKey, payload: String) {
        if (downloadedMeshesByAnchor.containsKey(anchorKey)) {
            return
        }

        if (_meshDownloadStatus.value == MeshDownloadStatus.IN_PROGRESS) {
            return
        }

        // An asset id means we can take the asset route instead of the anchor payload route.
        val vpsMapAssetId = resolvedVpsMapAssetId
        if (vpsMapAssetId != null) {
            startAssetMeshDownload(vpsMapAssetId)
        } else {
            _meshDownloadStatus.value = MeshDownloadStatus.IN_PROGRESS
            startLegacyMeshDownload(anchorKey, payload)
        }
    }

    private fun startLegacyMeshDownload(anchorKey: UUIDKey, payload: String) {
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
                        Log.d(TAG, "startLegacyMeshDownload: success, ${meshes.size} chunks")
                        downloadedMeshesByAnchor[anchorKey] = meshes.toMutableList()
                        _meshDownloadStatus.value = MeshDownloadStatus.COMPLETED
                        updateMesh()
                    } else {
                        Log.w(TAG, "startLegacyMeshDownload: returned no meshes")
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
        // The asset path owns the renderer once it is active; both write the same callback, and a
        // tap-created anchor would otherwise clear the asset's mesh.
        if (assetMeshDownloadStarted) {
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

    /**
     * Places a marker at a map-tapped coordinate. Rejected (returns false) unless localization is
     * PRECISE and a device altitude is known: a coarse fix would land the marker metres off. The AR
     * pose is resolved lazily in [updatePlacedMarkers], so a marker appears once VPS2 can convert
     * its coordinate.
     */
    fun placeMarker(latitude: Double, longitude: Double): Boolean {
        if (localizationTrackingState != Vps2TrackingState.PRECISE) return false
        val altitude = latestDeviceAltitude ?: return false
        synchronized(placementsLock) {
            placements += Placement(nextPlacementId++, latitude, longitude, altitude, placements.size)
        }
        updatePlacedMarkers()
        return true
    }

    /**
     * Geotags the spot [GEOTAG_DISTANCE_M] in front of the camera: VPS2 converts that AR pose to a
     * geolocation with [Vps2Session.getGeolocation], and the result is added as a placement. So it
     * shows on the mini-map, and — resolved back through getPose in [updatePlacedMarkers] — as a
     * cube in AR that should sit where the camera was pointing, which makes the round trip visible.
     *
     * Rejected (returns null) unless localization is PRECISE, for the same reason as [placeMarker].
     */
    fun geotagInFront(): Vps2GeolocationData? {
        if (!trackingStarted || localizationTrackingState != Vps2TrackingState.PRECISE) return null
        val frame = nsdkManager.currentFrame ?: return null

        val geolocation = runCatching {
            // Keep the camera's rotation so the geolocation's heading is the way the camera faces
            // (-Z is forward in ARCore).
            val pose = frame.camera.getDisplayOrientedPose()
                .compose(Pose.makeTranslation(0f, 0f, -GEOTAG_DISTANCE_M))
            vps2Session.getGeolocation(pose)
        }.onFailure { e ->
            Log.w(TAG, "geotagInFront: could not get the geolocation of the pose", e)
        }.getOrNull() ?: return null

        synchronized(placementsLock) {
            placements += Placement(
                nextPlacementId++,
                geolocation.latitude,
                geolocation.longitude,
                geolocation.altitude,
                placements.size,
            )
        }
        updatePlacedMarkers()
        return geolocation
    }

    /**
     * Removes what the user added this session: every map-placed or long-press geotagged marker, and
     * every anchor created by tapping the screen.
     *
     * Payload-tracked anchors are deliberately left in place. They are the sample's own content —
     * tracked once by [startTracking] and never re-tracked — so clearing them would empty the scene
     * (and strand the meshes downloaded against them) with no way back short of Stop then Start.
     * Screen-tap anchors are distinguished by their null payload; they never carry a mesh, so no
     * mesh state has to be reconciled here.
     */
    fun clearAll() {
        synchronized(placementsLock) {
            placements.clear()
            placedMarkers = emptyList()
        }

        // Snapshot the keys before mutating the map we are iterating.
        val createdAnchors = anchors.filterValues { payload -> payload == null }.keys.toList()
        if (createdAnchors.isEmpty()) return

        createdAnchors.forEach { anchorKey ->
            runCatching {
                vps2Session.removeAnchor(anchorKey.bytes)
            }.onFailure { e ->
                Log.w(TAG, "clearAll(): failed to remove anchor id=${anchorKey.bytes.toString(Charsets.UTF_8)}: ${e.message}")
            }
            anchors.remove(anchorKey)
            anchorStates.remove(anchorKey)
            anchorTransforms.remove(anchorKey)
            anchorGeolocations.remove(anchorKey)
        }
        _anchorTransformsFlow.value = anchorTransforms.toMap()
        _anchorGeolocationsFlow.value = anchorGeolocations.toMap()
    }

    /**
     * Re-resolves every placement's AR pose against a single localization snapshot, so all markers
     * in a pass share one conversion basis rather than each picking up a different one mid-loop.
     *
     * Poses are only resolved while that snapshot is PRECISE: a coarse (WPS-only) localization
     * would place markers inaccurately, so transforms drop to `null` (markers hidden) until
     * tracking sharpens back up, and stay `null` before the first localization lands.
     *
     * The read-modify-publish of [placements] into [placedMarkers] is done under [placementsLock]
     * so a placement added by [placeMarker] (UI thread) can't be dropped by a concurrent refresh
     * from the localization thread publishing a stale snapshot after it.
     */
    private fun updatePlacedMarkers() = synchronized(placementsLock) {
        if (placements.isEmpty()) {
            if (placedMarkers.isNotEmpty()) placedMarkers = emptyList()
            return@synchronized
        }

        // One snapshot for the whole batch. getPose(localization, …) throws when the snapshot is
        // UNAVAILABLE, and a coarse one is not accurate enough to place against, so anything short
        // of PRECISE resolves to no transform at all.
        val localization = latestLocalization
            ?.takeIf { it.trackingState == Vps2TrackingState.PRECISE }

        var failure: Throwable? = null
        placedMarkers = placements.map { placement ->
            val matrix = localization?.let { snapshot ->
                runCatching {
                    val geolocation = GeolocationData(
                        latitude = placement.lat,
                        longitude = placement.lng,
                        altitude = placement.altitude,
                        // A placed marker carries position only; the cube is axis-aligned, so the
                        // orientation the pose is built with is irrelevant here. These match what
                        // the SDK's own Location overload passes.
                        heading = 0.0,
                        orientationEdn = floatArrayOf(0f, 0f, 0f, 1f),
                    )
                    val pose = vps2Session.getPose(snapshot, geolocation)
                    FloatArray(16).also { m -> pose.toMatrix(m, 0) }
                }.onFailure { e -> failure = e }.getOrNull()
            }
            VPS2PlacedMarker(
                id = placement.id,
                latitude = placement.lat,
                longitude = placement.lng,
                color = VPS2MarkerPalette.colorAt(placement.colorIndex),
                transform = matrix,
            )
        }

        // Log the first failure of a streak only: this runs on every localization update, so an
        // unconditional log here would spam at frame rate.
        val currentFailure = failure
        if (currentFailure != null && !poseResolutionFailed) {
            Log.w(TAG, "updatePlacedMarkers: failed to resolve marker pose", currentFailure)
        }
        poseResolutionFailed = currentFailure != null
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

        // Unregister every requested site before stopping, so its maps are not localization targets
        // after a later start. The session outlives this: backgrounding routes through onPause.
        runCatching { vps2Session.stopLocalize() }
            .onFailure { e -> Log.w(TAG, "stopTracking(): stopLocalize failed: ${e.message}") }
        runCatching { vps2Session.stop() }
        targetSiteId = null
        targetAssetId = null
        siteVpsAssets = emptyList()

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
        // Drop the site-resolved asset: the next startTracking may target a different site.
        resolvedVpsMapAssetId = vpsMapAssetId

        // A fresh run re-issues the nearby-sites request and may land on a different asset.
        clearLocalizedAsset()
        // Back to what the anchor path expects, in case the next run takes it.
        _meshRootPose.value = identityMatrix()

        // Clear anchor transforms flow
        _anchorTransformsFlow.value = emptyMap()

        anchorGeolocations.clear()
        _anchorGeolocationsFlow.value = emptyMap()

        // Map-placed markers are durable across a stop/start (and backgrounding, which routes
        // through here via onPause) — only clearAll() removes them, mirroring the Swift sample.
        // Their transforms are dropped, though: the next run re-resolves them against its own
        // localization rather than leaving cubes at the previous session's poses.
        latestDeviceAltitude = null
        latestLocalization = null
        poseResolutionFailed = false
        updatePlacedMarkers()

        // Drop the geolocation of the run that just ended. Without this the next run reports the
        // previous fix until its own first localization lands, which reads as a live position and
        // would let the map open centred somewhere the device no longer is.
        vps2Latitude = 0.0
        vps2Longitude = 0.0
        vps2HeadingDeg = 0.0
    }

    override fun onPause(owner: LifecycleOwner) {
        stopTracking()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopTracking()
        coroutineScope.cancel()
        runCatching { vps2Session.close() }
        runCatching { meshDownloaderSession.close() }
        runCatching { sitesSession?.close() }
    }
}

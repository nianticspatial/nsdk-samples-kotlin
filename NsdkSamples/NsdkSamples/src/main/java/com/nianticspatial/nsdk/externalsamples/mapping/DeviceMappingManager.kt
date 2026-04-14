// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.mapping

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.AnchorTrackingState
import com.nianticspatial.nsdk.AnchorUpdate
import com.nianticspatial.nsdk.UUID
import java.io.File
import java.io.FileOutputStream
import android.content.Context
import com.nianticspatial.nsdk.mapping.DeviceMappingSession
import com.nianticspatial.nsdk.mapping.MappingStorageSession
import com.nianticspatial.nsdk.NsdkStatusException
import com.nianticspatial.nsdk.Vps2Config
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import com.nianticspatial.nsdk.vps2.Vps2Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DeviceMappingManager(
    private val nsdkManager: NSDKSessionManager,
    private val context: Context,
    private val vps2Session: Vps2Session = nsdkManager.session.vps2.acquire(),
    private val mappingSession: DeviceMappingSession = nsdkManager.session.mapping.acquire(),
    private val mapStoreSession: MappingStorageSession = nsdkManager.session.mapStore.acquire(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
)  : FeatureManager() {

    var isMapping by mutableStateOf(false)
        private set

    var currentAnchorUpdate by mutableStateOf<AnchorUpdate?>(null)
        private set

    var rootAnchorBase64 by mutableStateOf<String?>(null)
        private set

    var vpsAnchorId by mutableStateOf<UUID?>(null)
        private set

    var hasMappingStarted by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String>("")
        private set

    private var isInitialized = false
    private var mapUpdatesJob: Job? = null
    private var anchorUpdatesJob: Job? = null

    var processPendingMapsDelegate : ((ByteArray) -> Unit)? = null
    var processVpsMetadataDelegate: ((String, MappingStorageSession) -> Unit)? = null

    fun setMapProcessingCallbacks(pendingMapDelegate: ((ByteArray) -> Unit)?, pendingVpsDelegate: ((String, MappingStorageSession) -> Unit)?) {
        processPendingMapsDelegate = pendingMapDelegate
        processVpsMetadataDelegate = pendingVpsDelegate
    }

    init {
        initializeVpsAndMapping()
    }

    private fun initializeVpsAndMapping() {
        if (isInitialized) return

        mappingSession.start()

        val vps2Config = Vps2Config(
            enableUniversalLocalization = false,
            enableVpsMapLocalization = false,
            enableDeviceMapLocalization = true
        )
        try {
            vps2Session.configure(vps2Config)
        } catch (e: NsdkStatusException){
            errorMessage = "Error: failed to configure VPS session. Status: $e"
            Log.e("NSDK DeviceMapping", errorMessage)

        }

        isInitialized = true
        Log.i("NSDK DeviceMapping", "DeviceMappingManager initialized successfully")
    }

    fun startMapping() {
        if (isMapping) return

        Log.i("NSDK DeviceMapping", "Start creating map")
        mappingSession.startCreating()

        resetVpsTracking()
        hasMappingStarted = true
        isMapping = true

        startMapUpdatesCollection()
    }

    fun stopMapping() {
        if (!isMapping) return

        Log.i("NSDK DeviceMapping", "Stop creating map")
        mappingSession.stopCreating()
        isMapping = false

        mapUpdatesJob?.cancel()
        mapUpdatesJob = null
    }

    private fun startMapUpdatesCollection() {
        mapUpdatesJob?.cancel()
        mapUpdatesJob = coroutineScope.launch {
            mapStoreSession.mapUpdates.collect { mapUpdate ->
                if (mapUpdate == null || !isMapping) return@collect

                Log.i("NSDK DeviceMapping", "Received map update with ${mapUpdate.size} bytes")

                if (rootAnchorBase64 == null) {
                    setupRootAnchor()
                }

                if (rootAnchorBase64 != null) {
                    processPendingMapsDelegate?.invoke(mapUpdate)
                }
            }
        }
    }

    private fun startAnchorUpdatesCollection() {
        anchorUpdatesJob?.cancel()
        anchorUpdatesJob = coroutineScope.launch {
            vps2Session.anchorUpdates.collect { update ->
                val anchorId = vpsAnchorId ?: return@collect
                if (!update.uuid.contentEquals(anchorId)) return@collect
                val anchor = rootAnchorBase64
                if (anchor != null && update.trackingState == AnchorTrackingState.TRACKED) {
                    processVpsMetadataDelegate?.invoke(anchor, mapStoreSession)
                }
                currentAnchorUpdate = update
            }
        }
    }

    private fun setupRootAnchor() {
        val rootAnchor = mapStoreSession.createRootAnchor()
        if (rootAnchor == null) return

        rootAnchorBase64 = rootAnchor
        Log.i("NSDK DeviceMapping", "Got root anchor during mapping")

        vps2Session.start()
        try {
            coroutineScope.launch {
                vpsAnchorId = vps2Session.trackAnchor(rootAnchorBase64!!)
                Log.i("NSDK DeviceMapping","Started tracking anchor: ${vpsAnchorId}")
                startAnchorUpdatesCollection()
            }
        } catch (e: NsdkStatusException) {
            errorMessage = "Failed to track anchor ($e)"
            Log.e("NSDK DeviceMapping", errorMessage)
        }
    }

    private fun resetVpsTracking() {
        anchorUpdatesJob?.cancel()
        anchorUpdatesJob = null

        vpsAnchorId?.let { vps2Session.removeAnchor(it) }
        vps2Session.stop()

        vpsAnchorId = null
        rootAnchorBase64 = null
        currentAnchorUpdate = null
    }

    private fun shutdownVpsAndMapping() {
        if (!isInitialized) return

        processPendingMapsDelegate = null
        processVpsMetadataDelegate = null
        mapStoreSession.clear()
        mapStoreSession.close()
        mappingSession.stop()
        mappingSession.close()
        vps2Session.stop()
        vps2Session.close()

        isInitialized = false
        Log.i("NSDK DeviceMapping", "Mapping session and VPS shut down successfully")
    }

    fun saveMapToDisk(fileName: String): Boolean {
        val mapData = mapStoreSession.getData()
        if (mapData == null) {
            errorMessage = "Tried to save map but map has no data."
            Log.e("NSDK DeviceMapping", errorMessage)
            return false
        }

        Log.i("NSDK DeviceMapping", "Retrieved complete map from storage, size: ${mapData.size} bytes")

        try {
            val file = File(context.filesDir, fileName)
            FileOutputStream(file).use { output ->
                output.write(mapData)
            }

            Log.i("NSDK DeviceMapping", "Map saved successfully to: ${file.absolutePath}")
            return true
        } catch (e: Exception) {
            errorMessage = "Failed to save map: ${e.message}"
            Log.e("NSDK DeviceMapping", errorMessage)
            return false
        }
    }

    fun loadMapFromDisk(fileName: String): Boolean {
        val file = File(context.filesDir, fileName)

        try {
            val mapData = file.readBytes()
            Log.i("NSDK DeviceMapping", "Read map data: ${mapData.size} bytes")

            try {
                mapStoreSession.add(mapData)
                Log.i("NSDK DeviceMapping", "Map added to map storage successfully")

                val rootAnchorPayload = mapStoreSession.createRootAnchor()
                if (rootAnchorPayload == null) {
                    errorMessage = "Failed to get root anchor for loaded map."
                    Log.e( "NSDK DeviceMapping", errorMessage )
                    return false
                }

                vps2Session.start()
                try {
                    coroutineScope.launch {
                        vpsAnchorId = vps2Session.trackAnchor(rootAnchorPayload)
                        rootAnchorBase64 = rootAnchorPayload

                        processPendingMapsDelegate?.invoke(mapData)

                        Log.i("NSDK DeviceMapping", "Started tracking loaded map anchor: ${vpsAnchorId}")
                        startAnchorUpdatesCollection()
                    }
                    return true
                } catch (e: NsdkStatusException) {
                    errorMessage = "Failed to track VPS anchor: ${e}"
                    Log.e("NSDK DeviceMapping", errorMessage)
                    return false
                }
            } catch (e: NsdkStatusException) {
                errorMessage = "Failed to add map to storage: ${e}"
                Log.e("NSDK DeviceMapping", errorMessage)
                return false
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load map from disk: ${e.message}"
            Log.e("NSDK DeviceMapping", errorMessage)
            return false
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopMapping()
        resetVpsTracking()
        shutdownVpsAndMapping()
    }
}

package com.nianticspatial.nsdk.externalsamples.mapping

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.AnchorTrackingState
import com.nianticspatial.nsdk.AnchorUpdate
import com.nianticspatial.nsdk.UUID
import com.nianticspatial.nsdk.VPSConfig
import java.io.File
import java.io.FileOutputStream
import android.content.Context
import com.nianticspatial.nsdk.mapping.DeviceMappingSession
import com.nianticspatial.nsdk.mapping.MappingStorageSession
import com.nianticspatial.nsdk.NsdkStatusException
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import com.nianticspatial.nsdk.externalsamples.vps.VPSManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DeviceMappingManager(
    private val nsdkManager: NSDKSessionManager,
    private val context: Context,
    private val vpsManager: VPSManager = VPSManager(nsdkManager, nsdkManager.session.vps.acquire()),
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

        val vpsConfig = VPSConfig(
            enableContinuousLocalization = true,
            enableDeviceMapLocalization = true
        )
        try {
            vpsManager.configureVPSSession(vpsConfig)
        } catch (e: NsdkStatusException){
            errorMessage = "Error: failed to configure VPS session. Status: $e"
            Log.e("NSDK DeviceMapping", errorMessage)

        }

        isInitialized = true
        Log.i("NSDK DeviceMapping", "NSDK initialized successfully")
    }

    fun startMapping() {
        if (isMapping) return

        Log.i("NSDK DeviceMapping", "Start creating map")
        mappingSession.startCreating()

        resetVpsTracking()
        hasMappingStarted = true
        isMapping = true
    }

    fun stopMapping() {
        if (!isMapping) return

        Log.i("NSDK DeviceMapping", "Stop creating map")
        mappingSession.stopCreating()
        isMapping = false
    }

    fun processMapUpdates() {
        if (!isMapping) return

        val mapUpdate = mapStoreSession.getUpdate()
        if (mapUpdate == null) return

        Log.i("NSDK DeviceMapping", "Received map update with ${mapUpdate.size} bytes")

        if (rootAnchorBase64 == null) {
            setupRootAnchor()
        }

        if (rootAnchorBase64 != null) {
            processPendingMapsDelegate?.invoke(mapUpdate)
        }
    }

    fun processVpsUpdates() {
        val anchorId = vpsAnchorId ?: return
        val anchor = rootAnchorBase64 ?: return

        val update = vpsManager.vpsSession.getAnchorUpdate(anchorId)
        currentAnchorUpdate = update

        if (update.trackingState == AnchorTrackingState.TRACKED) {
            processVpsMetadataDelegate?.invoke(anchor, mapStoreSession)
        }
    }

    private fun setupRootAnchor() {
        val rootAnchor = mapStoreSession.createRootAnchor()
        if (rootAnchor == null) return

        rootAnchorBase64 = rootAnchor
        Log.i("NSDK DeviceMapping", "Got root anchor during mapping")

        vpsManager.vpsSession.start()
        try {
            coroutineScope.launch {
                vpsAnchorId = vpsManager.vpsSession.trackAnchor(rootAnchorBase64!!)
                Log.i("NSDK DeviceMapping","Started tracking anchor: ${vpsAnchorId}")
            }
        } catch (e: NsdkStatusException) {
            errorMessage = "Failed to track anchor ($e)"
            Log.e("NSDK DeviceMapping", errorMessage)
        }
    }

    private fun resetVpsTracking() {
        vpsAnchorId?.let { vpsManager.vpsSession.removeAnchor(it) }
        vpsManager.vpsSession.stop()

        vpsAnchorId = null
        rootAnchorBase64 = null
        currentAnchorUpdate = null
    }

    fun cleanup() {
        resetVpsTracking()
        shutdownVpsAndMapping()
    }

    private fun shutdownVpsAndMapping() {
        if (!isInitialized) return

        processPendingMapsDelegate = null
        processVpsMetadataDelegate = null
        mapStoreSession.clear()
        mapStoreSession.close()
        mappingSession.stop()
        mappingSession.close()
        vpsManager.vpsSession.stop()
        vpsManager.vpsSession.close()

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

                vpsManager.vpsSession.start()
                try {
                    coroutineScope.launch {
                        vpsAnchorId = vpsManager.vpsSession.trackAnchor(rootAnchorPayload)
                        rootAnchorBase64 = rootAnchorPayload

                        processPendingMapsDelegate?.invoke(mapData)

                        Log.i("NSDK DeviceMapping", "Started tracking loaded map anchor: ${vpsAnchorId}")
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
        cleanup()
    }
}

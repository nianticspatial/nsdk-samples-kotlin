package com.nianticspatial.nsdk.externalsamples.meshing

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.NsdkStatusException
import com.nianticspatial.nsdk.MeshData
import com.nianticspatial.nsdk.MeshingConfig
import com.nianticspatial.nsdk.MeshingUpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.externalsamples.FeatureManager

class MeshingManager(
    private val nsdkSession: NSDKSession,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : FeatureManager() {

    companion object {
        val meshingRenderDelayMs = 33L
        val meshDataHasUpdateFlag = 1.toByte()
    }

    val meshingSession = nsdkSession.meshingSession.acquire()

    val meshIdToMeshData = mutableStateMapOf<Long, MeshData>()

    var meshingStarted by mutableStateOf(false)
        private set

    private var meshUpdateJob: Job? = null
    private var lastToastTimeMs: Long = 0
    private val toastThrottleMs = 5000L // Show toast at most once every 5 seconds

    init {
        meshingSession.configure(
            MeshingConfig(
                fuseKeyframesOnly = true,
            )
        )
    }

    private suspend fun emitThrottledToast(message: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastToastTimeMs >= toastThrottleMs) {
            _toasts.emit(message)
            lastToastTimeMs = currentTime
        }
    }

    fun startMeshing(updateMeshCallback: (MutableMap<Long, MeshData>, List<Long>) -> Unit) {
        meshingStarted = true
        meshingSession.start()

        // Use the Flow-based API to receive mesh updates automatically.
        // The Flow handles polling, deduplication, and lifecycle management.
        meshUpdateJob = coroutineScope.launch {
            meshingSession.meshUpdates.collect { updatedMeshInfo ->
                if (updatedMeshInfo != null) {
                    Log.d("Meshing", "Received MeshingUpdateInfo: $updatedMeshInfo")
                    emitThrottledToast("New Meshing Update Time: ${meshingSession.getLastUpdateTime()}")

                    meshIdToMeshData.clear()

                    // Iterate through the IDs provided in the updateInfo
                    updatedMeshInfo.ids.forEachIndexed { index, meshId ->
                        val isUpdated = (updatedMeshInfo.updated.getOrNull(index) == meshDataHasUpdateFlag)

                        // Get the MeshData for this specific meshId if it's updated
                        if (isUpdated) {
                            try {
                                val meshData = meshingSession.getData(meshId)
                                if (meshData != null) {
                                    meshIdToMeshData[meshId] = meshData
                                }
                            } catch (e: NsdkStatusException) {
                                Log.e("Meshing", "Exception getting MeshData for ID $meshId: $e")
                            }
                        }
                    }

                    updateMeshCallback.invoke(meshIdToMeshData, updatedMeshInfo.ids.toList())
                } else {
                    emitThrottledToast("No mesh info available.")
                }
            }
        }
    }

    fun stopMeshing() {
        meshingStarted = false
        meshUpdateJob?.cancel()
        meshUpdateJob = null
        lastToastTimeMs = 0 // Reset throttle to allow immediate toast on next start
        meshingSession.stop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopMeshing()
        coroutineScope.cancel()
        meshingSession.close()
    }
}

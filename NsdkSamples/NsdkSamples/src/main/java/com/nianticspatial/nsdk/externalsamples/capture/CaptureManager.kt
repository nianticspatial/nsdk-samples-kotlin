// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.capture

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.AsyncResult
import com.nianticspatial.nsdk.ScanSaveInfo
import com.nianticspatial.nsdk.ScannerConfig
import com.nianticspatial.nsdk.recording.RecordingExporter
import com.nianticspatial.nsdk.scanning.RaycastBuffer
import com.nianticspatial.nsdk.scanning.ScanningSession
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CaptureManager(
    private val scanningSession: ScanningSession,
    private val recordingExporter: RecordingExporter,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : FeatureManager() {

    companion object {
        const val EXPORT_POLL_DELAY_MS = 16L
        const val FRAME_COUNT_POLL_DELAY_MS = 100L
        const val RAYCAST_BUFFER_POLL_DELAY_MS = 16L  // 60 FPS
    }

    var isRecording by mutableStateOf(false)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var isExporting by mutableStateOf(false)
        private set
    var exportProgress by mutableStateOf(0.0f)
        private set
    var saveInfo by mutableStateOf<ScanSaveInfo?>(null)
        private set
    var frameCount by mutableStateOf(0)
        private set
    var raycastBuffer by mutableStateOf<RaycastBuffer?>(null)
        private set

    fun startCapture() {
        if (isRecording) return

        isRecording = true
        saveInfo = null
        frameCount = 0
        raycastBuffer = null

        val config = ScannerConfig()
        config.useNsdkDepthsIfPlatformUnavailable = true
        config.enableRaycastVisualization = true
        config.enableVoxelVisualization = false
        scanningSession.configure(config)
        scanningSession.start()

        Log.i("CaptureManager", "Capture started")

        coroutineScope.launch {
            while (isRecording) {
                frameCount = try {
                    scanningSession.getRecordingInfo().frameCount
                } catch (e: Exception) {
                    Log.e("CaptureManager", "Error getting frame count", e)
                    0
                }
                delay(FRAME_COUNT_POLL_DELAY_MS)
            }
            frameCount = 0
        }

        coroutineScope.launch {
            while (isRecording) {
                try {
                    raycastBuffer = scanningSession.raycastBuffer()
                } catch (e: Exception) {
                    Log.e("CaptureManager", "Error getting raycast buffer", e)
                }
                delay(RAYCAST_BUFFER_POLL_DELAY_MS)
            }
            raycastBuffer = null
        }
    }

    suspend fun save(): Result<ScanSaveInfo> {
        if (!isRecording) {
            return Result.failure(Exception("Not currently capturing"))
        }

        isSaving = true
        isRecording = false

        val result = when (val result = scanningSession.save()) {
            is AsyncResult.Success -> {
                saveInfo = result.value
                isSaving = false
                Log.i(
                    "CaptureManager",
                    "Capture saved - ID: ${result.value.scanId}, Path: ${result.value.savePath}"
                )
                Result.success(result.value)
            }

            is AsyncResult.Error -> {
                isSaving = false
                Log.e("CaptureManager", "Failed to save capture: ${result.code}")
                Result.failure(Exception("Failed to save capture: ${result.code}"))
            }

            is AsyncResult.Timeout -> {
                isSaving = false
                Log.e("CaptureManager", "Timeout while saving capture")
                Result.failure(Exception("Timeout while saving capture"))
            }
        }
        scanningSession.stop()
        return result
    }

    fun stop() {
        if (isRecording) {
            isRecording = false
            raycastBuffer = null
            scanningSession.stop()
            Log.i("CaptureManager", "Capture stopped")
        }
    }

    suspend fun export(): Result<String> {
        val scanInfo = saveInfo
        if (scanInfo?.scanId == null || scanInfo.savePath == null) {
            Log.e("CaptureManager", "Save info is null or incomplete")
            return Result.failure(Exception("No capture available to export"))
        }

        try {
            val basePath = scanInfo.savePath!!
            val scanId = scanInfo.scanId!!

            Log.i("CaptureManager", "Starting export - Base: $basePath, ScanID: $scanId")

            isExporting = true
            exportProgress = 0.0f

            return when (val result =
                recordingExporter.export(
                    basePath,
                    scanId,
                    onProgress = { p -> exportProgress = p },
                    timeoutMillis = 600000)) {
                is AsyncResult.Success -> {
                    Log.i("CaptureManager", "Export completed: ${result.value}")
                    Result.success(result.value)
                }

                is AsyncResult.Error -> {
                    Log.e("CaptureManager", "Export failed with error code: ${result.code}")
                    Result.failure(Exception("Export failed: ${result.code}"))
                }

                is AsyncResult.Timeout -> {
                    Log.e("CaptureManager", "Export timeout")
                    Result.failure(Exception("Export timeout"))
                }
            }
        } catch (e: Exception) {
            Log.e("CaptureManager", "Export failed with exception", e)
            return Result.failure(Exception("Export failed: ${e.message}"))
        } finally {
            isExporting = false
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stop()
        scanningSession.close()
        recordingExporter.close()
        coroutineScope.cancel()
    }
}

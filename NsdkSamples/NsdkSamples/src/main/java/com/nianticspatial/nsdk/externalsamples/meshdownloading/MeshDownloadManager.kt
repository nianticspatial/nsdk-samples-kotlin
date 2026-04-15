// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.vps

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.AsyncResult
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import com.nianticspatial.nsdk.MeshDownloaderData
import com.nianticspatial.nsdk.externalsamples.auth.AuthRetryHelper
import com.nianticspatial.nsdk.mesh.MeshDownloaderSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MeshDownloadStatus() {
    READY,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    FAILED,
    TIMEOUT
}

class MeshDownloadManager(
    private val nsdkSession: NSDKSession,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
) : FeatureManager() {

    companion object {
        private const val TAG = "MeshDownloadManager"
        private const val MAX_ATTEMPTS = 60
        private const val MAX_DOWNLOAD_SIZE_KB = 50 * 1024 // 50 MB
        private const val TIMEOUT_MILLIS = 30000L // 30 seconds
    }

    // MutableStateFlow is used so the data can be observed from both Compose
    // and non-UI consumers (ViewModels, tests, etc.) while still exposing a
    // reactive stream that supports multiple collectors.
    private val _status = MutableStateFlow<MeshDownloadStatus>(MeshDownloadStatus.READY)
    val status: StateFlow<MeshDownloadStatus> = _status

    private val _downloadedResultDetails = MutableStateFlow<String?>(null)
    val downloadedResultDetails: StateFlow<String?> = _downloadedResultDetails

    private val meshDownloaderSession: MeshDownloaderSession = nsdkSession.meshDownload.acquire()
    private val authRetryHelper: AuthRetryHelper = AuthRetryHelper(nsdkSession)
    private var downloadJob: Job? = null

    var _meshDownloadCallback: ((Array<MeshDownloaderData>) -> Unit)? = null

    fun startDownload(
        locationName: String,
        payload: String,
        meshDownloadCallback: ((Array<MeshDownloaderData>) -> Unit)? = null
    ) {
        if (_status.value == MeshDownloadStatus.IN_PROGRESS) {
            return
        }

        _meshDownloadCallback = meshDownloadCallback

        _downloadedResultDetails.value = null
        _status.value = MeshDownloadStatus.IN_PROGRESS

        downloadJob = coroutineScope.launch {
            val asyncResult = authRetryHelper.withRetry {
                withContext(Dispatchers.IO) {
                    meshDownloaderSession.download(
                        payload = payload,
                        getTexture = true,
                        maxDownloadSizeKb = MAX_DOWNLOAD_SIZE_KB,
                        timeoutMillis = TIMEOUT_MILLIS
                    )
                }
            }

            when (asyncResult) {
                is AsyncResult.Success -> {
                    val meshes = asyncResult.value
                    if (meshes.isNotEmpty()) {
                        _meshDownloadCallback?.invoke(meshes)
                    } else {
                        Log.w(TAG, "Downloaded mesh list is empty.")
                    }
                    val details = buildString {
                        appendLine("Location: $locationName")
                        appendLine()
                        appendLine("Anchor Payload:")
                        appendLine(payload)
                        appendLine()
                        appendLine("Mesh Download Results:")
                        meshes.forEachIndexed { index, mesh ->
                            appendLine("Mesh #${index + 1}")
                            appendLine("Vertex count: ${mesh.meshData.vertices.size}")
                            appendLine("Index count: ${mesh.meshData.indices.size}")
                            if (index < meshes.size - 1) appendLine()
                        }
                    }

                    withContext(Dispatchers.Main) {
                        _status.value = MeshDownloadStatus.COMPLETED
                        _downloadedResultDetails.value = details
                    }
                }

                is AsyncResult.Timeout -> {
                    withContext(Dispatchers.Main) {
                        _status.value = MeshDownloadStatus.TIMEOUT
                        _toasts.emit("Mesh download timed out.")
                    }
                }

                is AsyncResult.Error -> {
                    withContext(Dispatchers.Main) {
                        _status.value = MeshDownloadStatus.FAILED
                        _toasts.emit("Mesh download failed (${asyncResult.code}).")
                    }
                }
            }
        }
    }

    fun cancelDownload(forceCancel: Boolean = false) {
        if (!forceCancel && _status.value != MeshDownloadStatus.IN_PROGRESS) {
            return
        }
        downloadJob?.cancel()
        downloadJob = null
        _status.value = if (forceCancel) MeshDownloadStatus.READY else MeshDownloadStatus.CANCELLED
    }

    fun dismissDialog() {
        _downloadedResultDetails.value = null
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        cancelDownload()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        cancelDownload()
        // Note: we don't close the download session here, as it's a singleton, and
        // closing it with downloads in progress leads to crashes.
        _status.value = MeshDownloadStatus.READY
    }
}

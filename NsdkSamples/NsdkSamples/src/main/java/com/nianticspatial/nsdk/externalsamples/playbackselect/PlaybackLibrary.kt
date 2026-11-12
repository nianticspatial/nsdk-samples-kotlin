// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.playbackselect

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nianticspatial.nsdk.externalsamples.ARSessionManager
import com.nianticspatial.nsdk.playback.CachedPlaybackState
import com.nianticspatial.nsdk.playback.PlaybackCacheSession
import com.nianticspatial.nsdk.playback.PlaybackDataset
import com.nianticspatial.nsdk.playback.PlaybackFileInfo
import com.nianticspatial.nsdk.playback.PlaybackOpenException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A scan's download state in this app run. */
sealed interface ScanDownload {
    data class Downloading(val progress: Double) : ScanDownload
    data object Cached : ScanDownload
    data object Opening : ScanDownload
    data object Deleting : ScanDownload
    /** On disk without a complete recording, and not downloading; selecting it downloads again. */
    data object Incomplete : ScanDownload
    data class Failed(val reason: String) : ScanDownload
}

/**
 * App-wide scan downloads and playback source: downloads run concurrently, and a cached scan plays
 * in place of the camera once selected.
 */
class PlaybackLibrary(
    private val playbackCache: PlaybackCacheSession,
    private val arManager: ARSessionManager,
) {
    companion object {
        private const val TAG = "PlaybackLibrary"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var opening: Job? = null
    private val openJobs = mutableSetOf<Job>()
    private val deleteJobs = mutableSetOf<Job>()
    private var pendingSource: String? = null

    /** Download state by scan ID; absent for a scan not known to be cached or downloading. */
    val downloads = mutableStateMapOf<String, ScanDownload>()

    /** True while [clearAll] runs. */
    var clearing by mutableStateOf(false)
        private set

    /** Why the last [clearAll] failed, or null. */
    var clearError by mutableStateOf<String?>(null)
        private set

    /** How many scans are downloading. */
    val downloadingCount: Int
        get() = downloads.values.count { it is ScanDownload.Downloading }

    /** Whether any scan is in the cache or downloading into it. */
    val hasCachedEntries: Boolean
        get() = downloads.values.any { it !is ScanDownload.Failed }

    /** The scan playing in place of the camera, or null in live mode. */
    var activeScan by mutableStateOf<PlaybackScan?>(null)
        private set

    /** The recording each scan plays, as the portal lists it, for the scans looked up so far. */
    val fileInfos = mutableStateMapOf<String, PlaybackFileInfo>()

    /** Scans whose lookup is in flight or has failed this session; neither is looked up again. */
    private val fileInfoRequested = mutableSetOf<String>()

    /**
     * Downloads [scan] if it is not cached or incomplete, or plays it in place of the camera if it
     * is cached.
     */
    fun select(scan: PlaybackScan) {
        if (clearing) return
        when (downloads[scan.id]) {
            null, is ScanDownload.Failed, ScanDownload.Incomplete -> download(scan)
            ScanDownload.Cached -> if (opening?.isActive != true) activate(scan)
            else -> Unit
        }
    }

    private fun download(scan: PlaybackScan) {
        downloads[scan.id] = ScanDownload.Downloading(0.0)
        track(openJobs) {
            val dataset = openOrFail(scan.id) { downloads[scan.id] = ScanDownload.Downloading(it) }
                ?: return@track
            dataset.close()
            downloads[scan.id] = ScanDownload.Cached
        }
    }

    private fun activate(scan: PlaybackScan) {
        if (activeScan?.id == scan.id) return
        downloads[scan.id] = ScanDownload.Opening
        pendingSource = scan.id
        opening = track(openJobs) {
            val dataset = openOrFail(scan.id) {} ?: return@track
            downloads[scan.id] = ScanDownload.Cached
            if (pendingSource == scan.id) {
                arManager.setPlaybackDataset(dataset)
                activeScan = scan
            } else {
                dataset.close()
            }
        }
    }

    /** Keeps a scan still opening from becoming the source; the open itself finishes. */
    fun dropPendingSource() {
        pendingSource = null
    }

    /** Opens [scanId] from the cache or the portal, or records why it failed and returns null. */
    private suspend fun openOrFail(scanId: String, onProgress: (Double) -> Unit): PlaybackDataset? =
        try {
            playbackCache.open(scanId, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open scan $scanId", e)
            val reason = (e as? PlaybackOpenException)?.error?.name ?: e.message
            downloads[scanId] = ScanDownload.Failed(reason ?: "unknown error")
            null
        }

    /** Marks the scans among [scanIds] already in the cache as [ScanDownload.Cached]. */
    fun seedCached(scanIds: List<String>) {
        val unknown = scanIds.filter { it !in downloads }
        if (unknown.isEmpty()) return
        scope.launch {
            val cached = withContext(Dispatchers.IO) { unknown.filter(::isCached) }
            cached.forEach { if (it !in downloads) downloads[it] = ScanDownload.Cached }
        }
    }

    /**
     * Lists the scan IDs in the cache, marking each ready one [ScanDownload.Cached] and each
     * incomplete one [ScanDownload.Incomplete] unless it already has a state.
     *
     * @throws Exception if the cache cannot be listed.
     */
    suspend fun loadCached(): List<String> {
        val entries = withContext(Dispatchers.IO) { playbackCache.cachedPlaybacks() }
        for (entry in entries) {
            if (entry.scanId in downloads) continue
            when (entry.state) {
                CachedPlaybackState.READY -> downloads[entry.scanId] = ScanDownload.Cached
                CachedPlaybackState.INCOMPLETE -> downloads[entry.scanId] = ScanDownload.Incomplete
                CachedPlaybackState.DOWNLOADING -> Unit
            }
        }
        return entries.map { it.scanId }
    }

    /** Returns to the live camera, closing the playing scan and dropping one still opening. */
    fun useLiveCamera() {
        pendingSource = null
        activeScan = null
        arManager.setPlaybackDataset(null)
    }

    /**
     * Whether [scanId] can be deleted: it is cached or incomplete, with no download, delete or
     * clear running.
     */
    fun canDelete(scanId: String): Boolean =
        !clearing && downloads[scanId].let {
            it == ScanDownload.Cached || it == ScanDownload.Incomplete
        }

    /**
     * Removes [scanId] from the cache, returning to the live camera first if it is playing; does
     * nothing unless [canDelete].
     */
    fun delete(scanId: String) {
        if (!canDelete(scanId)) return
        if (activeScan?.id == scanId) useLiveCamera()
        downloads[scanId] = ScanDownload.Deleting
        track(deleteJobs) {
            try {
                withContext(Dispatchers.IO) { playbackCache.deleteCached(scanId) }
                downloads.remove(scanId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete scan $scanId", e)
                downloads[scanId] = if (withContext(Dispatchers.IO) { isCached(scanId) }) {
                    ScanDownload.Cached
                } else {
                    ScanDownload.Incomplete
                }
            }
        }
    }

    /**
     * Removes every scan from the cache, returning to the live camera first if one is playing.
     * Cancels downloads and opens still running and waits for them and any delete to finish,
     * since the cache refuses to clear before then.
     */
    fun clearAll() {
        if (clearing) return
        if (activeScan != null) useLiveCamera()
        clearError = null
        clearing = true
        val opens = openJobs.toList()
        val deletes = deleteJobs.toList()
        opens.forEach { it.cancel() }
        scope.launch {
            (opens + deletes).joinAll()
            downloads.filterValues { it is ScanDownload.Downloading || it == ScanDownload.Opening }
                .keys.forEach { downloads.remove(it) }
            try {
                withContext(Dispatchers.IO) { playbackCache.clearCache() }
                downloads.clear()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear the playback cache", e)
                clearError = "Could not clear the cache: ${e.message}"
            } finally {
                clearing = false
            }
        }
    }

    /** Lists the cache as [loadCached] does, logging instead of throwing when it cannot. */
    suspend fun refreshCached() {
        try {
            loadCached()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list the playback cache", e)
        }
    }

    /** Launches [block] and keeps its job in [jobs] until it completes. */
    private fun track(jobs: MutableSet<Job>, block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(block = block)
        jobs += job
        job.invokeOnCompletion { jobs -= job }
        return job
    }

    /**
     * Looks up the recording [scanId] plays, once per session; a row calls it as it appears. Runs
     * in the library's scope, so a row scrolling away keeps the result.
     */
    fun loadFileInfo(scanId: String) {
        if (!fileInfoRequested.add(scanId)) return
        scope.launch {
            try {
                fileInfos[scanId] = playbackCache.playbackFileInfo(scanId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to look up the recording of scan $scanId", e)
            }
        }
    }

    private fun isCached(scanId: String): Boolean =
        runCatching { playbackCache.isCached(scanId) }.getOrDefault(false)

    /** Cancels downloads in progress. */
    fun close() {
        scope.cancel()
    }
}

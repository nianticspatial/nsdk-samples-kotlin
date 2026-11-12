// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.playbackselect

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import com.nianticspatial.nsdk.externalsamples.auth.AuthRetryHelper
import com.nianticspatial.nsdk.sites.SitesSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Lists the cached scans and looks up each one's name and duration through Sites. */
class DownloadedPlaybacksManager(
    nsdkSession: NSDKSession,
    private val library: PlaybackLibrary,
) : FeatureManager() {

    companion object {
        private const val TAG = "DownloadedPlaybacks"
        private const val MAX_CONCURRENT_SCAN_REQUESTS = 6
        private val order = compareBy<PlaybackScan, Long?>(nullsLast(reverseOrder())) {
            it.createdTimestamp
        }.thenBy { it.id }
    }

    private val sitesSession: SitesSession = nsdkSession.sites.acquire()
    private val retryHelper = AuthRetryHelper(nsdkSession)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loadJob: Job? = null

    /**
     * The cached scans, null while listing. Rows keep their places while lookups run, then sort
     * newest first with unresolved ones last.
     */
    var scans by mutableStateOf<List<PlaybackScan>?>(null)
        private set

    /** Why the cache could not be listed, or null. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Lists the cache, then replaces each entry with its scan info as each lookup answers. */
    fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            scans = null
            error = null
            val scanIds = try {
                library.loadCached()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list the playback cache", e)
                error = "Could not list the cache: ${e.message}"
                return@launch
            }
            var current = scanIds.sorted().map { PlaybackScan.unresolved(it) }
            scans = current
            val permits = Semaphore(MAX_CONCURRENT_SCAN_REQUESTS)
            coroutineScope {
                scanIds.forEach { scanId ->
                    launch {
                        val scan = permits.withPermit { resolve(scanId) } ?: return@launch
                        current = current.map { if (it.id == scanId) scan else it }
                        scans = current
                    }
                }
            }
            scans = current.sortedWith(order)
        }
    }

    /** The scan's info, or null if Sites cannot return it. */
    private suspend fun resolve(scanId: String): PlaybackScan? =
        try {
            retryHelper.withRetry { sitesSession.requestScanInfo(scanId) }.scans.firstOrNull()
                ?.let { PlaybackScan(it.id, it.name, "", it.durationSeconds, it.createdTimestamp) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to look up scan $scanId", e)
            null
        }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
        runCatching { sitesSession.close() }
    }
}

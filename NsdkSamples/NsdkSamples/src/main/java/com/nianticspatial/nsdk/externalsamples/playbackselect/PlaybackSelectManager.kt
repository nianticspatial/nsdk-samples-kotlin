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
import com.nianticspatial.nsdk.sites.OrganizationInfo
import com.nianticspatial.nsdk.sites.ScanType
import com.nianticspatial.nsdk.sites.SiteInfo
import com.nianticspatial.nsdk.sites.SitesError
import com.nianticspatial.nsdk.sites.SitesException
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

/** What the Playback Select screen shows. */
internal sealed class BrowseState {
    data object Loading : BrowseState()
    data class Organizations(val orgs: List<OrganizationInfo>) : BrowseState()
    data class Sites(val org: OrganizationInfo, val sites: List<SiteInfo>) : BrowseState()
    /**
     * [site] is null for every site in [org]. While those load, [sitesLoaded] of [sitesTotal]
     * sites have answered; it is null once loading ends.
     */
    data class Scans(
        val org: OrganizationInfo,
        val site: SiteInfo?,
        val entries: List<PlaybackScan>,
        val sitesLoaded: Int? = null,
        val sitesTotal: Int = 0,
    ) : BrowseState()
    data class Error(val message: String) : BrowseState()
}

/** Browses an organization's validation scans by site, or all of them at once. */
class PlaybackSelectManager(nsdkSession: NSDKSession) : FeatureManager() {

    companion object {
        private const val TAG = "PlaybackSelectManager"
        private const val MAX_CONCURRENT_SITE_REQUESTS = 6
    }

    private val sitesSession: SitesSession = nsdkSession.sites.acquire()
    private val retryHelper = AuthRetryHelper(nsdkSession)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var orgs: List<OrganizationInfo> = emptyList()
    private var sites: List<SiteInfo> = emptyList()
    private var loadJob: Job? = null
    private var started = false

    internal var state by mutableStateOf<BrowseState>(BrowseState.Loading)
        private set

    /** Case-insensitive name filter for the shown list, kept from a site's scans to All playbacks. */
    var filterText by mutableStateOf("")
        private set

    fun onFilterChange(text: String) {
        filterText = text
    }

    /** Lists the organizations on the first call; later calls keep what the screen shows. */
    fun start() {
        if (started) return
        started = true
        loadOrganizations()
    }

    /** Lists the user's organizations. */
    fun loadOrganizations() = load {
        orgs = retryHelper.withRetry { sitesSession.requestSelfOrganizationInfo() }.organizations
        BrowseState.Organizations(orgs)
    }

    fun onOrganizationClick(org: OrganizationInfo) {
        filterText = ""
        load { loadSites(org) }
    }

    fun onSiteClick(org: OrganizationInfo, site: SiteInfo) {
        filterText = ""
        load { BrowseState.Scans(org, site, scansFor(site)) }
    }

    /**
     * Lists the validation scans of every site in [org], adding each site's scans as its response
     * arrives. Fails only if every site fails.
     */
    fun onAllClick(org: OrganizationInfo) {
        if (state is BrowseState.Sites) filterText = ""
        loadAll(org)
    }

    private fun loadAll(org: OrganizationInfo) = load {
        var scans = BrowseState.Scans(
            org, null, emptyList(), sitesLoaded = 0, sitesTotal = sites.size
        )
        state = scans
        var succeeded = 0
        var firstFailure: Exception? = null
        val permits = Semaphore(MAX_CONCURRENT_SITE_REQUESTS)
        coroutineScope {
            sites.forEach { site ->
                launch {
                    val entries = try {
                        permits.withPermit { scansFor(site) }.also { succeeded++ }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to list scans for site ${site.name}", e)
                        if (firstFailure == null) firstFailure = e
                        emptyList()
                    }
                    scans = scans.copy(
                        entries = (scans.entries + entries)
                            .sortedByDescending { it.createdTimestamp },
                        sitesLoaded = scans.sitesLoaded!! + 1,
                    )
                    state = scans
                }
            }
        }
        if (succeeded == 0) firstFailure?.let { throw it }
        scans.copy(sitesLoaded = null)
    }

    fun onBySiteClick(org: OrganizationInfo) {
        loadJob?.cancel()
        filterText = ""
        state = BrowseState.Sites(org, sites)
    }

    fun onBackToOrganizations() {
        loadJob?.cancel()
        filterText = ""
        state = BrowseState.Organizations(orgs)
    }

    private suspend fun loadSites(org: OrganizationInfo): BrowseState {
        sites = retryHelper.withRetry { sitesSession.requestSitesForOrganization(org.id) }.sites
        return BrowseState.Sites(org, sites)
    }

    private suspend fun scansFor(site: SiteInfo): List<PlaybackScan> =
        retryHelper.withRetry { sitesSession.requestScansForSite(site.id) }.scans
            .filter { it.scanType == ScanType.VALIDATION }
            .map {
                PlaybackScan(it.id, it.name, site.name, it.durationSeconds, it.createdTimestamp)
            }

    private fun load(block: suspend () -> BrowseState) {
        loadJob?.cancel()
        loadJob = scope.launch {
            state = BrowseState.Loading
            state = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load playbacks", e)
                BrowseState.Error(errorMessage(e))
            }
        }
    }

    private fun errorMessage(e: Exception): String =
        if ((e as? SitesException)?.error == SitesError.HTTP_FORBIDDEN) {
            "Error: HTTP_FORBIDDEN. Playback Select needs a token that can list scans; a token " +
                "scoped to site-assets:read cannot."
        } else {
            "Error: ${e.message}"
        }

    override fun onDestroy(owner: LifecycleOwner) = close()

    /** Cancels loads and closes the Sites session. */
    fun close() {
        scope.cancel()
        runCatching { sitesSession.close() }
    }
}

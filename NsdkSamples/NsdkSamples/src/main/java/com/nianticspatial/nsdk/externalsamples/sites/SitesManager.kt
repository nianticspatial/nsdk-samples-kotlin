// Copyright 2026 Niantic.
package com.nianticspatial.nsdk.externalsamples.sites

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.LatLng
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import com.nianticspatial.nsdk.externalsamples.auth.AuthRetryHelper
import com.nianticspatial.nsdk.sites.AssetDeploymentType
import com.nianticspatial.nsdk.sites.AssetInfo
import com.nianticspatial.nsdk.sites.AssetScope
import com.nianticspatial.nsdk.sites.AssetType
import com.nianticspatial.nsdk.sites.OrganizationInfo
import com.nianticspatial.nsdk.sites.SitesException
import com.nianticspatial.nsdk.sites.SitesSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class SitesManager(
    private val nsdkSession: NSDKSession,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
) : FeatureManager() {

    companion object {
        private const val TAG = "SitesManager"

        /** The VPS assets in [scope] that VPS2 can localize to: those with a map. */
        private fun vpsAssets(assets: List<AssetInfo>, scope: AssetScope): List<AssetInfo> =
            assets.filter {
                it.vpsData?.anchorPayload?.isNotBlank() == true &&
                    (scope == AssetScope.ALL || it.deployment == AssetDeploymentType.PRODUCTION)
            }
    }

    private val sitesSession: SitesSession = nsdkSession.sites.acquire()
    private val retryHelper = AuthRetryHelper(nsdkSession)

    internal var navState by mutableStateOf<SitesNavState>(SitesNavState.ModeSelection)
        private set

    var filterText by mutableStateOf("")
        private set

    // Retained for back navigation and site sorting
    private var currentOrganizations: List<OrganizationInfo> = emptyList()
    var currentLocation: LatLng? = null
        private set

    // Which assets the lists show: production only, or all. Non-production ones localize by asset ID.
    var assetScope by mutableStateOf(AssetScope.PRODUCTION)
        private set

    // The last near-me search (lat, lng, radius), so a scope change can re-run it.
    private var lastNearMeSearch: Triple<Double, Double, Double>? = null

    fun onStartFromOrgsFlow() {
        loadInitialData()
    }

    fun onStartNearMeFlow() {
        filterText = ""
        lastNearMeSearch = null
        navState = SitesNavState.NearMeResults(emptyList())
    }

    /** Switches [assetScope] and re-runs the list on screen with it. */
    fun onAssetScopeChange(scope: AssetScope) {
        if (scope == assetScope) return
        assetScope = scope
        when (val state = navState) {
            is SitesNavState.NearMeResults -> lastNearMeSearch?.let { (lat, lng, radiusMeters) ->
                coroutineScope.launch { loadNearMeSites(lat, lng, radiusMeters) }
            }
            is SitesNavState.OrganizationView ->
                coroutineScope.launch { loadSitesForOrganization(state.org) }
            else -> Unit
        }
    }

    fun onBackToModeSelection() {
        filterText = ""
        navState = SitesNavState.ModeSelection
    }

    fun onNearMeSearch(lat: Double, lng: Double, radiusMeters: Double, location: LatLng?) {
        filterText = ""
        currentLocation = location
        lastNearMeSearch = Triple(lat, lng, radiusMeters)
        coroutineScope.launch {
            loadNearMeSites(lat, lng, radiusMeters)
        }
    }

    fun loadInitialData() {
        coroutineScope.launch {
            try {
                navState = SitesNavState.Loading

                val orgsResult = retryHelper.withRetry {
                    sitesSession.requestSelfOrganizationInfo()
                }
                val organizations = orgsResult.organizations
                currentOrganizations = organizations

                val warning = if (organizations.isEmpty()) "No organizations found" else null
                navState = SitesNavState.UserView(null, organizations, warning)

            } catch (e: CancellationException) {
                throw e
            } catch (e: SitesException) {
                Log.e(TAG, "Sites error: ${e.error}", e)
                navState = SitesNavState.Error("Error: ${e.error}")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading data", e)
                navState = SitesNavState.Error("Error: ${e.message}")
            }
        }
    }

    private suspend fun loadNearMeSites(lat: Double, lng: Double, radiusMeters: Double) {
        try {
            navState = SitesNavState.Loading
            val scope = assetScope

            val result = retryHelper.withRetry {
                sitesSession.requestSiteAssetsByLocation(
                    lat = lat,
                    lng = lng,
                    radiusMeters = radiusMeters,
                    assetType = AssetType.VPS_INFO,
                    scope = scope,
                )
            }

            // One entry per VPS asset with a non-blank payload.
            // Pass the VPS map asset ID; MeshDownloader resolves the mesh sibling.
            val sitesWithAssets = result.entries.flatMap { entry ->
                vpsAssets(entry.assets, scope).map { SiteWithVpsAsset(entry.site, it) }
            }

            val warning = if (sitesWithAssets.isEmpty())
                "No VPS sites found within ${radiusMeters.toInt()}m"
            else null
            navState = SitesNavState.NearMeResults(sitesWithAssets, warning)

        } catch (e: CancellationException) {
            throw e
        } catch (e: SitesException) {
            Log.e(TAG, "Sites error: ${e.error}", e)
            navState = SitesNavState.NearMeResults(emptyList(), "Error: ${e.error}")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading nearby sites", e)
            navState = SitesNavState.NearMeResults(emptyList(), "Error: ${e.message}")
        }
    }

    fun onOrganizationClick(org: OrganizationInfo, location: LatLng?) {
        filterText = ""
        currentLocation = location
        coroutineScope.launch {
            loadSitesForOrganization(org)
        }
    }

    fun onBackToUserView() {
        filterText = ""
        navState = SitesNavState.UserView(null, currentOrganizations)
    }

    fun onFilterChange(text: String) {
        filterText = text
    }

    private suspend fun loadSitesForOrganization(org: OrganizationInfo) {
        try {
            navState = SitesNavState.Loading

            val result = retryHelper.withRetry {
                sitesSession.requestSitesForOrganization(org.id)
            }
            val allSites = result.sites

            if (allSites.isEmpty()) {
                navState = SitesNavState.OrganizationView(org, emptyList(), "No sites found for ${org.name}")
                return
            }

            val scope = assetScope
            val sitesWithAssets = coroutineScope {
                allSites.map { site ->
                    async {
                        try {
                            // This endpoint returns every asset, production or not, so vpsAssets
                            // applies the scope.
                            val assetsResult = retryHelper.withRetry {
                                sitesSession.requestAssetsForSite(site.id)
                            }
                            // Pass the VPS map asset ID; MeshDownloader resolves the mesh sibling.
                            vpsAssets(assetsResult.assets, scope).map { SiteWithVpsAsset(site, it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load assets for site ${site.name}: ${e.message}")
                            emptyList()
                        }
                    }
                }.flatMap { it.await() }
            }

            val warning = if (sitesWithAssets.isEmpty())
                "No sites with a ${if (scope == AssetScope.PRODUCTION) "Production " else ""}VPS asset found for ${org.name}"
            else null
            navState = SitesNavState.OrganizationView(org, sitesWithAssets, warning)

        } catch (e: CancellationException) {
            throw e
        } catch (e: SitesException) {
            Log.e(TAG, "Sites error: ${e.error}", e)
            navState = SitesNavState.UserView(null, currentOrganizations, "Error loading sites: ${e.error}")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sites", e)
            navState = SitesNavState.UserView(null, currentOrganizations, "Error: ${e.message}")
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        coroutineScope.cancel()
        runCatching { sitesSession.close() }
    }
}

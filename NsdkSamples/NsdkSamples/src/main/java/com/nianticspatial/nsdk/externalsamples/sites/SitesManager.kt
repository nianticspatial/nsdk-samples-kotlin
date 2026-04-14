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
import com.nianticspatial.nsdk.sites.AssetType
import com.nianticspatial.nsdk.sites.OrganizationInfo
import com.nianticspatial.nsdk.sites.SitesException
import com.nianticspatial.nsdk.sites.SitesSession
import com.nianticspatial.nsdk.sites.UserInfo
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
    }

    private val sitesSession: SitesSession = nsdkSession.sites.acquire()
    private val retryHelper = AuthRetryHelper(nsdkSession)

    internal var navState by mutableStateOf<SitesNavState>(SitesNavState.ModeSelection)
        private set

    var filterText by mutableStateOf("")
        private set

    // Retained for back navigation and site sorting
    private var currentUser: UserInfo? = null
    private var currentOrganizations: List<OrganizationInfo> = emptyList()
    var currentLocation: LatLng? = null
        private set

    fun onStartFromOrgsFlow() {
        loadInitialData()
    }

    fun onStartNearMeFlow() {
        filterText = ""
        navState = SitesNavState.NearMeResults(emptyList())
    }

    fun onBackToModeSelection() {
        filterText = ""
        navState = SitesNavState.ModeSelection
    }

    fun onNearMeSearch(lat: Double, lng: Double, radiusMeters: Double, location: LatLng?) {
        filterText = ""
        currentLocation = location
        coroutineScope.launch {
            loadNearMeSites(lat, lng, radiusMeters)
        }
    }

    fun loadInitialData() {
        coroutineScope.launch {
            try {
                navState = SitesNavState.Loading

                val userResult = retryHelper.withRetry {
                    sitesSession.requestSelfUserInfo()
                }
                val user = userResult.user
                if (user == null) {
                    navState = SitesNavState.Error("Failed to retrieve user information")
                    return@launch
                }
                currentUser = user

                val orgsResult = retryHelper.withRetry {
                    sitesSession.requestOrganizationsForUser(user.id)
                }
                val organizations = orgsResult.organizations
                currentOrganizations = organizations

                val warning = if (organizations.isEmpty()) "No organizations found" else null
                navState = SitesNavState.UserView(user, organizations, warning)

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

            val result = retryHelper.withRetry {
                sitesSession.requestSiteAssetsByLocation(
                    lat = lat,
                    lng = lng,
                    radiusMeters = radiusMeters,
                    assetType = AssetType.VPS_INFO
                )
            }

            // Keep only entries that have a Production VPS asset with a non-blank payload
            val sitesWithAssets = result.entries.mapNotNull { entry ->
                val vpsAsset = entry.assets.firstOrNull { asset ->
                    val vps = asset.vpsData
                    vps != null && vps.anchorPayload.isNotBlank() &&
                        asset.deployment == AssetDeploymentType.PRODUCTION
                }
                if (vpsAsset != null) SiteWithVpsAsset(entry.site, vpsAsset) else null
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
        val user = currentUser ?: return
        navState = SitesNavState.UserView(user, currentOrganizations)
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

            val sitesWithAssets = coroutineScope {
                allSites.map { site ->
                    async {
                        try {
                            val assetsResult = retryHelper.withRetry {
                                sitesSession.requestAssetsForSite(site.id)
                            }
                            val vpsAsset = assetsResult.assets.firstOrNull { asset ->
                                val vps = asset.vpsData
                                vps != null &&
                                    vps.anchorPayload.isNotBlank() &&
                                    asset.deployment == AssetDeploymentType.PRODUCTION
                            }
                            if (vpsAsset != null) SiteWithVpsAsset(site, vpsAsset) else null
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load assets for site ${site.name}: ${e.message}")
                            null
                        }
                    }
                }.mapNotNull { it.await() }
            }

            val warning = if (sitesWithAssets.isEmpty())
                "No sites with Production VPS asset found for ${org.name}"
            else null
            navState = SitesNavState.OrganizationView(org, sitesWithAssets, warning)

        } catch (e: CancellationException) {
            throw e
        } catch (e: SitesException) {
            Log.e(TAG, "Sites error: ${e.error}", e)
            val user = currentUser
            navState = if (user != null)
                SitesNavState.UserView(user, currentOrganizations, "Error loading sites: ${e.error}")
            else
                SitesNavState.Error("Error loading sites: ${e.error}")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sites", e)
            val user = currentUser
            navState = if (user != null)
                SitesNavState.UserView(user, currentOrganizations, "Error: ${e.message}")
            else
                SitesNavState.Error("Error: ${e.message}")
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        coroutineScope.cancel()
        runCatching { sitesSession.close() }
    }
}

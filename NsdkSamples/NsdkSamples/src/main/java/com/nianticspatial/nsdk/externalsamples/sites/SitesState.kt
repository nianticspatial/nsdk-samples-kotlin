// Copyright 2026 Niantic.
package com.nianticspatial.nsdk.externalsamples.sites

import com.nianticspatial.nsdk.sites.AssetDeploymentType
import com.nianticspatial.nsdk.sites.AssetInfo
import com.nianticspatial.nsdk.sites.OrganizationInfo
import com.nianticspatial.nsdk.sites.SiteInfo
import com.nianticspatial.nsdk.sites.UserInfo

/** A site with one of its VPS assets, production or not (for localization). */
internal data class SiteWithVpsAsset(
    val site: SiteInfo,
    val asset: AssetInfo,
)

/** "Production" or "Not production": the portal has no other deployment state. */
internal fun deploymentLabel(deployment: AssetDeploymentType): String =
    if (deployment == AssetDeploymentType.PRODUCTION) "Production" else "Not production"

/**
 * Navigation state for the Sites demo.
 * Each state (except Loading and initial Error) supports showing a warning message
 * while still allowing navigation back.
 * OrganizationView and NearMeResults list the VPS assets of their sites in the chosen scope,
 * one entry per asset.
 */
internal sealed class SitesNavState {
    object ModeSelection : SitesNavState()
    object Loading : SitesNavState()
    data class UserView(
        val user: UserInfo?,
        val organizations: List<OrganizationInfo>,
        val warning: String? = null
    ) : SitesNavState()
    data class OrganizationView(
        val org: OrganizationInfo,
        val sitesWithAssets: List<SiteWithVpsAsset>,
        val warning: String? = null
    ) : SitesNavState()
    /** VPS assets with a valid anchor payload, of the sites near a location. */
    data class NearMeResults(
        val sitesWithAssets: List<SiteWithVpsAsset>,
        val warning: String? = null
    ) : SitesNavState()
    data class Error(val message: String) : SitesNavState()
}

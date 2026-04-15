// Copyright 2026 Niantic.
package com.nianticspatial.nsdk.externalsamples.sites

import com.nianticspatial.nsdk.sites.AssetInfo
import com.nianticspatial.nsdk.sites.OrganizationInfo
import com.nianticspatial.nsdk.sites.SiteInfo
import com.nianticspatial.nsdk.sites.UserInfo

/** A site with its chosen Production VPS asset (for localization). */
internal data class SiteWithVpsAsset(val site: SiteInfo, val asset: AssetInfo)

/**
 * Navigation state for the Sites demo.
 * Each state (except Loading and initial Error) supports showing a warning message
 * while still allowing navigation back.
 * Sites in OrganizationView are filtered to those with at least one Production VPS asset.
 */
internal sealed class SitesNavState {
    object ModeSelection : SitesNavState()
    object Loading : SitesNavState()
    data class UserView(
        val user: UserInfo,
        val organizations: List<OrganizationInfo>,
        val warning: String? = null
    ) : SitesNavState()
    data class OrganizationView(
        val org: OrganizationInfo,
        val sitesWithAssets: List<SiteWithVpsAsset>,
        val warning: String? = null
    ) : SitesNavState()
    /** Sites near a location that have a valid VPS anchor payload. */
    data class NearMeResults(
        val sitesWithAssets: List<SiteWithVpsAsset>,
        val warning: String? = null
    ) : SitesNavState()
    data class Error(val message: String) : SitesNavState()
}

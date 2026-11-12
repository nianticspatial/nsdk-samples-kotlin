// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.vps2

import kotlinx.serialization.Serializable

@Serializable
data class VPS2Route(
    val payload: String? = null,
    /**
     * With [useSiteLocalization], the VPS map asset of [siteId] to localize to, production or not.
     * Null localizes to the site's production assets.
     */
    val vpsMapAssetId: String? = null,
    /**
     * Whether Switch Asset offers the site's non-production VPS assets too, as the Sites browser's
     * scope toggle was set. False offers production assets only.
     */
    val allAssets: Boolean = false,
    val siteId: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /**
     * Localize with `Vps2Session.localize(siteId, assetId)` against a site ID instead of tracking an
     * anchor payload.
     */
    val useSiteLocalization: Boolean = false,
    /**
     * Localize with `Vps2Session.localize(latitude, longitude)` against every VPS site near [latitude] /
     * [longitude], with no site selection at all. Set by the "Localize to Nearby Site
     * Automatically" mode of the Sites Manager scene.
     */
    val useNearbySites: Boolean = false,
)

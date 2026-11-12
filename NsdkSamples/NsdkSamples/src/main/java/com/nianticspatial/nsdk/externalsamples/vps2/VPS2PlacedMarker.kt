// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.vps2

import androidx.compose.ui.graphics.Color

/**
 * A pose the user dropped by tapping the map. The [transform] is re-resolved from [latitude] /
 * [longitude] against the latest VPS2 localization every frame (via [VPS2Manager.placeMarker] /
 * updatePlacedMarkers), so a single instance drives both the map dot ([latitude], [longitude],
 * [color]) and the AR cube ([transform], [color]). [transform] is null while VPS2 has not resolved
 * a pose for the coordinate yet.
 */
class VPS2PlacedMarker(
    /** Stable id used to reuse/prune the map annotation and the AR node across frames. */
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    /** Shared by the map dot and the AR cube so the two never drift apart. */
    val color: Color,
    /** 16-float column-major AR pose, or null while unresolved. */
    val transform: FloatArray?,
)

/**
 * Colors that already carry a fixed meaning in this sample, on the map and on the AR cubes alike.
 * Kept together so the marker palette below can be checked against them at a glance.
 */
object VPS2ReservedColors {
    /** The device's own dot on the mini-map. */
    val UserDot = Color(0xFF1E88E5)

    /** An anchor that is fully tracked: green dot on the map, green cube in AR. */
    val AnchorTracked = Color(0xFF42FF44)

    /** An anchor tracked only coarsely: orange dot on the map, orange cube in AR. */
    val AnchorLimited = Color(0xFFFF8C00)
}

/**
 * Cycles marker colors so successive placements are visually distinct.
 *
 * Deliberately contains no blue, green or orange: those are taken by [VPS2ReservedColors], and a
 * placed marker sharing one would be indistinguishable from the user's own dot or from an anchor's
 * tracking state — on the map and on the AR cubes, which use the same colors.
 */
object VPS2MarkerPalette {
    val colors = listOf(
        Color(0xFF9C27B0), // purple
        Color(0xFF00BCD4), // cyan
        Color(0xFFE91E63), // pink
        Color(0xFFFFEB3B), // yellow
    )

    fun colorAt(index: Int): Color = colors[index % colors.size]
}

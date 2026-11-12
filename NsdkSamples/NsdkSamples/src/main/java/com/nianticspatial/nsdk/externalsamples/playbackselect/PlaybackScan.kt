// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.playbackselect

/**
 * A playable scan's identity and display fields. [durationSeconds] and [createdTimestamp] are null
 * for a scan known only by its ID.
 */
data class PlaybackScan(
    val id: String,
    val name: String,
    val siteName: String,
    val durationSeconds: Double?,
    val createdTimestamp: Long?,
) {
    companion object {
        /** A scan known only by its ID. */
        fun unresolved(id: String) = PlaybackScan(id, "", "", null, null)
    }
}

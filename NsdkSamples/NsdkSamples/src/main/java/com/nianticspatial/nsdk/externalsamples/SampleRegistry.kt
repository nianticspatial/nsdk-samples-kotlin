// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

/**
 * Registry of samples contributed at runtime by the active build flavor.
 *
 * The default build registers nothing, so [entries] is empty and only the built-in samples are
 * reachable. A flavor can contribute additional samples by calling [register] from its startup
 * initializer, so they appear without shared `main` code ever naming them.
 */
object SampleRegistry {
    private val mutableEntries = mutableListOf<SampleEntry>()

    /** Samples registered by the active flavor. Empty by default. */
    val entries: List<SampleEntry>
        @Synchronized get() = mutableEntries.toList()

    /** Called by a flavor's startup initializer to contribute its samples. */
    @Synchronized
    fun register(entries: List<SampleEntry>) {
        mutableEntries.addAll(entries)
    }
}

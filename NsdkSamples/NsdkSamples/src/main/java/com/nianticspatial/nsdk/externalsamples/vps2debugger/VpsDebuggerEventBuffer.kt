// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.vps2debugger

import android.util.Log
import com.google.protobuf.util.JsonFormat
import com.nianticspatial.nsdk.protobuf.VpsDebuggerDataEvent

/**
 * Session-scoped buffer of VPS debugger events for the hosted WebView overlay.
 * Filters noisy event types (matching Argus [VpsDebuggerEventBuffer]).
 */
class VpsDebuggerEventBuffer(
    private val maxEvents: Int = 50_000,
) {
    private val events = ArrayList<String>()
    private var maxEventsWarningLogged = false

    val count: Int
        get() = events.size

    fun clear() {
        events.clear()
        maxEventsWarningLogged = false
    }

    fun eventJsonAt(index: Int): String = events[index]

    fun add(event: VpsDebuggerDataEvent) {
        if (!shouldInclude(event)) {
            return
        }
        if (events.size >= maxEvents) {
            if (!maxEventsWarningLogged) {
                maxEventsWarningLogged = true
                Log.w(TAG, "Max event cap ($maxEvents) reached; dropping further events.")
            }
            return
        }
        try {
            events.add(JsonFormat.printer().print(event))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serialize debugger event: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VpsDebuggerEventBuffer"

        fun shouldInclude(event: VpsDebuggerDataEvent): Boolean {
            if (event.hasMapPoint2D()) return false
            if (event.hasAssetReceived()) return false
            if (event.hasAnchor()) return false
            return true
        }
    }
}

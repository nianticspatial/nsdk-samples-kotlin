// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.occlusion

import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.depth.DepthManager

/**
 * Manages per-frame global depth occlusion using NSDK depth data.
 *
 * On construction, creates an owned [DepthManager]. Call [start] to open the NSDK depth data
 * pipeline — the active camera stream detects data flowing and activates depth writing
 * automatically via its own frame listener. Call [onDestroy] to stop depth polling and release
 * resources.
 *
 * Works identically in live and playback mode. [LiveNsdkCameraStream] and
 * [PlaybackNsdkCameraStream] each manage depth rendering internally.
 *
 * @param nsdkSessionManager Created in NSDKDemoView, passed to OcclusionView as a Compose
 *                           function parameter, and forwarded here as a constructor argument.
 *                           Neither this class nor the view owns it.
 */
class OcclusionManager(
    private val nsdkSessionManager: NSDKSessionManager,
): FeatureManager() {

    private val _depthManager = DepthManager(nsdkSessionManager.session.depthSession.acquire())

    /**
     * Starts the NSDK depth session via [DepthManager].
     *
     * Call this from a post-composition side effect (e.g. [DisposableEffect]), since it starts
     * native work and writes Compose state.
     */
    fun start() {
        _depthManager.startDepth()
    }

    /** Releases resources. */
    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        _depthManager.onDestroy(owner)
    }
}

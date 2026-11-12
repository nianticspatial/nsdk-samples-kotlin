// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import androidx.compose.runtime.Composable

/**
 * A sample contributed at runtime by the active build flavor (see [SampleRegistry]).
 *
 * This type lives in `main` so the shared menu/nav can consume it. The default build registers none
 * of these; a flavor self-registers its samples at startup via [SampleRegistry.register].
 */
data class SampleEntry(
    /** Unique navigation route id for this sample. */
    val routeId: String,
    /** Label shown on the selector button. */
    val label: String,
    /**
     * The screen to render when the route is navigated to. Receives the shared
     * [NSDKSessionManager] so flavor samples can acquire NSDK feature sessions
     * (mirrors how the built-in sample views are passed it).
     */
    val content: @Composable (nsdkManager: NSDKSessionManager) -> Unit,
)

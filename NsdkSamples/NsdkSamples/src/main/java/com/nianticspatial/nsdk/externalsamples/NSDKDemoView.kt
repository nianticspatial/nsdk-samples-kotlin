// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.externalsamples.auth.AuthConstants
import com.nianticspatial.nsdk.externalsamples.auth.AuthManager
import com.nianticspatial.nsdk.externalsamples.auth.AuthView
import com.nianticspatial.nsdk.externalsamples.meshing.MeshingRoute
import com.nianticspatial.nsdk.externalsamples.meshing.MeshingView
import com.nianticspatial.nsdk.externalsamples.capture.CaptureRoute
import com.nianticspatial.nsdk.externalsamples.capture.CaptureView
import com.nianticspatial.nsdk.externalsamples.common.OverlayContent
import com.nianticspatial.nsdk.externalsamples.depth.DepthRoute
import com.nianticspatial.nsdk.externalsamples.depth.DepthView
import com.nianticspatial.nsdk.externalsamples.mapping.DeviceMappingRoute
import com.nianticspatial.nsdk.externalsamples.mapping.DeviceMappingView
import com.nianticspatial.nsdk.externalsamples.occlusion.OcclusionRoute
import com.nianticspatial.nsdk.externalsamples.occlusion.OcclusionView
import com.nianticspatial.nsdk.externalsamples.playbackselect.DownloadedPlaybacksRoute
import com.nianticspatial.nsdk.externalsamples.playbackselect.DownloadedPlaybacksView
import com.nianticspatial.nsdk.externalsamples.playbackselect.PlaybackBanner
import com.nianticspatial.nsdk.externalsamples.playbackselect.PlaybackLibrary
import com.nianticspatial.nsdk.externalsamples.playbackselect.PlaybackSelectRoute
import com.nianticspatial.nsdk.externalsamples.playbackselect.PlaybackSelectView
import com.nianticspatial.nsdk.externalsamples.scenesegmentation.SceneSegmentationRoute
import com.nianticspatial.nsdk.externalsamples.scenesegmentation.SceneSegmentationView
import com.nianticspatial.nsdk.externalsamples.sites.SitesRoute
import com.nianticspatial.nsdk.externalsamples.sites.SitesView
import com.nianticspatial.nsdk.externalsamples.vps2.VPS2Route
import com.nianticspatial.nsdk.externalsamples.vps2.VPS2View

private const val TAG = "NSDKDemoView"

// When true and the device supports the ARCore Depth API, NSDK uses platform depth instead of
// running the neural depth model on-device. The session fixes this at creation from the live probe;
// a scan opened later does not change it. Set to false to always use neural (Lightship) depth.
//
// Setting this to true may add a noticeable latency (~100 ms) to the AR camera view due to
// the overhead of ARCore's depth estimation.
private const val USE_PLATFORM_DEPTH = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NSDKDemoView(modifier: Modifier = Modifier, activity: Activity) {
    val navController = rememberNavController()
    val sessionManager = remember {
        ARSessionManager(
            activity = activity,
            usePlatformDepth = USE_PLATFORM_DEPTH
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    // sessionManager always needs lifecycle events regardless of session state
    DisposableEffect(lifecycleOwner, sessionManager) {
        lifecycleOwner.lifecycle.addObserver(sessionManager)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(sessionManager)
        }
    }

    // Wait for the ARCore depth support probe so depthAvailability is final when the NSDK session
    // is created below.
    val isLoading = sessionManager.isLoading
    if (isLoading) return

    // Fixed at session creation; a later playback switch does not recreate the session.
    val depthAvailability = remember { sessionManager.depthAvailability }
    val nsdkSession = remember(depthAvailability) {
        NSDKSession(
            accessToken = AuthConstants.accessToken,
            useLidar = depthAvailability
        )
    }

    val nsdkSessionManager = remember(nsdkSession, sessionManager) {
        NSDKSessionManager(activity, nsdkSession, sessionManager, captureDepth = depthAvailability)
    }

    DisposableEffect(lifecycleOwner, nsdkSessionManager) {
        lifecycleOwner.lifecycle.addObserver(nsdkSessionManager)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(nsdkSessionManager)
        }
    }

    val playbackLibrary = remember(nsdkSession) {
        PlaybackLibrary(nsdkSession.playbackCache, sessionManager)
    }
    DisposableEffect(playbackLibrary) {
        onDispose { playbackLibrary.close() }
    }

    val authManager = remember(nsdkSession) { AuthManager(nsdkSession) }
    val isLoggedIn = authManager.isLoggedIn
    var showAuthSheet by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, authManager) {
        lifecycleOwner.lifecycle.addObserver(authManager)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(authManager)
        }
    }

    // Auto-prompt login if no session is active on first composition
    LaunchedEffect(Unit) {
        if (!isLoggedIn) {
            showAuthSheet = true
        }
    }

    if (showAuthSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAuthSheet = false },
        ) {
            AuthView(
                manager = authManager,
                modifier = Modifier.padding(bottom = 32.dp),
            )
        }
    }

    val authIconOverlay: @Composable () -> Unit = {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopEnd
        ) {
            IconButton(
                onClick = { showAuthSheet = true },
                modifier = Modifier
                    .padding(top = 8.dp, end = 16.dp, bottom = 8.dp, start = 8.dp)
                    .size(40.dp)
                    .background(
                        color = if (isLoggedIn) Color(0xFF4CAF50) else Color.Transparent,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = if (isLoggedIn) "Account (logged in)" else "Account (logged out)",
                    tint = if (isLoggedIn) Color.White else Color.Gray
                )
            }
        }
    }

    val navContent: @Composable BoxScope.(overlayContentState: MutableState<OverlayContent?>) -> Unit = { overlayContentState ->
        NavHost(navController = navController, startDestination = SelectorRoute, modifier) {
            composable<SelectorRoute> { backStackEntry ->
                SelectorView(navController, nsdkSession, topBarTrailingContent = authIconOverlay)
            }

            composable<VPS2Route> { backStackEntry ->
                val args = backStackEntry.toRoute<VPS2Route>()
                BackHelpScaffold(navController) { helpContentState ->
                    VPS2View(
                        activity,
                        nsdkSessionManager,
                        helpContentState,
                        args.payload,
                        vpsMapAssetId = args.vpsMapAssetId,
                        allAssets = args.allAssets,
                        siteId = args.siteId,
                        useSiteLocalization = args.useSiteLocalization,
                        useNearbySites = args.useNearbySites,
                        // The site's coordinate serves both the nearby-sites query and, as a
                        // fallback when there's no GPS fix, the mesh download's chunk ordering.
                        siteLatitude = args.latitude,
                        siteLongitude = args.longitude,
                        nearbyLatitude = args.latitude,
                        nearbyLongitude = args.longitude,
                    )
                }
            }

            composable<MeshingRoute> { backStackEntry ->
                BackHelpScaffold(navController) { helpContentState ->
                    MeshingView(activity, nsdkSessionManager, helpContentState)
                }
            }

            composable<CaptureRoute> { backStackEntry ->
                BackHelpScaffold(navController) { helpContentState ->
                    CaptureView(activity, nsdkSessionManager, helpContentState, overlayContentState)
                }
            }

            composable<DepthRoute> { backStackEntry ->
                BackHelpScaffold(navController) { helpContentState ->
                    DepthView(nsdkSessionManager, helpContentState, overlayContentState)
                }
            }

            composable<OcclusionRoute> { _ ->
                BackHelpScaffold(navController) { helpContentState ->
                    OcclusionView(nsdkSessionManager, helpContentState)
                }
            }

            composable<SceneSegmentationRoute> { backStackEntry ->
                BackHelpScaffold(navController) { helpContentState ->
                    SceneSegmentationView(nsdkSessionManager, helpContentState, overlayContentState)
                }
            }

            composable<DeviceMappingRoute> { backStackEntry ->
                BackHelpScaffold(navController) { helpContentState ->
                    DeviceMappingView(activity, nsdkSessionManager, helpContentState)
                }
            }

            composable<PlaybackSelectRoute> { _ ->
                BackHelpScaffold(navController) { helpContentState ->
                    PlaybackSelectView(nsdkSession, playbackLibrary, helpContentState) {
                        navController.navigate(DownloadedPlaybacksRoute)
                    }
                }
            }

            composable<DownloadedPlaybacksRoute> { _ ->
                BackHelpScaffold(navController) { helpContentState ->
                    DownloadedPlaybacksView(nsdkSession, playbackLibrary, helpContentState)
                }
            }

            composable<SitesRoute> { backStackEntry ->
                BackHelpScaffold(navController) { helpContentState ->
                    SitesView(nsdkSessionManager, navHostController = navController, helpContentState)
                }
            }

            // Samples contributed at runtime via SampleRegistry (empty by default).
            SampleRegistry.entries.forEach { entry ->
                composable(entry.routeId) {
                    BackHelpScaffold(navController) { _ ->
                        entry.content(nsdkSessionManager)
                    }
                }
            }
        }
    }
    // The playback banner takes the status bar inset from the screens below it while shown.
    val bannerInsets = if (playbackLibrary.activeScan != null) WindowInsets.statusBars else WindowInsets(0)
    // Only menu screens switch the playback source; feature views run on the current one.
    val destination = navController.currentBackStackEntryAsState().value?.destination
    val showPlaybackStop = destination == null ||
        destination.hasRoute(SelectorRoute::class) ||
        destination.hasRoute(PlaybackSelectRoute::class) ||
        destination.hasRoute(DownloadedPlaybacksRoute::class)
    ARSceneView(modifier = Modifier.fillMaxSize(), sessionManager = sessionManager, nsdkSessionManager = nsdkSessionManager) { overlayContentState ->
        Column(modifier = Modifier.fillMaxSize()) {
            PlaybackBanner(playbackLibrary, showPlaybackStop)
            Box(modifier = Modifier.weight(1f).consumeWindowInsets(bannerInsets)) {
                navContent(overlayContentState)
            }
        }
    }
}

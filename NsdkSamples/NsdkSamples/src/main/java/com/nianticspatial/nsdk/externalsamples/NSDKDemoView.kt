// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import com.nianticspatial.nsdk.externalsamples.scenesegmentation.SceneSegmentationRoute
import com.nianticspatial.nsdk.externalsamples.scenesegmentation.SceneSegmentationView
import com.nianticspatial.nsdk.externalsamples.sites.SitesRoute
import com.nianticspatial.nsdk.externalsamples.sites.SitesView
import com.nianticspatial.nsdk.externalsamples.vps2.VPS2Route
import com.nianticspatial.nsdk.externalsamples.vps2.VPS2View

private const val TAG = "NSDKDemoView"

// Paste the name of your playback dataset here to enable playback mode (e.g. "playback/MyDataset").
// Leave empty to use live AR mode instead.
private const val PLAYBACK_DATASET_PATH = "playback/PUT_DATASET_NAME_HERE"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NSDKDemoView(modifier: Modifier = Modifier, activity: Activity) {
    val navController = rememberNavController()
    val sessionManager = remember {
        ARSessionManager(
            activity = activity,
            playbackDatasetPath = PLAYBACK_DATASET_PATH
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

    // Wait for the playback dataset to finish loading before creating the session.
    // In live mode, isPlaybackLoading is always false so this is a no-op.
    val isPlaybackLoading = sessionManager.isPlaybackLoading
    if (isPlaybackLoading) return

    val depthAvailability = sessionManager.depthAvailability
    val nsdkSession = remember(depthAvailability) {
        NSDKSession(
            accessToken = AuthConstants.accessToken,
            useLidar = depthAvailability
        )
    }

    val nsdkSessionManager = remember(nsdkSession, sessionManager) {
        NSDKSessionManager(activity, nsdkSession, sessionManager)
    }

    DisposableEffect(lifecycleOwner, nsdkSessionManager) {
        lifecycleOwner.lifecycle.addObserver(nsdkSessionManager)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(nsdkSessionManager)
        }
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
                LaunchedEffect(Unit) { sessionManager.setEnabled(false) }
                SelectorView(navController, nsdkSession, topBarTrailingContent = authIconOverlay)
            }

            composable<VPS2Route> { backStackEntry ->
                LaunchedEffect(Unit) { sessionManager.setEnabled(true) }
                val args = backStackEntry.toRoute<VPS2Route>()
                BackHelpScaffold(navController) { helpContentState ->
                    VPS2View(activity, nsdkSessionManager, helpContentState, args.payload)
                }
            }

            composable<MeshingRoute> { backStackEntry ->
                LaunchedEffect(Unit) { sessionManager.setEnabled(true) }
                BackHelpScaffold(navController) { helpContentState ->
                    MeshingView(activity, nsdkSession, helpContentState)
                }
            }

            composable<CaptureRoute> { backStackEntry ->
                LaunchedEffect(Unit) { sessionManager.setEnabled(true) }
                BackHelpScaffold(navController) { helpContentState ->
                    CaptureView(activity, nsdkSessionManager, helpContentState, overlayContentState)
                }
            }

            composable<DepthRoute> { backStackEntry ->
                LaunchedEffect(Unit) { sessionManager.setEnabled(true) }
                BackHelpScaffold(navController) { helpContentState ->
                    DepthView(nsdkSessionManager, helpContentState, overlayContentState)
                }
            }

            composable<OcclusionRoute> { _ ->
                LaunchedEffect(Unit) { sessionManager.setEnabled(true) }
                BackHelpScaffold(navController) { helpContentState ->
                    OcclusionView(nsdkSessionManager, helpContentState)
                }
            }

            composable<SceneSegmentationRoute> { backStackEntry ->
                LaunchedEffect(Unit) { sessionManager.setEnabled(true) }
                BackHelpScaffold(navController) { helpContentState ->
                    SceneSegmentationView(nsdkSessionManager, helpContentState, overlayContentState)
                }
            }

            composable<DeviceMappingRoute> { backStackEntry ->
                LaunchedEffect(Unit) { sessionManager.setEnabled(true) }
                BackHelpScaffold(navController) { helpContentState ->
                    DeviceMappingView(activity, nsdkSessionManager, helpContentState)
                }
            }

            composable<SitesRoute> { backStackEntry ->
                LaunchedEffect(Unit) { sessionManager.setEnabled(false) }
                BackHelpScaffold(navController) { helpContentState ->
                    SitesView(nsdkSessionManager, navHostController = navController, helpContentState)
                }
            }

        }
    }
    ARSceneView(modifier = Modifier.fillMaxSize(), sessionManager = sessionManager, content = navContent)
}

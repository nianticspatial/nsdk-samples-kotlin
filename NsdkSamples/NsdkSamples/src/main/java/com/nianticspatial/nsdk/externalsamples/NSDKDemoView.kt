// Copyright 2025 Niantic.
package com.nianticspatial.nsdk.externalsamples

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.externalsamples.auth.AuthConstants
import com.nianticspatial.nsdk.externalsamples.auth.AuthUtils
import com.nianticspatial.nsdk.externalsamples.objectdetection.ObjectDetectionRoute
import com.nianticspatial.nsdk.externalsamples.objectdetection.ObjectDetectionView
import com.nianticspatial.nsdk.externalsamples.meshing.MeshingRoute
import com.nianticspatial.nsdk.externalsamples.meshing.MeshingView
import com.nianticspatial.nsdk.externalsamples.capture.CaptureRoute
import com.nianticspatial.nsdk.externalsamples.capture.CaptureView
import com.nianticspatial.nsdk.externalsamples.depth.DepthRoute
import com.nianticspatial.nsdk.externalsamples.depth.DepthView
import com.nianticspatial.nsdk.externalsamples.scenesegmentation.SceneSegmentationRoute
import com.nianticspatial.nsdk.externalsamples.scenesegmentation.SceneSegmentationView
import com.nianticspatial.nsdk.externalsamples.vps2.VPS2Route
import com.nianticspatial.nsdk.externalsamples.vps2.VPS2View
import com.nianticspatial.nsdk.externalsamples.wps.WpsRoute
import com.nianticspatial.nsdk.externalsamples.wps.WpsView
import com.nianticspatial.nsdk.externalsamples.mapping.DeviceMappingRoute
import com.nianticspatial.nsdk.externalsamples.mapping.DeviceMappingView
import com.nianticspatial.nsdk.externalsamples.sites.SitesRoute
import com.nianticspatial.nsdk.externalsamples.sites.SitesView
import com.nianticspatial.nsdk.externalsamples.auth.AuthRoute
import com.nianticspatial.nsdk.externalsamples.auth.AuthView
import com.nianticspatial.nsdk.externalsamples.auth.UserSessionManager

private const val TAG = "NSDKDemoView"

@Composable
fun NSDKDemoView(modifier: Modifier = Modifier, activity: Activity) {
    val navController = rememberNavController()
    val sessionManager = remember { ARSessionManager(activity) }

    // Build NSDKSession with either API key (if present and not default) or access/refresh tokens
    val nsdkSession = remember {
        if (BuildConfig.API_KEY.isNotEmpty() && BuildConfig.API_KEY != "YOUR_API_KEY") {
            NSDKSession(apiKey = BuildConfig.API_KEY, useLidar = false)
        } else {
            NSDKSession(
                accessToken = AuthConstants.accessToken,
                refreshToken = AuthConstants.refreshToken,
                useLidar = false
            )
        }
    }
    val nsdkSessionManager = remember(nsdkSession, sessionManager) {
        NSDKSessionManager(activity, nsdkSession, sessionManager)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, sessionManager, nsdkSessionManager) {
        lifecycleOwner.lifecycle.addObserver(sessionManager)
        lifecycleOwner.lifecycle.addObserver(nsdkSessionManager)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(nsdkSessionManager)
            lifecycleOwner.lifecycle.removeObserver(sessionManager)
        }
    }

    // Start UserSessionManager and check if we need to navigate to login
    LaunchedEffect(Unit) {
        UserSessionManager.start(activity.applicationContext)

        // Check if login is needed
        val hasValidApiKey = BuildConfig.API_KEY.isNotEmpty() && BuildConfig.API_KEY != "YOUR_API_KEY"
        val hasValidAccessToken = !AuthUtils.isTokenEmptyOrExpiring(AuthConstants.accessToken, 0)
        val hasValidRefreshToken = !AuthUtils.isTokenEmptyOrExpiring(AuthConstants.refreshToken, 0)
        val userSessionInProgress = UserSessionManager.isSessionInProgress

        if (!hasValidApiKey && !hasValidAccessToken && !hasValidRefreshToken
            && !userSessionInProgress) {
            // Navigate to the AuthView to handle login
            // AuthView will handle both developer and enterprise flows and set appropriate tokens on nsdkSession
            navController.navigate(AuthRoute)
        }
    }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        sessionManager = sessionManager
    ) { overlayContentState ->
        NavHost(navController = navController, startDestination = SelectorRoute, modifier) {
            composable<SelectorRoute> { backStackEntry ->
                sessionManager.setEnabled(false)
                SelectorView(navController, nsdkSession)
            }

            composable<WpsRoute> { backStackEntry ->
                sessionManager.setEnabled(true)
                BackHelpScaffold(navController) { helpContentState ->
                    WpsView(activity, nsdkSessionManager, helpContentState, modifier)
                }
            }

            composable<VPS2Route> { backStackEntry ->
                sessionManager.setEnabled(true)
                val args = backStackEntry.toRoute<VPS2Route>()
                BackHelpScaffold(navController) { helpContentState ->
                    VPS2View(activity, nsdkSessionManager, helpContentState, args.payload)
                }
            }

            composable<ObjectDetectionRoute> { backStackEntry ->
                sessionManager.setEnabled(true)
                BackHelpScaffold(navController) { helpContentState ->
                    ObjectDetectionView(nsdkSessionManager, helpContentState)
                }
            }

            composable<MeshingRoute> { backStackEntry ->
                sessionManager.setEnabled(true)
                BackHelpScaffold(navController) { helpContentState ->
                    MeshingView(activity, nsdkSession, helpContentState)
                }
            }

            composable<CaptureRoute> { backStackEntry ->
                sessionManager.setEnabled(true)
                BackHelpScaffold(navController) { helpContentState ->
                    CaptureView(activity, nsdkSessionManager, helpContentState, overlayContentState)
                }
            }

            composable<DepthRoute> { backStackEntry ->
                sessionManager.setEnabled(true)
                BackHelpScaffold(navController) { helpContentState ->
                    DepthView(nsdkSessionManager, helpContentState, overlayContentState)
                }
            }

            composable<SceneSegmentationRoute> { backStackEntry ->
                sessionManager.setEnabled(true)
                BackHelpScaffold(navController) { helpContentState ->
                    SceneSegmentationView(nsdkSessionManager, helpContentState, overlayContentState)
                }
            }

            composable<DeviceMappingRoute> { backStackEntry ->
                sessionManager.setEnabled(true)
                BackHelpScaffold(navController) { helpContentState ->
                    DeviceMappingView(activity, nsdkSessionManager, helpContentState)
                }
            }

            composable<SitesRoute> { backStackEntry ->
                sessionManager.setEnabled(false)
                BackHelpScaffold(navController) { helpContentState ->
                    SitesView(nsdkSessionManager, navHostController = navController, helpContentState)
                }
            }

            composable<AuthRoute> { backStackEntry ->
                sessionManager.setEnabled(false)
                BackHelpScaffold(navController) { helpContentState ->
                    AuthView(activity, nsdkSession, helpContentState)
                }
            }
        }
    }
}

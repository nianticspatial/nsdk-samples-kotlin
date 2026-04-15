// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.depth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.common.OverlayContent
import com.nianticspatial.nsdk.externalsamples.HelpContent
import kotlinx.serialization.Serializable

@Serializable
object DepthRoute

@Composable
fun DepthView(
    nsdkSessionManager: NSDKSessionManager,
    helpContentState: MutableState<HelpContent?>,
    overlayContentState: MutableState<OverlayContent?>
) {
    val depthManager = remember {
        DepthManager(nsdkSessionManager.session.depthSession.acquire())
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    val depthOverlayContent = remember {
        DepthOverlayContent(depthManager, nsdkSessionManager)
    }

    // Register overlay content with ARSceneView
    DisposableEffect(depthOverlayContent) {
        overlayContentState.value = depthOverlayContent
        onDispose {
            depthOverlayContent.hide()
            overlayContentState.value = null
        }
    }

    // Set Help Contents
    DisposableEffect(Unit) {
      helpContentState.value = {
        Text(
          text = "Depth Visualization Overlay Help\n\nThis sample visualizes depth data as a color-coded overlay. Green indicates close objects, red indicates far objects.",
          color = Color.White
        )
      }
      onDispose { helpContentState.value = null }
    }

    // Lifecycle management
    DisposableEffect(depthManager) {
        lifecycleOwner.lifecycle.addObserver(depthManager)
        onDispose {
            depthManager.onDestroy(lifecycleOwner)
            lifecycleOwner.lifecycle.removeObserver(depthManager)
        }
    }

    // Depth visualization parameters
    var alphaValue by remember { mutableStateOf(0.7f) }

    // Update parameters when slider changes
    LaunchedEffect(alphaValue) {
        depthOverlayContent.setAlpha(alphaValue)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(16.dp))

            // Depth visualization controls
            Text(text = "Alpha: ${String.format("%.2f", alphaValue)}")
            Slider(
                value = alphaValue,
                onValueChange = { alphaValue = it },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Start/Stop button
            var isRendering by remember { mutableStateOf(false) }
            Button(
                onClick = {
                    if (isRendering) {
                        depthOverlayContent.hide()
                        depthManager.stop()
                        isRendering = false
                    } else {
                        depthManager.startDepth()
                        depthOverlayContent.show()
                        isRendering = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (isRendering) "Stop Overlay" else "Start Overlay")
            }
        }
    }
}

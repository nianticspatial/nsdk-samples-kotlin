// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.externalsamples.capture.CaptureRoute
import com.nianticspatial.nsdk.externalsamples.depth.DepthRoute
import com.nianticspatial.nsdk.externalsamples.vps2.VPS2Route
import com.nianticspatial.nsdk.externalsamples.meshing.MeshingRoute
import com.nianticspatial.nsdk.externalsamples.scenesegmentation.SceneSegmentationRoute
import com.nianticspatial.nsdk.externalsamples.mapping.DeviceMappingRoute
import com.nianticspatial.nsdk.externalsamples.sites.SitesRoute
import com.nianticspatial.nsdk.externalsamples.occlusion.OcclusionRoute
import kotlinx.serialization.Serializable

@Serializable
object SelectorRoute

@Composable
fun SelectorView(
    navHostController: NavHostController,
    nsdkSession: NSDKSession,
    topBarTrailingContent: (@Composable () -> Unit)? = null
) {
    val buttonModifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 16.dp)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.icon),
                contentDescription = "App Logo",
            )
            Button(
                onClick = { navHostController.navigate(SitesRoute) },
                modifier = buttonModifier
            ) { Text(text = "VPS2 (With sites)") }
            Button(
                onClick = { navHostController.navigate(DepthRoute) },
                modifier = buttonModifier
            ) { Text(text = "Depth") }
            Button(onClick = {navHostController.navigate(OcclusionRoute)},
                modifier = buttonModifier
            ) {Text(text = "Occlusion")}
            Button(
                onClick = { navHostController.navigate(MeshingRoute) },
                modifier = buttonModifier
            ) { Text(text = "Live Meshing") }
            Button(
                onClick = { navHostController.navigate(CaptureRoute()) },
                modifier = buttonModifier
            ) { Text(text = "Capture") }
            Button(
                onClick = { navHostController.navigate(SceneSegmentationRoute) },
                modifier = buttonModifier
            ) { Text(text = "Scene Segmentation") }
            Button(
                onClick = { navHostController.navigate(DeviceMappingRoute) },
                modifier = buttonModifier
            ) { Text(text = "Device Mapping") }
            Button(
                onClick = { navHostController.navigate(VPS2Route()) },
                modifier = buttonModifier
            ) { Text(text = "VPS2 (with anchor payload)") }
            Text(text = "NSDK v" + nsdkSession.getVersion())
        }
        topBarTrailingContent?.invoke()
    }
}

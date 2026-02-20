// Copyright 2026 Niantic.
package com.nianticspatial.nsdk.externalsamples.vps2

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nianticspatial.nsdk.AnchorTrackingState
import com.nianticspatial.nsdk.UUIDKey
import com.nianticspatial.nsdk.externalsamples.BuildConfig
import com.nianticspatial.nsdk.externalsamples.HelpContent
import com.nianticspatial.nsdk.externalsamples.MeshRenderer
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.LocalSceneEngine
import com.nianticspatial.nsdk.externalsamples.LocalSceneMaterialLoader
import com.nianticspatial.nsdk.externalsamples.arChildNodes
import com.nianticspatial.nsdk.externalsamples.createUnlitColorMaterial
import com.nianticspatial.nsdk.externalsamples.destroyRecursively
import com.nianticspatial.nsdk.vps2.VPS2TrackingState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.node.CubeNode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VPS2View(
    context: Context,
    nsdkManager: NSDKSessionManager,
    helpContentState: MutableState<HelpContent?>,
    initialPayload: String? = null
) {
    val engine = LocalSceneEngine.current
    val materialLoader = LocalSceneMaterialLoader.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val meshRenderer = remember { MeshRenderer(engine, materialLoader) }
    val vps2Manager = remember {
        VPS2Manager(nsdkManager, nsdkManager.session.vps2.acquire()).apply {
            // Set callback: manager decides what to render, view just calls the renderer
            onMeshDataReady = { chunks, meshIds ->
                meshRenderer.updateMeshChunks(chunks, meshIds)
            }
        }
    }

    // Set Help contents
    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "VPS2 Sample Help\n\nThis sample shows the VPS2 flow.\nTO USE:\n Begin by pressing the" +
                    " \"Start Tracking\" button to start tracking VPS2 anchors.\n Once anchors are tracked, " +
                    "cubes will appear at their locations. Orange cubes indicate coarse localization, " +
                    "green cubes indicate refined localization.\n Meshes will be automatically downloaded " +
                    "and displayed when anchors are successfully tracked in refined mode.",
                color = Color.White
            )
        }
        onDispose { helpContentState.value = null }
    }

    // Stop tracking when the view exits the composition
    DisposableEffect(lifecycleOwner, vps2Manager) {
        lifecycleOwner.lifecycle.addObserver(vps2Manager)
        onDispose {
            vps2Manager.stopTracking()
            lifecycleOwner.lifecycle.removeObserver(vps2Manager)
        }
    }

    // Map of location name to VPS2 anchor payload.
    // If you have set DEFALT VPS PAYLOAD in BuildConfig, it will be one of the localization targets.
    val payloadOptions =
        if (BuildConfig.DEFAULT_VPS_PAYLOAD == "YOUR_PAYLOAD")
            mapOf()
        else
            mapOf(
                "Default Location" to BuildConfig.DEFAULT_VPS_PAYLOAD
            )
    val payloadToName = remember(payloadOptions) { payloadOptions.entries.associate { it.value to it.key } }

    // Load compass images
    val redCompass = remember {
        try {
            context.assets.open("wps/compass-red.png").use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }
    val blueCompass = remember {
        try {
            context.assets.open("wps/compass-blue.png").use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }

    // Track which anchor IDs currently have nodes in the scene
    val localAnchorNodeMap = remember { mutableMapOf<UUIDKey, PoseNode>() }

    // Track previous updateType for each anchor to detect changes
    val previousUpdateStates = remember { mutableMapOf<UUIDKey, AnchorTrackingState>() }

    // Create root mesh node early so it's available when meshes are downloaded
    val rootMeshNode = remember {
        PoseNode(engine = engine).apply {
            arChildNodes.add(this)
        }
    }

    // Create materials for anchor cubes
    val refinedGreenMaterial = remember {
        createUnlitColorMaterial(context, materialLoader, Color(0xFF42FF44))
    }
    val coarseOrangeMaterial = remember {
        createUnlitColorMaterial(context, materialLoader, Color(0xFFFF8C00))
    }

    // Release the Filament material instances before the engine is torn down
    DisposableEffect(engine) {
        onDispose {
            localAnchorNodeMap.values.forEach { node ->
                node.destroyRecursively(arChildNodes)
            }
            localAnchorNodeMap.clear()
            rootMeshNode.destroyRecursively(arChildNodes)
            engine.destroyMaterialInstance(refinedGreenMaterial)
            engine.destroyMaterialInstance(coarseOrangeMaterial)
        }
    }

    LaunchedEffect(vps2Manager) {
        vps2Manager.toasts.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Handle cube creation/removal and material/size updates based on state and updateType
    LaunchedEffect(vps2Manager, engine, materialLoader) {
        androidx.compose.runtime.snapshotFlow {
            vps2Manager.anchorStates.toMap()
        }.collectLatest { states ->
            // Determine which anchors should be visible
            val visibleAnchors = states.keys.filter { anchorKey ->
                val state = states[anchorKey] ?: AnchorTrackingState.NOT_TRACKED
                state != AnchorTrackingState.NOT_TRACKED
            }.toSet()

            // Remove nodes for anchors that should no longer be visible
            val anchorsToRemove = localAnchorNodeMap.keys.filter { anchorKey ->
                anchorKey !in visibleAnchors
            }
            anchorsToRemove.forEach { anchorKey ->
                localAnchorNodeMap[anchorKey]?.destroyRecursively(arChildNodes)
                localAnchorNodeMap.remove(anchorKey)
                previousUpdateStates.remove(anchorKey)
            }

            // Create or update cube nodes for visible anchors
            visibleAnchors.forEach { anchorKey ->
                val state = states[anchorKey] ?: AnchorTrackingState.NOT_TRACKED
                val existingNode = localAnchorNodeMap[anchorKey]
                val previousUpdateState = previousUpdateStates[anchorKey]

                // Determine material and size based on updateType
                val material = if (state == AnchorTrackingState.LIMITED) {
                    coarseOrangeMaterial
                } else {
                    refinedGreenMaterial
                }
                val cubeSize = if (state == AnchorTrackingState.LIMITED) {
                    Float3(1.0f, 1.0f, 1.0f) // Large cube for limited
                } else {
                    Float3(0.2f, 0.2f, 0.2f) // Small cube for tracked
                }

                if (existingNode == null) {
                    // Create new PoseNode with CubeNode child
                    val poseNode = PoseNode(engine = engine).apply {
                        val cubeNode = CubeNode(
                            engine = engine,
                            size = cubeSize,
                            center = Float3(0f, 0f, 0f),
                            materialInstance = material
                        )
                        addChildNode(cubeNode)
                    }
                    arChildNodes.add(poseNode)
                    localAnchorNodeMap[anchorKey] = poseNode
                    previousUpdateStates[anchorKey] = state
                } else {
                    // Update cube material and size if tracking state changed
                    if (previousUpdateState != state) {
                        // Remove old cube node children
                        existingNode.childNodes.toList().forEach { child ->
                            existingNode.removeChildNode(child)
                            child.destroyRecursively()
                        }

                        // Create new cube node with updated material and size
                        val cubeNode = CubeNode(
                            engine = engine,
                            size = cubeSize,
                            center = Float3(0f, 0f, 0f),
                            materialInstance = material
                        )
                        existingNode.addChildNode(cubeNode)
                    }
                    previousUpdateStates[anchorKey] = state
                }
            }
        }
    }

    // Update transforms for existing cube nodes
    LaunchedEffect(vps2Manager, engine, materialLoader) {
        vps2Manager.anchorTransformsFlow.collectLatest { transforms ->
            // Only update transforms for anchors that have nodes
            transforms.forEach { (anchorKey, matrix) ->
                val existingNode = localAnchorNodeMap[anchorKey] ?: return@forEach

                // Convert matrix to Mat4 for worldTransform
                    val mat4 = Mat4(
                    Float4(
                        matrix[0], matrix[1], matrix[2], matrix[3]
                    ),
                    Float4(
                        matrix[4], matrix[5], matrix[6], matrix[7]
                    ),
                    Float4(
                        matrix[8], matrix[9], matrix[10], matrix[11]
                    ),
                    Float4(
                        matrix[12], matrix[13], matrix[14], matrix[15]
                    )
                )

                // Update transform only (material/size handled separately)
                existingNode.worldTransform = mat4
            }
        }
    }


    // Poll for mesh processing needs and create mesh nodes
    LaunchedEffect(Unit) {
        while (true) {
            if (meshRenderer.needsMeshProcessing) {
                meshRenderer.createMeshNodes(context, rootMeshNode)
            }
            delay(200)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Intentionally no dropdown: we track all payloads in the map.
            // (If a payload is provided via route, we include it too.)
            if (!vps2Manager.trackingStarted && !initialPayload.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Anchor payload found via VPS Coverage",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.DarkGray.copy(alpha = 0.5f))
                        .padding(8.dp),
                    color = Color(0xFF_FFFFFF)
                )
            }
        }

        // Info box for tracked anchors
        if (vps2Manager.trackingStarted && vps2Manager.anchors.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 140.dp)
                    .fillMaxWidth(0.45f)
                    .heightIn(max = 300.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            ) {
                Text(
                    text = "Tracked Anchors (${vps2Manager.anchors.size}):",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                LazyColumn {
                    itemsIndexed(vps2Manager.anchors.keys.toList()) { index, anchorKey ->
                        val state = vps2Manager.anchorStates[anchorKey] ?: AnchorTrackingState.NOT_TRACKED
                        val payload = vps2Manager.anchors[anchorKey]
                        val anchorName = payload?.let { payloadToName[it] } ?: "Anchor ${index + 1}"
                        val stateColor = when (state) {
                            AnchorTrackingState.TRACKED -> Color.Green
                            AnchorTrackingState.LIMITED -> Color.Yellow
                            AnchorTrackingState.NOT_TRACKED -> Color.Red
                        }
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(stateColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "$anchorName - $state",
                                color = Color.White,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }

        // Compass like WPS sample, plus lat/lng from latest transformer conversion (bottom-end)
        if (vps2Manager.trackingStarted) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 140.dp)
                    .fillMaxWidth(0.45f)
                    .heightIn(max = 300.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "VPS2 - Lat: %.6f, Lng: %.6f, Heading: %.1f",
                        vps2Manager.vps2Latitude,
                        vps2Manager.vps2Longitude,
                        vps2Manager.vps2HeadingDeg,
                    ),
                    color = Color.White,
                    fontSize = 9.sp,
                )

                redCompass?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "VPS2 Compass",
                        modifier = Modifier
                            .size(90.dp)
                            .rotate(-vps2Manager.vps2HeadingDeg.toFloat())
                    )
                }

                Text(
                    text = String.format(
                        Locale.US,
                        "Device - Lat: %.6f, Lng: %.6f, Heading: %.1f",
                        vps2Manager.gpsLatitude,
                        vps2Manager.gpsLongitude,
                        vps2Manager.deviceHeadingDeg,
                    ),
                    color = Color.White,
                    fontSize = 9.sp,
                )

                blueCompass?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Device Compass",
                        modifier = Modifier
                            .size(90.dp)
                            .rotate(-vps2Manager.deviceHeadingDeg.toFloat())
                    )
                }
            }
        }

        // Localization quality indicator circle is tied to the transformer tracking state:
        // - UNAVAILABLE -> Red
        // - COARSE -> Yellow
        // - PRECISE -> Green
        val indicatorColor = when (vps2Manager.transformerTrackingState) {
            VPS2TrackingState.UNAVAILABLE -> Color.Red
            VPS2TrackingState.COARSE -> Color.Yellow
            VPS2TrackingState.PRECISE -> Color.Green
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(indicatorColor)
        )

        // Start/Stop VPS2 button
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (!vps2Manager.trackingStarted) {
                        val payloads = buildList {
                            initialPayload?.let { if (it.isNotBlank()) add(it) }
                            addAll(payloadOptions.values.filter { it.isNotBlank() })
                        }.distinct()
                        vps2Manager.startTracking(payloads)
                    } else {
                        vps2Manager.stopTracking()
                    }
                },
                modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp)
            ) {
                Text(if (vps2Manager.trackingStarted) "Stop Tracking" else "Start Tracking")
            }
        }
    }
}

// Copyright 2026 Niantic Spatial.
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import com.google.android.filament.MaterialInstance
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
import com.nianticspatial.nsdk.externalsamples.vps2debugger.VpsDebuggerEventBuffer
import com.nianticspatial.nsdk.externalsamples.vps2debugger.VpsDebuggerWebBridge
import com.nianticspatial.nsdk.externalsamples.vps2debugger.VpsDebuggerWebView
import com.nianticspatial.nsdk.externalsamples.sites.deploymentLabel
import com.nianticspatial.nsdk.vps2.Vps2TrackingState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.node.CubeNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import java.util.Locale
import com.nianticspatial.nsdk.GeolocationData

/**
 * The panel occupying the band between the AR view and the VPS2 controls. Only one fits on screen,
 * so the sample offers them as a single choice instead of one toggle each.
 *
 * @param label How the panel is named in the selector.
 */
private enum class VPS2Panel(val label: String) {
    NONE("None"),
    COMPASS("Compass"),
    MAP("Map"),
    DEBUGGER("Debugger"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VPS2View(
    context: Context,
    nsdkManager: NSDKSessionManager,
    helpContentState: MutableState<HelpContent?>,
    initialPayload: String?,
    vpsMapAssetId: String? = null,
    allAssets: Boolean = false,
    siteId: String? = null,
    siteLatitude: Double = 0.0,
    siteLongitude: Double = 0.0,
    useSiteLocalization: Boolean = false,
    useNearbySites: Boolean = false,
    nearbyLatitude: Double = 0.0,
    nearbyLongitude: Double = 0.0,
) {
    val engine = LocalSceneEngine.current
    val materialLoader = LocalSceneMaterialLoader.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Downloaded meshes without a texture are drawn as a wireframe so the camera passthrough stays
    // visible through them, which is what makes it possible to judge mesh alignment.
    val meshRenderer = remember {
        MeshRenderer(engine, materialLoader, wireframeWhenUntextured = true)
    }
    val vps2Session = remember { nsdkManager.session.vps2.acquire() }
    val debuggerScope = remember { CoroutineScope(Dispatchers.Main + SupervisorJob()) }
    val eventBuffer = remember { VpsDebuggerEventBuffer() }
    val webBridge = remember { VpsDebuggerWebBridge(eventBuffer, debuggerScope) }
    // The compass, the mini-map and the debugger all want the band above the controls and only one
    // fits, so a single selector picks between them rather than a toggle per panel. Starts on None:
    // every panel is fed by the localization callback, so before tracking runs they would all just
    // report zeros over the AR view.
    var selectedPanel by remember { mutableStateOf(VPS2Panel.NONE) }
    var panelMenuExpanded by remember { mutableStateOf(false) }
    val showDebuggerWebView = selectedPanel == VPS2Panel.DEBUGGER
    val vps2Manager = remember {
        VPS2Manager(
            nsdkManager,
            vps2Session,
            vpsMapAssetId = vpsMapAssetId,
            allAssets = allAssets,
            siteId = siteId,
            siteLatitude = siteLatitude,
            siteLongitude = siteLongitude,
        ).apply {
            // Set callback: manager decides what to render, view just calls the renderer
            onMeshDataReady = { chunks, meshIds ->
                meshRenderer.updateMeshChunks(chunks, meshIds)
            }
        }
    }

    // Enable AR session for the duration this view is in composition
    DisposableEffect(Unit) {
        nsdkManager.arManager.setEnabled(true)
        onDispose { nsdkManager.arManager.setEnabled(false) }
    }

    // Set Help contents
    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = if (useNearbySites)
                    "VPS2 Sample Help\n\nThis sample localizes to whatever VPS sites are near you, " +
                        "with no site selection.\nTO USE:\n Press \"Start Tracking\". The SDK looks " +
                        "up every site holding VPS assets within the default 100m radius of the " +
                        "location captured when you entered this screen, and localizes against all " +
                        "of their assets.\n Once a localization lands, the panel at the top-left " +
                        "names the site and asset that matched, and that asset's mesh is downloaded " +
                        "and drawn.\n The mesh stays in place while the camera is tracking, and " +
                        "hides only if AR tracking is lost.\n " +
                        "Tap anywhere to drop your own anchor, which renders as a cube.\n\n" +
                        "Use \"Show Debugger\" to open the hosted VPS debugger viewer overlay while " +
                        "AR keeps running.\n\n" +
                        "Once any localization lands, a mini-map appears at the bottom — tap \"Show Map\" to view it. Dropping a marker still needs precise (green) localization. Tap " +
                        "the map to drop a colored marker at that spot — it shows both on the map and " +
                        "as a matching cube in AR." +
                        "\n\nLong-press to geotag the spot 2m in front of the camera: VPS2 converts that " +
                        "pose to a latitude and longitude, which drops a marker on the map and a " +
                        "matching cube in AR."
                else if (useSiteLocalization)
                    "VPS2 Sample Help\n\nThis sample localizes to a site by its site ID.\nTO USE:\n " +
                        "Enter (or confirm) a site ID in the field at the top, and optionally the " +
                        "ID of one of its VPS assets, then press \"Start Tracking\". Picking an " +
                        "asset in the Sites browser fills both in for you.\n With an asset ID the " +
                        "SDK localizes against that asset, whatever its deployment; without one, " +
                        "against every production VPS asset in the site. While tracking, " +
                        "\"Switch Asset\" moves to another VPS asset of the site, and \"Stop " +
                        "Tracking\" stops localizing to it.\n Watch the quality indicator in the " +
                        "top-right corner: " +
                        "red means no localization, yellow coarse, green precise. The panels at the " +
                        "bottom show the VPS2 and device geolocations once a fix lands.\n Once a " +
                        "localization lands, the panel at the top-left names the site and asset " +
                        "that matched, and that asset's mesh is downloaded and drawn. The mesh " +
                        "stays in place while the camera is tracking, and hides only if AR " +
                        "tracking is lost.\n Tap anywhere to drop your own anchor " +
                        "1m in front of the camera and render it as as a cube.\n\n" +
                        "Use \"Show Debugger\" to open the hosted VPS debugger viewer overlay while " +
                        "AR keeps running.\n\n" +
                        "Once any localization lands, a mini-map appears at the bottom — tap \"Show Map\" to view it. Dropping a marker still needs precise (green) localization. Tap " +
                        "the map to drop a colored marker at that spot — it shows both on the map and " +
                        "as a matching cube in AR." +
                        "\n\nLong-press to geotag the spot 2m in front of the camera: VPS2 converts that " +
                        "pose to a latitude and longitude, which drops a marker on the map and a " +
                        "matching cube in AR."
                else
                    "VPS2 Sample Help\n\nThis sample shows the VPS2 flow.\nTO USE:\n Begin by pressing the" +
                        " \"Start Tracking\" button to start tracking VPS2 anchors.\n Once anchors are tracked, " +
                        "cubes will appear at their locations. Orange cubes indicate coarse localization, " +
                        "green cubes indicate refined localization.\n Meshes will be automatically downloaded " +
                        "and displayed when anchors are successfully tracked in refined mode.\n\n" +
                        "Use \"Show Debugger\" to open the hosted VPS debugger viewer overlay while AR keeps running.\n\n" +
                        "Once any localization lands, a mini-map appears at the bottom — tap \"Show Map\" to view it. Dropping a marker still needs precise (green) localization. Tap the map to " +
                        "drop a colored marker at that spot — it shows both on the map and as a matching cube in AR." +
                        "\n\nLong-press to geotag the spot 2m in front of the camera: VPS2 converts that " +
                        "pose to a latitude and longitude, which drops a marker on the map and a " +
                        "matching cube in AR.",
                color = Color.White
            )
        }
        onDispose { helpContentState.value = null }
    }

    // Stop tracking when the view exits the composition
    DisposableEffect(lifecycleOwner, vps2Manager) {
        lifecycleOwner.lifecycle.addObserver(vps2Manager)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(vps2Manager)
            vps2Manager.onDestroy(lifecycleOwner)
        }
    }

    // Site to localize to, editable in-app. Prefilled from the site picked in the sites browser, or
    // from DEFAULT_SITE_ID in BuildConfig when one has been set locally.
    var siteIdInput by remember {
        mutableStateOf(siteId ?: BuildConfig.DEFAULT_SITE_ID.takeIf { it != "YOUR_SITE_ID" } ?: "")
    }
    // VPS map asset of that site to localize to, also editable. Prefilled from the asset picked in
    // the sites browser; left blank, the site's production assets are used.
    var assetIdInput by remember { mutableStateOf(vpsMapAssetId.orEmpty()) }
    var switchAssetMenuExpanded by remember { mutableStateOf(false) }

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

    // Nodes + per-color materials for markers dropped by tapping the map, keyed for reuse/prune.
    val placedMarkerNodeMap = remember { mutableMapOf<Long, PoseNode>() }
    val placedMarkerMaterials = remember { mutableMapOf<Int, MaterialInstance>() }

    // Create root mesh node early so it's available when meshes are downloaded
    val rootMeshNode = remember {
        PoseNode(engine = engine).apply {
            arChildNodes.add(this)
        }
    }

    // Create materials for anchor cubes
    val refinedGreenMaterial = remember {
        createUnlitColorMaterial(context, materialLoader, VPS2ReservedColors.AnchorTracked)
    }
    val coarseOrangeMaterial = remember {
        createUnlitColorMaterial(context, materialLoader, VPS2ReservedColors.AnchorLimited)
    }

    // Release the Filament material instances before the engine is torn down
    DisposableEffect(engine) {
        onDispose {
            localAnchorNodeMap.values.forEach { node ->
                node.destroyRecursively(arChildNodes)
            }
            localAnchorNodeMap.clear()
            // Destroys all mesh nodes (and the buffers, MaterialInstances and Textures they own)
            // before MaterialLoader tears down the parent Material. Node.destroy() detaches each
            // node from rootMeshNode, so the destroyRecursively below only frees the root itself.
            meshRenderer.destroy()
            rootMeshNode.destroyRecursively(arChildNodes)
            engine.destroyMaterialInstance(refinedGreenMaterial)
            engine.destroyMaterialInstance(coarseOrangeMaterial)
            placedMarkerNodeMap.values.forEach { it.destroyRecursively(arChildNodes) }
            placedMarkerNodeMap.clear()
            placedMarkerMaterials.values.forEach { engine.destroyMaterialInstance(it) }
            placedMarkerMaterials.clear()
        }
    }

    LaunchedEffect(vps2Manager) {
        vps2Manager.toasts.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(vps2Session, eventBuffer) {
        vps2Session.debuggerEvents
            .onEach { event ->
                eventBuffer.add(event)
                webBridge.onDebuggerEvent()
            }
            .launchIn(this)
    }

    LaunchedEffect(showDebuggerWebView) {
        if (showDebuggerWebView) {
            webBridge.show()
        } else {
            webBridge.hide()
        }
    }

    // Every panel is fed by tracking, so drop back to None as soon as it stops. Keyed off the
    // tracking state rather than the Stop button because tracking also stops on its own when
    // startTracking fails.
    LaunchedEffect(vps2Manager.trackingStarted) {
        if (!vps2Manager.trackingStarted) {
            selectedPanel = VPS2Panel.NONE
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webBridge.hide()
            eventBuffer.clear()
            debuggerScope.cancel()
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

    // Create/reposition/prune AR cubes for markers placed by tapping the map. One node per marker
    // id; a null transform (VPS2 has not resolved the coordinate yet) drops the cube until a pose
    // comes back. Cube colors match the map dots via the shared per-color materials.
    LaunchedEffect(vps2Manager, engine, materialLoader) {
        androidx.compose.runtime.snapshotFlow { vps2Manager.placedMarkers }
            .collectLatest { markers ->
                val liveIds = markers.map { it.id }.toSet()
                placedMarkerNodeMap.keys.filter { it !in liveIds }.forEach { id ->
                    placedMarkerNodeMap.remove(id)?.destroyRecursively(arChildNodes)
                }

                markers.forEach { marker ->
                    val matrix = marker.transform
                    if (matrix == null) {
                        placedMarkerNodeMap.remove(marker.id)?.destroyRecursively(arChildNodes)
                        return@forEach
                    }

                    val node = placedMarkerNodeMap[marker.id] ?: run {
                        val material = placedMarkerMaterials.getOrPut(marker.color.toArgb()) {
                            createUnlitColorMaterial(context, materialLoader, marker.color)
                        }
                        PoseNode(engine = engine).apply {
                            addChildNode(
                                CubeNode(
                                    engine = engine,
                                    size = Float3(0.25f, 0.25f, 0.25f),
                                    center = Float3(0f, 0f, 0f),
                                    materialInstance = material,
                                )
                            )
                            arChildNodes.add(this)
                        }.also { placedMarkerNodeMap[marker.id] = it }
                    }

                    node.worldTransform = Mat4(
                        Float4(matrix[0], matrix[1], matrix[2], matrix[3]),
                        Float4(matrix[4], matrix[5], matrix[6], matrix[7]),
                        Float4(matrix[8], matrix[9], matrix[10], matrix[11]),
                        Float4(matrix[12], matrix[13], matrix[14], matrix[15]),
                    )
                }
            }
    }


    // Place or hide every downloaded mesh chunk at once by moving their shared parent. Hiding
    // keeps the built mesh, so it reappears as soon as tracking returns.
    LaunchedEffect(vps2Manager) {
        vps2Manager.meshRootPose.collect { matrix ->
            if (matrix == null) {
                rootMeshNode.isVisible = false
                return@collect
            }
            rootMeshNode.worldTransform = Mat4(
                Float4(matrix[0], matrix[1], matrix[2], matrix[3]),
                Float4(matrix[4], matrix[5], matrix[6], matrix[7]),
                Float4(matrix[8], matrix[9], matrix[10], matrix[11]),
                Float4(matrix[12], matrix[13], matrix[14], matrix[15]),
            )
            rootMeshNode.isVisible = true
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

    Box(modifier = Modifier
        .fillMaxSize()
        .pointerInput(vps2Manager, showDebuggerWebView) {
            if (!showDebuggerWebView) {
                detectTapGestures(
                    onTap = { vps2Manager.createAnchor() },
                    onLongPress = {
                        val geolocation = vps2Manager.geotagInFront()
                        val message = if (geolocation == null) {
                            "Localize precisely (green) to geotag"
                        } else {
                            "Geotagged %.6f, %.6f (heading %.0f°)".format(
                                geolocation.latitude,
                                geolocation.longitude,
                                geolocation.heading,
                            )
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (useSiteLocalization && !vps2Manager.trackingStarted) {
                val idFieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Black.copy(alpha = 0.7f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.7f),
                    focusedLabelColor = Color.White,
                    unfocusedLabelColor = Color.LightGray,
                )
                OutlinedTextField(
                    value = siteIdInput,
                    onValueChange = { siteIdInput = it },
                    label = { Text("Site ID") },
                    singleLine = true,
                    colors = idFieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                OutlinedTextField(
                    value = assetIdInput,
                    onValueChange = { assetIdInput = it },
                    label = { Text("Asset ID (optional)") },
                    singleLine = true,
                    colors = idFieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
            if (!vps2Manager.trackingStarted && !siteId.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (vpsMapAssetId.isNullOrBlank()) "Site ID obtained from Sites browser"
                        else "Site and asset IDs obtained from Sites browser",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.DarkGray.copy(alpha = 0.5f))
                        .padding(8.dp),
                    color = Color(0xFF_FFFFFF)
                )
            }
        }

        // Which site/asset the SDK actually localized against. Ids arrive with the localization;
        // names land one portal round-trip later, so fall back to the id until then.
        if ((useNearbySites || useSiteLocalization) && vps2Manager.trackingStarted) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 8.dp)
                    .fillMaxWidth(0.55f)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val siteId = vps2Manager.localizedSiteId
                if (siteId == null) {
                    Text(
                        text = if (useNearbySites) "Searching nearby sites…" else "Localizing to site…",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                    )
                } else {
                    // Heading and detail color track the live localization confidence, so moving
                    // away from the map (confidence drops to COARSE) no longer leaves the panel
                    // asserting a precise "Localized to:" fix. Mirrors the indicator circle's
                    // state → color mapping.
                    val (statusLabel, statusColor) = when (vps2Manager.localizationTrackingState) {
                        Vps2TrackingState.PRECISE -> "Localized to:" to Color.Green
                        Vps2TrackingState.COARSE -> "Low confidence — near:" to Color.Yellow
                        Vps2TrackingState.UNAVAILABLE -> "Localization lost — last seen:" to Color.Red
                    }
                    Text(
                        text = statusLabel,
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "Site: ${vps2Manager.localizedSiteName ?: siteId}",
                        color = statusColor,
                        fontSize = 10.sp,
                    )
                    // The area count is only worth saying when the asset is split; one area is
                    // the ordinary case and would be noise beside every asset.
                    val areaCount = vps2Manager.localizedAssetAreaCount
                    val areaSuffix = if (areaCount != null && areaCount > 1) " ($areaCount areas)" else ""
                    Text(
                        text = "Asset: ${vps2Manager.localizedAssetName ?: vps2Manager.localizedAssetId}$areaSuffix",
                        color = statusColor,
                        fontSize = 10.sp,
                    )
                    // Areas have no names in the portal, so the id is what there is to show. The
                    // first is the area the device is in.
                    val meshAreaIds = vps2Manager.meshAreaIds
                    if (meshAreaIds.isNotEmpty()) {
                        Text(
                            text = "Mesh areas:",
                            color = statusColor,
                            fontSize = 10.sp,
                        )
                        meshAreaIds.forEachIndexed { index, areaId ->
                            Text(
                                text = if (index == 0) "  $areaId (current)" else "  $areaId",
                                color = statusColor,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }

        // The map needs a localization to centre on, so it falls back to the compass until one
        // lands, even when the map is the selected panel.
        val hasUserLocation = vps2Manager.vps2Latitude != 0.0 || vps2Manager.vps2Longitude != 0.0
        val showMapView = selectedPanel == VPS2Panel.MAP && hasUserLocation
        val showCompassView = selectedPanel == VPS2Panel.COMPASS ||
            (selectedPanel == VPS2Panel.MAP && !hasUserLocation)

        // Localization quality indicator circle is tied to the localization tracking state:
        // - UNAVAILABLE -> Red
        // - COARSE -> Yellow
        // - PRECISE -> Green
        val indicatorColor = when (vps2Manager.localizationTrackingState) {
            Vps2TrackingState.UNAVAILABLE -> Color.Red
            Vps2TrackingState.COARSE -> Color.Yellow
            Vps2TrackingState.PRECISE -> Color.Green
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

        // Everything pinned to the bottom of the screen lives in this column, so a panel can
        // never end up on top of the buttons: the column hands the buttons their measured height
        // and the panel above takes what is left. The panels used to be positioned individually
        // against a hand-tuned clearance constant, which could not account for the 'Clear' button
        // appearing once an anchor exists and so let the map slide under it (NSDK-8967).
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            // The band above the buttons shows one panel at a time. The debugger takes the whole
            // band while it is open, then the mini-map, otherwise the anchor list and compass
            // share it side by side. The map needs a localization to centre on, so it stays on
            // the compass until one lands even when toggled on.
            //
            // Every branch is weighted with fill = false, so the buttons are measured first and
            // the band can only ever use what is left over. That keeps the buttons on screen in
            // landscape, where the whole overlay has little vertical room to begin with.
            if (showDebuggerWebView) {
                VpsDebuggerWebView(
                    bridge = webBridge,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            } else if (vps2Manager.trackingStarted && showMapView) {
                val anchorDots = vps2Manager.anchorGeolocations.mapNotNull { (anchorKey, geo) ->
                    val state = vps2Manager.anchorStates[anchorKey] ?: AnchorTrackingState.NOT_TRACKED
                    if (state == AnchorTrackingState.NOT_TRACKED) return@mapNotNull null
                    // Match the AR cube colors: orange while coarse (LIMITED), green once precise.
                    val dotColor =
                        if (state == AnchorTrackingState.LIMITED) VPS2ReservedColors.AnchorLimited
                        else VPS2ReservedColors.AnchorTracked
                    VPS2MapDot(geo.latitude, geo.longitude, dotColor)
                }
                VPS2MapView(
                    userLatitude = vps2Manager.vps2Latitude,
                    userLongitude = vps2Manager.vps2Longitude,
                    headingDeg = vps2Manager.vps2HeadingDeg,
                    placementEnabled = vps2Manager.localizationTrackingState == Vps2TrackingState.PRECISE,
                    anchorDots = anchorDots,
                    placedMarkers = vps2Manager.placedMarkers,
                    onTapCoordinate = { lat, lng ->
                        if (!vps2Manager.placeMarker(lat, lng)) {
                            Toast.makeText(
                                context,
                                "Localize precisely (green) to place a marker",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else if (vps2Manager.trackingStarted && showCompassView) {
                // The compass spans the band, with the anchor list stacked above it. The list is
                // the weighted one, so the compass is measured first and the list takes whatever
                // vertical room is left rather than pushing the compass out.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (vps2Manager.anchors.isNotEmpty()) {
                        TrackedAnchorsPanel(
                            vps2Manager,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth(0.6f),
                        )
                    }
                    VPS2CompassPanel(
                        vps2Manager,
                        redCompass,
                        blueCompass,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // VPS2 controls, as two rows of equal-width buttons rather than one centered stack: what
            // is shown on screen, then what tracking does. A row with one button gives it the width.
            val canClear = vps2Manager.createdAnchorCount > 0 || vps2Manager.placedMarkers.isNotEmpty()
            // The site's other VPS assets: production only unless allAssets. They load after
            // Start. A site started without an asset ID has no target asset, so the localized
            // one is left out too.
            val switchableAssets = vps2Manager.siteVpsAssets.filter {
                it.id != vps2Manager.targetAssetId && it.id != vps2Manager.localizedAssetId
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // One selector for the whole band, rather than a toggle per panel: only one
                    // panel can be on screen at a time, so the buttons they would need are all
                    // mutually exclusive anyway.
                    Box(modifier = Modifier.weight(1f)) {
                        FilledTonalButton(
                            onClick = { panelMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View: ${selectedPanel.label}", maxLines = 1)
                        }
                        DropdownMenu(
                            expanded = panelMenuExpanded,
                            onDismissRequest = { panelMenuExpanded = false },
                        ) {
                            VPS2Panel.entries.forEach { panel ->
                                DropdownMenuItem(
                                    text = { Text(panel.label) },
                                    onClick = {
                                        selectedPanel = panel
                                        panelMenuExpanded = false
                                    },
                                    // Nothing feeds the compass or the map until tracking is
                                    // running, and the map additionally has nothing to centre on
                                    // until a localization actually lands. The debugger stays
                                    // available so it can be opened before pressing Start.
                                    enabled = when (panel) {
                                        VPS2Panel.NONE, VPS2Panel.DEBUGGER -> true
                                        VPS2Panel.COMPASS -> vps2Manager.trackingStarted
                                        VPS2Panel.MAP ->
                                            vps2Manager.trackingStarted && hasUserLocation
                                    },
                                )
                            }
                        }
                    }
                    // Only offered when the user has something of their own to clear.
                    // Payload-tracked anchors don't count: clearAll() leaves them alone, since they
                    // are the demo content rather than something the user added.
                    if (canClear) {
                        FilledTonalButton(
                            onClick = { vps2Manager.clearAll() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear", maxLines = 1)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (vps2Manager.trackingStarted && switchableAssets.isNotEmpty()) {
                        Box(modifier = Modifier.weight(1f)) {
                            Button(
                                onClick = { switchAssetMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Switch Asset", maxLines = 1)
                            }
                            DropdownMenu(
                                expanded = switchAssetMenuExpanded,
                                onDismissRequest = { switchAssetMenuExpanded = false },
                            ) {
                                switchableAssets.forEach { asset ->
                                    DropdownMenuItem(
                                        text = { Text("${asset.name} · ${deploymentLabel(asset.deployment)}") },
                                        onClick = {
                                            switchAssetMenuExpanded = false
                                            vps2Manager.switchAsset(asset.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = {
                            if (!vps2Manager.trackingStarted) {
                                if (useNearbySites) {
                                    vps2Manager.startTracking(
                                        nearbySites = VPS2Manager.NearbySitesRequest(
                                            latitude = nearbyLatitude,
                                            longitude = nearbyLongitude,
                                        )
                                    )
                                } else if (useSiteLocalization) {
                                    vps2Manager.startTracking(siteId = siteIdInput, assetId = assetIdInput)
                                } else {
                                    vps2Manager.startTracking(
                                        listOfNotNull(initialPayload?.takeIf { it.isNotBlank() })
                                    )
                                }
                            } else {
                                // The panel selection resets itself off trackingStarted.
                                eventBuffer.clear()
                                vps2Manager.stopTracking()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (vps2Manager.trackingStarted) "Stop Tracking" else "Start Tracking",
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}


/**
 * Bottom-band panel listing the anchors VPS2 is currently tracking, each with its tracking state
 * and, once known, its geolocation.
 */
@Composable
private fun TrackedAnchorsPanel(
    vps2Manager: VPS2Manager,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
                val anchorName = "Anchor ${index + 1}"
                val stateColor = when (state) {
                    AnchorTrackingState.TRACKED -> Color.Green
                    AnchorTrackingState.LIMITED -> Color.Yellow
                    AnchorTrackingState.NOT_TRACKED -> Color.Red
                }
                val geo: GeolocationData? = vps2Manager.anchorGeolocations[anchorKey]
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    Row(
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
                    if (geo != null) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "  %.5f, %.5f, %.1f°",
                                geo.latitude,
                                geo.longitude,
                                geo.heading
                            ),
                            color = Color.LightGray,
                            fontSize = 8.sp,
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bottom-band panel showing the VPS2 and device positions, each with a compass rose oriented to
 * its own heading. The two readouts sit side by side so the panel stays short enough to centre in
 * the band rather than filling it.
 */
@Composable
private fun VPS2CompassPanel(
    vps2Manager: VPS2Manager,
    redCompass: ImageBitmap?,
    blueCompass: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompassReadout(
            label = "VPS2",
            latitude = vps2Manager.vps2Latitude,
            longitude = vps2Manager.vps2Longitude,
            headingDeg = vps2Manager.vps2HeadingDeg,
            rose = redCompass,
            contentDescription = "VPS2 Compass",
            modifier = Modifier.weight(1f),
        )
        CompassReadout(
            label = "Device",
            latitude = vps2Manager.gpsLatitude,
            longitude = vps2Manager.gpsLongitude,
            headingDeg = vps2Manager.deviceHeadingDeg,
            rose = blueCompass,
            contentDescription = "Device Compass",
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A single compass rose, rotated to [headingDeg], above the position it is reporting. The readout
 * is broken across lines because two of these share the width of the screen.
 */
@Composable
private fun CompassReadout(
    label: String,
    latitude: Double,
    longitude: Double,
    headingDeg: Double,
    rose: ImageBitmap?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = String.format(
                Locale.US,
                "%s\nLat: %.6f\nLng: %.6f\nHeading: %.1f",
                label,
                latitude,
                longitude,
                headingDeg,
            ),
            color = Color.White,
            fontSize = 9.sp,
        )

        rose?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(90.dp)
                    .rotate(-headingDeg.toFloat())
            )
        }
    }
}

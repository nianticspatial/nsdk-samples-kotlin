// Copyright 2026 Niantic Spatial.

package com.nianticspatial.nsdk.externalsamples.mapping

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Pose
import com.nianticspatial.nsdk.AnchorTrackingState
import com.nianticspatial.nsdk.MapMetadata
import com.nianticspatial.nsdk.mapping.MappingStorageSession
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.HelpContent
import com.nianticspatial.nsdk.externalsamples.LocalSceneEngine
import com.nianticspatial.nsdk.externalsamples.LocalSceneMaterialLoader
import com.nianticspatial.nsdk.externalsamples.addChildNode
import com.nianticspatial.nsdk.externalsamples.arChildNodes
import com.nianticspatial.nsdk.externalsamples.clearChildNodes
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.ar.node.PoseNode
import kotlinx.serialization.Serializable
import io.github.sceneview.safeDestroyMaterialInstance
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
object DeviceMappingRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceMappingView(context: Activity, nsdkSessionManager: NSDKSessionManager, helpContentState: MutableState<HelpContent?>) {
    val engine = LocalSceneEngine.current
    val materialLoader = LocalSceneMaterialLoader.current

    val lifecycleOwner = LocalLifecycleOwner.current
    val mappingManager = remember { DeviceMappingManager(nsdkSessionManager, context) }

    var mapsPendingVisualization by remember { mutableStateOf<List<ByteArray>>(emptyList()) }

    var extractedMetadata by remember { mutableStateOf<List<MapMetadata>>(emptyList()) }

    var visualizationPointCount by remember { mutableStateOf(0) }

    var showSaveButton by remember { mutableStateOf(false) }

    var showLoadButton by remember { mutableStateOf(false) }

    var showMapList by remember { mutableStateOf(false) }

    var showSaveDialog by remember { mutableStateOf(false) }

    var mapNameInput by remember { mutableStateOf("") }

    var savedMapsList by remember { mutableStateOf<List<String>>(emptyList()) }

    var infoText by remember { mutableStateOf("") }

    var infoColor by remember { mutableStateOf(Color.Black) }

    val colorCustomYellow = Color(0xFFFFA500)

    val pointMaterialInstance = remember {
        val buffer = context.assets.open("materials/point_cloud.filamat").use {
            ByteBuffer.wrap(it.readBytes())
        }
        val material = materialLoader.createMaterial(buffer)
        val materialInstance = material.createInstance()
        materialInstance.setParameter("pointSize", 15.0f)
        materialInstance
    }

    // Track the point cloud node and its parent pose node for AR world space tracking
    var pointCloudNode by remember { mutableStateOf<PointCloudNode?>(null) }
    var pointCloudPoseNode by remember { mutableStateOf<PoseNode?>(null) }

    // Set Help contents
    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text =  "Device Mapping Sample Help\n\n" +
                    "This sample lets you anchor content to the world by scanning the local area and saving a device map. " +
                    "These maps are saved to the device and can be shared to others.\n\n" +
                    "TO USE:\nPress \"Start Mapping\" to begin scanning the area around you. " +
                    "Once you're done press \"Stop Mapping\" to finalize the map.\nOnce the map creation is complete, " +
                    "press \"Save Map\" to save the file.\n\nTo view existing maps, press \"Load Map\" to view the list of " +
                    "maps saved on the device and select one to localize to.\nYou can append additional data to this map by " +
                    "repeating the \"Start Mapping\" process on the loaded map.",
                color = Color.White
            )
        }
        onDispose {
            helpContentState.value = null
        }
    }

    fun updateInfoText(text: String, color: Color = Color.Black) {
        infoText = text
        infoColor = color
    }

    fun listSavedMaps(): List<String> {
        return try {
            val directory = context.filesDir
            val files = directory.listFiles { file ->
                file.isFile && file.name.endsWith(".map")
            }
            files?.map { it.name }?.sorted() ?: emptyList()
        } catch (e: Exception) {
            Log.e("NSDK DeviceMapping", "Error listing saved maps: ${e.message}")
            emptyList()
        }
    }

    fun checkSavedMapAvailable() {
        if (mappingManager.hasMappingStarted) {
            showLoadButton = false
            return
        }

        savedMapsList = listSavedMaps()
        showLoadButton = savedMapsList.isNotEmpty()
    }

    fun clearVisualization() {
        visualizationPointCount = 0
        pointCloudNode?.destroy()
        pointCloudNode = null

        pointCloudPoseNode?.let {
            arChildNodes.remove(it)
            it.destroy()
        }
        pointCloudPoseNode = null

        clearChildNodes()
    }

    fun visualizeMapPoints(anchorTransform: FloatArray) {
        val totalPointCount = extractedMetadata.sumOf { it.pointsCount.toInt() }
        if (totalPointCount == 0) return

        // Check if we need to create/update the point cloud
        val needsRecreate = pointCloudNode == null || visualizationPointCount != totalPointCount

        if (needsRecreate) {
            Log.i("NSDK DeviceMapping", "Creating point cloud with ${totalPointCount} points")

            // Clear old visualization
            pointCloudNode?.destroy()
            pointCloudNode = null

            val pointsArray = FloatArray(totalPointCount * 3)
            var arrayIndex = 0

            for (metadata in extractedMetadata) {
                val points = metadata.points
                val pointCount = metadata.pointsCount.toInt()

                for (i in 0 until pointCount) {
                    val pointIndex = i * 3
                    pointsArray[arrayIndex++] = points[pointIndex]
                    pointsArray[arrayIndex++] = points[pointIndex + 1]
                    pointsArray[arrayIndex++] = points[pointIndex + 2]
                }
            }

            visualizationPointCount = totalPointCount

            if (pointCloudPoseNode == null) {
                pointCloudPoseNode = PoseNode(
                    engine = engine,
                    pose = Pose.IDENTITY
                )
                addChildNode(pointCloudPoseNode!!)
            }

            pointCloudNode = PointCloudNode(
                engine = engine,
                points = pointsArray,
                pointCount = totalPointCount,
                materialInstance = pointMaterialInstance,
                color = io.github.sceneview.math.Color(0.3f, 0.8f, 0.0f, 1.0f)
            )
            pointCloudNode?.setPointSize(15.0f)
            pointCloudPoseNode?.addChildNode(pointCloudNode!!)

            Log.i("NSDK DeviceMapping", "Created point cloud with ${totalPointCount} points")
        }

        // Update the pose node's transform from localization
        pointCloudPoseNode?.let { poseNode ->
            val mat4 = Mat4(
                Float4(
                    anchorTransform[0], anchorTransform[1], anchorTransform[2], anchorTransform[3]
                ),
                Float4(
                    anchorTransform[4], anchorTransform[5], anchorTransform[6], anchorTransform[7]
                ),
                Float4(
                    anchorTransform[8], anchorTransform[9], anchorTransform[10], anchorTransform[11]
                ),
                Float4(
                    anchorTransform[12], anchorTransform[13], anchorTransform[14], anchorTransform[15]
                )
            )
            poseNode.worldTransform = mat4
        }
    }

    fun updateVisualizationIfNeeded()
    {
        if (mappingManager.currentAnchorUpdate?.trackingState != AnchorTrackingState.TRACKED) {
            if( mappingManager.currentAnchorUpdate != null)
                updateInfoText("Localizing... (${mappingManager.currentAnchorUpdate?.trackingState})", colorCustomYellow)
            return
        }

        val totalPoints = extractedMetadata.sumOf { it.pointsCount }
        if (totalPoints > 0) {
            val matrix = FloatArray(16)
            mappingManager.currentAnchorUpdate!!.anchorToLocalTrackingTransform.toMatrix(matrix, 0)
            visualizeMapPoints(matrix)
            updateInfoText("Localized! Showing $totalPoints map points", Color.Green)
        } else {
            updateInfoText("Localized!", Color.Green)
        }
    }

    fun startMapping() {
        updateInfoText("Mapping...", colorCustomYellow)
        mappingManager.startMapping()
        showLoadButton = false
        showMapList = false
        clearVisualization()
    }

    fun stopMapping() {
        mappingManager.stopMapping()

        if (extractedMetadata.isNotEmpty()) {
            showSaveButton = true
        }
    }

    fun processPendingMaps(updateBuffer: ByteArray) {
        mapsPendingVisualization = mapsPendingVisualization + updateBuffer
        Log.i("NSDK DeviceMapping", "Added chunk ${mapsPendingVisualization.size} for visualization")
    }

    fun processVpsMetadata(anchor: String, mapStoreSession: MappingStorageSession) {
        val newMetadata = mutableListOf<MapMetadata>()


        for (mapData in mapsPendingVisualization) {
          val metadataResult = mapStoreSession.extractMetadata(anchor, mapData)
          if (metadataResult != null) {
            newMetadata.add(metadataResult)
          } else {
            Log.w("NSDK DeviceMapping", "Failed to extract metadata")
          }
        }

        extractedMetadata = extractedMetadata + newMetadata
        mapsPendingVisualization = emptyList()
    }

    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(mappingManager)
        mappingManager.setMapProcessingCallbacks(::processPendingMaps, ::processVpsMetadata)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(mappingManager)
            mappingManager.onDestroy(lifecycleOwner);

            // Clean up point cloud nodes first
            clearVisualization()

            // Destroy material instance before the material itself is destroyed
            engine.safeDestroyMaterialInstance(pointMaterialInstance)

            // Clear remaining child nodes
            clearChildNodes()
        }
    }

    LaunchedEffect(Unit) {
        checkSavedMapAvailable()
    }

    LaunchedEffect(mappingManager.currentAnchorUpdate) {
        updateVisualizationIfNeeded()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (infoText.isNotEmpty()) {
            Text(
                text = infoText,
                color = infoColor,
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .offset(y = 80.dp)
                    .padding(horizontal = 16.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .offset(y = 120.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    if (mappingManager.isMapping) {
                        stopMapping()
                    } else {
                        mapsPendingVisualization = emptyList()
                        startMapping()
                    }
                }
            ) {
                Text(if (mappingManager.isMapping) "Stop Mapping" else "Start Mapping")
            }

            if (showSaveButton) {
                Button(
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                            .format(Date())
                        mapNameInput = "Map-$timestamp"
                        showSaveDialog = true
                    }
                ) {
                    Text("Save Map")
                }
            }

            if (showLoadButton) {
                Button(
                    onClick = {
                        savedMapsList = listSavedMaps()
                        showMapList = !showMapList
                    }
                ) {
                    Text(if (showMapList) "Hide Maps" else "Load Map")
                }
            }

            if (showMapList) {
                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Found ${savedMapsList.size} map(s)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    savedMapsList.forEach { fileName ->
                        MapSaveCard(
                            fileName = fileName,
                            onClick = {
                                // Don't allow loading maps during active mapping session
                                if (mappingManager.isMapping) {
                                    return@MapSaveCard
                                }

                                if (mappingManager.loadMapFromDisk(fileName)) {
                                    updateInfoText("Tracking anchor for localization...", colorCustomYellow)
                                    showMapList = false
                                    showLoadButton = false
                                } else {
                                    updateInfoText(mappingManager.errorMessage, Color.Red)
                                }
                            }
                        )
                    }
                }
            }
        }
        if (showSaveDialog) {
            SaveMapDialog(
                mapName = mapNameInput,
                onMapNameChange = { mapNameInput = it },
                onDismiss = { showSaveDialog = false },
                onSave = {
                    val fileName = if (mapNameInput.endsWith(".map")) {
                        mapNameInput
                    } else {
                        "$mapNameInput.map"
                    }

                    if (mappingManager.saveMapToDisk(fileName)) {
                        updateInfoText("Map saved successfully", Color.Green)
                        showSaveButton = false
                        showSaveDialog = false
                        checkSavedMapAvailable()
                    } else {
                        updateInfoText(mappingManager.errorMessage, Color.Red)
                        showSaveDialog = false
                    }
                }
            )
        }
    }
}

@Composable
fun SaveMapDialog(
    mapName: String,
    onMapNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Map") },
        text = {
            Column {
                Text(
                    text = "Please enter a name for your map:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = mapName,
                    onValueChange = onMapNameChange,
                    label = { Text("Map Name") },
                    placeholder = { Text("Enter map name...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (mapName.isNotBlank()) onSave() }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = mapName.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun MapSaveCard(
    fileName: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )
        }
    }
}

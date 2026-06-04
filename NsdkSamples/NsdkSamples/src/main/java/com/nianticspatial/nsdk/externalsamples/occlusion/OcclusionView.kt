// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.occlusion

import android.util.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nianticspatial.nsdk.externalsamples.HelpContent
import com.nianticspatial.nsdk.externalsamples.LocalARCameraNode
import com.nianticspatial.nsdk.externalsamples.LocalARCameraStream
import com.nianticspatial.nsdk.externalsamples.LocalSceneEngine
import com.nianticspatial.nsdk.externalsamples.LocalSceneMaterialLoader
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.OnFrameUpdateListener
import com.nianticspatial.nsdk.externalsamples.arChildNodes
import com.nianticspatial.nsdk.externalsamples.createUnlitColorMaterial
import com.nianticspatial.nsdk.externalsamples.destroyRecursively
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.node.CubeNode
import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin
import com.google.ar.core.Pose
import com.nianticspatial.nsdk.NsdkFrame

private const val _DEFAULT_FOLLOW_DISTANCE_METERS = 2.0f
private const val _MIN_FOLLOW_DISTANCE_METERS = 0.5f
private const val _MAX_FOLLOW_DISTANCE_METERS = 8.0f
private const val _CUBE_YAW_RADIANS = (Math.PI.toFloat() / 4.0f)
private const val _CUBE_EDGE_METERS = 0.4f
private const val _DEFAULT_DEPTH_NEAR = 0.1f
private const val _DEFAULT_DEPTH_FAR = 1000f
private const val _NSDK_DEPTH_NEAR = 0.2f
private const val _NSDK_DEPTH_FAR = 100f
private const val _HELP_CONTENT_1 = "Occlusion Sample Help\n\n"
private const val _HELP_CONTENT_2 = "This sample uses NSDK depth to hide cube pixels behind real geometry. "
private const val _HELP_CONTENT_3 = "Move the cube behind a real-world surface to see it occlude. Use the slider to adjust the cube distance."
private const val _HELP_CONTENT_AGGR = _HELP_CONTENT_1 + _HELP_CONTENT_2 + _HELP_CONTENT_3

@Serializable
object OcclusionRoute

/**
 * Composable screen that demonstrates global depth occlusion via a custom
 * ARCameraStream material driven by NSDK depth data.
 *
 * Renders a white cube that floats at an adjustable distance in front of the
 * camera. The camera background material writes gl_FragDepth from NSDK depth,
 * causing the cube (and any other virtual geometry) to be occluded by real-world
 * surfaces without any per-object material changes.
 *
 * @param nsdkSessionManager Provides AR session, depth session, and frame update listener.
 * @param helpContentState Receives help overlay content for the BackHelpScaffold.
 */
@Composable
fun OcclusionView(
    nsdkSessionManager: NSDKSessionManager,
    helpContentState: MutableState<HelpContent?>) {
    val engine = LocalSceneEngine.current
    val materialLoader = LocalSceneMaterialLoader.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraStream = LocalARCameraStream.current
    val cameraNode = LocalARCameraNode.current
    var viewportSize by remember { mutableStateOf<Size?>(null) }
    val followDistanceState = remember { mutableFloatStateOf(_DEFAULT_FOLLOW_DISTANCE_METERS) }
    var followDistance by followDistanceState

    val cubeMaterial = remember(materialLoader) {
        createUnlitColorMaterial(context, materialLoader, Color.White)
    }

    val cubeNode = remember(engine, cubeMaterial) {
        CubeNode(
            engine = engine,
            size = Float3(_CUBE_EDGE_METERS, _CUBE_EDGE_METERS, _CUBE_EDGE_METERS),
            center = Float3(0f, 0f, 0f),
            materialInstance = cubeMaterial
        ).apply {
            transform = createYawRotationTransform(_CUBE_YAW_RADIANS)
        }
    }

    val cubeRootNode = remember(engine, cubeNode) {
        PoseNode(engine = engine).apply { addChildNode(cubeNode) }
    }

    val occlusionManager = remember(engine, cameraStream) {
        OcclusionManager(
            engine = engine,
            nsdkSessionManager = nsdkSessionManager,
            context = context,
            materialLoader = materialLoader,
            cameraStream = cameraStream
        )
    }

    // In live mode the ARScene drives the render camera internally in lock-step with
    // onFrameUpdate, so a listener is fine. In playback mode the render camera is driven
    // by a LaunchedEffect in ARSceneView — the cube must use the same async path so both
    // are updated in the same Compose frame and the render never sees them out of sync.
    val cubeFrameListener = remember(cubeRootNode, followDistanceState) {
        object : OnFrameUpdateListener {
            private val _matrix = FloatArray(16)
            override fun onFrameUpdate(frame: NsdkFrame) {
                if (frame !is NsdkFrame.Live) return  // playback handled by LaunchedEffect below
                frame.camera.pose.compose(Pose.makeTranslation(0f, 0f, -followDistanceState.floatValue))
                    .also { it.toMatrix(_matrix, 0) }
                cubeRootNode.worldTransform = floatArrayToMat4(_matrix)
            }
        }
    }

    // Enable AR session for the duration this view is in composition
    DisposableEffect(Unit) {
        nsdkSessionManager.arManager.setEnabled(true)
        onDispose { nsdkSessionManager.arManager.setEnabled(false) }
    }

    DisposableEffect(cubeFrameListener) {
        nsdkSessionManager.arManager.addFrameUpdateListener(cubeFrameListener)
        onDispose {
            nsdkSessionManager.arManager.removeFrameUpdateListener(cubeFrameListener)
        }
    }

    DisposableEffect(Unit) {
        helpContentState.value = { Text(text = _HELP_CONTENT_AGGR, color = Color.White) }
        onDispose { helpContentState.value = null }
    }

    DisposableEffect(engine, cubeRootNode, cubeMaterial) {
        arChildNodes.add(cubeRootNode)
        onDispose {
            cubeRootNode.destroyRecursively(arChildNodes)
            engine.destroyMaterialInstance(cubeMaterial)
        }
    }

    DisposableEffect(occlusionManager) {
        occlusionManager.start()
        onDispose {
            occlusionManager.onDestroy(lifecycleOwner)
        }
    }

    // Note: in playback mode cameraNode (ARCameraNode) is not the active render camera —
    // ARSceneView uses playbackCameraNode instead — so this adjustment is live-mode only.
    // Playback occlusion still works because both the depth prepass and the cube share
    // playbackCameraNode's projection, but depth precision near 0.1–0.2 m may differ slightly.
    DisposableEffect(cameraNode) {
        val originalNear = cameraNode?.near ?: _DEFAULT_DEPTH_NEAR
        val originalFar = cameraNode?.far ?: _DEFAULT_DEPTH_FAR
        cameraNode?.near = _NSDK_DEPTH_NEAR
        cameraNode?.far = _NSDK_DEPTH_FAR
        onDispose {
            cameraNode?.near = originalNear
            cameraNode?.far = originalFar
        }
    }

    LaunchedEffect(occlusionManager, viewportSize) {
        occlusionManager.viewportSize = viewportSize
    }

    // In playback mode the render camera is driven by a LaunchedEffect in ARSceneView that fires
    // asynchronously (after Compose recomposition). The synchronous onFrameUpdate path fires
    // earlier in the same frame, so using it for the cube in playback creates an ~11ms window
    // where cube and camera are on different frames, causing visible jitter. By using a matching
    // LaunchedEffect here both updates land in the same Compose recomposition.
    val currentPlaybackFrame = nsdkSessionManager.arManager.currentPlaybackFrame
    val deviceOrientation = nsdkSessionManager.arManager.deviceOrientation
    // followDistance is a key so the cube repositions immediately when the slider changes,
    // not just on the next playback frame.
    LaunchedEffect(currentPlaybackFrame, deviceOrientation, followDistance) {
        val frame = currentPlaybackFrame ?: return@LaunchedEffect
        val displayPose = frame.camera.getDisplayOrientedPose(deviceOrientation)
        val cubePose = displayPose.compose(Pose.makeTranslation(0f, 0f, -followDistance))
        val matrix = FloatArray(16)
        cubePose.toMatrix(matrix, 0)
        cubeRootNode.worldTransform = floatArrayToMat4(matrix)
    }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { size ->
        viewportSize = Size(size.width, size.height)
    }) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Cube distance: ${"%.1f".format(followDistance)}m",
                color = Color.White
            )
            Slider(
                value = followDistance,
                onValueChange = { followDistance = it },
                valueRange = _MIN_FOLLOW_DISTANCE_METERS.._MAX_FOLLOW_DISTANCE_METERS
            )
        }
    }
}

/**
 * Builds a [Mat4] rotation around the Y axis by [angleRadians].
 */
private fun createYawRotationTransform(angleRadians: Float): Mat4 {
    val c = cos(angleRadians)
    val s = sin(angleRadians)
    return Mat4(
        Float4(c, 0f, s, 0f),
        Float4(0f, 1f, 0f, 0f),
        Float4(-s, 0f, c, 0f),
        Float4(0f, 0f, 0f, 1f)
    )
}

/**
 * Converts a column-major [FloatArray] of 16 elements into a [Mat4].
 */
private fun floatArrayToMat4(matrix: FloatArray): Mat4 {
    return Mat4(
        Float4(matrix[0], matrix[1], matrix[2], matrix[3]),
        Float4(matrix[4], matrix[5], matrix[6], matrix[7]),
        Float4(matrix[8], matrix[9], matrix[10], matrix[11]),
        Float4(matrix[12], matrix[13], matrix[14], matrix[15])
    )
}

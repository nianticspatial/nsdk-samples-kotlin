// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.occlusion

import android.util.Size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.nianticspatial.nsdk.NsdkFrame

private const val _FOLLOW_DISTANCE_METERS = 2.0f
private const val _CUBE_YAW_RADIANS = (Math.PI.toFloat() / 4.0f)
private const val _CUBE_EDGE_METERS = 0.4f
private const val _DEFAULT_DEPTH_NEAR = 0.1f
private const val _DEFAULT_DEPTH_FAR = 1000f
private const val _NSDK_DEPTH_NEAR = 0.2f
private const val _NSDK_DEPTH_FAR = 100f
private const val _HELP_CONTENT_1 = "Occlusion Sample Help\n\n"
private const val _HELP_CONTENT_2 = "This sample uses NSDK depth to hide cube pixels behind real geometry. "
private const val _HELP_CONTENT_3 = "Occlusion will start when you press \"Start Occlusion\" button."
private const val _HELP_CONTENT_AGGR = _HELP_CONTENT_1 + _HELP_CONTENT_2 + _HELP_CONTENT_3

@Serializable
object OcclusionRoute
/**
 * Composable screen that demonstrates global depth occlusion via a custom
 * ARCameraStream material driven by NSDK depth data.
 *
 * Renders a white cube that floats [_FOLLOW_DISTANCE_METERS] in front of the
 * camera. The camera background material writes gl_FragDepth from NSDK depth,
 * causing the cube (and any other virtual geometry) to be occluded by real-world
 * surfaces without any per-object material changes.
 *
 * Lifecycle responsibilities:
 * - Loads and applies [camera_stream_depth_nsdk.filamat] to the ARCameraStream.
 * - Starts and observes [DepthManager] for NSDK depth updates.
 * - Sets virtual camera near=0.2f / far=100.0f to match NSDK depth range.
 * - Tracks viewport size and forwards it to [OcclusionManager] each frame.
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
    var _viewportSize by remember { mutableStateOf<Size?>(null) }

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

    val cubeFrameListener = remember(cubeRootNode) {
        object : OnFrameUpdateListener {
            private val _matrix = FloatArray(16)
            override fun onFrameUpdate(frame: NsdkFrame) {
                val pose = frame.camera.pose.compose(
                    Pose.makeTranslation(0f, 0f, -_FOLLOW_DISTANCE_METERS)
                )
                pose.toMatrix(_matrix, 0)
                cubeRootNode.worldTransform = floatArrayToMat4(_matrix)
            }
        }
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

    LaunchedEffect(occlusionManager,_viewportSize) {
        occlusionManager.viewportSize = _viewportSize
    }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { size ->
        _viewportSize = Size(size.width, size.height)
    }) {}
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

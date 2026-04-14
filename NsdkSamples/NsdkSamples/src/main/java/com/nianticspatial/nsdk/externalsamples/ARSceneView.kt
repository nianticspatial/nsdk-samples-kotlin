// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.nianticspatial.nsdk.externalsamples.common.OverlayContent
import com.nianticspatial.nsdk.externalsamples.common.ScreenOverlayView
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.Scene
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import com.nianticspatial.nsdk.playback.PlaybackBackgroundView
import io.github.sceneview.ar.arcore.transform
import io.github.sceneview.math.toTransform
import java.nio.ByteBuffer
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.ar.rememberARCameraNode



/**
 * An external reference to the list of nodes so it can be dynamically modified.
 * This is a common pattern for managing lists that need external changes in Compose.
 */
val arChildNodes = mutableStateListOf<Node>()

/**
 * CompositionLocal to provide the AR scene's engine to composables inside ARSceneView.
 * This ensures all nodes use the same Filament engine.
 */
val LocalSceneEngine = compositionLocalOf<Engine> {
    error("No SceneEngine provided")
}

/**
 * CompositionLocal to provide the AR scene's material loader to composables inside ARSceneView.
 */
val LocalSceneMaterialLoader = compositionLocalOf<MaterialLoader> {
    error("No SceneMaterialLoader provided")
}

val LocalARCameraStream = compositionLocalOf<ARCameraStream?> { null }

val LocalARCameraNode = compositionLocalOf<ARCameraNode?> { null }


/**
 * Clean AR view using SceneView's ARScene.
 * This completely replaces the custom OpenGL rendering with SceneView's high-level AR API.
 *
 * ARScene automatically handles:
 * - AR session creation and management
 * - Camera background rendering (no manual OpenGL!)
 * - Frame updates
 * - Lifecycle management
 *
 * @param content Provides the screen content and a state holder so it can register overlay content.
 */
@Composable
fun ARSceneView(
    modifier: Modifier = Modifier,
    sessionManager: ARSessionManager,
    content: @Composable BoxScope.(overlayContentState: MutableState<OverlayContent?>) -> Unit
) {

    // Create and own the Filament engine and material loader
    // ARSceneView is responsible for all rendering, so it should manage these resources
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val cameraStream = rememberARCameraStream(materialLoader)
    val cameraNode = rememberARCameraNode(engine)

    // State for the current overlay content (nullable - no overlay by default)
    val overlayContentState = remember { mutableStateOf<OverlayContent?>(null) }

    val mode = sessionManager.mode
    val enabled = sessionManager.active
    val currentPlaybackFrame = sessionManager.currentPlaybackFrame
    val deviceOrientation = sessionManager.deviceOrientation

    // Camera node for playback mode: driven by playback pose each frame (see LaunchedEffect below)
    val playbackCameraNode = rememberCameraNode(engine)

    Box(modifier = modifier.fillMaxSize()) {
        when (mode) {
            SessionMode.LIVE -> {
                ARScene(
                    cameraStream = cameraStream,
                    cameraNode = cameraNode,
                    planeRenderer = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (enabled) 1f else 0f },
                    engine = engine,
                    materialLoader = materialLoader,
                    sessionConfiguration = { session, config ->
                        config.depthMode = when (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            true -> Config.DepthMode.AUTOMATIC
                            else -> Config.DepthMode.DISABLED
                        }
                        config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    },
                    onSessionCreated = { session -> sessionManager.setSession(session) },
                    onSessionUpdated = { _, frame -> sessionManager.updateFrame(frame) },
                    childNodes = arChildNodes,
                )
            }
            SessionMode.PLAYBACK -> {
                AndroidView(
                    factory = { ctx -> PlaybackBackgroundView(ctx) },
                    update = { view -> view.setPlaybackFrame(currentPlaybackFrame) },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (enabled) 1f else 0f }
                )
                Scene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    materialLoader = materialLoader,
                    isOpaque = false,
                    cameraNode = playbackCameraNode,
                    childNodes = arChildNodes,
                    cameraManipulator = null,
                )
            }
        }

        // Drive playback camera from current frame pose and intrinsics.
        // deviceOrientation is a key so the camera re-configures when the user rotates the device.
        LaunchedEffect(mode, currentPlaybackFrame, deviceOrientation) {
            if (mode == SessionMode.PLAYBACK) {
                val frame = currentPlaybackFrame ?: return@LaunchedEffect
                val cam = frame.camera
                playbackCameraNode.worldTransform = cam.getDisplayOrientedPose(deviceOrientation).transform
                val (imgW, imgH) = cam.imageResolution
                val near = 0.1f
                val far = 1000f
                val projMatrix = cam.getProjectionMatrix(near, far, imgW, imgH, deviceOrientation)
                playbackCameraNode.setCustomProjection(projMatrix.toTransform(), near.toDouble(), far.toDouble())
            }
        }

        val currentOverlayContent = overlayContentState.value
        if (currentOverlayContent != null && currentOverlayContent.isVisible) {
            AndroidView(
                factory = { ctx ->
                    ScreenOverlayView(ctx, currentOverlayContent, engine, materialLoader).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        startRendering()
                    }
                },
                onRelease = { view ->
                    view.stopRendering()
                    view.destroy()
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top layer: Compose UI content (buttons, dialogs, etc.)
        // Provide the scene engine and material loader to child composables
        // Pass overlayContentState as parameter so children can register overlays
        CompositionLocalProvider(
            LocalSceneEngine provides engine,
            LocalSceneMaterialLoader provides materialLoader,
            LocalARCameraStream provides cameraStream,
            LocalARCameraNode provides cameraNode
            ) {
            content(overlayContentState)
        }
    }
}

fun addChildNode(node: Node) {
    arChildNodes.add(node)
}

fun clearChildNodes() {
    arChildNodes.clear()
}

private val _unlitColorMaterialCache = HashMap<MaterialLoader, Material>()

/**
 * Creates a true unlit material instance with the specified color.
 * This uses a compiled Filament material with shadingModel: unlit.
 * NO PBR calculations, NO lighting, NO shadows - just flat color rendering.
 *
 * This is significantly more performant than PBR materials when rendering
 * thousands of objects like map points or debug visualizations.
 */
fun createUnlitColorMaterial(
    context: Context,
    materialLoader: MaterialLoader,
    color: Color
): MaterialInstance {
    val material = _unlitColorMaterialCache.getOrPut(materialLoader){
        val buffer = context.assets.open("materials/unlit_color.filamat").use {
            ByteBuffer.wrap(it.readBytes())
        }
        materialLoader.createMaterial(buffer)
    }

    val materialInstance = material.createInstance()
    // Set the base color parameter (RGBA as individual floats)
    materialInstance.setParameter(
        "baseColor",
        color.red, color.green, color.blue, color.alpha
    )

    return materialInstance
}

// Helper function to create an anchor node with a cube
// Engine and MaterialLoader should be obtained via CompositionLocal in composables
fun createAnchorNode(
    engine: Engine,
    materialLoader: MaterialLoader,
    anchor: Anchor
): AnchorNode {
    val anchorNode = AnchorNode(engine = engine, anchor = anchor)
    val cubeNode = CubeNode(
        engine,
        size = Float3(0.2f, 0.2f, 0.2f),
        center = Float3(0f, 0f, 0f),
        materialInstance = materialLoader.createColorInstance(Color.White.copy(alpha = 0.5f))
    )
    anchorNode.addChildNode(cubeNode)
    return anchorNode
}

fun addChildNodeToAnchor(childNode: Node) {

}

fun clearAnchorChildren() {

}

/**
 * Destroys this node and all of its children, removing it from the given parent collection.
 * Call this before destroying Filament material instances to avoid "still in use" aborts.
 */
fun Node.destroyRecursively(parentCollection: MutableCollection<Node>? = null) {
    // detach children first so none keeps a material instance alive
    childNodes.toList().forEach { child ->
        removeChildNode(child)
        child.destroyRecursively()     // recursive call handles nested nodes
    }

    parentCollection?.remove(this)   // e.g. arChildNodes
    destroy()
}

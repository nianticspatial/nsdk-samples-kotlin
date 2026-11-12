// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.content.Context
import android.util.Size
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
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
import io.github.sceneview.SceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.safeDestroy
import io.github.sceneview.ar.arcore.transform
import io.github.sceneview.math.toTransform
import java.nio.ByteBuffer
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.ar.rememberARCameraNode
import com.nianticspatial.nsdk.externalsamples.camerastream.LiveNsdkCameraStream
import com.nianticspatial.nsdk.externalsamples.camerastream.PlaybackNsdkCameraStream



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

/** NSDK depth sensor valid range applied to Filament camera nodes for depth buffer precision. */
private const val _DEPTH_NEAR = 0.2f
private const val _DEPTH_FAR  = 100f

// For VPS, NSDK's image pipeline upscales camera frames smaller than this, so pick an ARCore
// camera config at least this large to avoid upscaling.
private const val _NSDK_MIN_CAMERA_WIDTH = 720
private const val _NSDK_MIN_CAMERA_HEIGHT = 540

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
    nsdkSessionManager: NSDKSessionManager,
    content: @Composable BoxScope.(overlayContentState: MutableState<OverlayContent?>) -> Unit
) {

    // Retain EGL context+pbuffer so we can rebind after WebView clears the current context.
    val eglHolder = rememberEglContextHolder()
    val engine = remember(eglHolder) { SceneView.createEngine(eglHolder.context) }
    DisposableEffect(engine) {
        onDispose { engine.safeDestroy() }
    }
    val materialLoader = rememberMaterialLoader(engine)
    val arCameraStream = rememberARCameraStream(materialLoader)
    val cameraNode = rememberARCameraNode(engine)

    // State for the current overlay content (nullable - no overlay by default)
    val overlayContentState = remember { mutableStateOf<OverlayContent?>(null) }

    val mode = sessionManager.mode

    // Mode-agnostic camera stream wrappers.
    val liveNsdkStream = remember(arCameraStream, engine, materialLoader, mode) {
        if (mode == SessionMode.LIVE) {
            LiveNsdkCameraStream(arCameraStream, engine, materialLoader, nsdkSessionManager)
        } else null
    }
    val playbackNsdkStream = remember(engine, materialLoader, mode) {
        if (mode == SessionMode.PLAYBACK) {
            PlaybackNsdkCameraStream(engine, materialLoader, nsdkSessionManager)
        } else null
    }
    val enabled = sessionManager.active
    val currentPlaybackFrame = sessionManager.currentPlaybackFrame
    val deviceOrientation = sessionManager.deviceOrientation
    // Viewport size in pixels.
    var viewportSize by remember { mutableStateOf<Size?>(null) }

    // Apply NSDK depth sensor range to the live camera node for optimal depth buffer precision.
    cameraNode.near = _DEPTH_NEAR
    cameraNode.far  = _DEPTH_FAR

    // Camera node for playback mode: driven by playback pose each frame (see LaunchedEffect below)
    val playbackCameraNode = rememberCameraNode(engine)

    // Live stream owns depth material + session from construction; destroy when it goes away.
    DisposableEffect(liveNsdkStream) {
        onDispose { liveNsdkStream?.destroy() }
    }

    // Add the playback background node to the scene after composition commits.
    DisposableEffect(playbackNsdkStream) {
        playbackNsdkStream?.let { arChildNodes.add(it.backgroundNode) }
        onDispose { playbackNsdkStream?.destroy(arChildNodes) }
    }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { size ->
        val newViewportSize = Size(size.width, size.height)
        viewportSize = newViewportSize
        liveNsdkStream?.viewportSize = newViewportSize
        playbackNsdkStream?.viewportSize = newViewportSize
        // Sync deviceOrientation on every viewport size change (e.g. device rotation).
        // The DisplayListener in ARSessionManager handles this automatically in live mode,
        // but in playback mode (no ARCore session) it may not fire in time. Calling this
        // here ensures all consumers (depth UV, cube positioning, camera background) update.
        sessionManager.updateDisplayRotation()
    }) {
        when (mode) {
            SessionMode.LIVE -> {
                GlAwareARScene(
                    eglHolder = eglHolder,
                    cameraStream = arCameraStream,
                    cameraNode = cameraNode,
                    planeRenderer = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (enabled) 1f else 0f },
                    engine = engine,
                    materialLoader = materialLoader,
                    sessionConfiguration = { session, config ->
                        // ARCore depth makes ARCore deliver camera frames ~100 ms later, so enable
                        // ARCore depth only when NSDK needs platform depth.
                        // It is usually preferable to use NSDK's depth estimation feature to avoid
                        // adding observable latency to the AR camera view.
                        config.depthMode = when (sessionManager.depthAvailability &&
                            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            true -> Config.DepthMode.AUTOMATIC
                            else -> Config.DepthMode.DISABLED
                        }
                        // Instant placement also leads to 100 ms of additional frame latency, so
                        // keep it disabled.
                        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    },
                    // Use the smallest resolution that meets the minimum spec, otherwise use the largest available.
                    sessionCameraConfig = { session ->
                        val configs = session.getSupportedCameraConfigs()
                        configs.filter {
                            it.imageSize.width >= _NSDK_MIN_CAMERA_WIDTH &&
                                it.imageSize.height >= _NSDK_MIN_CAMERA_HEIGHT
                        }.minByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
                            ?: configs.maxByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
                            ?: configs.first()
                    },
                    onSessionCreated = { session -> sessionManager.setSession(session) },
                    onSessionUpdated = { _, frame -> sessionManager.updateFrame(frame) },
                    childNodes = arChildNodes,
                )
            }
            SessionMode.PLAYBACK -> {
                // Background is rendered by PlaybackNsdkCameraStream's GeometryNode (priority 0)
                // which is in arChildNodes — so the Scene is opaque and owns the full frame.
                Scene(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (enabled) 1f else 0f },
                    engine = engine,
                    materialLoader = materialLoader,
                    isOpaque = true,
                    cameraNode = playbackCameraNode,
                    childNodes = arChildNodes,
                    cameraManipulator = null,
                )
            }
        }

        // Drive playback camera from current frame pose and intrinsics.
        // deviceOrientation and viewportSize are keys so the camera re-configures when the user
        // rotates the device or the view is resized.
        LaunchedEffect(mode, currentPlaybackFrame, deviceOrientation, viewportSize) {
            if (mode == SessionMode.PLAYBACK) {
                val frame = currentPlaybackFrame ?: return@LaunchedEffect
                val vp = viewportSize ?: return@LaunchedEffect
                val cam = frame.camera
                val displayPose = cam.getDisplayOrientedPose(deviceOrientation)
                playbackCameraNode.worldTransform = displayPose.transform
                // The projection is cropped to the viewport aspect ratio, matching the background image.
                val projMatrix = cam.getProjectionMatrix(_DEPTH_NEAR, _DEPTH_FAR, vp.width, vp.height, deviceOrientation)
                playbackCameraNode.setCustomProjection(projMatrix.toTransform(), _DEPTH_NEAR.toDouble(), _DEPTH_FAR.toDouble())

                // Drive the playback camera background: upload the current JPEG frame
                // and push the camera UV transform so the image is correctly oriented.
                playbackNsdkStream?.update(frame, deviceOrientation)
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
private val _unlitVertexMaterialCache = HashMap<MaterialLoader, Material>()

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
    // Filament treats baseColor as linear and re-encodes to sRGB on output, so feed it linear
    // values. Passing Compose's sRGB channels directly double-brightens mid-tones and makes the
    // cube look lighter/less saturated than the same color drawn as an sRGB map dot.
    val linear = color.convert(ColorSpaces.LinearSrgb)
    materialInstance.setParameter(
        "baseColor",
        linear.red, linear.green, linear.blue, linear.alpha
    )

    return materialInstance
}

/**
 * Creates an unlit material instance that reads per-vertex COLOR attributes for shading.
 * Uses `unlit_vertex.filamat` whose fragment stage calls `getColor()` — the interpolated
 * per-vertex color — so every vertex on the mesh can carry its own distinct color.
 *
 * The returned instance's `baseColor` uniform is ignored by the fragment; vertex colors win.
 * Cache the underlying [Material] per [MaterialLoader] so repeated calls only allocate a new
 * [MaterialInstance], not a new [Material] compile each time.
 */
fun createUnlitVertexColorMaterial(
    context: Context,
    materialLoader: MaterialLoader
): MaterialInstance {
    val material = _unlitVertexMaterialCache.getOrPut(materialLoader) {
        val buffer = context.assets.open("materials/unlit_vertex.filamat").use {
            ByteBuffer.wrap(it.readBytes())
        }
        materialLoader.createMaterial(buffer)
    }
    return material.createInstance()
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

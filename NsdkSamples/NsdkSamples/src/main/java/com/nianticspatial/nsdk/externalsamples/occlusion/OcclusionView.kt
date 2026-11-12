// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.occlusion

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Box as FilamentBox
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.nianticspatial.nsdk.externalsamples.HelpContent
import com.nianticspatial.nsdk.externalsamples.LocalSceneEngine
import com.nianticspatial.nsdk.externalsamples.LocalSceneMaterialLoader
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.OnFrameUpdateListener
import com.nianticspatial.nsdk.externalsamples.arChildNodes
import com.nianticspatial.nsdk.externalsamples.createUnlitVertexColorMaterial
import com.nianticspatial.nsdk.externalsamples.destroyRecursively
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.node.MeshNode
import kotlinx.serialization.Serializable
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import com.google.ar.core.Pose
import com.nianticspatial.nsdk.NsdkFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val _DEFAULT_FOLLOW_DISTANCE_METERS = 2.0f
private const val _MIN_FOLLOW_DISTANCE_METERS = 0.5f
private const val _MAX_FOLLOW_DISTANCE_METERS = 8.0f
private const val _CUBE_YAW_RADIANS = (Math.PI.toFloat() / 4.0f)
// Tilt the cube so a corner faces the camera (standard isometric orientation: pitch ≈ 35.26°).
private val _CUBE_PITCH_RADIANS = atan(1f / sqrt(2f))
private const val _CUBE_EDGE_METERS = 0.4f
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
 * Renders a vertex-colored cube that floats at an adjustable distance in front of the
 * camera. Each face carries the same color palette as the iOS occlusion scene cube, with
 * a baked per-face directional-light multiplier so face edges read clearly. The cube is
 * oriented with a corner facing the camera (isometric yaw + pitch) to maximise depth cues.
 * The camera background material writes gl_FragDepth from NSDK depth, causing the cube
 * (and any other virtual geometry) to be occluded by real-world surfaces without any
 * per-object material changes.
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
    val followDistanceState = remember { mutableFloatStateOf(_DEFAULT_FOLLOW_DISTANCE_METERS) }
    var followDistance by followDistanceState

    // Load the vertex-color material: the fragment calls getColor() (the interpolated per-vertex
    // COLOR attribute) so each face blends between its corner colors, same palette as iOS.
    val cubeMaterial = remember(materialLoader) {
        createUnlitVertexColorMaterial(context, materialLoader)
    }

    // Build the cube geometry once per engine lifetime. 24 vertices (4 per face) with per-face
    // directional shading baked in so face edges are clearly visible.
    val s = _CUBE_EDGE_METERS / 2f
    val cubeVertexBuffer = remember(engine) { buildCubeVertexBuffer(engine, s) }
    val cubeIndexBuffer = remember(engine) { buildCubeIndexBuffer(engine) }

    val cubeNode = remember(engine, cubeMaterial) {
        MeshNode(
            engine = engine,
            primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
            vertexBuffer = cubeVertexBuffer,
            indexBuffer = cubeIndexBuffer,
            boundingBox = FilamentBox(0f, 0f, 0f, s, s, s),
            materialInstance = cubeMaterial,
        ).apply {
            // Pitch × yaw: yaw 45° turns an edge toward the camera; the additional ≈35.26° pitch
            // tilts the cube so a corner faces the viewer (standard isometric orientation).
            transform = createPitchRotationTransform(_CUBE_PITCH_RADIANS) *
                createYawRotationTransform(_CUBE_YAW_RADIANS)
        }
    }

    val cubeRootNode = remember(engine, cubeNode) {
        PoseNode(engine = engine).apply { addChildNode(cubeNode) }
    }

    val occlusionManager = remember {
        OcclusionManager(nsdkSessionManager = nsdkSessionManager)
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

    // Teardown order matters: destroy the renderable entity (via destroyRecursively) before
    // releasing the geometry buffers it references, to avoid Filament "resource still in use" aborts.
    DisposableEffect(engine, cubeRootNode, cubeMaterial) {
        arChildNodes.add(cubeRootNode)
        onDispose {
            cubeRootNode.destroyRecursively(arChildNodes)
            engine.destroyMaterialInstance(cubeMaterial)
            engine.destroyVertexBuffer(cubeVertexBuffer)
            engine.destroyIndexBuffer(cubeIndexBuffer)
        }
    }

    DisposableEffect(occlusionManager) {
        occlusionManager.start()
        onDispose {
            occlusionManager.onDestroy(lifecycleOwner)
        }
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

    Box(modifier = Modifier.fillMaxSize()) {
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
 * Builds a [VertexBuffer] with POSITION (buffer 0) and COLOR (buffer 1) attributes for a cube
 * using 24 vertices (4 per face). Each face's colors are drawn from the same 8-corner palette as
 * the iOS `setupCube()` — red, green, blue, yellow, magenta, cyan, white, dark-gray — multiplied
 * by a per-face shade factor that simulates a subtle directional light, so face edges read clearly.
 *
 * Light model: direction = normalize(1,1,1), ambient = 0.70, diffuse = 0.30.
 *   Lit faces  (+X, +Y, +Z): shade ≈ 0.873  (ambient + diffuse × dot(normal, lightDir))
 *   Dark faces (−X, −Y, −Z): shade  = 0.700  (dot < 0, clamped to 0)
 *
 * @param engine The Filament [Engine] that owns this buffer.
 * @param halfSize Half of the cube edge length in metres.
 */
private fun buildCubeVertexBuffer(engine: Engine, halfSize: Float): VertexBuffer {
    val s = halfSize
    // Per-face shade multipliers (see KDoc above).
    val litF  = 0.873f
    val darkF = 0.700f

    // 24 vertices: 4 per face, ordered so adjacent faces share the same corner color before
    // shading. Same corner color palette as iOS setupCube(); colors and winding match iOS.
    // Face groups (positions, x,y,z per vertex):
    //   Face 0 front (+Z, litF ): (-s,-s,+s) (+s,-s,+s) (+s,+s,+s) (-s,+s,+s)
    //   Face 1 right (+X, litF ): (+s,-s,+s) (+s,-s,-s) (+s,+s,-s) (+s,+s,+s)
    //   Face 2 back  (-Z, darkF): (+s,-s,-s) (-s,-s,-s) (-s,+s,-s) (+s,+s,-s)
    //   Face 3 left  (-X, darkF): (-s,-s,-s) (-s,-s,+s) (-s,+s,+s) (-s,+s,-s)
    //   Face 4 top   (+Y, litF ): (-s,+s,+s) (+s,+s,+s) (+s,+s,-s) (-s,+s,-s)
    //   Face 5 bot   (-Y, darkF): (-s,-s,-s) (+s,-s,-s) (+s,-s,+s) (-s,-s,+s)
    val positions = floatArrayOf(
        // face 0 — front (+Z)
        -s, -s,  s,   s, -s,  s,   s,  s,  s,  -s,  s,  s,
        // face 1 — right (+X)
         s, -s,  s,   s, -s, -s,   s,  s, -s,   s,  s,  s,
        // face 2 — back (-Z)
         s, -s, -s,  -s, -s, -s,  -s,  s, -s,   s,  s, -s,
        // face 3 — left (-X)
        -s, -s, -s,  -s, -s,  s,  -s,  s,  s,  -s,  s, -s,
        // face 4 — top (+Y)
        -s,  s,  s,   s,  s,  s,   s,  s, -s,  -s,  s, -s,
        // face 5 — bottom (-Y)
        -s, -s, -s,   s, -s, -s,   s, -s,  s,  -s, -s,  s,
    )
    // Per-vertex colors (r,g,b,a), iOS palette × face shade (see KDoc):
    val colors = floatArrayOf(
        // face 0 — front (+Z, litF): red, green, blue, yellow
        litF, 0f,   0f,   1f,   0f,   litF, 0f,   1f,   0f,   0f,   litF, 1f,   litF, litF, 0f,   1f,
        // face 1 — right (+X, litF): green, cyan, white, blue
        0f,   litF, 0f,   1f,   0f,   litF, litF, 1f,   litF, litF, litF, 1f,   0f,   0f,   litF, 1f,
        // face 2 — back (-Z, darkF): cyan, magenta, dark-gray, white
        0f,   darkF, darkF, 1f,   darkF, 0f,    darkF, 1f,
        0.2f*darkF, 0.2f*darkF, 0.2f*darkF, 1f,   darkF, darkF, darkF, 1f,
        // face 3 — left (-X, darkF): magenta, red, yellow, dark-gray
        darkF, 0f,    darkF, 1f,   darkF, 0f,    0f,    1f,   darkF, darkF, 0f,    1f,
        0.2f*darkF, 0.2f*darkF, 0.2f*darkF, 1f,
        // face 4 — top (+Y, litF): yellow, blue, white, dark-gray
        litF, litF, 0f,   1f,   0f,   0f,   litF, 1f,   litF, litF, litF, 1f,
        0.2f*litF,  0.2f*litF,  0.2f*litF,  1f,
        // face 5 — bottom (-Y, darkF): magenta, cyan, green, red
        darkF, 0f,    darkF, 1f,   0f,    darkF, darkF, 1f,   0f,    darkF, 0f,    1f,   darkF, 0f,    0f,    1f,
    )
    val vertexCount = 24

    val posBuffer = ByteBuffer.allocateDirect(vertexCount * 3 * 4).order(ByteOrder.nativeOrder())
    posBuffer.asFloatBuffer().put(positions)
    posBuffer.position(0)

    val colorBuffer = ByteBuffer.allocateDirect(vertexCount * 4 * 4).order(ByteOrder.nativeOrder())
    colorBuffer.asFloatBuffer().put(colors)
    colorBuffer.position(0)

    val vertexBuffer = VertexBuffer.Builder()
        .bufferCount(2)
        .vertexCount(vertexCount)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 3 * 4)
        .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.FLOAT4, 0, 4 * 4)
        .build(engine)

    vertexBuffer.setBufferAt(engine, 0, posBuffer)
    vertexBuffer.setBufferAt(engine, 1, colorBuffer)
    return vertexBuffer
}

/**
 * Builds an [IndexBuffer] for a cube with 36 indices (6 faces × 2 triangles × 3 vertices).
 * References the 24-vertex layout from [buildCubeVertexBuffer] where each face owns vertices
 * [faceIndex*4 .. faceIndex*4+3]. Winding is CCW as seen from outside — correct for Filament's
 * default back-face culling, and consistent with iOS `setupCube()` winding.
 *
 * @param engine The Filament [Engine] that owns this buffer.
 */
private fun buildCubeIndexBuffer(engine: Engine): IndexBuffer {
    // 6 faces × 2 triangles × 3 vertices = 36 indices.
    // Each face's local vertex indices (0–3) map to global indices (faceBase + 0..3).
    val indices = intArrayOf(
         0,  1,  2,   0,  2,  3,  // front  (+Z)
         4,  5,  6,   4,  6,  7,  // right  (+X)
         8,  9, 10,   8, 10, 11,  // back   (-Z)
        12, 13, 14,  12, 14, 15,  // left   (-X)
        16, 17, 18,  16, 18, 19,  // top    (+Y)
        20, 21, 22,  20, 22, 23,  // bottom (-Y)
    )
    val byteBuffer = ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder())
    byteBuffer.asIntBuffer().put(indices)
    byteBuffer.position(0)

    val indexBuffer = IndexBuffer.Builder()
        .indexCount(indices.size)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)

    indexBuffer.setBuffer(engine, byteBuffer)
    return indexBuffer
}

/**
 * Builds a [Mat4] rotation around the X axis by [angleRadians] (pitch: positive tilts +Y toward +Z).
 */
private fun createPitchRotationTransform(angleRadians: Float): Mat4 {
    val c = cos(angleRadians)
    val s = sin(angleRadians)
    return Mat4(
        Float4(1f, 0f, 0f, 0f),
        Float4(0f,  c,  s, 0f),
        Float4(0f, -s,  c, 0f),
        Float4(0f, 0f, 0f, 1f)
    )
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

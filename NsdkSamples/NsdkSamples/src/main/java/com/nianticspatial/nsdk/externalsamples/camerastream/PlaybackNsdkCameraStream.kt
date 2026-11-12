// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.camerastream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Size
import androidx.core.graphics.times
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.nianticspatial.nsdk.DepthBuffer
import com.nianticspatial.nsdk.NSDKResult
import com.nianticspatial.nsdk.NsdkFrame
import com.nianticspatial.nsdk.Orientation
import com.nianticspatial.nsdk.playback.PlaybackFrame
import com.nianticspatial.nsdk.utils.ImageMath
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.OnFrameUpdateListener
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.GeometryNode
import io.github.sceneview.node.Node
import io.github.sceneview.safeDestroyTexture
import com.nianticspatial.nsdk.externalsamples.destroyRecursively
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan

private const val _CAMERA_MATERIAL = "materials/camera_stream_playback_depth.filamat"

// Identity mat3 used to initialise the camera UV transform before the first real frame arrives.
private val _IDENTITY_MAT3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

/**
 * Playback-mode camera stream.
 *
 * Owns a full-screen [GeometryNode] (device-domain quad, render priority 0) that renders the
 * JPEG camera background and NSDK depth through Filament. This gives playback mode a shared
 * depth buffer with scene objects, enabling depth occlusion without a separate prepass node.
 *
 * Depth writing is driven by a frame listener that self-manages the [depthActive] material
 * parameter — it activates when NSDK depth data flows and deactivates when it stops.
 *
 * Lifecycle: add [backgroundNode] to [arChildNodes] via a [DisposableEffect] in [ARSceneView]
 * and call [destroy] when the stream is no longer needed.
 *
 * @param engine Filament engine used to create GPU resources.
 * @param materialLoader SceneView loader used to create the background material.
 * @param nsdkSessionManager Provides depth session and frame update listener.
 */
internal class PlaybackNsdkCameraStream(
    private val engine: Engine,
    private val materialLoader: MaterialLoader,
    private val nsdkSessionManager: NSDKSessionManager? = null,
) {

    /** Updated by [ARSceneView] each frame before [update]. */
    var deviceOrientation: Orientation = Orientation.PORTRAIT

    /** Updated by [ARSceneView] via [onSizeChanged] so the camera UV transform is correct. */
    var viewportSize: Size? = null

    private val _defaultMaterial = materialLoader.createMaterial(_CAMERA_MATERIAL)
    private val _defaultMaterialInstance: MaterialInstance = _defaultMaterial.createInstance().also { mi ->
        mi.setParameter("cameraUvTransform", MaterialInstance.FloatElement.MAT3, _IDENTITY_MAT3, 0, 1)
        mi.setParameter("depthUvTransform",  MaterialInstance.FloatElement.MAT3, _IDENTITY_MAT3, 0, 1)
    }
    private var _activeMaterialInstance: MaterialInstance = _defaultMaterialInstance

    private var _cameraTexture: Texture? = null
    private val _cameraSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE
    )
    private val _cameraUvMatrix = FloatArray(9)
    private val _cameraInverseMatrix = Matrix()

    // Reused across frames to avoid per-frame direct memory allocation.
    private var _cameraByteBuffer: ByteBuffer? = null
    // Scratch bitmap for HARDWARE/non-ARGB_8888 conversion. Reallocated only on dimension changes.
    private var _scratchBitmap: Bitmap? = null

    // ── Depth texture ──────────────────────────────────────────────────────────────────────────

    private var _depthTexture: Texture? = null
    private val _depthTextureSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE
    )
    private val _depthUvMatrix = FloatArray(9)
    private val _depthUvTransformMatrix = Matrix()
    private val _referenceView = FloatArray(16)
    private val _targetView = FloatArray(16)
    private var _lastDepthTimestamp: Long = -1L
    private var _depthByteBuffer: ByteBuffer? = null

    // ── Depth session ─────────────────────────────────────────────────────────────────────────
    //
    // Owns one acquire() of the depth session so it stays alive for the lifetime of playback mode.
    // SessionBase is ref-counted, so DepthManager can safely acquire/close its own ref independently.

    private var _ownedDepthSession: com.nianticspatial.nsdk.depth.DepthSession? = null

    private val _depthFrameListener = object : OnFrameUpdateListener {
        override fun onFrameUpdate(frame: NsdkFrame) {
            // Guard: only read when the session is alive (acquired + started).
            // Calling latestDepth() on an unacquired session can trigger native initialization
            // as a side effect. _ownedDepthSession keeps it alive once startDepth() is called.
            val depthSession = _ownedDepthSession
            if (depthSession == null || !depthSession.isAlive) return
            val depthBuffer = (depthSession.latestDepth() as? NSDKResult.Success)?.value
            if (depthBuffer == null) {
                _activeMaterialInstance.setParameter("depthActive", false)
                return
            }
            _activeMaterialInstance.setParameter("depthActive", true)

            val vp = viewportSize ?: return
            if (depthBuffer.timestampMs != _lastDepthTimestamp) {
                _uploadDepthTexture(depthBuffer)
                _lastDepthTimestamp = depthBuffer.timestampMs
            }
            _updateDepthUvTransform(depthBuffer, frame, vp)
        }
    }

    init {
        // Acquire one ref so the session stays alive for latestDepth() reads.
        // Do NOT call start() here — that is DepthManager's responsibility. Calling start()
        // twice crashes the native module.
        val depthSession = nsdkSessionManager?.session?.depthSession?.acquire()
        if (depthSession != null) {
            _ownedDepthSession = depthSession
            nsdkSessionManager!!.arManager.addFrameUpdateListener(_depthFrameListener)
        }
    }


    /**
     * Background node. Add to [arChildNodes] via a [DisposableEffect] in [ARSceneView].
     * Rendered at priority 0 — before all scene objects — so the depth buffer is populated
     * before the cube (or any other virtual geometry) renders.
     */
    val backgroundNode: GeometryNode = _createBackgroundNode()

    /**
     * Called by [ARSceneView] each frame to upload the current JPEG and recompute the camera
     * UV transform. This drives the visible background independently of [OcclusionManager].
     */
    fun update(frame: PlaybackFrame?, orientation: Orientation) {
        deviceOrientation = orientation
        val vp = viewportSize ?: return
        val sourceBitmap = frame?.image ?: return

        val width = sourceBitmap.width
        val height = sourceBitmap.height

        // HARDWARE-config bitmaps cannot be read by the CPU via copyPixelsToBuffer. Non-ARGB_8888
        // configs also produce wrong byte layout for RGBA/UBYTE upload. Use a Canvas drawBitmap
        // rather than Bitmap.copy() — Android handles the GPU→CPU readback internally and we
        // reuse the scratch bitmap across frames (reallocating only on dimension changes).
        val bitmap: Bitmap = if (sourceBitmap.config == Bitmap.Config.HARDWARE ||
            sourceBitmap.config != Bitmap.Config.ARGB_8888) {
            val scratch = _scratchBitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    _scratchBitmap?.recycle()
                    _scratchBitmap = it
                }
            Canvas(scratch).drawBitmap(sourceBitmap, 0f, 0f, null)
            scratch
        } else {
            sourceBitmap
        }

        // Recreate the Filament texture if this is the first frame or dimensions changed.
        if (_cameraTexture == null ||
            _cameraTexture?.getWidth(0) != width ||
            _cameraTexture?.getHeight(0) != height
        ) {
            _cameraTexture?.let { engine.safeDestroyTexture(it) }
            _cameraTexture = Texture.Builder()
                .width(width).height(height)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8)
                .levels(1)
                .build(engine)
            _activeMaterialInstance.setParameter("cameraTexture", _cameraTexture!!, _cameraSampler)
        }

        // Reuse the ByteBuffer across frames; reallocate only when the required size grows.
        val requiredBytes = bitmap.byteCount
        if (_cameraByteBuffer == null || _cameraByteBuffer!!.capacity() < requiredBytes) {
            _cameraByteBuffer = ByteBuffer.allocateDirect(requiredBytes)
        }
        val byteBuffer = _cameraByteBuffer!!
        byteBuffer.clear()
        bitmap.copyPixelsToBuffer(byteBuffer)
        byteBuffer.rewind()
        _cameraTexture?.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(byteBuffer, Texture.Format.RGBA, Texture.Type.UBYTE)
        )

        // Compute and push the camera UV transform so the JPEG is correctly oriented on screen.
        // imageOrientationOverride tells displayTransform the orientation in which this frame was
        // recorded, so it can derive the correct rotation to the current display orientation.
        // Without it the function has no way to know the image's intrinsic orientation and the
        // background squishes on device rotation.
        // frame is guaranteed non-null here — if it were null, sourceBitmap above would be null
        // and we'd have returned early. Use !! to make this invariant explicit.
        val recordedOrientation = frame!!.camera.orientation
            .takeIf { it != Orientation.UNKNOWN }
        val forward = ImageMath.displayTransform(
            orientation = deviceOrientation,
            viewportSize = vp,
            imageSize = Size(width, height),
            imageOrientationOverride = recordedOrientation
        ) * ImageMath.affineInvertVertical()
        forward.invert(_cameraInverseMatrix)
        _cameraInverseMatrix.getValues(_cameraUvMatrix)

        _activeMaterialInstance.setParameter("cameraUvTransform", MaterialInstance.FloatElement.MAT3, _cameraUvMatrix, 0, 1)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────────

    /**
     * Releases all Filament resources. Call from [ARSceneView]'s [DisposableEffect] onDispose.
     * [nodes] is [arChildNodes] — the node is removed and destroyed by [destroyRecursively].
     */
    fun destroy(nodes: MutableCollection<Node>) {
        nsdkSessionManager?.arManager?.removeFrameUpdateListener(_depthFrameListener)
        _ownedDepthSession?.close()
        _ownedDepthSession = null
        backgroundNode.destroyRecursively(nodes)
        // _defaultMaterialInstance was created via createInstance() — must be destroyed explicitly
        // before destroyMaterial(), which only handles the material's own defaultInstance.
        engine.destroyMaterialInstance(_defaultMaterialInstance)
        materialLoader.destroyMaterial(_defaultMaterial)
        _cameraTexture?.let { engine.safeDestroyTexture(it) }
        _cameraTexture = null
        _depthTexture?.let { engine.safeDestroyTexture(it) }
        _depthTexture = null
        _depthByteBuffer = null
        _scratchBitmap?.recycle()
        _scratchBitmap = null
        _cameraByteBuffer = null
    }

    // ── Private ───────────────────────────────────────────────────────────────────────────────

    private fun _uploadDepthTexture(depthBuffer: DepthBuffer) {
        try {
            val width = depthBuffer.imageWidth
            val height = depthBuffer.imageHeight
            if (_depthTexture == null ||
                _depthTexture?.getWidth(0) != width ||
                _depthTexture?.getHeight(0) != height) {
                _depthTexture?.let { engine.safeDestroyTexture(it) }
                _depthTexture = Texture.Builder()
                    .width(width).height(height)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    // R16F, not R32F: R32F can't be linearly filtered without
                    // OES_texture_float_linear (missing on e.g. Samsung Xclipse) and samples as 0.
                    .format(Texture.InternalFormat.R16F)
                    .levels(1)
                    .build(engine)
                _activeMaterialInstance.setParameter("depthTexture", _depthTexture!!, _depthTextureSampler)
            }
            val requiredBytes = depthBuffer.image.size * Float.SIZE_BYTES
            if (_depthByteBuffer == null || _depthByteBuffer!!.capacity() < requiredBytes) {
                _depthByteBuffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
            }
            val byteBuffer = _depthByteBuffer!!
            byteBuffer.clear()
            byteBuffer.asFloatBuffer().put(depthBuffer.image)
            byteBuffer.rewind()
            _depthTexture?.setImage(engine, 0,
                Texture.PixelBufferDescriptor(byteBuffer, Texture.Format.R, Texture.Type.FLOAT))
        } catch (e: Exception) {
            android.util.Log.e("PlaybackNsdkCameraStream", "Failed to upload depth texture: ${e.message}", e)
        }
    }

    private fun _updateDepthUvTransform(depthBuffer: DepthBuffer, arFrame: NsdkFrame, vp: Size) {
        try {
            val imageSize = depthBuffer.intrinsics.getImageDimensions()
            val display = ImageMath.displayTransform(
                orientation = nsdkSessionManager!!.currentImageOrientation,
                viewportSize = vp,
                imageSize = Size(imageSize[0], imageSize[1])
            ) * ImageMath.affineInvertVertical()
            val reference = depthBuffer.pose.inverse()
            val target = arFrame.camera.pose.inverse()
            reference.toMatrix(_referenceView, 0)
            target.toMatrix(_targetView, 0)
            val focalLengthY = depthBuffer.intrinsics.getFocalLength()[1]
            val reprojection = ImageMath.reprojection(
                aspect = imageSize[0].toFloat() / imageSize[1].toFloat(),
                fovRadians = 2f * atan(imageSize[1].toFloat() / (2f * focalLengthY)),
                zNear = 0.2f,
                zFar  = 100f,
                referenceView = _referenceView,
                targetView = _targetView
            )
            (display * reprojection).invert(_depthUvTransformMatrix)
            _depthUvTransformMatrix.getValues(_depthUvMatrix)
            _activeMaterialInstance.setParameter("depthUvTransform", MaterialInstance.FloatElement.MAT3, _depthUvMatrix, 0, 1)
        } catch (e: Exception) {
            android.util.Log.e("PlaybackNsdkCameraStream", "Failed to update depth UV transform: ${e.message}", e)
        }
    }

    private fun _createBackgroundNode(): GeometryNode {
        val geometry = Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
            .vertices(listOf(
                Geometry.Vertex(position = Position(-1f, -1f, 0f)),
                Geometry.Vertex(position = Position( 1f, -1f, 0f)),
                Geometry.Vertex(position = Position( 1f,  1f, 0f)),
                Geometry.Vertex(position = Position(-1f,  1f, 0f))
            ))
            .primitivesIndices(listOf(listOf(0, 1, 2, 2, 3, 0)))
            .build(engine)
        return GeometryNode(
            engine = engine,
            geometry = geometry,
            materialInstance = _defaultMaterialInstance,
            builderApply = {
                priority(0)
                culling(false)
                castShadows(false)
                receiveShadows(false)
            }
        )
    }
}

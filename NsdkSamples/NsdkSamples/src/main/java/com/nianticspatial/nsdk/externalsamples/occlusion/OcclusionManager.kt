// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.occlusion

import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.core.graphics.times
import androidx.lifecycle.LifecycleOwner
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.nianticspatial.nsdk.DepthBuffer
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import com.nianticspatial.nsdk.externalsamples.OnFrameUpdateListener
import com.nianticspatial.nsdk.externalsamples.depth.DepthManager
import com.nianticspatial.nsdk.utils.ImageMath
import io.github.sceneview.safeDestroyTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan
import android.content.Context
import com.google.android.filament.Material
import com.nianticspatial.nsdk.NsdkFrame
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.camera.kCameraTextureParameter
import io.github.sceneview.ar.camera.kUVTransformParameter
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setExternalTexture
import io.github.sceneview.material.setParameter
import io.github.sceneview.math.Transform

private const val _DEPTH_MATERIAL = "materials/camera_stream_depth_nsdk.filamat"
private const val _TAG = "OcclusionManager"

/**
 * Manages per-frame global depth occlusion using NSDK depth data.
 *
 * On construction, loads [camera_stream_depth_nsdk.filamat] from assets, applies it to
 * [cameraStream], creates an owned [DepthManager], and registers itself as a frame update
 * listener on [nsdkSessionManager.arManager]. Call [start] after construction to begin
 * depth polling. Call [onDestroy] to stop depth polling, unregister the frame listener,
 * restore the original [cameraStream] material, and release all Filament objects.
 *
 * Two per-frame corrections are applied:
 * ...
 *
 * @param engine Filament engine used to create and destroy GPU resources.
 * @param nsdkSessionManager Created in NSDKDemoView, passed to OcclusionView as a Compose
 *                           function parameter, and forwarded here as a constructor argument.
 *                           Neither this class nor the view owns it.
 * @param context Android context used to load the depth material from assets.
 *                Must be supplied by the caller — this value is a CompositionLocal
 *                ([LocalContext]) and cannot be accessed outside the Compose tree.
 * @param materialLoader SceneView loader used to create and destroy the depth material.
 *                       Must be supplied by the caller — this value is a CompositionLocal
 *                       ([LocalSceneMaterialLoader]) and cannot be accessed outside the
 *                       Compose tree.
 * @param cameraStream Camera background stream that receives the depth material on init
 *                     and has its original material restored on destroy.
 *                     Must be supplied by the caller — this value is a CompositionLocal
 *                     ([LocalARCameraStream]) and cannot be accessed outside the
 *                     Compose tree.
 */
class OcclusionManager(
    private val engine: Engine,
    private val nsdkSessionManager: NSDKSessionManager,
    private val context: Context,
    private val materialLoader: MaterialLoader,
    private val cameraStream: ARCameraStream?,
): FeatureManager(), OnFrameUpdateListener {

    private val _depthManager = DepthManager(nsdkSessionManager.session.depthSession.acquire())
    private var materialInstance: MaterialInstance? = null
    private var _nsdkMaterial: Material? = null
    private var _originalMaterialInstances: List<MaterialInstance>? = null
    private var _depthTexture: Texture? = null
    private var _lastDepthTimestamp: Long = -1L
    private val _referenceView: FloatArray = FloatArray(16)
    private val _targetView: FloatArray = FloatArray(16)
    private val _uvTransformMatrix: FloatArray = FloatArray(9)
    private val _depthTextureSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE
    )

    /** Current viewport dimensions. Must be set before depth UV transform is computed. */
    var viewportSize: Size? = null

    init {
        nsdkSessionManager.arManager.addFrameUpdateListener(this)
        cameraStream?.let { stream ->
            _originalMaterialInstances = stream.materialInstances
            val buffer = context.assets.open(_DEPTH_MATERIAL).use {
                ByteBuffer.wrap(it.readBytes())
            }
            _nsdkMaterial = materialLoader.createMaterial(buffer).apply {
                defaultInstance.apply {
                    setParameter(kUVTransformParameter, Transform())
                    setExternalTexture(kCameraTextureParameter, stream.cameraTexture)
                }
            }
            stream.setMaterialInstances(_nsdkMaterial!!.defaultInstance)
            materialInstance = _nsdkMaterial!!.defaultInstance
        }
    }

    /**
     * Starts NSDK depth polling. Must be called from a post-composition side effect
     * (e.g. [DisposableEffect]) rather than at construction time. Compose snapshot
     * writes made during [remember] are not committed to the global snapshot until
     * after composition ends — calling this during construction risks the poll
     * coroutine reading a stale [isRunning] value and exiting immediately.
     */
    fun start() {
        _depthManager.startDepth()
    }

    /**
     * Two per-frame corrections are applied:
     * - Display orientation: rotates/flips the sensor-space depth image to match the current
     *   display orientation and viewport aspect ratio.
     * - Temporal reprojection: compensates for pose drift between the async depth capture
     *   and the current AR frame so occlusion edges stay correctly placed under motion.
     */
    override fun onFrameUpdate(frame: NsdkFrame) {
        val mi = materialInstance ?: return
        val depthBuffer = _depthManager.currentDepthBuffer ?: return
        val currentViewportSize = viewportSize ?: return

        if (depthBuffer.timestampMs != _lastDepthTimestamp) {
            _updateDepthTexture(depthBuffer, mi)
            _lastDepthTimestamp = depthBuffer.timestampMs
        }
        _updateUvTransform(depthBuffer, frame, currentViewportSize, mi)
    }

    /**
     * Allocates or reuses a Filament R32F texture and uploads the latest depth
     * image from [depthBuffer]. Recreates the texture if dimensions have changed.
     */
    private fun _updateDepthTexture(depthBuffer: DepthBuffer, mi: MaterialInstance) {
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
                    .format(Texture.InternalFormat.R32F)
                    .levels(1)
                    .build(engine)
                mi.setParameter("depthTexture", _depthTexture!!, _depthTextureSampler)
            }
            val byteBuffer = ByteBuffer.allocateDirect(depthBuffer.image.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            byteBuffer.asFloatBuffer().put(depthBuffer.image)
            byteBuffer.rewind()
            _depthTexture?.setImage(engine, 0,
                Texture.PixelBufferDescriptor(byteBuffer, Texture.Format.R, Texture.Type.FLOAT))
        } catch (e: Exception) {
            Log.e(_TAG, "Failed to update depth texture: ${e.message}", e)
        }
    }

    /**
     * Computes the combined display + reprojection UV transform and writes it
     * to the [MaterialInstance] as a mat3 parameter.
     *
     * Two corrections are applied each frame:
     * - Display orientation: the NSDK depth image is in sensor orientation and must
     *   be rotated/flipped to match the current display orientation and viewport aspect ratio.
     * - Temporal reprojection: NSDK depth runs asynchronously and lags behind the
     *   AR frame. The depth was captured from a slightly different camera pose, so
     *   each screen pixel must be remapped to where it fell in the depth image at
     *   capture time. Without this, fast motion causes depth to drift against the scene.
     */
    private fun _updateUvTransform(depthBuffer: DepthBuffer, arFrame: NsdkFrame, viewportSize: Size, mi: MaterialInstance) {
        try {
            val imageSize = depthBuffer.intrinsics.getImageDimensions()
            val display = ImageMath.displayTransform(
                orientation = nsdkSessionManager.currentImageOrientation,
                viewportSize = viewportSize,
                imageSize = Size(imageSize[0], imageSize[1])
            ) * ImageMath.affineInvertVertical()
            val reprojection = _calculateReprojection(depthBuffer, arFrame)
            val uvTransform = Matrix()
            (display * reprojection).invert(uvTransform)
            uvTransform.getValues(_uvTransformMatrix)
            mi.setParameter("depthUvTransform", MaterialInstance.FloatElement.MAT3, _uvTransformMatrix, 0, 1)
        } catch (e: Exception) {
            Log.e(_TAG, "Failed to update UV transform: ${e.message}", e)
        }
    }

    /**
     * Computes the perspective reprojection matrix from the depth capture pose
     * to the current camera pose, accounting for the depth intrinsics.
     *
     * Because NSDK depth is captured asynchronously, [depthBuffer.pose] and the
     * current [arFrame] camera pose will differ whenever the device has moved since
     * the last depth capture. This matrix re-aligns depth samples to the current
     * viewpoint so occlusion edges remain correctly placed under motion.
     */
    private fun _calculateReprojection(depthBuffer: DepthBuffer, arFrame: NsdkFrame): Matrix {
        val reference = depthBuffer.pose.inverse()
        val target = arFrame.camera.pose.inverse()
        reference.toMatrix(_referenceView, 0)
        target.toMatrix(_targetView, 0)
        val imageSize = depthBuffer.intrinsics.getImageDimensions()
        val focalLengthY = depthBuffer.intrinsics.getFocalLength()[1]
        return ImageMath.reprojection(
            aspect = imageSize[0].toFloat() / imageSize[1].toFloat(),
            fovRadians = 2f * atan(imageSize[1].toFloat() / (2f * focalLengthY)),
            zNear = 0.2f,
            zFar = 100f,
            referenceView = _referenceView,
            targetView = _targetView
        )
    }

    /** Releases the depth [Texture] allocated on the GPU. */
    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        nsdkSessionManager.arManager.removeFrameUpdateListener(this)
        _depthManager.onDestroy(owner)
        materialInstance = null
        _originalMaterialInstances?.firstOrNull()?.let { cameraStream?.setMaterialInstances(it) }
        _nsdkMaterial?.let { materialLoader.destroyMaterial(it) }
        _nsdkMaterial = null
        _depthTexture?.let { engine.safeDestroyTexture(it) }
        _depthTexture = null
    }

}

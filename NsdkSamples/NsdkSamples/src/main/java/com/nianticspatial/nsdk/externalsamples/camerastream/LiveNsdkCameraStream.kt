// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.camerastream

import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.core.graphics.times
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.nianticspatial.nsdk.DepthBuffer
import com.nianticspatial.nsdk.NSDKResult
import com.nianticspatial.nsdk.NsdkFrame
import com.nianticspatial.nsdk.depth.DepthSession
import com.nianticspatial.nsdk.utils.ImageMath
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.OnFrameUpdateListener
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.camera.kCameraTextureParameter
import io.github.sceneview.ar.camera.kUVTransformParameter
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setExternalTexture
import io.github.sceneview.material.setParameter
import io.github.sceneview.math.Transform
import io.github.sceneview.safeDestroyTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan

private const val _DEPTH_MATERIAL = "materials/camera_stream_live_depth.filamat"
private const val _TAG = "LiveNsdkCameraStream"

/**
 * Live-mode camera stream wrapping SceneView's [ARCameraStream].
 *
 * On construction, applies the depth material to [ARCameraStream], acquires a depth session ref,
 * and registers a frame listener. The material's [depthActive] parameter is driven by the frame
 * listener — it activates automatically when NSDK depth data starts flowing (i.e. when a
 * [DepthManager] calls [DepthSession.start]) and deactivates when data stops. Callers do not
 * need to call [startDepth] or [stopDepth]. Call [destroy] when the stream is no longer needed.
 *
 * @param arCameraStream SceneView's hardware camera background stream.
 * @param engine Filament engine; required for depth texture creation.
 * @param materialLoader Required for depth material loading.
 * @param nsdkSessionManager Provides depth session and orientation; required for depth driving.
 */
internal class LiveNsdkCameraStream(
    private val arCameraStream: ARCameraStream,
    private val engine: Engine? = null,
    private val materialLoader: MaterialLoader? = null,
    private val nsdkSessionManager: NSDKSessionManager? = null,
) {

    /** Updated by [ARSceneView] via [onSizeChanged]. */
    var viewportSize: Size? = null

    private var _depthMaterial: Material? = null
    private var _depthMaterialInstance: MaterialInstance? = null
    private var _originalMaterialInstance: MaterialInstance? = null
    private var _ownedDepthSession: DepthSession? = null
    private var _depthTexture: Texture? = null
    private var _depthByteBuffer: ByteBuffer? = null
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

    private val _depthFrameListener = object : OnFrameUpdateListener {
        override fun onFrameUpdate(frame: NsdkFrame) {
            val mi = _depthMaterialInstance ?: return
            val ownedSession = _ownedDepthSession
            if (ownedSession == null || !ownedSession.isAlive) return
            val depthBuffer = (ownedSession.latestDepth() as? NSDKResult.Success)?.value
            if (depthBuffer == null) {
                mi.setParameter("depthActive", false)
                return
            }
            mi.setParameter("depthActive", true)
            val vp = viewportSize ?: return
            if (depthBuffer.timestampMs != _lastDepthTimestamp) {
                _uploadDepthTexture(depthBuffer, mi)
                _lastDepthTimestamp = depthBuffer.timestampMs
            }
            _updateDepthUvTransform(depthBuffer, frame, vp, mi)
        }
    }

    init {
        val ml = materialLoader
        val eng = engine
        val nsdk = nsdkSessionManager
        if (ml != null && eng != null && nsdk != null) {
            _originalMaterialInstance = arCameraStream.materialInstances.firstOrNull()
            _depthMaterial = ml.createMaterial(_DEPTH_MATERIAL)
            _depthMaterialInstance = _depthMaterial!!.defaultInstance.also { mi ->
                mi.setParameter(kUVTransformParameter, Transform())
                mi.setExternalTexture(kCameraTextureParameter, arCameraStream.cameraTexture)
            }
            arCameraStream.setMaterialInstances(_depthMaterialInstance!!)
            _ownedDepthSession = nsdk.session.depthSession.acquire()
            nsdk.arManager.addFrameUpdateListener(_depthFrameListener)
        }
    }

    // ── Public lifecycle ──────────────────────────────────────────────────────────────────────

    /**
     * Removes the frame listener, closes the depth session ref, restores the original
     * [ARCameraStream] material, and releases depth GPU resources.
     * Call from [ARSceneView]'s [DisposableEffect] when the live stream is discarded.
     */
    fun destroy() {
        nsdkSessionManager?.arManager?.removeFrameUpdateListener(_depthFrameListener)
        _ownedDepthSession?.close()
        _ownedDepthSession = null
        _originalMaterialInstance?.let { arCameraStream.setMaterialInstances(it) }
        _originalMaterialInstance = null
        _depthMaterial?.let { materialLoader?.destroyMaterial(it) }
        _depthMaterial = null
        _depthMaterialInstance = null
        _depthTexture?.let { engine?.safeDestroyTexture(it) }
        _depthTexture = null
        _depthByteBuffer = null
    }

    // ── Private ───────────────────────────────────────────────────────────────────────────────

    private fun _uploadDepthTexture(depthBuffer: DepthBuffer, mi: MaterialInstance) {
        try {
            val eng = engine ?: return
            val width = depthBuffer.imageWidth
            val height = depthBuffer.imageHeight
            if (_depthTexture == null ||
                _depthTexture?.getWidth(0) != width ||
                _depthTexture?.getHeight(0) != height) {
                _depthTexture?.let { eng.safeDestroyTexture(it) }
                _depthTexture = Texture.Builder()
                    .width(width).height(height)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    // R16F, not R32F: R32F can't be linearly filtered without
                    // OES_texture_float_linear (missing on e.g. Samsung Xclipse) and samples as 0.
                    .format(Texture.InternalFormat.R16F)
                    .levels(1)
                    .build(eng)
                mi.setParameter("depthTexture", _depthTexture!!, _depthTextureSampler)
            }
            val requiredBytes = depthBuffer.image.size * Float.SIZE_BYTES
            if (_depthByteBuffer == null || _depthByteBuffer!!.capacity() < requiredBytes) {
                _depthByteBuffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
            }
            val byteBuffer = _depthByteBuffer!!
            byteBuffer.clear()
            byteBuffer.asFloatBuffer().put(depthBuffer.image)
            byteBuffer.rewind()
            _depthTexture?.setImage(eng, 0,
                Texture.PixelBufferDescriptor(byteBuffer, Texture.Format.R, Texture.Type.FLOAT))
        } catch (e: Exception) {
            Log.e(_TAG, "Failed to upload depth texture: ${e.message}", e)
        }
    }

    private fun _updateDepthUvTransform(depthBuffer: DepthBuffer, arFrame: NsdkFrame, vp: Size, mi: MaterialInstance) {
        try {
            val nsdk = nsdkSessionManager ?: return
            val imageSize = depthBuffer.intrinsics.getImageDimensions()
            val display = ImageMath.displayTransform(
                orientation = nsdk.currentImageOrientation,
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
            mi.setParameter("depthUvTransform", MaterialInstance.FloatElement.MAT3, _depthUvMatrix, 0, 1)
        } catch (e: Exception) {
            Log.e(_TAG, "Failed to update depth UV transform: ${e.message}", e)
        }
    }
}

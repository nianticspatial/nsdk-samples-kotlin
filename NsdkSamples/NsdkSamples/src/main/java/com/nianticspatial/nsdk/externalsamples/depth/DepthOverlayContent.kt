// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.depth

import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.core.graphics.times
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.nianticspatial.nsdk.DepthBuffer
import com.nianticspatial.nsdk.NsdkFrame
import com.nianticspatial.nsdk.utils.ImageMath
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.common.OverlayContent
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.safeDestroyTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan

/**
 * Content provider for depth visualization overlay rendering using ScreenOverlayView.
 * Uses the depth_visualization material to render depth data as a color-coded overlay with reprojection.
 */
class DepthOverlayContent(
    private val depthManager: DepthManager,
    private val sessionManager: NSDKSessionManager
) : OverlayContent() {
    companion object {
        private const val TAG = "DepthOverlayContent"
        private const val DEFAULT_ALPHA = 0.7f
        private const val DEFAULT_MIN_DISTANCE = 0.0f
        private const val DEFAULT_MAX_DISTANCE = 8.0f
    }

    private var materialInstance: MaterialInstance? = null
    private var depthTexture: Texture? = null
    private var engine: Engine? = null

    // Material parameters
    private var alpha = DEFAULT_ALPHA
    private var minDistance = DEFAULT_MIN_DISTANCE
    private var maxDistance = DEFAULT_MAX_DISTANCE

    private var lastDepthBufferTimestamp: Long = -1L

    // Reprojection helpers
    private val referenceView: FloatArray = FloatArray(16)
    private val targetView: FloatArray = FloatArray(16)
    private val uvTransformMatrix: FloatArray = FloatArray(9)

    override fun getUVCoordinates(rotation: Int): FloatArray {
        return floatArrayOf(0f, 1f,  1f, 1f,  1f, 0f,  0f, 0f)
    }

    /**
     * Set the overlay alpha transparency.
     * @param alpha Alpha component (0.0 to 1.0)
     */
    fun setAlpha(alpha: Float) {
        this.alpha = alpha.coerceIn(0f, 1f)
        materialInstance?.setParameter("u_Alpha", this.alpha)
    }

    override fun onCreateMaterial(engine: Engine, materialLoader: MaterialLoader): MaterialInstance {
        this.engine = engine

        val material = materialLoader.createMaterial("materials/depth_visualization.filamat")
        val instance = material.createInstance()

        // Set initial material parameters
        instance.setParameter("u_Alpha", alpha)
        instance.setParameter("_MinDistance", minDistance)
        instance.setParameter("_MaxDistance", maxDistance)

        // Set initial UV transform (identity matrix)
        val identityMatrix = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        instance.setParameter("u_UvTransform", MaterialInstance.FloatElement.MAT3, identityMatrix, 0, 1)

        materialInstance = instance
        return instance
    }

    override fun onFrame(engine: Engine, materialInstance: MaterialInstance): Boolean {
        val depthBuffer = depthManager.currentDepthBuffer ?: return false
        val currentFrame = sessionManager.currentFrame ?: return false
        val currentViewportSize = viewportSize ?: return false

        // Check if we have a new depth frame
        val currentTimestamp = depthBuffer.timestampMs
        val needsTextureUpdate = currentTimestamp != lastDepthBufferTimestamp

        if (needsTextureUpdate) {
            updateDepthTexture(engine, depthBuffer, materialInstance)
            lastDepthBufferTimestamp = currentTimestamp
        }

        // Calculate and update UV transform for reprojection
        updateUvTransform(depthBuffer, currentFrame, currentViewportSize, materialInstance)

        return depthTexture != null
    }

    private fun updateUvTransform(
        depthBuffer: DepthBuffer,
        arFrame: NsdkFrame,
        viewportSize: Size,
        materialInstance: MaterialInstance
    ) {
        try {
            val imageSize = depthBuffer.intrinsics.getImageDimensions()
            val display = ImageMath.displayTransform(
                orientation = sessionManager.currentImageOrientation,
                viewportSize = viewportSize,
                imageSize = Size(imageSize[0], imageSize[1])
            ) * ImageMath.affineInvertVertical()

            val reprojection = calculateReprojection(depthBuffer, arFrame)

            val uvTransform = Matrix()
            (display * reprojection).invert(uvTransform)

            uvTransform.getValues(uvTransformMatrix)

            materialInstance.setParameter(
                "u_UvTransform",
                MaterialInstance.FloatElement.MAT3,
                uvTransformMatrix,
                0,
                1
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update UV transform: ${e.message}", e)
        }
    }

    private fun calculateReprojection(depthBuffer: DepthBuffer, arFrame: NsdkFrame): Matrix {
        val reference = depthBuffer.pose.inverse()
        val target = arFrame.camera.pose.inverse()
        reference.toMatrix(referenceView, 0)
        target.toMatrix(targetView, 0)

        val imageSize = depthBuffer.intrinsics.getImageDimensions()
        val focalLengthY = depthBuffer.intrinsics.getFocalLength()[1] // fy
        return ImageMath.reprojection(
            aspect = imageSize[0].toFloat() / imageSize[1].toFloat(),
            fovRadians = 2f * atan(imageSize[1].toFloat() / (2f * focalLengthY)),
            zNear = 0.2f,
            zFar = 100f,
            referenceView = referenceView,
            targetView = targetView
        )
    }

    private fun updateDepthTexture(engine: Engine, depthBuffer: DepthBuffer, materialInstance: MaterialInstance) {
        try {
            val width = depthBuffer.imageWidth
            val height = depthBuffer.imageHeight

            if (depthTexture == null ||
                depthTexture?.getWidth(0) != width ||
                depthTexture?.getHeight(0) != height) {

                depthTexture?.let { engine.safeDestroyTexture(it) }
                depthTexture = Texture.Builder()
                    .width(width)
                    .height(height)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .format(Texture.InternalFormat.R32F)
                    .levels(1)
                    .build(engine)

                materialInstance.setParameter(
                    "s_Texture",
                    depthTexture!!,
                    TextureSampler(
                        TextureSampler.MinFilter.LINEAR,
                        TextureSampler.MagFilter.LINEAR,
                        TextureSampler.WrapMode.CLAMP_TO_EDGE
                    )
                )

                Log.d(TAG, "Created depth texture: ${width}x${height}")
            }

            val byteBuffer = ByteBuffer.allocateDirect(depthBuffer.image.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())

            byteBuffer.asFloatBuffer().put(depthBuffer.image)
            byteBuffer.rewind()

            depthTexture?.setImage(
                engine,
                0,
                Texture.PixelBufferDescriptor(
                    byteBuffer,
                    Texture.Format.R,
                    Texture.Type.FLOAT
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update depth texture: ${e.message}", e)
        }
    }

    override fun onDestroy(engine: Engine) {
        // Clean up texture
        depthTexture?.let { engine.safeDestroyTexture(it) }
        depthTexture = null
        materialInstance = null
        this.engine = null
    }
}

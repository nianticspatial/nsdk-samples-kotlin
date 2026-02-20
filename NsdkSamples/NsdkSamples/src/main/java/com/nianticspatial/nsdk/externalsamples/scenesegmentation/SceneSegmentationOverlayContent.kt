package com.nianticspatial.nsdk.externalsamples.scenesegmentation

import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.core.graphics.times
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.ar.core.Frame
import com.nianticspatial.nsdk.Image
import com.nianticspatial.nsdk.awareness.semantics.SemanticsResult
import com.nianticspatial.nsdk.utils.ImageMath
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.common.OverlayContent
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.safeDestroyTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan

class SceneSegmentationOverlayContent(
    private val sceneSegmentationManager: SceneSegmentationManager,
    private val nsdkSessionManager: NSDKSessionManager,
    private val initialOpacity: Float
) : OverlayContent() {

    private var texture: Texture? = null
    private var lastTimestampMs: Long = 0L
    private val uvMatrix = FloatArray(9)
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE
    )
    private var opacity: Float = initialOpacity

    // Reprojection helpers
    private val referenceView: FloatArray = FloatArray(16)
    private val targetView: FloatArray = FloatArray(16)

    companion object {
        private const val TAG = "SemanticsOverlayContent"
    }

    override fun onCreateMaterial(engine: Engine, materialLoader: MaterialLoader): MaterialInstance {
        val material = materialLoader.createMaterial("materials/semantics_overlay.filamat")
        return material.createInstance().also { instance ->
            instance.setParameter("tintAlpha", opacity)

            val identityMatrix = floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
            instance.setParameter("uvTransform", MaterialInstance.FloatElement.MAT3, identityMatrix, 0, 1)
        }
    }

    fun setOpacity(value: Float) {
        opacity = value.coerceIn(0f, 1f)
    }

    fun reset() {
        lastTimestampMs = 0L
    }

    override fun onFrame(engine: Engine, materialInstance: MaterialInstance): Boolean {
        val result = sceneSegmentationManager.latestResult.value ?: return false
        val frame = nsdkSessionManager.currentFrame ?: return false

        val image = result.image as? Image.FloatImage ?: return false

        if (texture == null ||
            texture!!.getWidth(0) != image.width ||
            texture!!.getHeight(0) != image.height
        ) {
            recreateTexture(engine, image.width, image.height, materialInstance)
        }

        if (result.timestampMs > lastTimestampMs) {
            uploadImage(engine, image)
            lastTimestampMs = result.timestampMs
        }

        updateUvTransform(result, frame, materialInstance)

        materialInstance.setParameter("tintAlpha", opacity)

        return true
    }

    override fun onDestroy(engine: Engine) {
        texture?.let { engine.safeDestroyTexture(it) }
        texture = null
    }

    private fun recreateTexture(
        engine: Engine,
        width: Int,
        height: Int,
        materialInstance: MaterialInstance
    ) {
        texture?.let { engine.safeDestroyTexture(it) }
        texture = Texture.Builder()
            .width(width)
            .height(height)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.R32F)
            .build(engine)

        materialInstance.setParameter("confidenceTexture", texture!!, sampler)
    }

    private fun uploadImage(engine: Engine, image: Image.FloatImage) {
        val buffer = ByteBuffer
            .allocateDirect(image.data.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(image.data)
        buffer.rewind()
        texture?.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(
                buffer,
                Texture.Format.R,
                Texture.Type.FLOAT
            )
        )
    }

    private fun updateUvTransform(
        semanticsResult: SemanticsResult,
        arFrame: Frame,
        materialInstance: MaterialInstance
    ) {
        try {
            val currentViewportSize = viewportSize ?: return

            val imageSize = semanticsResult.intrinsics.getImageDimensions()

            val display = ImageMath.displayTransform(
                orientation = nsdkSessionManager.arManager.lastImageOrientation,
                viewportSize = currentViewportSize,
                imageSize = Size(imageSize[0], imageSize[1])
            ) * ImageMath.affineInvertVertical()

            val reprojection = calculateReprojection(semanticsResult, arFrame)

            val uvTransform = Matrix()
            (display * reprojection).invert(uvTransform)

            uvTransform.getValues(uvMatrix)

            materialInstance.setParameter(
                "uvTransform",
                MaterialInstance.FloatElement.MAT3,
                uvMatrix,
                0,
                1
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update UV transform: ${e.message}", e)
        }
    }

    /**
     * Calculate reprojection matrix from semantics result pose to current AR frame.
     */
    private fun calculateReprojection(
        semanticsResult: SemanticsResult,
        arFrame: Frame
    ): Matrix {
        val reference = semanticsResult.pose.inverse()
        val target = arFrame.camera.pose.inverse()
        reference.toMatrix(referenceView, 0)
        target.toMatrix(targetView, 0)

        val imageSize = semanticsResult.intrinsics.getImageDimensions()
        val focalLengthY = semanticsResult.intrinsics.getFocalLength()[1] // fy

        return ImageMath.reprojection(
            aspect = imageSize[0].toFloat() / imageSize[1].toFloat(),
            fovRadians = 2f * atan(imageSize[1].toFloat() / (2f * focalLengthY)),
            zNear = 0.2f,
            zFar = 100f,
            referenceView = referenceView,
            targetView = targetView
        )
    }

    override fun getUVCoordinates(rotation: Int): FloatArray {
        return floatArrayOf(0f, 1f,  1f, 1f,  1f, 0f,  0f, 0f)
    }
}

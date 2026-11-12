// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.capture

import android.graphics.Matrix
import android.util.Size
import androidx.core.graphics.times
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.nianticspatial.nsdk.utils.ImageMath
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.common.OverlayContent
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.safeDestroyTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Overlay content provider for capture visualization.
 *
 * The raycast image is drawn on a screen-aligned quad with identity UVs. The material's
 * uvTransform rotates and crops it the same way the camera background is displayed, so the
 * coverage overlay lines up with the camera image.
 *
 * @param captureManager The capture manager to get raycast buffer data from.
 * @param sessionManager Supplies the current device orientation.
 */
class CaptureOverlayContent(
    private val captureManager: CaptureManager,
    private val sessionManager: NSDKSessionManager
) : OverlayContent() {

    private var texture: Texture? = null
    private val uvTransformMatrix = FloatArray(9)

    override fun getUVCoordinates(rotation: Int): FloatArray {
        return floatArrayOf(0f, 1f,  1f, 1f,  1f, 0f,  0f, 0f)
    }

    override fun onCreateMaterial(engine: Engine, materialLoader: MaterialLoader): MaterialInstance {
        val material = materialLoader.createMaterial("materials/capture_viz.filamat")
        val materialInstance = material.createInstance()
        materialInstance.setParameter("stripeStride", 2)     // Stripe repeat interval
        materialInstance.setParameter("stripeRedPixels", 1)  // Width of red stripe

        return materialInstance
    }

    override fun onFrame(engine: Engine, materialInstance: MaterialInstance): Boolean {
        val raycastBuffer = captureManager.raycastBuffer ?: return false
        val intImage = raycastBuffer.rgba

        if (intImage.width <= 0 || intImage.height <= 0) return false
        val currentViewportSize = viewportSize ?: return false

        // Create texture on first call or if size changes
        if (texture == null ||
            texture!!.getWidth(0) != intImage.width ||
            texture!!.getHeight(0) != intImage.height) {

            texture?.let { engine.safeDestroyTexture(it) }

            texture = Texture.Builder()
                .width(intImage.width)
                .height(intImage.height)
                .levels(1)
                .format(Texture.InternalFormat.RGBA8)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .build(engine)

            materialInstance.setParameter(
                "visualizationTexture",
                texture!!,
                TextureSampler(
                    TextureSampler.MinFilter.LINEAR,
                    TextureSampler.MagFilter.LINEAR,
                    TextureSampler.WrapMode.CLAMP_TO_EDGE
                )
            )
        }

        val buffer = ByteBuffer.allocateDirect(intImage.data.size * 4)
            .order(ByteOrder.nativeOrder())

        for (pixel in intImage.data) {
            buffer.put((pixel shr 0 and 0xFF).toByte())   // R
            buffer.put((pixel shr 8 and 0xFF).toByte())   // G
            buffer.put((pixel shr 16 and 0xFF).toByte())  // B
            buffer.put((pixel shr 24 and 0xFF).toByte())  // A
        }
        buffer.flip()

        val pixelBuffer = Texture.PixelBufferDescriptor(
            buffer,
            Texture.Format.RGBA,
            Texture.Type.UBYTE
        )

        texture?.setImage(engine, 0, pixelBuffer)
        updateUvTransform(Size(intImage.width, intImage.height), currentViewportSize, materialInstance)
        return true
    }

    private fun updateUvTransform(imageSize: Size, viewportSize: Size, materialInstance: MaterialInstance) {
        val display = ImageMath.displayTransform(
            orientation = sessionManager.currentImageOrientation,
            viewportSize = viewportSize,
            imageSize = imageSize
        ) * ImageMath.affineInvertVertical()

        val uvTransform = Matrix()
        display.invert(uvTransform)
        uvTransform.getValues(uvTransformMatrix)

        materialInstance.setParameter(
            "uvTransform",
            MaterialInstance.FloatElement.MAT3,
            uvTransformMatrix,
            0,
            1
        )
    }

    override fun onDestroy(engine: Engine) {
        texture?.let { engine.safeDestroyTexture(it) }
        texture = null
    }
}

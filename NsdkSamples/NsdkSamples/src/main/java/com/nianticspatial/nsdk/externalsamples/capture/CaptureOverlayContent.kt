// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.capture

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.nianticspatial.nsdk.externalsamples.common.OverlayContent
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.safeDestroyTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Overlay content provider for capture visualization.
 * @param captureManager The capture manager to get raycast buffer data from.
 */
class CaptureOverlayContent(
    private val captureManager: CaptureManager
) : OverlayContent() {

    private var texture: Texture? = null

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
        return true
    }

    override fun onDestroy(engine: Engine) {
        texture?.let { engine.safeDestroyTexture(it) }
        texture = null
    }
}

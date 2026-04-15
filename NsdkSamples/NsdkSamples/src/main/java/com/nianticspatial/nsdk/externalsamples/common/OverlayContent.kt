// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.common

import android.util.Size
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import io.github.sceneview.loaders.MaterialLoader

/**
 * Abstract base class for screen-space overlay content providers.
 *
 * Implementations provide their own materials and textures, created using
 * the overlay's Engine and MaterialLoader. The overlay view handles:
 * - Fullscreen quad geometry
 * - Render loop
 * - MaterialInstance cleanup
 *
 * The user is responsible for:
 * - Creating Material and MaterialInstance in [onCreateMaterial]
 * - Creating/updating textures in [onFrame]
 * - Cleaning up textures in [onDestroy]
 */
abstract class OverlayContent {
    /**
     * Whether the overlay is currently visible and rendering.
     */
    var isVisible by mutableStateOf(false)
        private set

    /**
     * The current viewport size. Set by ScreenOverlayView.
     */
    var viewportSize: Size? = null
        internal set

    /**
     * Show the overlay and start rendering.
     */
    fun show() {
        isVisible = true
    }

    /**
     * Hide the overlay and stop rendering.
     */
    fun hide() {
        isVisible = false
    }

    /**
     * Called once when the overlay starts rendering.
     * Create your Material, MaterialInstance, and any initial textures here.
     *
     * @param engine The Filament engine owned by the overlay view.
     * @param materialLoader The material loader for loading .filamat files.
     * @return The MaterialInstance to use for rendering. The overlay will destroy this on cleanup.
     */
    abstract fun onCreateMaterial(engine: Engine, materialLoader: MaterialLoader): MaterialInstance

    /**
     * Called each frame while visible.
     * Update textures and material parameters here.
     *
     * @param engine The Filament engine owned by the overlay view.
     * @param materialInstance The MaterialInstance returned from [onCreateMaterial].
     * @return true to render this frame, false to skip rendering.
     */
    abstract fun onFrame(engine: Engine, materialInstance: MaterialInstance): Boolean

    /**
     * Called when the overlay is destroyed.
     * Clean up your textures and other resources here.
     * MaterialInstance cleanup is handled automatically by the overlay.
     *
     * @param engine The Filament engine owned by the overlay view.
     */
    abstract fun onDestroy(engine: Engine)

    /**
     * Get UV coordinates for the fullscreen quad based on screen rotation.
     * Override this to provide custom UV coordinates (e.g., identity UVs for custom transforms).
     *
     * @param rotation The current screen rotation (Surface.ROTATION_*)
     * @return Array of UV coordinates in the format [u0, v0, u1, v1, u2, v2, u3, v3]
     *         for [bottom-left, bottom-right, top-right, top-left] vertices
     */
    open fun getUVCoordinates(rotation: Int): FloatArray {
        // Default behavior: Apply rotation to UVs based on screen orientation
        return when (rotation) {
            Surface.ROTATION_0 -> {
                // Portrait (0°): 90° counter-clockwise rotation
                floatArrayOf(1f, 0f,  1f, 1f,  0f, 1f,  0f, 0f)
            }
            Surface.ROTATION_90 -> {
                // Landscape right (90°): Standard UV mapping
                floatArrayOf(0f, 0f,  1f, 0f,  1f, 1f,  0f, 1f)
            }
            Surface.ROTATION_270 -> {
                // Landscape left (270°): 180° rotation
                floatArrayOf(1f, 1f,  0f, 1f,  0f, 0f,  1f, 0f)
            }
            else -> {
                // Default to ROTATION_0
                floatArrayOf(1f, 0f,  1f, 1f,  0f, 1f,  0f, 0f)
            }
        }
    }
}

// Copyright 2025 Niantic.
package com.nianticspatial.nsdk.externalsamples.common

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Size
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.safeDestroyCamera
import io.github.sceneview.safeDestroyEntity
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyMaterialInstance
import io.github.sceneview.safeDestroyRenderer
import io.github.sceneview.safeDestroyScene
import io.github.sceneview.safeDestroyVertexBuffer
import io.github.sceneview.safeDestroyView
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generic screen-space overlay view for rendering pixel-perfect visualizations
 * using a separate Filament rendering engine.
 *
 * Can be used for various visualization types: depth maps, normal maps, semantic
 * segmentation, capture coverage, etc.
 *
 * Uses TextureView instead of SurfaceView to properly integrate with Compose UI Z-ordering.
 *
 * @param content The content provider that supplies materials and textures.
 */
class ScreenOverlayView @JvmOverloads constructor(
    context: Context,
    private val content: OverlayContent,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), Choreographer.FrameCallback, TextureView.SurfaceTextureListener {

    private val engine: Engine
    private val renderer: Renderer
    private val scene: Scene
    private val view: View
    private val camera: Camera
    private val materialLoader: MaterialLoader

    private var swapChain: SwapChain? = null
    private var nativeSurface: Surface? = null

    private var overlayEntity: Int = 0
    private var vertexBuffer: VertexBuffer? = null
    private var indexBuffer: IndexBuffer? = null

    private var materialInstance: MaterialInstance? = null

    private var isRendering = false
    private var currentRotation: Int = Surface.ROTATION_0

    init {
        isOpaque = false

        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        setOnTouchListener { _, _ -> false }

        engine = Engine.create()
        materialLoader = MaterialLoader(engine, context)

        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()

        val cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)

        view.camera = camera
        view.scene = scene
        view.blendMode = View.BlendMode.TRANSLUCENT

        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
            // Clear the intermediate buffer to avoid previous overlays lingering on screen.
            // The AR camera feed runs in a separate layer, so resetting with transparent color is safe.
            clearColor = floatArrayOf(0f, 0f, 0f, 0f)
        }

        surfaceTextureListener = this
        createFullscreenQuad()
    }

    private fun getVerticesForRotation(rotation: Int): FloatArray {
        // Get UV coordinates from content (allows override for custom UV handling)
        val uvs = content.getUVCoordinates(rotation)

        // Position vertices (always the same - fullscreen quad)
        // Combined with UV coordinates from content
        return floatArrayOf(
            // position (x, y, z)    // UV (u, v)
            -1f, -1f, 0f,            uvs[0], uvs[1],  // Bottom-left
             1f, -1f, 0f,            uvs[2], uvs[3],  // Bottom-right
             1f,  1f, 0f,            uvs[4], uvs[5],  // Top-right
            -1f,  1f, 0f,            uvs[6], uvs[7]   // Top-left
        )
    }

    private fun updateVertexBufferForRotation(rotation: Int) {
        if (rotation == currentRotation) return

        currentRotation = rotation
        val vertices = getVerticesForRotation(rotation)

        val vertexData = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .flip()

        vertexBuffer?.setBufferAt(engine, 0, vertexData)
    }

    private fun createFullscreenQuad() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation
        currentRotation = rotation
        val vertices = getVerticesForRotation(rotation)

        val vertexCount = 4
        val vertexSize = (3 + 2) * Float.SIZE_BYTES // 3 for position, 2 for UV

        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                vertexSize
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0,
                0,
                VertexBuffer.AttributeType.FLOAT2,
                3 * Float.SIZE_BYTES,
                vertexSize
            )
            .build(engine)

        val vertexData = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .flip()

        vertexBuffer?.setBufferAt(engine, 0, vertexData)

        val indices = shortArrayOf(
            0, 1, 2,  // First triangle
            2, 3, 0   // Second triangle
        )

        indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)

        val indexData = ByteBuffer.allocateDirect(indices.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indices)
            .flip()

        indexBuffer?.setBuffer(engine, indexData)
        overlayEntity = EntityManager.get().create()
    }

    /**
     * Start rendering the overlay.
     * Calls content.onCreateMaterial() to get the MaterialInstance.
     */
    fun startRendering() {
        if (isRendering) return

        materialInstance = content.onCreateMaterial(engine, materialLoader)

        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer!!,
                indexBuffer!!,
                0,
                6 // 6 indices
            )
            .material(0, materialInstance!!)
            .boundingBox(Box(-1f, -1f, -1f, 1f, 1f, 1f))
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .priority(7) // Render last
            .build(engine, overlayEntity)

        scene.addEntity(overlayEntity)

        isRendering = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * Stop rendering the overlay.
     */
    fun stopRendering() {
        if (!isRendering) return

        isRendering = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(timestamp: Long) {
        if (!isRendering) return
        Choreographer.getInstance().postFrameCallback(this)
        // Note: this is currently only used when going from landscape left to landscape right,
        // as the views are recreated when switching between portrait and landsacpe. Nevertheless,
        // it is required for the texture to behave correctly.
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation
        updateVertexBufferForRotation(rotation)

        // Let content update textures/params, skip render if it returns false
        val shouldRender = materialInstance?.let { mi ->
            content.onFrame(engine, mi)
        } ?: false

        if (!shouldRender) return

        swapChain?.let { sc ->
            if (renderer.beginFrame(sc, timestamp)) {
                renderer.render(view)
                renderer.endFrame()
            }
        }
    }

    /**
     * Clean up all Filament resources.
     */
    fun destroy() {
        stopRendering()

        // Notify content to clean up its resources (textures, etc.)
        content.onDestroy(engine)

        nativeSurface?.release()
        nativeSurface = null

        scene.removeEntity(overlayEntity)
        runCatching {
            engine.renderableManager.destroy(overlayEntity)
        }
        engine.safeDestroyEntity(overlayEntity)

        // Destroy MaterialInstance (provided by content, but we destroy it for safety)
        materialInstance?.let { instance ->
            engine.safeDestroyMaterialInstance(instance)
        }
        materialInstance = null

        vertexBuffer?.let { engine.safeDestroyVertexBuffer(it) }
        indexBuffer?.let { engine.safeDestroyIndexBuffer(it) }

        swapChain?.let { engine.destroySwapChain(it) }

        engine.safeDestroyCamera(camera)
        engine.safeDestroyRenderer(renderer)
        engine.safeDestroyView(view)
        engine.safeDestroyScene(scene)

        materialLoader.destroy()
        engine.destroy()
    }

    // TextureView.SurfaceTextureListener implementation
    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        val surface = Surface(surfaceTexture)
        nativeSurface = surface
        swapChain = engine.createSwapChain(surface)

        // Set orthographic projection to match screen exactly
        camera.setProjection(
            Camera.Projection.ORTHO,
            -1.0, 1.0,  // left, right (normalized)
            -1.0, 1.0,  // bottom, top (normalized)
            0.0, 2.0    // near, far
        )
        view.viewport = Viewport(0, 0, width, height)

        // Provide viewport size to content for reprojection calculations
        content.viewportSize = Size(width, height)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        camera.setProjection(
            Camera.Projection.ORTHO,
            -1.0, 1.0,
            -1.0, 1.0,
            0.0, 2.0
        )
        view.viewport = Viewport(0, 0, width, height)

        // Update viewport size in content
        content.viewportSize = Size(width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        nativeSurface?.release()
        nativeSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
    }
}

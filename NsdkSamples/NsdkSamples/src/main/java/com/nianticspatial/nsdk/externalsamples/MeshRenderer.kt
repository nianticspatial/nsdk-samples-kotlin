// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.nianticspatial.nsdk.MeshData
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.MeshNode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.get

/**
 * A helper class that processes NSDK mesh data and generates MeshNodes from that data
 *
 * @param wireframeWhenUntextured When true, chunks that arrive without a usable texture are drawn
 *   as a wireframe with invisible faces, so the camera passthrough stays visible through the mesh
 *   while the mesh still hides its own far edges. This is useful for downloaded meshes, where the
 *   point of the overlay is checking that the mesh lines up with the real world. Defaults to false
 *   so the live meshing sample keeps its solid shaded look.
 */
class MeshRenderer(
    private val engine: Engine,
    private val materialLoader: MaterialLoader,
    private val wireframeWhenUntextured: Boolean = false
) {

    companion object {
        private const val TAG = "MeshRenderer"

        // Upper bound on the triangle count for a wireframe chunk. The wireframe copies every
        // triangle's corners, so beyond this we draw solid faces instead of allocating an unbounded
        // buffer. Roughly where the old line-based wireframe gave up.
        private const val MAX_WIREFRAME_TRIANGLES = 1_500_000

        // Draw order within the transparent pass (0 = first, 7 = last; opaque objects, including the
        // camera feed, are always drawn before any transparent one). The occluder must finish writing
        // depth before the wireframe is depth tested, so it gets the lower value. No polygon offset is
        // needed: the wireframe is drawn from the same triangles as the occluder, so shared surfaces
        // produce equal depth, and Filament's default depth test (GE) passes on equal.
        private const val OCCLUDER_PRIORITY = 3
        private const val WIREFRAME_PRIORITY = 4
    }

    // Class to hold data for each mesh chunk
    data class RenderableMeshChunk(
        val chunkId: Long,
        val meshData: MeshData,
        val modelMatrix: FloatArray,
        val textureData: ByteArray? = null,
        var indexCount: Int = 0,
    )

    /**
     * The Filament resources backing a single mesh chunk. SceneView's MeshNode.destroy() only
     * destroys the renderable entity, so every buffer, MaterialInstance and Texture we create has
     * to be released by us, in that order (the renderable must be gone before the resources it
     * references are destroyed).
     */
    private class ChunkResources(
        val nodes: List<MeshNode>,
        val vertexBuffers: List<VertexBuffer>,
        val indexBuffers: List<IndexBuffer>,
        // Instances we create via material.createInstance(). MaterialLoader does not track these,
        // so they go to engine.destroyMaterialInstance().
        val ownedMaterialInstances: List<MaterialInstance>,
        // Instances handed out by MaterialLoader (createImageInstance). These are in its registry,
        // so they must go back through MaterialLoader.destroyMaterialInstance(), which unregisters
        // them and so does not double-destroy at teardown.
        val loaderMaterialInstances: List<MaterialInstance>,
        val texture: Texture?,
    )

    private var _defaultMaterialColor: Color = Color.Companion.White

    private var _unlitVertexMaterial: Material? = null

    // Depth-only occluder material (materials/occluder.filamat), loaded on first use.
    private var _occluderMaterial: Material? = null

    // Barycentric wireframe shader material (materials/wireframe.filamat), loaded on first use.
    private var _wireframeMaterial: Material? = null

    // Containers for all mesh chunk data
    private var newMeshChunks = ConcurrentHashMap<Long, RenderableMeshChunk>()
    private var meshNodesToRemove = mutableListOf<Long>()
    private var processedMeshNodes = ConcurrentHashMap<Long, ChunkResources>()

    // Flag for reloading all chunks, mostly for if we receive a new mesh
    private var _needsMeshProcessing = false
    val needsMeshProcessing: Boolean get() = _needsMeshProcessing

    @Volatile private var isDestroyed = false

    // Get the new mesh chunks that need to be processed. some chunks might be new, others might just be getting updated, while others are being removed
    @Synchronized
    fun updateMeshChunks(updatedMeshChunks: List<RenderableMeshChunk>, allUpdatedMeshIds : List<Long>) {

        // We need to remove any meshIds that are no longer tracked. Start with everything we currently have, and remove all the updated ones
        meshNodesToRemove.addAll(processedMeshNodes.keys)
        meshNodesToRemove.removeAll(allUpdatedMeshIds)

        // Chunks queued but not built yet are not in processedMeshNodes, so drop the ones no longer
        // wanted here, or the next createMeshNodes would still draw them.
        newMeshChunks.keys.retainAll(allUpdatedMeshIds.toSet())

        updatedMeshChunks.forEach { chunk ->
            // If this chunk already exists but is being updated, we need to remove it then re-add it to update the content
            if( processedMeshNodes.containsKey(chunk.chunkId))
                meshNodesToRemove.add(chunk.chunkId)

            newMeshChunks.putIfAbsent(chunk.chunkId, chunk)
        }

        _needsMeshProcessing = true;
    }

    /**
     * Creates MeshNodes for the loaded mesh chunks and adds them to the active scene.
     */
    @Synchronized
    fun createMeshNodes(context: Context, parentNode: PoseNode) {
        if (isDestroyed) return
        // First delete any old chunks flagged for removal (even if no new chunks to add)
        meshNodesToRemove.forEach { chunkId ->
            val resources = processedMeshNodes.remove(chunkId)
            if (resources != null) {
                resources.nodes.forEach { parentNode.removeChildNode(it) }
                releaseChunkResources(resources)
            }
        }
        meshNodesToRemove.clear()

        if (newMeshChunks.isEmpty()) {
            // No new chunks to add, but removals have been processed
            _needsMeshProcessing = false
            return
        }

        // Now go through and add the new chunks
        newMeshChunks.forEach { chunkId, chunk ->

            val meshData = chunk.meshData

            // Skip mesh chunk with no geometry, or Filament resource creation aborts
            if (meshData.vertices.isEmpty() || meshData.indices.isEmpty()) {
                Log.d(
                    TAG,
                    "Chunk: no geometry (${meshData.vertices.size} vertex floats, " +
                        "${meshData.indices.size} indices); skipping."
                )
                return@forEach
            }

            // Create the vertex buffer data combining the vertex and UV data
            val originalVertices = meshData.vertices
            val vertexCount = originalVertices.size / 3
            val correctedVertices = FloatArray(originalVertices.size) { i ->
                when (i % 3) {
                    0 -> originalVertices[i]       // X
                    1 -> -originalVertices[i]      // -Y
                    else -> -originalVertices[i]   // -Z
                }
            }

            val uvs = meshData.uvs
            val hasUvs = uvs != null && uvs.isNotEmpty()
            if (!hasUvs) {
                Log.d(TAG, "Chunk: No UV data.")
            }

            val normals = meshData.normals
            val hasNormals = normals != null && normals.isNotEmpty()

            /*
                Decode the texture up front: whether the chunk has a usable texture decides both the
                material AND the geometry we build for it, so it cannot wait until after the buffers
                have been created. The mesh downloader hands back a non-null ByteArray that is empty
                when the asset has no texture, so all three failure modes are treated the same.
             */
            val textureBytes = chunk.textureData
            val bitmap: Bitmap? = if (textureBytes == null || textureBytes.isEmpty()) {
                Log.d(TAG, "Chunk: No texture data.")
                null
            } else {
                runCatching {
                    BitmapFactory.decodeByteArray(textureBytes, 0, textureBytes.size)
                }.getOrNull().also {
                    if (it == null) Log.w(TAG, "Chunk: Failed to decode texture image.")
                }
            }

            // A texture we cannot address is no more useful than no texture at all.
            val hasUsableTexture = bitmap != null && hasUvs
            if (bitmap != null && !hasUsableTexture) {
                Log.w(TAG, "Chunk: Texture decoded but the mesh has no UVs; ignoring texture.")
                bitmap.recycle()
            }

            // Untextured meshes are drawn as a wireframe, unless the chunk is too dense for one.
            val wantWireframe = wireframeWhenUntextured && !hasUsableTexture
            val triangleCount = meshData.indices.size / 3
            val useWireframe = wantWireframe && triangleCount <= MAX_WIREFRAME_TRIANGLES
            if (wantWireframe && !useWireframe) {
                Log.w(
                    TAG,
                    "Chunk: $triangleCount triangles exceeds the wireframe limit; drawing solid faces."
                )
            }

            val maxExtents = Float3(x = Float.MIN_VALUE, y = Float.MIN_VALUE, z = Float.MIN_VALUE)
            for (i in 0 until vertexCount) {
                // We need the extents of the mesh for the bounding box
                val cX = abs(correctedVertices[i * 3 + 0])
                val cY = abs(correctedVertices[i * 3 + 1])
                val cZ = abs(correctedVertices[i * 3 + 2])

                if (cX > maxExtents.x)
                    maxExtents.x = cX

                if (cY > maxExtents.y)
                    maxExtents.y = cY

                if (cZ > maxExtents.z)
                    maxExtents.z = cZ
            }
            val boundingBox = Box(0f, 0f, 0f, maxExtents.x, maxExtents.y, maxExtents.z)

            // Process the mesh indices for this chunk
            chunk.indexCount = meshData.indices.size

            val nodes = mutableListOf<MeshNode>()
            val vertexBuffers = mutableListOf<VertexBuffer>()
            val indexBuffers = mutableListOf<IndexBuffer>()
            val ownedMaterialInstances = mutableListOf<MaterialInstance>()
            val loaderMaterialInstances = mutableListOf<MaterialInstance>()
            var chunkTexture: Texture? = null

            if (useWireframe) {
                // Depth-only occluder: an invisible copy of the solid triangles that writes depth but
                // no color, so near surfaces hide the mesh's own far edges while the camera still
                // shows through.
                val occMaterial = createOccluderInstance(context)
                val occVertexBuffer = buildVertexBuffer(
                    correctedVertices,
                    vertexCount,
                    uvs = null,
                    colors = null
                )
                val occIndexBuffer = buildIndexBuffer(meshData.indices)
                vertexBuffers.add(occVertexBuffer)
                indexBuffers.add(occIndexBuffer)
                ownedMaterialInstances.add(occMaterial)
                nodes.add(
                    MeshNode(
                        engine = engine,
                        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                        vertexBuffer = occVertexBuffer,
                        indexBuffer = occIndexBuffer,
                        boundingBox = boundingBox,
                        materialInstance = occMaterial,
                    ).apply { setPriority(OCCLUDER_PRIORITY) }
                )

                // Shader wireframe: de-index into flat triangles carrying barycentric coordinates in
                // the vertex color and let the fragment shader paint the edges. Drawn from the same
                // triangles as the occluder, so shared surfaces pass its depth test and far edges
                // fail it.
                val wireInstance = createWireframeInstance(context)
                val bary = buildBarycentricGeometry(correctedVertices, meshData.indices)
                val baryVertexBuffer = buildBarycentricVertexBuffer(bary)
                val baryIndexBuffer = buildIndexBuffer(bary.indices)
                vertexBuffers.add(baryVertexBuffer)
                indexBuffers.add(baryIndexBuffer)
                ownedMaterialInstances.add(wireInstance)
                nodes.add(
                    MeshNode(
                        engine = engine,
                        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                        vertexBuffer = baryVertexBuffer,
                        indexBuffer = baryIndexBuffer,
                        boundingBox = boundingBox,
                        materialInstance = wireInstance,
                    ).apply { setPriority(WIREFRAME_PRIORITY) }
                )
            } else {
                /*
                    Solid path. When normals are available we use them to color the vertices:
                    X,Y,Z as R,G,B values, with a fixed alpha of 0.5.
                 */
                val colors =
                    if (hasNormals) buildNormalColors(vertexCount, normals!!, 0.5f) else null
                val vertexBuffer = buildVertexBuffer(
                    correctedVertices,
                    vertexCount,
                    uvs = if (hasUsableTexture) uvs else null,
                    colors = colors
                )
                val triangleIndexBuffer = buildIndexBuffer(meshData.indices)
                vertexBuffers.add(vertexBuffer)
                indexBuffers.add(triangleIndexBuffer)

                val materialInstance: MaterialInstance
                if (hasUsableTexture) {
                    val texture = Texture.Builder()
                        .width(bitmap!!.width)
                        .height(bitmap.height)
                        .sampler(Texture.Sampler.SAMPLER_2D)
                        .format(Texture.InternalFormat.SRGB8_A8) // Use SRGB for color textures
                        .levels(0xff) // Use all mipmap levels
                        .build(engine)

                    val byteBuffer = ByteBuffer.allocateDirect(bitmap.byteCount)
                    bitmap.copyPixelsToBuffer(byteBuffer)
                    byteBuffer.rewind()

                    texture.setImage(
                        engine,
                        0,
                        Texture.PixelBufferDescriptor(
                            byteBuffer,
                            Texture.Format.RGBA,
                            Texture.Type.UBYTE
                        )
                    )
                    chunkTexture = texture

                    materialInstance = materialLoader.createImageInstance(texture, TextureSampler())
                        .apply {
                            // SceneView's image material is transparent-blended (it is meant for
                            // 2D image planes) and so does not write depth. On a room-scale mesh
                            // that means surfaces are composited in draw order instead of by
                            // depth, and far geometry paints over near geometry. The scanned
                            // texture is fully opaque, so restore depth testing and writes.
                            setDepthWrite(true)
                            setDepthCulling(true)
                        }
                    loaderMaterialInstances.add(materialInstance)

                    // Clean up the bitmap data
                    bitmap.recycle()
                } else {
                    materialInstance =
                        createUnlitVertexColorMaterial(context, _defaultMaterialColor)
                    ownedMaterialInstances.add(materialInstance)
                }

                nodes.add(
                    MeshNode(
                        engine = engine,
                        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                        vertexBuffer = vertexBuffer,
                        indexBuffer = triangleIndexBuffer,
                        boundingBox = boundingBox,
                        materialInstance = materialInstance,
                    )
                )
            }

            // Set the relative position of the nodes from the chunk modelMatrix data
            val transform = Mat4(
                x = Float4(
                    chunk.modelMatrix[0],
                    chunk.modelMatrix[1],
                    chunk.modelMatrix[2],
                    chunk.modelMatrix[3]
                ),
                y = Float4(
                    chunk.modelMatrix[4],
                    chunk.modelMatrix[5],
                    chunk.modelMatrix[6],
                    chunk.modelMatrix[7]
                ),
                z = Float4(
                    chunk.modelMatrix[8],
                    chunk.modelMatrix[9],
                    chunk.modelMatrix[10],
                    chunk.modelMatrix[11]
                ),
                w = Float4(
                    chunk.modelMatrix[12],
                    chunk.modelMatrix[13],
                    chunk.modelMatrix[14],
                    chunk.modelMatrix[15]
                )
            )

            nodes.forEach { node ->
                node.transform = transform
                parentNode.addChildNode(node)
                // SceneView applies a parent's visibility only when it changes, so a node added
                // under a hidden parent would otherwise be drawn.
                node.setLayerVisible(node.isVisible)
            }

            processedMeshNodes[chunkId] = ChunkResources(
                nodes = nodes,
                vertexBuffers = vertexBuffers,
                indexBuffers = indexBuffers,
                ownedMaterialInstances = ownedMaterialInstances,
                loaderMaterialInstances = loaderMaterialInstances,
                texture = chunkTexture,
            )
        }

        newMeshChunks.clear()
        _needsMeshProcessing = false
    }

    @Synchronized
    fun clearData() {
        newMeshChunks.clear()
        meshNodesToRemove.clear()
        // Destroy each chunk's Filament renderable entity, and then the buffers, MaterialInstances
        // and Textures that entity referenced. Releasing the resources while a renderable still
        // points at them makes Filament panic.
        processedMeshNodes.values.forEach { releaseChunkResources(it) }
        processedMeshNodes.clear()
        _needsMeshProcessing = false
    }

    // Must be called before the Filament engine is torn down.
    // Synchronized with createMeshNodes so it either runs before any in-progress
    // mesh creation starts, or waits for it to finish before marking destroyed.
    @Synchronized
    fun destroy() {
        isDestroyed = true
        processedMeshNodes.values.forEach { releaseChunkResources(it) }
        processedMeshNodes.clear()
        newMeshChunks.clear()
        meshNodesToRemove.clear()
        _needsMeshProcessing = false
    }

    /**
     * Releases everything a chunk owns. SceneView's MeshNode.destroy() only destroys the renderable
     * entity (GeometryNode is the only node type that frees geometry), so the vertex/index buffers
     * are ours to destroy. MaterialInstances created via material.createInstance() are likewise not
     * tracked by MaterialLoader and must be destroyed before it tears down the parent Material.
     *
     * The chunk's texture is destroyed last, after the MaterialInstance that sampled it: Filament
     * aborts on the next commit if a live instance still references a destroyed texture
     * ("Invalid texture still bound to MaterialInstance").
     */
    private fun releaseChunkResources(resources: ChunkResources) {
        resources.nodes.forEach { it.destroy() }
        resources.indexBuffers.forEach { engine.destroyIndexBuffer(it) }
        resources.vertexBuffers.forEach { engine.destroyVertexBuffer(it) }
        resources.ownedMaterialInstances.forEach { engine.destroyMaterialInstance(it) }
        resources.loaderMaterialInstances.forEach { materialLoader.destroyMaterialInstance(it) }
        resources.texture?.let { engine.destroyTexture(it) }
    }

    /**
     * Builds a VertexBuffer with a POSITION attribute, plus UV0 and/or COLOR when supplied.
     */
    private fun buildVertexBuffer(
        vertices: FloatArray,
        vertexCount: Int,
        uvs: FloatArray?,
        colors: FloatArray?
    ): VertexBuffer {
        val vertexStride = 3 * 4 // (X,Y,Z) * 4 bytes
        val uvStride = 2 * 4 // (U,V) * 4 bytes
        val colorStride = 4 * 4 // (R,G,B,A) * 4 bytes

        // Each optional attribute gets its own buffer; bufferCount ends up as the total.
        var bufferCount = 1
        val uvBufferIdx = if (uvs != null) bufferCount++ else -1
        val colorBufferIdx = if (colors != null) bufferCount++ else -1

        val builder = VertexBuffer.Builder()
            .bufferCount(bufferCount)
            .vertexCount(vertexCount)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                vertexStride
            )

        if (uvs != null) {
            builder.attribute(
                VertexBuffer.VertexAttribute.UV0,
                uvBufferIdx,
                VertexBuffer.AttributeType.FLOAT2,
                0,
                uvStride
            )
        }

        if (colors != null) {
            builder.attribute(
                VertexBuffer.VertexAttribute.COLOR,
                colorBufferIdx,
                VertexBuffer.AttributeType.FLOAT4,
                0,
                colorStride
            )
        }

        val vertexBuffer = builder.build(engine)
        vertexBuffer.setBufferAt(engine, 0, toFloatBuffer(vertices, vertexCount * 3))

        if (uvs != null) {
            vertexBuffer.setBufferAt(engine, uvBufferIdx, toFloatBuffer(uvs, vertexCount * 2))
        }

        if (colors != null) {
            vertexBuffer.setBufferAt(engine, colorBufferIdx, toFloatBuffer(colors, vertexCount * 4))
        }

        return vertexBuffer
    }

    private fun toFloatBuffer(values: FloatArray, count: Int): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        byteBuffer.asFloatBuffer().put(values, 0, count)
        byteBuffer.position(0)
        return byteBuffer
    }

    // IndexBuffer expects an untyped ByteBuffer as input, so we need to convert the intArray from
    // the meshData into a byteBuffer
    private fun buildIndexBuffer(indices: IntArray): IndexBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder())
        byteBuffer.asIntBuffer().put(indices, 0, indices.size)
        byteBuffer.position(0)

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)

        indexBuffer.setBuffer(engine, byteBuffer)
        return indexBuffer
    }

    /**
     * Packs the normals into per-vertex colors: X,Y,Z as R,G,B, with the given alpha.
     */
    private fun buildNormalColors(vertexCount: Int, normals: FloatArray, alpha: Float): FloatArray {
        val colors = FloatArray(vertexCount * 4)
        for (i in 0 until vertexCount) {
            val colorIndex = i * 4
            val normalIndex = i * 3
            colors[colorIndex + 0] = abs(normals[normalIndex + 0])     // X -> R
            colors[colorIndex + 1] = abs(normals[normalIndex + 1])     // Y -> G
            colors[colorIndex + 2] = abs(normals[normalIndex + 2])     // Z -> B
            colors[colorIndex + 3] = alpha                             // A
        }
        return colors
    }

    private fun createUnlitVertexColorMaterial(
        context: Context,
        color: Color
    ): MaterialInstance {
        if (_unlitVertexMaterial == null){
            val buffer = context.assets.open("materials/unlit_vertex.filamat").use {
                ByteBuffer.wrap(it.readBytes())
            }
            _unlitVertexMaterial = materialLoader.createMaterial(buffer)
        }
        val materialInstance = _unlitVertexMaterial!!.createInstance()
        materialInstance.setParameter("baseColor", color.red, color.green, color.blue, color.alpha)
        return materialInstance
    }

    /**
     * A depth-only occluder MaterialInstance from materials/occluder.filamat: writes depth but no
     * color, so the triangles it is applied to are invisible yet still hide the wireframe drawn after
     * it. Owned by the caller (destroy via engine.destroyMaterialInstance).
     */
    private fun createOccluderInstance(context: Context): MaterialInstance {
        if (_occluderMaterial == null) {
            val buffer = context.assets.open("materials/occluder.filamat").use {
                ByteBuffer.wrap(it.readBytes())
            }
            _occluderMaterial = materialLoader.createMaterial(buffer)
        }
        return _occluderMaterial!!.createInstance().apply {
            setDepthWrite(true)
            setDepthCulling(true)
        }
    }

    /**
     * An instance of the barycentric wireframe material (materials/wireframe.filamat), which draws
     * triangle edges in the fragment shader. Owned by the caller (destroy via
     * engine.destroyMaterialInstance).
     */
    private fun createWireframeInstance(context: Context): MaterialInstance {
        if (_wireframeMaterial == null) {
            val buffer = context.assets.open("materials/wireframe.filamat").use {
                ByteBuffer.wrap(it.readBytes())
            }
            _wireframeMaterial = materialLoader.createMaterial(buffer)
        }
        return _wireframeMaterial!!.createInstance().apply {
            setParameter("lineColor", 0.2f, 1.0f, 1.0f)
            setParameter("lineWidth", 1.5f)
        }
    }

    /**
     * Flat (de-indexed) triangle geometry. Each corner's barycentric coordinate is stored as 4 bytes
     * (RGBA, 0 or 255) and read by the shader as a normalized color.
     */
    private class BarycentricMesh(
        val positions: FloatArray,
        val corners: ByteArray,
        val indices: IntArray,
        val vertexCount: Int,
    )

    /**
     * De-indexes a triangle mesh so every triangle has three unique vertices, tagging each corner
     * with a barycentric coordinate (1,0,0)/(0,1,0)/(0,0,1). The wireframe shader reads these to
     * draw edges. Vertex count triples.
     */
    private fun buildBarycentricGeometry(vertices: FloatArray, indices: IntArray): BarycentricMesh {
        val triangleCount = indices.size / 3
        val vertexCount = triangleCount * 3
        val positions = FloatArray(vertexCount * 3)
        val corners = ByteArray(vertexCount * 4)
        val seqIndices = IntArray(vertexCount)
        var out = 0
        for (t in 0 until triangleCount) {
            for (corner in 0 until 3) {
                val src = indices[t * 3 + corner]
                positions[out * 3] = vertices[src * 3]
                positions[out * 3 + 1] = vertices[src * 3 + 1]
                positions[out * 3 + 2] = vertices[src * 3 + 2]
                corners[out * 4 + corner] = 0xFF.toByte()
                corners[out * 4 + 3] = 0xFF.toByte()
                seqIndices[out] = out
                out++
            }
        }
        return BarycentricMesh(positions, corners, seqIndices, vertexCount)
    }

    /** Builds a VertexBuffer with POSITION plus the barycentric corners as a normalized UBYTE4 COLOR. */
    private fun buildBarycentricVertexBuffer(mesh: BarycentricMesh): VertexBuffer {
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(2)
            .vertexCount(mesh.vertexCount)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                3 * 4
            )
            .attribute(
                VertexBuffer.VertexAttribute.COLOR,
                1,
                VertexBuffer.AttributeType.UBYTE4,
                0,
                4
            )
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .build(engine)

        vertexBuffer.setBufferAt(engine, 0, toFloatBuffer(mesh.positions, mesh.vertexCount * 3))
        val cornerBuffer = ByteBuffer.allocateDirect(mesh.corners.size).order(ByteOrder.nativeOrder())
        cornerBuffer.put(mesh.corners)
        cornerBuffer.position(0)
        vertexBuffer.setBufferAt(engine, 1, cornerBuffer)
        return vertexBuffer
    }
}

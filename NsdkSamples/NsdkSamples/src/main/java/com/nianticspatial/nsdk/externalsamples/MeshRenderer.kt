// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.content.Context
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
 */
class MeshRenderer(
    private val engine: Engine,
    private val materialLoader: MaterialLoader
) {

    companion object {
        private const val TAG = "MeshRenderer"
    }

    // Class to hold data for each mesh chunk
    data class RenderableMeshChunk(
        val chunkId: Long,
        val meshData: MeshData,
        val modelMatrix: FloatArray,
        val textureData: ByteArray? = null,
        var indexCount: Int = 0,
    )

    private var _defaultMaterialColor: Color = Color.Companion.White

    private var _unlitVertexMaterial: Material? = null

    // Containers for all mesh chunk data
    private var newMeshChunks = ConcurrentHashMap<Long, RenderableMeshChunk>()
    private var meshNodesToRemove = mutableListOf<Long>()
    private var processedMeshNodes = ConcurrentHashMap<Long, MeshNode>()

    // Flag for reloading all chunks, mostly for if we receive a new mesh
    private var _needsMeshProcessing = false
    val needsMeshProcessing: Boolean get() = _needsMeshProcessing

    @Volatile private var isDestroyed = false

    // MaterialInstances created via material.createInstance() are NOT tracked by MaterialLoader,
    // so we must destroy them ourselves before MaterialLoader tears down the parent Material.
    private val trackedMaterialInstances = mutableListOf<MaterialInstance>()

    // Get the new mesh chunks that need to be processed. some chunks might be new, others might just be getting updated, while others are being removed
    @Synchronized
    fun updateMeshChunks(updatedMeshChunks: List<RenderableMeshChunk>, allUpdatedMeshIds : List<Long>) {

        // We need to remove any meshIds that are no longer tracked. Start with everything we currently have, and remove all the updated ones
        meshNodesToRemove.addAll(processedMeshNodes.keys)
        meshNodesToRemove.removeAll(allUpdatedMeshIds)

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
            val meshNode = processedMeshNodes.remove(chunkId)
            if (meshNode != null) {
                parentNode.removeChildNode(meshNode)
                // Destroy the Filament renderable entity so its MaterialInstance can be
                // safely destroyed later. removeChildNode only detaches from the scene
                // graph; it does not free GPU resources.
                meshNode.destroy()
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
            var correctedUvs: FloatArray? = null
            if (hasUvs) {
                correctedUvs = FloatArray(uvs.size) { i ->
                    uvs[i]
                }
            } else {
                Log.d(TAG, "Chunk: No UV data.")
            }

            /*
                Get the optional normal data. While typically used for lighting,
                here we're just using the data to set color values for the vertices.
                Using X,Y,Z as R,G,B values, and a fixed alpha of 0.5
             */
            val normals = meshData.normals
            val hasColors = normals != null && normals.isNotEmpty()
            var correctedColors: FloatArray? = null
            if( hasColors ) {
                correctedColors = FloatArray(vertexCount * 4)

                for (i in 0 until vertexCount) {
                    val colorIndex = i * 4
                    val normalIndex = i * 3
                    correctedColors[colorIndex + 0] = abs(normals[normalIndex + 0])     // X -> R
                    correctedColors[colorIndex + 1] = abs(normals[normalIndex + 1])     // Y -> G
                    correctedColors[colorIndex + 2] = abs(normals[normalIndex + 2])     // Z -> B
                    correctedColors[colorIndex + 3] = 0.5f                              // A
                }
            }

            // We need to convert the incoming float arrays into VertexBuffers
            val vertexStride = 3 * 4 // (X,Y,Z) * 4 bytes
            val uvStride = 2 * 4 // (U,V) * 4 bytes
            val colorStride = 4 * 4 // (R,G,B,A) * 4 bytes
            val correctedVertexBuffer = ByteBuffer.allocateDirect(vertexCount * vertexStride)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            val correctedUVBuffer = ByteBuffer.allocateDirect(vertexCount * uvStride)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            val correctedColorBuffer = ByteBuffer.allocateDirect(vertexCount * colorStride)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            var maxExtents = Float3(x = Float.MIN_VALUE, y = Float.MIN_VALUE, z = Float.MIN_VALUE)

            for (i in 0 until vertexCount) {
                correctedVertexBuffer.put(correctedVertices, i * 3, 3) // Put X, Y, Z

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
            correctedVertexBuffer.position(0)

            var bufferCount = 1
            var uvBufferIdx = -1
            var colorBufferIdx = -1

            // Create the UV buffer
            if (hasUvs) {
                bufferCount++
                uvBufferIdx = bufferCount -1
                for (i in 0 until vertexCount) {
                    correctedUVBuffer.put(correctedUvs, i * 2, 2) // Put U, V
                }
                correctedUVBuffer.position(0)
            }

            // Create the Normals buffer
            if (hasColors) {
                bufferCount++
                colorBufferIdx = bufferCount -1
                for (i in 0 until vertexCount) {
                    correctedColorBuffer.put(correctedColors, i * 4, 4) // Put R, G, B, A
                }
                correctedColorBuffer.position(0)
            }

            // Build the combined VertexBuffer (with both attributes if they exist)
            val vertexBufferBuilder = VertexBuffer.Builder()
                .bufferCount(bufferCount)
                .vertexCount(vertexCount)
                .attribute(
                    VertexBuffer.VertexAttribute.POSITION,
                    0,
                    VertexBuffer.AttributeType.FLOAT3,
                    0,
                    vertexStride
                )

            if (hasUvs) {
                vertexBufferBuilder.attribute(
                    VertexBuffer.VertexAttribute.UV0,
                    uvBufferIdx,
                    VertexBuffer.AttributeType.FLOAT2,
                    0,
                    uvStride
                )
            }

            // Normals are stored in the vertexBuffer as quaternions, so float4
            if (hasColors) {
                vertexBufferBuilder.attribute(
                    VertexBuffer.VertexAttribute.COLOR,
                    colorBufferIdx,
                    VertexBuffer.AttributeType.FLOAT4,
                    0,
                    colorStride
                )
            }

            val meshVertexBuffer = vertexBufferBuilder.build(engine)
            meshVertexBuffer.setBufferAt(engine, 0, correctedVertexBuffer)

            if (hasUvs) {
                meshVertexBuffer.setBufferAt(engine, uvBufferIdx, correctedUVBuffer)
            }

            if (hasColors) {
                meshVertexBuffer.setBufferAt(engine, colorBufferIdx, correctedColorBuffer)
            }

            // Process the mesh indices for this chunk
            chunk.indexCount = meshData.indices.size

            // IndexBuffer expects an untyped ByteBuffer as input, so we need to convert the intArray from the meshData into a byteBuffer
            val indicesBuffer = ByteBuffer.allocateDirect(chunk.indexCount * 4)
                .order(ByteOrder.nativeOrder())

            for (i in 0 until chunk.indexCount) {
                indicesBuffer.putInt(meshData.indices[i]) // Put the index into the byte array
            }
            indicesBuffer.position(0)

            val meshIndexBuffer = IndexBuffer.Builder()
                .indexCount(chunk.indexCount)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)

            meshIndexBuffer.setBuffer(engine, indicesBuffer)


            // Create the texture for this chunk
            var materialInstance: MaterialInstance? = null

            if (chunk.textureData == null) {
                Log.d(TAG, "Chunk: No texture data. Using dummy.")
                materialInstance = createUnlitVertexColorMaterial(context, materialLoader, _defaultMaterialColor)
            } else {
                val bitmap =
                    BitmapFactory.decodeByteArray(chunk.textureData, 0, chunk.textureData.size)
                if (bitmap != null) {

                    val texture = Texture.Builder()
                        .width(bitmap.width)
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

                    materialInstance = materialLoader.createImageInstance(texture, TextureSampler())

                    // Clean up the bitmap data
                    bitmap.recycle()
                } else {
                    Log.e(TAG, "Chunk: Failed to decode image. Using dummy.")
                    materialInstance = materialLoader.createColorInstance(_defaultMaterialColor)
                }
            }

            // Create the meshNodes from the processed chunk data
            val meshNode = MeshNode(
                engine = engine,
                primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer = meshVertexBuffer,
                indexBuffer = meshIndexBuffer,
                boundingBox = Box(0f, 0f, 0f, maxExtents.x, maxExtents.y, maxExtents.z),
                materialInstance = materialInstance,
            )

            // Set the relative position of the node from the chunk modelMatrix data
            meshNode.transform = Mat4(
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

            parentNode.addChildNode(meshNode)

            processedMeshNodes[chunkId] = meshNode
        }

        newMeshChunks.clear()
        _needsMeshProcessing = false
    }

    @Synchronized
    fun clearData() {
        newMeshChunks.clear()
        meshNodesToRemove.clear()
        // Destroy each node's Filament renderable entity before clearing the map.
        // Without this, the renderables remain alive and still reference their
        // MaterialInstances; destroy() would then call destroyMaterialInstance()
        // on MIs still in use → PreconditionPanic.
        processedMeshNodes.values.forEach { it.destroy() }
        processedMeshNodes.clear()
        _needsMeshProcessing = false
    }

    // Must be called before the Filament engine is torn down.
    // Synchronized with createMeshNodes so it either runs before any in-progress
    // mesh creation starts, or waits for it to finish before marking destroyed.
    @Synchronized
    fun destroy() {
        isDestroyed = true
        // Destroy MeshNodes first (removes entities; SceneView also destroys their VBs/IBs).
        processedMeshNodes.values.forEach { it.destroy() }
        processedMeshNodes.clear()
        // Destroy MaterialInstances we own (created via material.createInstance(), not by
        // MaterialLoader). Must happen before MaterialLoader destroys the parent Material,
        // otherwise Filament panics with PreconditionPanic at material destruction.
        trackedMaterialInstances.forEach { engine.destroyMaterialInstance(it) }
        trackedMaterialInstances.clear()
        newMeshChunks.clear()
        meshNodesToRemove.clear()
        _needsMeshProcessing = false
    }

    private fun createUnlitVertexColorMaterial(
        context: Context,
        materialLoader: MaterialLoader,
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
        trackedMaterialInstances.add(materialInstance)
        return materialInstance
    }
}

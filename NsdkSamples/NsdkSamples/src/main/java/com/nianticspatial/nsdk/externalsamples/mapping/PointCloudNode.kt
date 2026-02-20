// Copyright 2025 Niantic.

package com.nianticspatial.nsdk.externalsamples.mapping

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.math.Color
import io.github.sceneview.math.Position
import io.github.sceneview.node.GeometryNode

class PointCloudNode : GeometryNode {

    /**
     * Creates a point cloud node from a flat array of coordinates.
     * @param engine Filament Engine
     * @param points FloatArray with x,y,z coordinates (length = pointCount * 3)
     * @param pointCount Number of points to render
     * @param materialInstance Material to use for rendering
     * @param color Color for all points (default: green)
     */
    constructor(
        engine: Engine,
        points: FloatArray,
        pointCount: Int,
        materialInstance: MaterialInstance?,
        color: Color = Color(0.3f, 0.8f, 0.0f, 1.0f)
    ) : super(
        engine = engine,
        geometry = createPointCloudGeometry(engine, points, pointCount, color),
        materialInstance = materialInstance,
        builderApply = {
            // Enable culling to improve performance
            culling(true)
            // Points don't cast shadows
            castShadows(false)
            receiveShadows(false)
        }
    )

    /**
     * Creates a point cloud node from a list of positions.
     * @param engine Filament Engine
     * @param positions List of Position objects
     * @param materialInstance Material to use for rendering
     * @param color Color for all points (default: green)
     */
    constructor(
        engine: Engine,
        positions: List<Position>,
        materialInstance: MaterialInstance?,
        color: Color = Color(0.3f, 0.8f, 0.0f, 1.0f)
    ) : super(
        engine = engine,
        geometry = createPointCloudGeometry(engine, positions, color),
        materialInstance = materialInstance,
        builderApply = {
            culling(true)
            castShadows(false)
            receiveShadows(false)
        }
    )

    /**
     * Sets the point size for rendering.
     * Larger values make points more visible.
     *
     * @param size Point size in pixels (default: 10.0)
     */
    fun setPointSize(size: Float) {
        materialInstance?.setParameter("pointSize", size)
    }

    companion object {
        /**
         * Creates a point cloud geometry from a flat array of coordinates.
         * @param engine Filament Engine
         * @param points FloatArray with x,y,z coordinates (length = pointCount * 3)
         * @param pointCount Number of points
         * @param color Color for all points
         * @return Geometry configured for point cloud rendering
         */
        private fun createPointCloudGeometry(
            engine: Engine,
            points: FloatArray,
            pointCount: Int,
            color: Color
        ): Geometry {
            val vertices = mutableListOf<Geometry.Vertex>()

            for (i in 0 until pointCount) {
                val index = i * 3
                vertices.add(
                    Geometry.Vertex(
                        position = Position(points[index], points[index + 1], points[index + 2]),
                        color = color
                    )
                )
            }

            // Each point is its own index
            val indices = (0 until pointCount).toList()

            return Geometry.Builder(RenderableManager.PrimitiveType.POINTS)
                .vertices(vertices)
                .primitivesIndices(listOf(indices))
                .build(engine)
        }

        /**
         * Creates a point cloud geometry from a list of positions.
         * @param engine Filament Engine
         * @param positions List of Position objects
         * @param color Color for all points
         * @return Geometry configured for point cloud rendering
         */
        private fun createPointCloudGeometry(
            engine: Engine,
            positions: List<Position>,
            color: Color
        ): Geometry {
            val vertices = positions.map { position ->
                Geometry.Vertex(position = position, color = color)
            }

            val indices = (0 until positions.size).toList()

            return Geometry.Builder(RenderableManager.PrimitiveType.POINTS)
                .vertices(vertices)
                .primitivesIndices(listOf(indices))
                .build(engine)
        }
    }
}

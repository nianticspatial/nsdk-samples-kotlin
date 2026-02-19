package com.nianticspatial.nsdk.externalsamples.objectdetection

import android.graphics.RectF
import com.nianticspatial.nsdk.objectdetection.DetectedObject
import com.nianticspatial.nsdk.objectdetection.height
import com.nianticspatial.nsdk.objectdetection.width
import java.util.UUID
import kotlin.math.max

class AggregationManager(
    private val objectDetectionManager: ObjectDetectionManager
) {
    companion object {
        // How many frames to keep objects around while aggregating
        private const val AGGREGATION_WINDOW = 5

        // How many consecutive frames of larger detections are required before we allow an expansion
        private const val EXPANSION_HYSTERESIS = 3

        // Thresholds used for containment/merge decisions
        private const val CONTAINMENT_THRESHOLD = 0.88f
        private const val IOU_THRESHOLD = 0.8f

        // Require >5% area increase to consider growth
        private const val GROWTH_THRESHOLD = 1.05f
    }

    // Current state of aggregated objects
    private val aggregatedObjects = mutableListOf<AggregatedObject>()

    /**
     * Process new detections and update aggregated objects.
     * Returns the current list of aggregated objects.
     */
    fun update(detectedObjects: List<DetectedObject>): List<AggregatedObject> {
        // Mark all aggregated objects as unseen for this frame; we'll reset when matched
        aggregatedObjects.forEach { it.unseenFrames += 1 }

        // Merge current detections into aggregatedObjects
        for (detectedObj in detectedObjects) {
            val newRect = detectedObjToRectF(detectedObj)
            val className = objectDetectionManager.getObjectName(detectedObj) ?: "Unknown"
            val confidence = detectedObj.probability

            var matchedIndex: Int? = null
            var bestScore = 0f

            // Find the best matching aggregated object
            for ((index, agg) in aggregatedObjects.withIndex()) {
                // Compute IoU
                val iou = intersectionOverUnion(agg.rect, newRect)

                // Compute containment ratios
                val intersection = RectF()
                if (intersection.setIntersect(agg.rect, newRect)) {
                    val newRectArea = area(newRect)
                    val aggRectArea = area(agg.rect)
                    val intersectionArea = area(intersection)

                    // Avoid division by zero - if either rectangle has zero/negative area, skip containment calculation
                    val aggContainsNew = if (newRectArea > 0f) intersectionArea / newRectArea else 0f
                    val newContainsAgg = if (aggRectArea > 0f) intersectionArea / aggRectArea else 0f

                    // Prefer containment, otherwise fall back to IoU
                    val score = when {
                        aggContainsNew >= CONTAINMENT_THRESHOLD -> 1.0f + aggContainsNew
                        newContainsAgg >= CONTAINMENT_THRESHOLD -> 1.0f + newContainsAgg
                        else -> iou
                    }

                    if (score > bestScore &&
                        (score > IOU_THRESHOLD ||
                         aggContainsNew >= CONTAINMENT_THRESHOLD ||
                         newContainsAgg >= CONTAINMENT_THRESHOLD)) {
                        bestScore = score
                        matchedIndex = index
                    }
                }
            }

            if (matchedIndex != null) {
                // Update or expand the matched aggregated object
                updateMatchedObject(matchedIndex, newRect, className, confidence)
            } else {
                // No match -> create a new aggregated object
                val newAgg = AggregatedObject(
                    id = UUID.randomUUID().toString(),
                    rect = newRect,
                    className = className,
                    confidence = confidence,
                    unseenFrames = 0,
                    expansionStreak = 0
                )
                aggregatedObjects.add(newAgg)
            }
        }

        // Remove stale aggregated objects
        aggregatedObjects.removeAll { it.unseenFrames > AGGREGATION_WINDOW }

        return aggregatedObjects.toList()
    }

    /**
     * Update a matched aggregated object based on the relationship between old and new rectangles.
     */
    private fun updateMatchedObject(
        index: Int,
        newRect: RectF,
        className: String,
        confidence: Float
    ) {
        val agg = aggregatedObjects[index]
        val oldRect = agg.rect
        val oldArea = area(oldRect)
        val newArea = area(newRect)

        when {
            // Case 1: newRect is inside existing -> nothing to change except refresh
            oldRect.contains(newRect) -> {
                agg.unseenFrames = 0
                // Keep the class name with higher confidence
                if (agg.confidence < confidence) {
                    agg.className = className
                }
                agg.confidence = max(agg.confidence, confidence)
                agg.expansionStreak = 0
            }

            // Case 2: new rect contains old -> consider expansion only after hysteresis
            newRect.contains(oldRect) -> {
                val growthRatio = if (oldArea > 0) (newArea / oldArea) else 1.0f
                if (growthRatio > GROWTH_THRESHOLD) {
                    agg.expansionStreak += 1
                } else {
                    agg.expansionStreak = 0
                }

                if (agg.expansionStreak >= EXPANSION_HYSTERESIS) {
                    // Apply expansion (smoothed) and reset streak
                    agg.rect = smoothedRect(old = oldRect, new = newRect)
                    agg.expansionStreak = 0
                    agg.className = className
                }

                agg.unseenFrames = 0
                agg.confidence = max(agg.confidence, confidence)
            }

            // Case 3: Overlapping -> consider union expansion with hysteresis
            else -> {
                val unionRect = RectF(oldRect)
                unionRect.union(newRect)
                val unionArea = area(unionRect)
                val unionRatio = if (oldArea > 0) (unionArea / oldArea) else 1.0f

                if (unionRatio > GROWTH_THRESHOLD) {
                    agg.expansionStreak += 1
                } else {
                    agg.expansionStreak = 0
                }

                if (agg.expansionStreak >= EXPANSION_HYSTERESIS) {
                    agg.rect = smoothedRect(old = oldRect, new = unionRect)
                    agg.expansionStreak = 0
                }

                agg.unseenFrames = 0
                agg.confidence = max(agg.confidence, confidence)
            }
        }
    }

    /**
     * Convert a DetectedObject to a RectF.
     */
    private fun detectedObjToRectF(obj: DetectedObject): RectF {
        return RectF(
            obj.left,
            obj.top,
            obj.left + obj.width(),
            obj.top + obj.height()
        )
    }

    /**
     * Clear all aggregated objects.
     */
    fun clear() {
        aggregatedObjects.clear()
    }

    /**
     * Get the current list of aggregated objects.
     */
    fun getAggregatedObjects(): List<AggregatedObject> {
        return aggregatedObjects.toList()
    }
}


package com.nianticspatial.nsdk.externalsamples.objectdetection

import android.graphics.Paint as AndroidPaint
import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot

/**
 * TapSelect presenter that only shows a selected object when the user taps/holds.
 */
class TapSelectPresenter {
    private var aggregatedObjects by mutableStateOf<List<AggregatedObject>>(emptyList())
    private val maxDistance = 100.0f
    
    var selectedObject by mutableStateOf<AggregatedObject?>(null)
        private set

    fun update(aggregatedObjects: List<AggregatedObject>) {
        this.aggregatedObjects = aggregatedObjects
    }

    fun handleTouchBegan(at: Offset) {
        selectedObject = pickCandidate(at)
    }

    fun handleTouchEnded() {
        selectedObject = null
    }

    fun clear() {
        aggregatedObjects = emptyList()
        selectedObject = null
    }

    /**
     * Find the best candidate object at the given point.
     */
    private fun pickCandidate(at: Offset): AggregatedObject? {
        val candidates = aggregatedObjects.filter { obj ->
            val contains = obj.rect.contains(at.x, at.y)
            contains
        }

        // If we have candidates from containment, return the first one
        if (candidates.isNotEmpty()) {
            return candidates.first()
        }

        // Otherwise, find the nearest object within a small radius
        var nearest: AggregatedObject? = null
        var bestDist = Float.MAX_VALUE

        for (agg in aggregatedObjects) {
            val centerX = agg.rect.centerX()
            val centerY = agg.rect.centerY()
            val distance = hypot(centerX - at.x, centerY - at.y)
            if (distance < bestDist && distance <= maxDistance) {
                bestDist = distance
                nearest = agg
            }
        }

        return nearest
    }

    @Composable
    fun Draw(modifier: Modifier = Modifier) {
        val density = LocalDensity.current
        val currentSelected = selectedObject

        Canvas(modifier = modifier.fillMaxSize()) {
            if (currentSelected == null) return@Canvas

            val rect = currentSelected.rect
            val strokeWidth = with(density) { 3.dp.toPx() }
            val textSize = with(density) { 12.sp.toPx() }
            val labelPadding = with(density) { 4.dp.toPx() }

            // Draw bounding box border (yellow for selected)
            drawRect(
                color = Color.Yellow,
                topLeft = Offset(rect.left, rect.top),
                size = ComposeSize(rect.width(), rect.height()),
                style = Stroke(width = strokeWidth)
            )

            // Draw label background and text
            val className = currentSelected.className.ifBlank { "Unknown" }
            val confidencePercent = (currentSelected.confidence * 100).toInt()
            val labelText = "$className ($confidencePercent%)"

            // Measure text to determine label background size
            val textPaint = AndroidPaint().apply {
                color = android.graphics.Color.WHITE
                this.textSize = textSize
                isAntiAlias = true
            }
            val textBounds = AndroidRect()
            textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)

            val labelWidth = textBounds.width() + labelPadding * 2
            val labelHeight = textBounds.height() + labelPadding * 2
            val labelTop = rect.top.coerceAtLeast(0f)
            val labelLeft = rect.left.coerceAtLeast(0f)

            // Draw label background
            drawRect(
                color = Color.Black.copy(alpha = 0.7f),
                topLeft = Offset(labelLeft, labelTop),
                size = ComposeSize(labelWidth, labelHeight)
            )

            // Draw text
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    labelText,
                    labelLeft + labelPadding,
                    labelTop + labelHeight - labelPadding,
                    textPaint
                )
            }
        }
    }
}

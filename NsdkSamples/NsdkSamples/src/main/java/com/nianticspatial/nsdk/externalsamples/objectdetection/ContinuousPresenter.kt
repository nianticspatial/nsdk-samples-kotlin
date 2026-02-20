package com.nianticspatial.nsdk.externalsamples.objectdetection

import android.graphics.Paint as AndroidPaint
import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

/**
 * Continuous presenter that shows all detected objects with red bounding boxes.
 */
class ContinuousPresenter {
    private var aggregatedObjects by mutableStateOf<List<AggregatedObject>>(emptyList())

    fun update(aggregatedObjects: List<AggregatedObject>) {
        this.aggregatedObjects = aggregatedObjects
    }

    fun clear() {
        aggregatedObjects = emptyList()
    }

    @Composable
    fun Draw(modifier: Modifier = Modifier) {
        val density = LocalDensity.current
        val currentObjects = aggregatedObjects

        Canvas(modifier = modifier.fillMaxSize()) {
            val strokeWidth = with(density) { 2.dp.toPx() }
            val textSize = with(density) { 12.sp.toPx() }
            val labelPadding = with(density) { 4.dp.toPx() }

            currentObjects.forEach { obj ->
                val rect = obj.rect

                // Draw bounding box border (red)
                drawRect(
                    color = Color.Red,
                    topLeft = Offset(rect.left, rect.top),
                    size = ComposeSize(rect.width(), rect.height()),
                    style = Stroke(width = strokeWidth)
                )

                val className = obj.className.ifBlank { "Unknown" }
                val labelText = "${className} (${String.format("%.2f", obj.confidence)})"

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

                drawRect(
                    color = Color.DarkGray.copy(alpha = 0.7f),
                    topLeft = Offset(labelLeft, labelTop),
                    size = ComposeSize(labelWidth, labelHeight)
                )

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
}

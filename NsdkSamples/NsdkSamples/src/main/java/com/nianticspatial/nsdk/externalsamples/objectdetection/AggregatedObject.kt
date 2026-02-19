package com.nianticspatial.nsdk.externalsamples.objectdetection

import android.graphics.RectF


data class AggregatedObject(
    val id: String,
    var rect: RectF,
    var className: String,
    var confidence: Float,
    var unseenFrames: Int,
    /// consecutive frames where incoming detections indicated a larger box than current
    var expansionStreak: Int
)

/**
 * Calculate the area of a rectangle.
 */
fun area(r: RectF): Float {
    return r.width() * r.height()
}

/**
 * Calculate Intersection over Union (IoU) between two rectangles.
 */
fun intersectionOverUnion(a: RectF, b: RectF): Float {
    val inter = RectF()
    if (!inter.setIntersect(a, b)) {
        return 0f
    }
    val interArea = area(inter)
    if (interArea <= 0) return 0f
    val unionArea = area(a) + area(b) - interArea
    return if (unionArea > 0) interArea / unionArea else 0f
}

/**
 * Smooths rectangles to avoid unchecked growth: grow slowly (small alpha) and shrink faster (larger alpha).
 */
fun smoothedRect(old: RectF, new: RectF): RectF {
    val oldArea = area(old)
    val newArea = area(new)

    // If either rect is zero-area, prefer the other
    if (oldArea <= 0) return RectF(new)
    if (newArea <= 0) return RectF(old)

    // When new area is larger (expansion), use smaller scaleRate to slow growth.
    // When new area is smaller (shrink), use larger scaleRate so we can shrink quicker and avoid creep.
    val scaleRate: Float = if (newArea > oldArea) 0.18f else 0.6f

    val oldCenterX = old.centerX()
    val oldCenterY = old.centerY()
    val newCenterX = new.centerX()
    val newCenterY = new.centerY()

    val centerX = oldCenterX + (newCenterX - oldCenterX) * scaleRate
    val centerY = oldCenterY + (newCenterY - oldCenterY) * scaleRate

    val width = old.width() + (new.width() - old.width()) * scaleRate
    val height = old.height() + (new.height() - old.height()) * scaleRate

    return RectF(
        centerX - width / 2f,
        centerY - height / 2f,
        centerX + width / 2f,
        centerY + height / 2f
    )
}

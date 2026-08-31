package com.warehouse.stockchecker.ml

import kotlin.math.max
import kotlin.math.min

/**
 * Axis-aligned box using pure Kotlin types so it can be unit tested on the JVM
 * without the Android framework (android.graphics.RectF is not available there).
 *
 * Coordinates are normalized to the range [0, 1].
 */
data class BoxF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
    val area: Float get() = width * height

    fun clamp(minValue: Float = 0f, maxValue: Float = 1f): BoxF = BoxF(
        left = left.coerceIn(minValue, maxValue),
        top = top.coerceIn(minValue, maxValue),
        right = right.coerceIn(minValue, maxValue),
        bottom = bottom.coerceIn(minValue, maxValue)
    )

    fun intersectionOverUnion(other: BoxF): Float {
        val interWidth = max(0f, min(right, other.right) - max(left, other.left))
        val interHeight = max(0f, min(bottom, other.bottom) - max(top, other.top))
        val intersection = interWidth * interHeight
        val union = area + other.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    companion object {
        /** Builds a box from a YOLO center-based prediction. */
        fun fromCenter(centerX: Float, centerY: Float, width: Float, height: Float): BoxF = BoxF(
            left = centerX - width / 2f,
            top = centerY - height / 2f,
            right = centerX + width / 2f,
            bottom = centerY + height / 2f
        )
    }
}

/**
 * A single detected mechanical part.
 *
 * @param box normalized coordinates relative to the upright camera frame.
 */
data class Detection(
    val classId: Int,
    val label: String,
    val score: Float,
    val box: BoxF
) {
    /** e.g. "Bolt 95%" */
    val displayText: String
        get() = "${label.replace('_', ' ').replaceFirstChar { it.uppercase() }} ${(score * 100).toInt()}%"
}

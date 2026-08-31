package com.warehouse.stockchecker.ui

import android.graphics.Color

/**
 * Stable, high contrast colour per detected class so a part keeps the same
 * colour between frames.
 */
object ClassColors {

    private val palette = intArrayOf(
        Color.parseColor("#FF5252"), // red
        Color.parseColor("#FFB300"), // amber
        Color.parseColor("#00E676"), // green
        Color.parseColor("#40C4FF"), // light blue
        Color.parseColor("#E040FB"), // purple
        Color.parseColor("#FF6E40"), // deep orange
        Color.parseColor("#1DE9B6"), // teal
        Color.parseColor("#FFFF00"), // yellow
        Color.parseColor("#7C4DFF")  // indigo
    )

    fun forClass(classId: Int): Int {
        val index = if (classId < 0) 0 else classId % palette.size
        return palette[index]
    }
}

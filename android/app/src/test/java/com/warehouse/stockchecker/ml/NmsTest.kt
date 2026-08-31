package com.warehouse.stockchecker.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmsTest {

    private fun detection(
        classId: Int,
        score: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) = Detection(classId, "class_$classId", score, BoxF(left, top, right, bottom))

    @Test
    fun `keeps highest scoring box among heavy overlaps`() {
        val kept = Nms.apply(
            listOf(
                detection(1, 0.90f, 0.10f, 0.10f, 0.50f, 0.50f),
                detection(1, 0.60f, 0.11f, 0.11f, 0.51f, 0.51f)
            )
        )

        assertEquals(1, kept.size)
        assertEquals(0.90f, kept[0].score, 1e-6f)
    }

    @Test
    fun `keeps distant boxes of the same class`() {
        val kept = Nms.apply(
            listOf(
                detection(1, 0.90f, 0.00f, 0.00f, 0.20f, 0.20f),
                detection(1, 0.80f, 0.60f, 0.60f, 0.90f, 0.90f)
            )
        )

        assertEquals(2, kept.size)
    }

    @Test
    fun `overlap across different classes is not suppressed`() {
        val kept = Nms.apply(
            listOf(
                detection(1, 0.90f, 0.10f, 0.10f, 0.50f, 0.50f),
                detection(2, 0.85f, 0.10f, 0.10f, 0.50f, 0.50f)
            )
        )

        assertEquals(2, kept.size)
        assertEquals(setOf(1, 2), kept.map { it.classId }.toSet())
    }

    @Test
    fun `results are sorted by score and capped`() {
        val input = (1..10).map { index ->
            detection(index, index / 10f, 0f, 0f, 0.1f, 0.1f)
        }

        val kept = Nms.apply(input, iouThreshold = 0.45f, maxDetections = 3)

        assertEquals(3, kept.size)
        assertTrue(kept[0].score >= kept[1].score)
        assertTrue(kept[1].score >= kept[2].score)
        assertEquals(1.0f, kept[0].score, 1e-6f)
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(Nms.apply(emptyList()).isEmpty())
    }
}

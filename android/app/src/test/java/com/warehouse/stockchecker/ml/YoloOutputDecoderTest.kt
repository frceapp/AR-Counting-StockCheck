package com.warehouse.stockchecker.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloOutputDecoderTest {

    private val labels = listOf(
        "-1", "bearing", "bolt", "flange", "gear", "nut", "retaining_ring", "spring", "washer"
    )
    private val numFeatures = 4 + labels.size

    /**
     * Zero-filled anchors used as padding. The layout auto-detection assumes the anchor
     * dimension dominates the feature dimension (8400 vs 13 in the real model), so a
     * fixture must carry more anchors than features to be interpreted correctly.
     */
    private fun padding(count: Int = numFeatures + 4) = List(count) { Anchor(0f, 0f, 0f, 0f, 1, 0f) }

    /** Builds a `[1, numFeatures, numAnchors]` (channels-first) tensor. */
    private fun channelsFirst(anchors: List<Anchor>): Pair<FloatArray, IntArray> {
        val data = FloatArray(numFeatures * anchors.size)
        anchors.forEachIndexed { anchorIndex, anchor ->
            anchor.write { feature, value -> data[feature * anchors.size + anchorIndex] = value }
        }
        return data to intArrayOf(1, numFeatures, anchors.size)
    }

    /** Builds a `[1, numAnchors, numFeatures]` (channels-last) tensor. */
    private fun channelsLast(anchors: List<Anchor>): Pair<FloatArray, IntArray> {
        val data = FloatArray(numFeatures * anchors.size)
        anchors.forEachIndexed { anchorIndex, anchor ->
            anchor.write { feature, value -> data[anchorIndex * numFeatures + feature] = value }
        }
        return data to intArrayOf(1, anchors.size, numFeatures)
    }

    private inner class Anchor(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val classId: Int,
        val score: Float
    ) {
        fun write(set: (feature: Int, value: Float) -> Unit) {
            set(0, cx)
            set(1, cy)
            set(2, w)
            set(3, h)
            set(4 + classId, score)
        }
    }

    @Test
    fun `decodes a channels-first tensor`() {
        val anchors = padding() +
            Anchor(cx = 0.5f, cy = 0.5f, w = 0.2f, h = 0.4f, classId = 2, score = 0.95f)
        val (data, shape) = channelsFirst(anchors)

        val detections = YoloOutputDecoder(labels).decode(data, shape)

        assertEquals(1, detections.size)
        val detection = detections.single()
        assertEquals(2, detection.classId)
        assertEquals("bolt", detection.label)
        assertEquals(0.95f, detection.score, 1e-6f)
        assertEquals(0.4f, detection.box.left, 1e-5f)
        assertEquals(0.3f, detection.box.top, 1e-5f)
        assertEquals(0.6f, detection.box.right, 1e-5f)
        assertEquals(0.7f, detection.box.bottom, 1e-5f)
        assertEquals("Bolt 95%", detection.displayText)
    }

    @Test
    fun `decodes a channels-last tensor identically`() {
        val anchors = padding() +
            Anchor(cx = 0.5f, cy = 0.5f, w = 0.2f, h = 0.4f, classId = 4, score = 0.8f)

        val fromFirst = channelsFirst(anchors).let { (d, s) -> YoloOutputDecoder(labels).decode(d, s) }
        val fromLast = channelsLast(anchors).let { (d, s) -> YoloOutputDecoder(labels).decode(d, s) }

        assertEquals(1, fromLast.size)
        assertEquals(fromFirst, fromLast)
        assertEquals("gear", fromLast.single().label)
    }

    @Test
    fun `drops the placeholder class -1`() {
        val anchors = padding() +
            Anchor(cx = 0.5f, cy = 0.5f, w = 0.4f, h = 0.4f, classId = 0, score = 0.99f)
        val (data, shape) = channelsFirst(anchors)

        val detections = YoloOutputDecoder(labels).decode(data, shape)

        assertTrue(detections.isEmpty())
    }

    @Test
    fun `drops scores below the confidence threshold`() {
        val anchors = padding() +
            Anchor(cx = 0.5f, cy = 0.5f, w = 0.4f, h = 0.4f, classId = 1, score = 0.20f)
        val (data, shape) = channelsFirst(anchors)

        assertTrue(YoloOutputDecoder(labels, confidenceThreshold = 0.35f).decode(data, shape).isEmpty())
        assertEquals(1, YoloOutputDecoder(labels, confidenceThreshold = 0.10f).decode(data, shape).size)
    }

    @Test
    fun `suppresses duplicate boxes of the same class`() {
        val anchors = padding() + listOf(
            Anchor(cx = 0.50f, cy = 0.50f, w = 0.30f, h = 0.30f, classId = 5, score = 0.90f),
            Anchor(cx = 0.51f, cy = 0.51f, w = 0.30f, h = 0.30f, classId = 5, score = 0.70f)
        )
        val (data, shape) = channelsFirst(anchors)

        val detections = YoloOutputDecoder(labels).decode(data, shape)

        assertEquals(1, detections.size)
        assertEquals(0.90f, detections.single().score, 1e-6f)
    }

    @Test
    fun `applies the letterbox transform to reported boxes`() {
        val anchors = padding() +
            Anchor(cx = 0.5f, cy = 0.5f, w = 0.2f, h = 0.2f, classId = 7, score = 0.9f)
        val (data, shape) = channelsFirst(anchors)
        val transform = LetterboxTransform.of(sourceWidth = 1280, sourceHeight = 720, inputSize = 640)

        val detection = YoloOutputDecoder(labels).decode(data, shape, transform).single()

        // A model-space centre stays a source-space centre; the box grows vertically
        // because the letterbox padding is removed from the height.
        assertEquals(0.5f, detection.box.centerX, 1e-3f)
        assertEquals(0.5f, detection.box.centerY, 1e-3f)
        assertEquals(0.2f, detection.box.width, 1e-3f)
        assertTrue(detection.box.height > detection.box.width)
    }

    @Test
    fun `degenerate boxes are skipped`() {
        val anchors = padding() +
            Anchor(cx = 0.5f, cy = 0.5f, w = 0f, h = 0f, classId = 3, score = 0.9f)
        val (data, shape) = channelsFirst(anchors)

        assertTrue(YoloOutputDecoder(labels).decode(data, shape).isEmpty())
    }

    @Test
    fun `ignored label detection covers placeholder and background`() {
        assertTrue(YoloOutputDecoder.isIgnoredLabel("-1"))
        assertTrue(YoloOutputDecoder.isIgnoredLabel(" background "))
        assertTrue(YoloOutputDecoder.isIgnoredLabel(""))
        assertFalse(YoloOutputDecoder.isIgnoredLabel("bolt"))
    }
}

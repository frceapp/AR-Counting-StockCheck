package com.warehouse.stockchecker.tracking

import com.warehouse.stockchecker.ml.BoxF
import com.warehouse.stockchecker.ml.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTrackerTest {

    private fun detection(
        classId: Int = 1,
        label: String = "bolt",
        score: Float = 0.9f,
        left: Float = 0.2f,
        top: Float = 0.2f,
        right: Float = 0.4f,
        bottom: Float = 0.4f
    ) = Detection(classId, label, score, BoxF(left, top, right, bottom))

    @Test
    fun `single sighting is not published`() {
        val tracker = DetectionTracker()

        assertTrue(tracker.update(listOf(detection()), 0L).isEmpty())
    }

    @Test
    fun `second sighting confirms the anchor`() {
        val tracker = DetectionTracker()
        tracker.update(listOf(detection()), 0L)

        val tracks = tracker.update(listOf(detection()), 400L)

        assertEquals(1, tracks.size)
        assertEquals(2, tracks[0].hits)
        assertTrue(tracks[0].isLive)
    }

    @Test
    fun `unconfirmed noise expires and never appears`() {
        val tracker = DetectionTracker()
        tracker.update(listOf(detection()), 0L)

        // Nothing seen for longer than the pending retention window.
        assertTrue(tracker.update(emptyList(), 5_000L).isEmpty())
        // The stale candidate is gone, so a later sighting starts counting from scratch.
        assertTrue(tracker.update(listOf(detection()), 5_400L).isEmpty())
    }

    @Test
    fun `confirmed anchor is held when the model misses the part`() {
        val tracker = DetectionTracker()
        tracker.update(listOf(detection()), 0L)
        tracker.update(listOf(detection()), 400L)

        val held = tracker.update(emptyList(), 3_000L)

        assertEquals(1, held.size)
        assertFalse(held[0].isLive)
        assertEquals(2_600L, held[0].millisSinceSeen)
    }

    @Test
    fun `confirmed anchor expires after the retention window`() {
        val tracker = DetectionTracker(confirmedRetentionMillis = 1_000L)
        tracker.update(listOf(detection()), 0L)
        tracker.update(listOf(detection()), 100L)

        assertTrue(tracker.update(emptyList(), 2_000L).isEmpty())
    }

    @Test
    fun `overlapping sightings keep the same track id`() {
        val tracker = DetectionTracker()
        tracker.update(listOf(detection()), 0L)
        val first = tracker.update(listOf(detection()), 400L)

        // Nudged slightly: still a high IoU with the anchor.
        val moved = detection(left = 0.22f, top = 0.22f, right = 0.42f, bottom = 0.42f)
        val second = tracker.update(listOf(moved), 800L)

        assertEquals(first[0].trackId, second[0].trackId)
        assertEquals(3, second[0].hits)
    }

    @Test
    fun `same class at a different place becomes a separate anchor`() {
        val tracker = DetectionTracker()
        val far = detection(left = 0.7f, top = 0.7f, right = 0.9f, bottom = 0.9f)

        tracker.update(listOf(detection(), far), 0L)
        val tracks = tracker.update(listOf(detection(), far), 400L)

        assertEquals(2, tracks.size)
        assertEquals(2, tracks.map { it.trackId }.distinct().size)
    }

    @Test
    fun `boxes at the same place but different classes do not merge`() {
        val tracker = DetectionTracker()
        val bolt = detection(classId = 1, label = "bolt")
        val nut = detection(classId = 2, label = "nut")

        tracker.update(listOf(bolt, nut), 0L)
        val tracks = tracker.update(listOf(bolt, nut), 400L)

        assertEquals(setOf("bolt", "nut"), tracks.map { it.detection.label }.toSet())
    }

    @Test
    fun `anchor box is smoothed towards the newest sighting`() {
        val tracker = DetectionTracker(smoothing = 0.5f)
        tracker.update(listOf(detection(left = 0.2f, right = 0.4f)), 0L)

        val tracks = tracker.update(listOf(detection(left = 0.3f, right = 0.5f)), 400L)

        // Halfway between 0.2 and 0.3 with a 0.5 blend weight.
        assertEquals(0.25f, tracks[0].detection.box.left, 1e-4f)
        assertEquals(0.45f, tracks[0].detection.box.right, 1e-4f)
    }

    @Test
    fun `confirmed anchor survives an arbitrarily long gap by default`() {
        val tracker = DetectionTracker()
        tracker.update(listOf(detection()), 0L)
        tracker.update(listOf(detection()), 400L)

        // Between captures nothing is analysed at all, so an anchor must never time out.
        val held = tracker.update(emptyList(), 10 * 60 * 1_000L)

        assertEquals(1, held.size)
        assertFalse(held[0].isLive)
    }

    @Test
    fun `dropUnconfirmed forgets candidates but keeps anchors`() {
        val tracker = DetectionTracker()
        val noise = detection(
            classId = 2,
            label = "nut",
            left = 0.7f,
            top = 0.7f,
            right = 0.9f,
            bottom = 0.9f
        )
        tracker.update(listOf(detection()), 0L)
        tracker.update(listOf(detection(), noise), 400L)

        tracker.dropUnconfirmed()

        // The bolt was seen twice so it stays; the single nut sighting is discarded.
        assertEquals(listOf("bolt"), tracker.update(emptyList(), 800L).map { it.detection.label })
        // And the dropped candidate counts from scratch instead of confirming instantly.
        assertEquals(listOf("bolt"), tracker.update(listOf(noise), 1_200L).map { it.detection.label })
    }

    @Test
    fun `reset drops every anchor`() {
        val tracker = DetectionTracker()
        tracker.update(listOf(detection()), 0L)
        tracker.update(listOf(detection()), 400L)

        tracker.reset()

        assertTrue(tracker.update(emptyList(), 800L).isEmpty())
    }

    @Test
    fun `anchors are returned highest score first`() {
        val tracker = DetectionTracker()
        val weak = detection(classId = 1, label = "bolt", score = 0.4f)
        val strong = detection(
            classId = 2,
            label = "nut",
            score = 0.95f,
            left = 0.6f,
            top = 0.6f,
            right = 0.8f,
            bottom = 0.8f
        )

        tracker.update(listOf(weak, strong), 0L)
        val tracks = tracker.update(listOf(weak, strong), 400L)

        assertEquals(listOf("nut", "bolt"), tracks.map { it.detection.label })
    }
}

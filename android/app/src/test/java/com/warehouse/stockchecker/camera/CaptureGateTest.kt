package com.warehouse.stockchecker.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGateTest {

    @Test
    fun `idle gate drops every frame`() {
        val gate = CaptureGate()

        assertFalse(gate.isCapturing)
        assertEquals(0, gate.remainingFrames)
        assertFalse(gate.tryConsume())
        assertFalse(gate.tryConsume())
    }

    @Test
    fun `arm allows exactly the burst length`() {
        val gate = CaptureGate(burstFrames = 3)
        gate.arm()

        assertEquals(3, gate.remainingFrames)
        assertTrue(gate.tryConsume())
        assertTrue(gate.tryConsume())
        assertTrue(gate.tryConsume())
        assertFalse(gate.tryConsume())
    }

    @Test
    fun `isCapturing stays true until the last frame is claimed`() {
        val gate = CaptureGate(burstFrames = 2)
        gate.arm()

        gate.tryConsume()
        // One frame left, so the UI should still report a capture in progress.
        assertTrue(gate.isCapturing)

        gate.tryConsume()
        assertFalse(gate.isCapturing)
    }

    @Test
    fun `cancel abandons the rest of the burst`() {
        val gate = CaptureGate(burstFrames = 4)
        gate.arm()
        gate.tryConsume()

        gate.cancel()

        assertFalse(gate.isCapturing)
        assertEquals(0, gate.remainingFrames)
        assertFalse(gate.tryConsume())
    }

    @Test
    fun `re-arming refills the budget instead of adding to it`() {
        val gate = CaptureGate(burstFrames = 2)
        gate.arm()
        gate.tryConsume()

        gate.arm()

        assertEquals(2, gate.remainingFrames)
    }

    @Test
    fun `default burst is long enough to confirm an anchor`() {
        val gate = CaptureGate()
        gate.arm()

        assertTrue(
            "a burst must cover at least DEFAULT_MIN_HITS sightings",
            gate.remainingFrames >= 2
        )
    }
}

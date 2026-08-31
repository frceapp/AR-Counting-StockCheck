package com.warehouse.stockchecker.camera

/**
 * Decides which camera frames are worth running the model on.
 *
 * Continuous inference costs ~300-600 ms per frame on this device, which makes the whole
 * app feel slow. Instead the user presses Capture: that arms a short burst of frames, the
 * model runs only on those, and everything else is dropped for free.
 *
 * A burst rather than a single frame matters because the tracker needs
 * [com.warehouse.stockchecker.tracking.WorldAnchorTracker.DEFAULT_MIN_HITS] sightings before it
 * trusts a box, and it gives the auto focus a moment to settle.
 *
 * Thread safe: armed from the UI thread, consumed on the analysis thread.
 */
class CaptureGate(private val burstFrames: Int = DEFAULT_BURST_FRAMES) {

    private var remaining = 0

    /** True while frames are still being consumed from the current capture. */
    val isCapturing: Boolean
        @Synchronized get() = remaining > 0

    /** Frames left in the current burst; 0 when idle. */
    val remainingFrames: Int
        @Synchronized get() = remaining

    /** Starts (or restarts) a capture burst. */
    @Synchronized
    fun arm() {
        remaining = burstFrames
    }

    /** Abandons the current burst, e.g. when the user clears the scene. */
    @Synchronized
    fun cancel() {
        remaining = 0
    }

    /**
     * Claims one frame of the burst.
     *
     * @return true when the caller should analyse this frame, false when it should be dropped.
     */
    @Synchronized
    fun tryConsume(): Boolean {
        if (remaining <= 0) return false
        remaining--
        return true
    }

    companion object {
        /** ~1.2 s at the measured throughput: enough to confirm an anchor twice over. */
        const val DEFAULT_BURST_FRAMES = 3
    }
}

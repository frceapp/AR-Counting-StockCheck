package com.warehouse.stockchecker.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.warehouse.stockchecker.camera.CameraOrientation
import com.warehouse.stockchecker.ml.PartDetector
import com.warehouse.stockchecker.tracking.WorldAnchorTracker
import com.warehouse.stockchecker.tracking.WorldTrackedDetection

/**
 * Bridges CameraX frames into [PartDetector] and the world [WorldAnchorTracker].
 *
 * Inference is **capture driven**: frames are dropped without any work until [CaptureGate]
 * is armed by the user pressing Capture. That keeps the preview smooth, since the model
 * needs a few hundred milliseconds per frame on CPU.
 *
 * CameraX invokes [analyze] on the executor supplied to [ImageAnalysis.setAnalyzer]; a
 * single threaded executor together with `STRATEGY_KEEP_ONLY_LATEST` guarantees no
 * overlapping inference calls, which matches the non thread safe detector and tracker.
 */
class DetectionAnalyzer(
    private val detector: PartDetector,
    private val tracker: WorldAnchorTracker,
    private val gate: CaptureGate,
    private val orientationProvider: OrientationProvider,
    private val onResult: (AnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    /**
     * One analysed frame plus the state of the capture that produced it.
     *
     * @param tracks every world-anchored detection, projected into the current camera frame;
     *   these stay on screen between captures until the user clears them.
     * @param bestScore highest class score seen anywhere in the burst, confident or not. It
     *   turns "nothing found" into something actionable: a near miss reads very differently
     *   from pure noise.
     * @param capturing true while more frames of the current burst are still pending.
     */
    data class AnalysisResult(
        val frame: PartDetector.DetectionFrame,
        val tracks: List<WorldTrackedDetection>,
        val inferenceTimeMs: Long,
        val bestScore: Float,
        val capturing: Boolean
    )

    private var lastLogNanos = 0L
    private var burstBestScore = 0f
    private var startingNewBurst = true

    /**
     * Bookkeeping for the idle re-projection throttle.
     *
     * We only need to redraw when the camera has actually moved enough to put the projected
     * boxes in a different place on screen; otherwise the previous frame is still correct.
     */
    private var lastIdleNanos = 0L
    private var lastEmittedOrientation: CameraOrientation? = null

    override fun analyze(image: ImageProxy) {
        try {
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val nowMillis = System.currentTimeMillis()
            val orientation = orientationProvider.orientationAt(nowNanos)

            // Burst-driven inference: only the frames inside a capture reach the model.
            val consuming = gate.tryConsume()
            if (!consuming) {
                // No inference this frame; re-project existing anchors through the camera
                // motion and publish so the overlay stays glued to the world point.
                val idleTracks = tracker.update(
                    detections = emptyList(),
                    current = orientation,
                    timestampMillis = nowMillis,
                    timestampNanos = nowNanos
                )
                emitIdle(idleTracks, orientation, nowMillis, nowNanos)
                return
            }

            // ImageAnalysis is configured for RGBA_8888 output, so this is a direct copy.
            val bitmap = image.toBitmap()
            val frame = detector.detect(bitmap, image.imageInfo.rotationDegrees)

            // Associate detections to projected anchors and re-emit. Skipping the idle call
            // here avoids doing the same math twice for the same orientation.
            val tracks = tracker.update(
                detections = frame.detections,
                current = orientation,
                timestampMillis = nowMillis,
                timestampNanos = nowNanos
            )

            val capturing = gate.isCapturing
            // Burst finished: candidates that never reached the hit threshold were noise.
            if (!capturing) tracker.dropUnconfirmed()

            if (startingNewBurst) burstBestScore = 0f
            startingNewBurst = !capturing
            burstBestScore = maxOf(burstBestScore, frame.peakScore)

            // Reset the idle throttle so the next non-burst frame redraws even if the camera
            // happens to be still — the burst just produced fresh tracks that need to land.
            lastEmittedOrientation = null

            val result =
                AnalysisResult(frame, tracks, frame.inferenceTimeMs, burstBestScore, capturing)
            log(result)
            onResult(result)
        } catch (t: Throwable) {
            // A single bad frame must never tear down the camera pipeline.
            Log.e(TAG, "Frame analysis failed", t)
        } finally {
            image.close()
        }
    }

    /**
     * Publishes the latest re-projected tracks without a new inference.
     *
     * Skipped when there's nothing to redraw and the camera hasn't moved enough to matter, so
     * we don't burn the main thread posting + invalidating the overlay at preview rate.
     */
    private fun emitIdle(
        tracks: List<WorldTrackedDetection>,
        orientation: CameraOrientation,
        timestampMillis: Long,
        timestampNanos: Long
    ) {
        if (tracks.isEmpty()) return

        val now = System.nanoTime()
        val orientationMoved = lastEmittedOrientation != null &&
            (
                Math.abs((orientation.yawRadians - lastEmittedOrientation!!.yawRadians).toDouble()) > IDLE_YAW_DELTA ||
                Math.abs((orientation.pitchRadians - lastEmittedOrientation!!.pitchRadians).toDouble()) > IDLE_PITCH_DELTA
            )
        val firstEmit = lastEmittedOrientation == null
        if (!firstEmit && !orientationMoved && now - lastIdleNanos < IDLE_REDRAW_NANOS) return

        lastIdleNanos = now
        lastEmittedOrientation = orientation

        // Empty DetectionFrame signals "no new model output this frame"; the overlay and the
        // view-model both keep the previous dimensions and just redraw with the new boxes.
        val frame = PartDetector.DetectionFrame.EMPTY
        val result = AnalysisResult(
            frame = frame,
            tracks = tracks,
            inferenceTimeMs = 0L,
            bestScore = burstBestScore,
            capturing = false
        )
        onResult(result)
    }

    /**
     * Progress heartbeat so the pipeline can be verified from logcat without a debugger.
     *
     * The frame that ends a burst is always logged: it carries the anchors that were actually
     * placed, which is the only line worth reading when a capture finishes.
     */
    private fun log(result: AnalysisResult) {
        val now = System.nanoTime()
        if (result.capturing && now - lastLogNanos < LOG_INTERVAL_NANOS) return
        lastLogNanos = now
        val frame = result.frame
        Log.i(
            TAG,
            "frame ${frame.frameWidth}x${frame.frameHeight} " +
                "detections=${frame.detections.size} " +
                "anchors=${result.tracks.size} " +
                "inference=${frame.inferenceTimeMs}ms " +
                "peak=${"%.3f".format(frame.peakScore)} " +
                "capturing=${result.capturing}" +
                result.tracks.take(3).joinToString(prefix = " [", postfix = "]") {
                    "#${it.trackId} ${it.detection.label}:" +
                        "${(it.detection.score * 100).toInt()}%/${it.hits}h"
                }
        )
    }

    private companion object {
        private const val TAG = "DetectionAnalyzer"
        private const val LOG_INTERVAL_NANOS = 1_000_000_000L

        /**
         * Hard cap on idle re-projections: even if the camera is steady, refresh at ~30 Hz so
         * the projected boxes stay glued when subtle gyro drift accumulates.
         */
        private const val IDLE_REDRAW_NANOS = 33_000_000L

        /** Camera rotations smaller than these do not move the projected boxes perceptibly. */
        private const val IDLE_YAW_DELTA = 0.0015 // ~0.09°
        private const val IDLE_PITCH_DELTA = 0.0015
    }
}

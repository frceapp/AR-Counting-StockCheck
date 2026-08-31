package com.warehouse.stockchecker.tracking

import com.warehouse.stockchecker.ml.BoxF
import com.warehouse.stockchecker.ml.Detection

/**
 * One anchored part: a detection that survived across frames.
 *
 * @param trackId stable id for as long as the part stays anchored.
 * @param detection smoothed box/score, normalized against the upright frame.
 * @param hits how many frames confirmed this part.
 * @param isLive true when the model saw the part in the frame that produced this result.
 * @param millisSinceSeen how long the anchor has been held without a fresh sighting.
 */
data class TrackedDetection(
    val trackId: Int,
    val detection: Detection,
    val hits: Int,
    val isLive: Boolean,
    val millisSinceSeen: Long
)

/**
 * Turns the per-frame YOLO output into persistent anchors.
 *
 * Two problems are solved here:
 *  * **Flicker** - a box that the model misses on one frame no longer disappears; the anchor
 *    is held for [confirmedRetentionMillis] after the last sighting.
 *  * **False positives** - a box is only published once it has been seen [minHits] times,
 *    so a single noisy frame never draws a label.
 *
 * Association is greedy by IoU within the same class id, and the anchor box is smoothed with
 * an exponential moving average so it does not jitter between frames.
 *
 * Anchors live in normalized frame space, which is stable while the device is still. World
 * locked anchors that survive camera movement need ARCore pose tracking; this tracker is the
 * data layer such a session would drive.
 *
 * Not thread safe: owned by the analysis thread.
 */
class DetectionTracker(
    private val iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    private val minHits: Int = DEFAULT_MIN_HITS,
    private val confirmedRetentionMillis: Long = DEFAULT_CONFIRMED_RETENTION_MILLIS,
    private val pendingRetentionMillis: Long = DEFAULT_PENDING_RETENTION_MILLIS,
    private val smoothing: Float = DEFAULT_SMOOTHING
) {

    private val tracks = ArrayList<Track>()
    private var nextTrackId = 1

    /**
     * Feeds one frame of detections and returns every anchor worth drawing.
     *
     * @param timestampMillis monotonic clock reading for this frame.
     */
    fun update(detections: List<Detection>, timestampMillis: Long): List<TrackedDetection> {
        associate(detections, timestampMillis)
        expire(timestampMillis)

        return tracks
            .filter { it.hits >= minHits }
            .map { it.toTracked(timestampMillis) }
            .sortedByDescending { it.detection.score }
    }

    /** Drops every anchor, e.g. when the user wants a clean scene. */
    fun reset() {
        tracks.clear()
    }

    /**
     * Forgets candidates that never reached [minHits].
     *
     * Called when a capture burst ends: anything still unconfirmed was noise, and leaving it
     * around would let it pair up with a sighting from a completely different scene later.
     */
    fun dropUnconfirmed() {
        tracks.removeAll { it.hits < minHits }
    }


    private fun associate(detections: List<Detection>, timestampMillis: Long) {
        val matchedDetections = BooleanArray(detections.size)
        val matchedTracks = HashSet<Int>()

        // Strongest overlaps win first, so two parts passing over each other keep their ids.
        val pairs = ArrayList<Match>()
        for (track in tracks) {
            detections.forEachIndexed { index, detection ->
                if (detection.classId != track.classId) return@forEachIndexed
                val iou = track.box.intersectionOverUnion(detection.box)
                if (iou >= iouThreshold) pairs.add(Match(iou, track, index))
            }
        }
        pairs.sortByDescending { it.iou }

        for (match in pairs) {
            if (match.track.id in matchedTracks) continue
            if (matchedDetections[match.detectionIndex]) continue
            matchedTracks.add(match.track.id)
            matchedDetections[match.detectionIndex] = true
            match.track.onSeen(detections[match.detectionIndex], timestampMillis, smoothing)
        }

        detections.forEachIndexed { index, detection ->
            if (!matchedDetections[index]) {
                tracks.add(Track(nextTrackId++, detection, timestampMillis))
            }
        }
    }

    private fun expire(timestampMillis: Long) {
        tracks.removeAll { track ->
            val retention =
                if (track.hits >= minHits) confirmedRetentionMillis else pendingRetentionMillis
            timestampMillis - track.lastSeenMillis > retention
        }
    }

    private class Match(val iou: Float, val track: Track, val detectionIndex: Int)

    private class Track(val id: Int, detection: Detection, timestampMillis: Long) {
        val classId: Int = detection.classId
        val label: String = detection.label
        var box: BoxF = detection.box
            private set
        var score: Float = detection.score
            private set
        var hits: Int = 1
            private set
        var lastSeenMillis: Long = timestampMillis
            private set

        fun onSeen(detection: Detection, timestampMillis: Long, smoothing: Float) {
            box = blend(box, detection.box, smoothing)
            score += smoothing * (detection.score - score)
            hits++
            lastSeenMillis = timestampMillis
        }

        fun toTracked(timestampMillis: Long): TrackedDetection {
            val sinceSeen = timestampMillis - lastSeenMillis
            return TrackedDetection(
                trackId = id,
                detection = Detection(classId, label, score, box),
                hits = hits,
                isLive = sinceSeen == 0L,
                millisSinceSeen = sinceSeen
            )
        }

        private fun blend(current: BoxF, target: BoxF, weight: Float) = BoxF(
            left = current.left + weight * (target.left - current.left),
            top = current.top + weight * (target.top - current.top),
            right = current.right + weight * (target.right - current.right),
            bottom = current.bottom + weight * (target.bottom - current.bottom)
        )
    }

    companion object {
        /** Overlap needed to treat a detection as the same part as an existing anchor. */
        const val DEFAULT_IOU_THRESHOLD = 0.3f

        /** Sightings required before an anchor is drawn. Two frames reject one-off noise. */
        const val DEFAULT_MIN_HITS = 2

        /**
         * How long a confirmed anchor is held after the model stops reporting it.
         *
         * Detection is capture driven, so "not seen right now" is the normal state between
         * captures: a placed anchor stays until the user clears it instead of timing out.
         */
        const val DEFAULT_CONFIRMED_RETENTION_MILLIS = Long.MAX_VALUE

        /** Unconfirmed candidates are cheap to drop: a few frames at ~2.5 FPS. */
        const val DEFAULT_PENDING_RETENTION_MILLIS = 1_500L

        /** Weight of the newest box when smoothing; lower is steadier but lags more. */
        const val DEFAULT_SMOOTHING = 0.4f
    }
}

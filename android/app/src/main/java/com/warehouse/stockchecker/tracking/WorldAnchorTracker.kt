package com.warehouse.stockchecker.tracking

import com.warehouse.stockchecker.camera.AnchorProjector
import com.warehouse.stockchecker.camera.CameraOrientation
import com.warehouse.stockchecker.camera.WorldAnchor
import com.warehouse.stockchecker.ml.BoxF
import com.warehouse.stockchecker.ml.Detection

/**
 * One part anchored to a real-world point, drawn in the most recent camera frame.
 *
 * @param box normalized box in the *current* camera frame (already projected forward through
 *   any camera motion since capture).
 * @param capture the orientation at the moment the anchor was captured; deltas relative to the
 *   current orientation are applied every frame to keep the box glued to its world point.
 */
data class WorldTrackedDetection(
    val trackId: Int,
    val detection: Detection,
    val box: BoxF,
    val capture: CameraOrientation,
    val hits: Int,
    val isLive: Boolean,
    val millisSinceSeen: Long
)

/**
 * Tracks parts in the camera's local world frame instead of in captured frame pixels.
 *
 * The flow per call to [update]:
 *
 *  1. project every existing anchor into the current image using the rotation-vector sensor,
 *     so anchors ride on their world points through camera motion,
 *  2. associate incoming detections to the projected anchors by class + nearest projected
 *     centre (greedy), then snap the anchor to the matched detection and re-unproject to keep
 *     depth current,
 *  3. emit the projected boxes for the overlay.
 *
 * Not thread safe: owned by the analysis thread.
 */
class WorldAnchorTracker(
    private val projector: AnchorProjector = AnchorProjector(),
    private val minHits: Int = DEFAULT_MIN_HITS,
    private val matchingRadiusNormalized: Float = DEFAULT_MATCHING_RADIUS
) {

    private data class Track(
        val id: Int,
        val classId: Int,
        val label: String,
        var anchor: WorldAnchor,
        var capture: CameraOrientation,
        var score: Float,
        var hits: Int,
        var lastSeenMillis: Long
    )

    private val tracks = ArrayList<Track>()
    private var nextTrackId = 1

    fun update(
        detections: List<Detection>,
        current: CameraOrientation,
        timestampMillis: Long,
        timestampNanos: Long
    ): List<WorldTrackedDetection> {
        associate(detections, current, timestampMillis, timestampNanos)
        expire(timestampMillis, current)

        return tracks
            .filter { it.hits >= minHits }
            .map { track ->
                val projected = projector.project(track.anchor, track.capture, current)
                // Live sightings get a touch of self-blend to take the edge off micro-jitter;
                // held anchors project straight from the world ray.
                val smoothedBox = if (track.lastSeenMillis == timestampMillis) {
                    smooth(projected, projected, 1f)
                } else {
                    projected
                }
                WorldTrackedDetection(
                    trackId = track.id,
                    detection = Detection(track.classId, track.label, track.score, smoothedBox),
                    box = smoothedBox,
                    capture = track.capture,
                    hits = track.hits,
                    isLive = track.lastSeenMillis == timestampMillis,
                    millisSinceSeen = timestampMillis - track.lastSeenMillis
                )
            }
            .sortedByDescending { it.detection.score }
    }

    fun reset() {
        tracks.clear()
    }

    fun dropUnconfirmed() {
        tracks.removeAll { it.hits < minHits }
    }

    private fun associate(
        detections: List<Detection>,
        current: CameraOrientation,
        timestampMillis: Long,
        timestampNanos: Long
    ) {
        // Step 1: project every existing anchor so we can match detections to where the world
        // point is *right now*, not where it was first detected.
        val projected = tracks.map { it to projector.project(it.anchor, it.capture, current) }

        val matchedDetections = BooleanArray(detections.size)
        val matchedTracks = HashSet<Int>()

        // Greedy nearest-centre matching: cheapest heuristic that survives moderate camera
        // motion, which is exactly what we need at our 2.5 FPS cadence.
        val pairs = ArrayList<PairMatch>()
        for ((track, projectedBox) in projected) {
            detections.forEachIndexed { index, detection ->
                if (detection.classId != track.classId) return@forEachIndexed
                val dx = detection.box.centerX - projectedBox.centerX
                val dy = detection.box.centerY - projectedBox.centerY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distance <= matchingRadiusNormalized) {
                    pairs.add(PairMatch(distance, track, index, projectedBox))
                }
            }
        }
        pairs.sortBy { it.distance }

        for (pair in pairs) {
            if (pair.track.id in matchedTracks) continue
            if (matchedDetections[pair.detectionIndex]) continue
            matchedTracks.add(pair.track.id)
            matchedDetections[pair.detectionIndex] = true
            pair.track.onSeen(detections[pair.detectionIndex], current, timestampMillis, timestampNanos)
        }

        detections.forEachIndexed { index, detection ->
            if (!matchedDetections[index]) {
                val anchor = projector.unproject(detection.box).copy(timestampNanos = timestampNanos)
                tracks.add(
                    Track(
                        id = nextTrackId++,
                        classId = detection.classId,
                        label = detection.label,
                        anchor = anchor,
                        capture = current,
                        score = detection.score,
                        hits = 1,
                        lastSeenMillis = timestampMillis
                    )
                )
            }
        }
    }

    private fun expire(timestampMillis: Long, current: CameraOrientation) {
        // Anchors live until cleared by the user; "not seen right now" is normal between
        // captures and the camera-motion compensation handles short gaps automatically.
        tracks.removeAll { track ->
            // Drop anchors the camera has rotated far away from: the world point is no longer
            // on screen, so drawing the box would be misleading.
            val yawDelta = kotlin.math.abs(current.yawRadians - track.capture.yawRadians)
            val pitchDelta = kotlin.math.abs(current.pitchRadians - track.capture.pitchRadians)
            yawDelta > OFFSCREEN_YAW_RADIANS || pitchDelta > OFFSCREEN_PITCH_RADIANS
        }
    }

    private fun Track.onSeen(
        detection: Detection,
        current: CameraOrientation,
        timestampMillis: Long,
        timestampNanos: Long
    ) {
        // Re-unproject from the live detection to refresh depth and aspect, but reuse the
        // captured yaw/pitch frame so the box does not snap to the new ray mid-frame. The new
        // anchor yaw/pitch will dominate as soon as the camera moves a bit further.
        val refreshed = projector.unproject(detection.box).copy(timestampNanos = timestampNanos)
        anchor = WorldAnchor(
            yawRadians = blend(anchor.yawRadians, refreshed.yawRadians, ANCHOR_SMOOTHING),
            pitchRadians = blend(anchor.pitchRadians, refreshed.pitchRadians, ANCHOR_SMOOTHING),
            depthMeters = blend(anchor.depthMeters, refreshed.depthMeters, ANCHOR_SMOOTHING),
            aspect = blend(anchor.aspect, refreshed.aspect, ANCHOR_SMOOTHING),
            timestampNanos = timestampNanos,
            valid = true
        )
        // Update capture to the current orientation only when the new detection sits squarely
        // on the projected box; a partial overlap means the camera is mid-motion and we should
        // not yet bake in the new pose.
        val projected = projector.project(anchor, capture, current)
        val dx = detection.box.centerX - projected.centerX
        val dy = detection.box.centerY - projected.centerY
        if (kotlin.math.sqrt(dx * dx + dy * dy) <= matchingRadiusNormalized / 2f) {
            capture = current
        }
        score = blend(score, detection.score, ANCHOR_SMOOTHING)
        hits++
        lastSeenMillis = timestampMillis
    }

    private fun blend(current: Float, target: Float, weight: Float): Float =
        current + weight * (target - current)

    private fun smooth(current: BoxF, target: BoxF, weight: Float): BoxF = BoxF(
        left = current.left + weight * (target.left - current.left),
        top = current.top + weight * (target.top - current.top),
        right = current.right + weight * (target.right - current.right),
        bottom = current.bottom + weight * (target.bottom - current.bottom)
    )

    private data class PairMatch(
        val distance: Float,
        val track: Track,
        val detectionIndex: Int,
        val projected: BoxF
    )

    companion object {
        /** Sightings required before a world anchor is drawn. */
        const val DEFAULT_MIN_HITS = 2

        /**
         * Maximum centre-distance (in normalized image space) at which a new detection can be
         * associated with a projected world anchor. Generous enough to cover a fast pan.
         */
        const val DEFAULT_MATCHING_RADIUS = 0.18f

        /** Weight of the newest detection when refining an anchor; lower is steadier. */
        const val ANCHOR_SMOOTHING = 0.4f

        /** Camera yaw beyond which an anchor is considered off-screen and dropped. */
        const val OFFSCREEN_YAW_RADIANS = (45f * kotlin.math.PI / 180f).toFloat()

        /** Camera pitch beyond which an anchor is considered off-screen and dropped. */
        const val OFFSCREEN_PITCH_RADIANS = (30f * kotlin.math.PI / 180f).toFloat()
    }
}

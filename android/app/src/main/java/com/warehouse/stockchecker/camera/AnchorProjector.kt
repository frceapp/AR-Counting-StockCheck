package com.warehouse.stockchecker.camera

import com.warehouse.stockchecker.ml.BoxF
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/**
 * Math that ties normalized image boxes to a simple world model.
 *
 * Without ARCore we don't have a calibrated camera pose, so the world model is intentionally
 * coarse:
 *
 *  * a world anchor is a unit ray (yaw, pitch) at an estimated depth,
 *  * depth is recovered from the box's apparent size against a configurable focal length,
 *  * projection re-uses the same focal length to drop the ray back into the image.
 *
 * This keeps a stationary real-world part glued to its bounding box as the user pans the
 * camera, which is what "marker stuck to the screen, not to the world" means in practice.
 *
 * Pure math: no Android imports, fully unit testable.
 */
class AnchorProjector(
    /** Vertical field of view of the camera, in radians. */
    private val verticalFovRadians: Float = DEFAULT_VERTICAL_FOV_RADIANS,
    /** Rough physical size of the detected class, in metres. Drives depth from box size. */
    private val classSizeMeters: Float = DEFAULT_CLASS_SIZE_METERS,
    /**
     * Horizontal aspect ratio of the image; the FOV above is the *vertical* one, so the
     * horizontal FOV is derived from this. Defaults to a 9:16 portrait phone camera.
     */
    private val aspectRatio: Float = 9f / 16f
) {

    private val horizontalFovRadians: Float = 2f * atan(aspectRatio * tan(verticalFovRadians / 2f))

    /** Half-extents in normalized image space, useful for projection bounds checks. */
    val verticalHalfFovNormalized: Float = tan(verticalFovRadians / 2f)
    val horizontalHalfFovNormalized: Float = tan(horizontalFovRadians / 2f)

    /**
     * Turns a normalized image box into a world anchor.
     *
     * Yaw and pitch come from the box centre; depth comes from the box height compared to
     * [classSizeMeters] using a pinhole model. Result is undefined when the box is degenerate.
     */
    fun unproject(box: BoxF): WorldAnchor {
        val cx = box.centerX.coerceIn(0f, 1f)
        val cy = box.centerY.coerceIn(0f, 1f)

        // Pinhole: a pixel at normalized (u, v) maps to a ray whose angle from the camera axis
        // is atan((u - 0.5) * 2 * tan(halfFov)) in the respective axis.
        val yaw = (cx - 0.5f) * 2f * horizontalHalfFovNormalized
        val pitch = (0.5f - cy) * 2f * verticalHalfFovNormalized

        // Depth: a part of physical height H seen as a fraction h of the vertical FOV has
        // distance D such that h = H / D. Rearranged: D = H / h, where h is in the same units
        // as 2 * tan(halfFov). Translate normalized box height into the same units first.
        val boxHeightNormalized = max(1e-4f, box.height)
        val heightInFov = boxHeightNormalized * 2f * verticalHalfFovNormalized
        val depth = (classSizeMeters / heightInFov).coerceIn(MIN_DEPTH_METERS, MAX_DEPTH_METERS)

        // Width sanity-check the depth estimate: a thin, tall box and a short, wide box of the
        // same classId are usually the same object; keep the depth conservative.
        val widthInFov = max(1e-4f, box.width) * 2f * horizontalHalfFovNormalized
        val depthFromWidth = classSizeMeters / widthInFov
        val blendedDepth = (depth + depthFromWidth.coerceIn(MIN_DEPTH_METERS, MAX_DEPTH_METERS)) / 2f

        return WorldAnchor(
            yawRadians = yaw,
            pitchRadians = pitch,
            depthMeters = blendedDepth,
            aspect = box.width / box.height,
            timestampNanos = 0L,
            valid = true
        )
    }

    /**
     * Projects a world anchor back into the upright-frame image, given the camera's current
     * orientation relative to the orientation the anchor was captured at.
     *
     * @param anchor the world anchor (yaw/pitch are relative to the capture orientation).
     * @param yawDelta camera yaw change since capture, in radians.
     * @param pitchDelta camera pitch change since capture, in radians.
     */
    fun project(anchor: WorldAnchor, yawDelta: Float, pitchDelta: Float): BoxF {
        if (!anchor.valid) return BoxF(0f, 0f, 0f, 0f)

        // Rotate the world ray into the *current* camera frame.
        val currentYaw = anchor.yawRadians + yawDelta
        val currentPitch = anchor.pitchRadians + pitchDelta

        val nx = (currentYaw / (2f * horizontalHalfFovNormalized)) + 0.5f
        val ny = 0.5f - (currentPitch / (2f * verticalHalfFovNormalized))

        val heightNormalized = classSizeMeters / (anchor.depthMeters * 2f * verticalHalfFovNormalized)
        val widthNormalized = heightNormalized * anchor.aspect

        val halfW = widthNormalized / 2f
        val halfH = heightNormalized / 2f
        val left = (nx - halfW).coerceIn(-1f, 2f)
        val top = (ny - halfH).coerceIn(-1f, 2f)
        val right = (nx + halfW).coerceIn(-1f, 2f)
        val bottom = (ny + halfH).coerceIn(-1f, 2f)

        // Clamp into [0, 1] only after drawing so we know how much was cut off; for overlay
        // drawing, partial visibility is acceptable.
        return BoxF(
            left = max(0f, min(1f, left)),
            top = max(0f, min(1f, top)),
            right = max(0f, min(1f, right)),
            bottom = max(0f, min(1f, bottom))
        )
    }

    /** Convenience wrapper used by the tracker: deltas computed by the caller. */
    fun project(anchor: WorldAnchor, capture: CameraOrientation, current: CameraOrientation): BoxF {
        val yawDelta = current.yawRadians - capture.yawRadians
        val pitchDelta = current.pitchRadians - capture.pitchRadians
        return project(anchor, yawDelta, pitchDelta)
    }

    companion object {
        /**
         * Vertical FOV of a typical phone back camera. Real phones vary from ~50° to ~75°;
         * 60° is the average and gives reasonable depth estimates without per-device calibration.
         */
        const val DEFAULT_VERTICAL_FOV_RADIANS = (60f * PI / 180f).toFloat()

        /**
         * Rough physical diameter of a mechanical part (bolt/nut/washer/etc.) in metres.
         * Depth recovery only needs a reasonable order of magnitude; precise calibration would
         * require ARCore.
         */
        const val DEFAULT_CLASS_SIZE_METERS = 0.025f

        /** Closer than this and depth becomes numerically unstable. */
        const val MIN_DEPTH_METERS = 0.05f

        /** Beyond this the box covers too few pixels to trust a position. */
        const val MAX_DEPTH_METERS = 5f

        private fun tan(value: Float): Float = kotlin.math.tan(value)
    }
}

/**
 * A part anchored in the camera's local world frame.
 *
 * Yaw and pitch describe the unit ray from the camera to the part at the moment the anchor
 * was captured. Depth is the estimated distance from the camera at that same moment.
 *
 * @param aspect width/height ratio of the captured box; preserved so re-projection keeps the
 *   box the right shape after the camera rotates.
 */
data class WorldAnchor(
    val yawRadians: Float,
    val pitchRadians: Float,
    val depthMeters: Float,
    val aspect: Float,
    val timestampNanos: Long,
    val valid: Boolean
) {
    companion object {
        val INVALID = WorldAnchor(0f, 0f, 0f, 1f, 0L, valid = false)
    }
}

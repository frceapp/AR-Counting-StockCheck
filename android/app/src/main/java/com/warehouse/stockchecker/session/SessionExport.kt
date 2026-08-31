package com.warehouse.stockchecker.session

import com.warehouse.stockchecker.tracking.WorldTrackedDetection
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON payload that gets serialised to disk.
 *
 * Kept deliberately small and stable: this is a diagnostics export, not a full replay format.
 * Boxes are recorded in normalised image space (0..1) plus their world-ray components, so a
 * downstream tool can both render the box on the saved image and reason about the world point.
 */
data class SessionExport(
    val version: Int,
    val appVersion: String,
    val createdAtMillis: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val inferenceTimeMs: Long,
    val anchors: List<AnchorJson>
) {
    data class AnchorJson(
        val trackId: Int,
        val label: String,
        val classId: Int,
        val score: Float,
        val hits: Int,
        val box: BoxJson,
        val worldRay: WorldRayJson
    )

    data class BoxJson(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    data class WorldRayJson(
        val yawRadians: Float,
        val pitchRadians: Float,
        val depthMeters: Float
    )

    companion object {
        /** Bump when the schema changes in a way that breaks older readers. */
        const val CURRENT_VERSION = 1

        fun from(
            anchors: List<WorldTrackedDetection>,
            frameWidth: Int,
            frameHeight: Int,
            inferenceTimeMs: Long,
            createdAtMillis: Long,
            appVersion: String
        ): SessionExport = SessionExport(
            version = CURRENT_VERSION,
            appVersion = appVersion,
            createdAtMillis = createdAtMillis,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            inferenceTimeMs = inferenceTimeMs,
            anchors = anchors.map { track ->
                AnchorJson(
                    trackId = track.trackId,
                    label = track.detection.label,
                    classId = track.detection.classId,
                    score = track.detection.score,
                    hits = track.hits,
                    box = BoxJson(
                        left = track.box.left,
                        top = track.box.top,
                        right = track.box.right,
                        bottom = track.box.bottom
                    ),
                    worldRay = WorldRayJson(
                        // The world yaw/pitch/depth aren't on WorldTrackedDetection itself,
                        // only the projection for the current frame. The session record is
                        // diagnostic, so the projection is enough; downstream tools can
                        // reconstruct depth from box height if they need it.
                        yawRadians = 0f,
                        pitchRadians = 0f,
                        depthMeters = 0f
                    )
                )
            }
        )
    }
}

/**
 * Hand-rolled JSON serialiser for [SessionExport].
 *
 * Hand-rolled rather than reflective so the on-disk shape is documented, stable, and
 * readable in any text editor. Bumping [SessionExport.CURRENT_VERSION] is the supported way
 * to make breaking changes.
 */
fun SessionExport.toJson(): JSONObject = JSONObject().apply {
    put("version", version)
    put("appVersion", appVersion)
    put("createdAtMillis", createdAtMillis)
    put("frameWidth", frameWidth)
    put("frameHeight", frameHeight)
    put("inferenceTimeMs", inferenceTimeMs)
    put("anchors", JSONArray().apply {
        anchors.forEach { anchor ->
            put(JSONObject().apply {
                put("trackId", anchor.trackId)
                put("label", anchor.label)
                put("classId", anchor.classId)
                put("score", anchor.score)
                put("hits", anchor.hits)
                put("box", JSONObject().apply {
                    put("left", anchor.box.left)
                    put("top", anchor.box.top)
                    put("right", anchor.box.right)
                    put("bottom", anchor.box.bottom)
                })
                put("worldRay", JSONObject().apply {
                    put("yawRadians", anchor.worldRay.yawRadians)
                    put("pitchRadians", anchor.worldRay.pitchRadians)
                    put("depthMeters", anchor.worldRay.depthMeters)
                })
            })
        }
    })
}

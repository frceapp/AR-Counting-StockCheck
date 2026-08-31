package com.warehouse.stockchecker.ml

/**
 * Decodes the raw output tensor of an Ultralytics YOLO detection head.
 *
 * The exported model emits a single tensor of `[1, 4 + numClasses, numAnchors]`
 * (channels-first, the Ultralytics default) or `[1, numAnchors, 4 + numClasses]`.
 * Each anchor carries `cx, cy, w, h` followed by one sigmoid score per class.
 * This export applies `_NormalizeCoords`, so box values are already in `[0, 1]`
 * relative to the letterboxed 640x640 input.
 *
 * Kept free of Android types so the decoding maths can be unit tested on the JVM.
 */
class YoloOutputDecoder(
    private val labels: List<String>,
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    private val iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    private val maxDetections: Int = DEFAULT_MAX_DETECTIONS
) {

    /** Class ids whose label marks them as background/ignored and must never be reported. */
    private val ignoredClassIds: Set<Int> = labels.indices
        .filter { isIgnoredLabel(labels[it]) }
        .toSet()

    /**
     * @param output flattened output tensor values (batch size 1).
     * @param shape the tensor shape reported by the interpreter.
     * @param transform letterbox used during preprocessing; when null the boxes are
     *   returned in model space (already normalized).
     */
    fun decode(
        output: FloatArray,
        shape: IntArray,
        transform: LetterboxTransform? = null
    ): List<Detection> {
        val layout = Layout.from(shape)
        val numClasses = layout.numFeatures - BOX_FEATURES
        if (numClasses <= 0) return emptyList()

        val candidates = ArrayList<Detection>()

        for (anchor in 0 until layout.numAnchors) {
            var bestScore = 0f
            var bestClassId = -1

            for (classId in 0 until numClasses) {
                if (classId in ignoredClassIds) continue
                val score = output[layout.indexOf(BOX_FEATURES + classId, anchor)]
                if (score > bestScore) {
                    bestScore = score
                    bestClassId = classId
                }
            }

            if (bestClassId < 0 || bestScore < confidenceThreshold) continue

            val box = BoxF.fromCenter(
                centerX = output[layout.indexOf(0, anchor)],
                centerY = output[layout.indexOf(1, anchor)],
                width = output[layout.indexOf(2, anchor)],
                height = output[layout.indexOf(3, anchor)]
            )
            val mapped = transform?.toSourceNormalized(box) ?: box.clamp()
            if (mapped.width <= 0f || mapped.height <= 0f) continue

            candidates.add(
                Detection(
                    classId = bestClassId,
                    label = labels.getOrElse(bestClassId) { "class_$bestClassId" },
                    score = bestScore,
                    box = mapped
                )
            )
        }

        return Nms.apply(candidates, iouThreshold, maxDetections)
    }

    /**
     * Highest class score in the tensor, ignoring background classes.
     *
     * Only used for diagnostics: it tells whether the model produced any signal at all,
     * which distinguishes "nothing in view" from "scores never clear the threshold".
     */
    fun peakScore(output: FloatArray, shape: IntArray): Float {
        val layout = Layout.from(shape)
        val numClasses = layout.numFeatures - BOX_FEATURES
        if (numClasses <= 0) return 0f

        var peak = 0f
        for (classId in 0 until numClasses) {
            if (classId in ignoredClassIds) continue
            for (anchor in 0 until layout.numAnchors) {
                val score = output[layout.indexOf(BOX_FEATURES + classId, anchor)]
                if (score > peak) peak = score
            }
        }
        return peak
    }

    /**
     * Resolves the tensor memory layout and provides flat-index lookup.
     */

    internal data class Layout(
        val numFeatures: Int,
        val numAnchors: Int,
        val channelsFirst: Boolean
    ) {
        fun indexOf(feature: Int, anchor: Int): Int =
            if (channelsFirst) feature * numAnchors + anchor else anchor * numFeatures + feature

        companion object {
            fun from(shape: IntArray): Layout {
                require(shape.size >= 3) { "Unexpected YOLO output shape: ${shape.joinToString()}" }
                val dimA = shape[shape.size - 2]
                val dimB = shape[shape.size - 1]
                // Anchor count (~8400 for 640px input) always dominates the feature count (4 + nc).
                val channelsFirst = dimA <= dimB
                return Layout(
                    numFeatures = if (channelsFirst) dimA else dimB,
                    numAnchors = if (channelsFirst) dimB else dimA,
                    channelsFirst = channelsFirst
                )
            }
        }
    }

    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.35f
        const val DEFAULT_IOU_THRESHOLD = 0.45f
        const val DEFAULT_MAX_DETECTIONS = 100

        /** cx, cy, w, h */
        private const val BOX_FEATURES = 4

        /**
         * The training dataset carries a placeholder class "-1" for unlabelled regions.
         * Predictions for it are noise and are dropped.
         */
        fun isIgnoredLabel(label: String): Boolean {
            val normalized = label.trim()
            return normalized == "-1" ||
                normalized.equals("background", ignoreCase = true) ||
                normalized.isEmpty()
        }
    }
}

package com.warehouse.stockchecker.ml

/**
 * Class-wise Non-Maximum Suppression.
 */
object Nms {

    /**
     * Keeps the highest scoring box of every cluster of overlapping boxes.
     * Suppression is applied per class id, so a bolt overlapping a washer is kept.
     *
     * @param iouThreshold boxes overlapping more than this with a kept box are dropped.
     * @param maxDetections hard cap on returned detections (highest score first).
     */
    fun apply(
        detections: List<Detection>,
        iouThreshold: Float = 0.45f,
        maxDetections: Int = 100
    ): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val kept = ArrayList<Detection>(minOf(detections.size, maxDetections))

        for ((_, sameClass) in detections.groupBy { it.classId }) {
            val candidates = sameClass.sortedByDescending { it.score }
            val suppressed = BooleanArray(candidates.size)

            for (i in candidates.indices) {
                if (suppressed[i]) continue
                val best = candidates[i]
                kept.add(best)
                for (j in i + 1 until candidates.size) {
                    if (suppressed[j]) continue
                    if (best.box.intersectionOverUnion(candidates[j].box) >= iouThreshold) {
                        suppressed[j] = true
                    }
                }
            }
        }

        return kept.sortedByDescending { it.score }.take(maxDetections)
    }
}

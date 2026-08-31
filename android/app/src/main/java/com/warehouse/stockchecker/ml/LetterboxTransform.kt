package com.warehouse.stockchecker.ml

/**
 * Describes the aspect-ratio preserving resize ("letterbox") applied before inference,
 * and maps model-space coordinates back to the source frame.
 *
 * The source frame is scaled by [scale] and centered inside a square [inputSize] canvas,
 * leaving [padX] / [padY] pixels of padding on each side.
 */
data class LetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputSize: Int,
    val scale: Float,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val padX: Float,
    val padY: Float
) {

    /**
     * Converts a box expressed in normalized model space ([0, 1] over the padded square)
     * into normalized source-frame space ([0, 1] over the original frame), then clamps it.
     */
    fun toSourceNormalized(box: BoxF): BoxF {
        if (scaledWidth <= 0 || scaledHeight <= 0) return box.clamp()
        return BoxF(
            left = (box.left * inputSize - padX) / scaledWidth,
            top = (box.top * inputSize - padY) / scaledHeight,
            right = (box.right * inputSize - padX) / scaledWidth,
            bottom = (box.bottom * inputSize - padY) / scaledHeight
        ).clamp()
    }

    companion object {
        fun of(sourceWidth: Int, sourceHeight: Int, inputSize: Int): LetterboxTransform {
            require(sourceWidth > 0 && sourceHeight > 0) { "Source frame must have positive dimensions" }
            val scale = minOf(
                inputSize.toFloat() / sourceWidth,
                inputSize.toFloat() / sourceHeight
            )
            val scaledWidth = Math.round(sourceWidth * scale)
            val scaledHeight = Math.round(sourceHeight * scale)
            return LetterboxTransform(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                inputSize = inputSize,
                scale = scale,
                scaledWidth = scaledWidth,
                scaledHeight = scaledHeight,
                padX = (inputSize - scaledWidth) / 2f,
                padY = (inputSize - scaledHeight) / 2f
            )
        }
    }
}

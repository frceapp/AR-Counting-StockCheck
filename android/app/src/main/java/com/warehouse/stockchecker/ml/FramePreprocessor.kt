package com.warehouse.stockchecker.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns a camera frame into the float32 tensor expected by the model.
 *
 * Ultralytics TFLite exports use NCHW (`[1, 3, size, size]`), while some converters emit
 * NHWC (`[1, size, size, 3]`), so the channel order is supplied by the caller after
 * inspecting the interpreter's input tensor.
 *
 * Buffers and the intermediate bitmap are allocated once and reused on every frame to
 * avoid per-frame GC pressure in the analysis loop.
 */
class FramePreprocessor(
    private val inputSize: Int = INPUT_SIZE,
    private val channelsFirst: Boolean = false
) {

    private val canvasBitmap: Bitmap =
        Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(inputSize * inputSize)

    val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * CHANNELS * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())

    /** Float view of [inputBuffer]; writing floats through it avoids per-value bounds work. */
    private val inputFloats = inputBuffer.asFloatBuffer()

    /**
     * Rotates [source] upright by [rotationDegrees], letterboxes it into a square canvas
     * and writes normalized RGB values into [inputBuffer].
     *
     * @return the transform needed to map detections back to the upright frame.
     */
    fun process(source: Bitmap, rotationDegrees: Int): LetterboxTransform {
        val rotated = rotationDegrees % 360 != 0
        val uprightWidth = if (rotationDegrees % 180 == 0) source.width else source.height
        val uprightHeight = if (rotationDegrees % 180 == 0) source.height else source.width

        val transform = LetterboxTransform.of(uprightWidth, uprightHeight, inputSize)

        val matrix = Matrix()
        if (rotated) {
            // Rotate about the frame center, then re-center in the upright bounding box.
            matrix.postTranslate(-source.width / 2f, -source.height / 2f)
            matrix.postRotate(rotationDegrees.toFloat())
            matrix.postTranslate(uprightWidth / 2f, uprightHeight / 2f)
        }
        matrix.postScale(transform.scale, transform.scale)
        matrix.postTranslate(transform.padX, transform.padY)

        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, matrix, paint)

        writeTensor()
        return transform
    }

    private fun writeTensor() {
        canvasBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputFloats.rewind()
        if (channelsFirst) {
            // NCHW: one contiguous plane per channel.
            for (shift in CHANNEL_SHIFTS) {
                for (pixel in pixels) {
                    inputFloats.put(((pixel shr shift) and 0xFF) / 255f)
                }
            }
        } else {
            // NHWC: interleaved RGB per pixel.
            for (pixel in pixels) {
                inputFloats.put(((pixel shr 16) and 0xFF) / 255f)
                inputFloats.put(((pixel shr 8) and 0xFF) / 255f)
                inputFloats.put((pixel and 0xFF) / 255f)
            }
        }
        inputFloats.rewind()
        // TFLite reads from the ByteBuffer itself, so its own position must be at zero.
        inputBuffer.rewind()
    }

    fun close() {
        if (!canvasBitmap.isRecycled) canvasBitmap.recycle()
    }

    companion object {
        const val INPUT_SIZE = 640
        private const val CHANNELS = 3

        /** Red, green, blue bit offsets inside an ARGB_8888 pixel. */
        private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
    }
}

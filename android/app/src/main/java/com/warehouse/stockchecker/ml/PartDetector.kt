package com.warehouse.stockchecker.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Runs the exported YOLO TFLite model on camera frames.
 *
 * Not thread safe: create one instance per analysis thread and always call [close] when done.
 */
class PartDetector private constructor(
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
    private val preprocessor: FramePreprocessor,
    private val decoder: YoloOutputDecoder,
    private val outputShape: IntArray,
    val labels: List<String>
) : AutoCloseable {

    private val outputElementCount = outputShape.fold(1) { acc, dim -> acc * dim }

    /**
     * TFLite can write directly into a direct ByteBuffer output, which avoids allocating the
     * nested `Array(1) { Array(features) { FloatArray(anchors) } }` on every frame.
     */
    private val outputBytes: ByteBuffer = ByteBuffer
        .allocateDirect(outputElementCount * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())

    private val outputFloats = FloatArray(outputElementCount)
    private var closed = false

    /**
     * @param rotationDegrees clockwise rotation needed to make [frame] upright.
     * @return detections with boxes normalized against the upright frame.
     */
    fun detect(frame: Bitmap, rotationDegrees: Int): DetectionFrame {
        check(!closed) { "PartDetector has already been closed" }
        val startNanos = System.nanoTime()

        val transform = preprocessor.process(frame, rotationDegrees)

        outputBytes.rewind()
        interpreter.run(preprocessor.inputBuffer, outputBytes)

        outputBytes.rewind()
        outputBytes.asFloatBuffer().get(outputFloats)

        val detections = decoder.decode(outputFloats, outputShape, transform)
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L

        return DetectionFrame(
            detections = detections,
            frameWidth = transform.sourceWidth,
            frameHeight = transform.sourceHeight,
            inferenceTimeMs = elapsedMs,
            peakScore = decoder.peakScore(outputFloats, outputShape)
        )

    }

    override fun close() {
        if (closed) return
        closed = true
        interpreter.close()
        gpuDelegate?.close()
        preprocessor.close()
    }

    data class DetectionFrame(
        val detections: List<Detection>,
        val frameWidth: Int,
        val frameHeight: Int,
        val inferenceTimeMs: Long,
        /** Best raw class score in the frame, before thresholding. Diagnostics only. */
        val peakScore: Float = 0f
    ) {
        companion object {
            val EMPTY = DetectionFrame(emptyList(), 0, 0, 0L)
        }
    }


    companion object {
        private const val TAG = "PartDetector"
        private const val CHANNELS = 3
        const val MODEL_ASSET = "component_parts.tflite"
        const val LABELS_ASSET = "labels.txt"


        /**
         * Loads the model from assets. Tries the GPU delegate first and falls back to
         * multi-threaded CPU when the device or driver does not support it.
         *
         * @throws java.io.IOException when the model or label asset cannot be read.
         */
        fun create(
            context: Context,
            confidenceThreshold: Float = YoloOutputDecoder.DEFAULT_CONFIDENCE_THRESHOLD,
            iouThreshold: Float = YoloOutputDecoder.DEFAULT_IOU_THRESHOLD
        ): PartDetector {
            val labels = loadLabels(context)
            val modelBuffer = context.assets.openFd(MODEL_ASSET).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.declaredLength
                    )
                }
            }

            val options = Interpreter.Options()
            var delegate: GpuDelegate? = null
            if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                delegate = runCatching { GpuDelegate() }
                    .onFailure { Log.w(TAG, "GPU delegate unavailable, falling back to CPU", it) }
                    .getOrNull()
            }
            if (delegate != null) {
                options.addDelegate(delegate)
                Log.i(TAG, "GPU delegate enabled")
            } else {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                options.numThreads = threads
                Log.i(TAG, "Using CPU inference with $threads threads")
            }

            val interpreter = try {
                Interpreter(modelBuffer, options)
            } catch (t: Throwable) {
                delegate?.close()
                throw t
            }

            val inputShape = interpreter.getInputTensor(0).shape()
            val outputShape = interpreter.getOutputTensor(0).shape()
            // Ultralytics exports NCHW [1, 3, size, size]; other converters emit NHWC
            // [1, size, size, 3]. Pick the layout from whichever axis holds the 3 channels.
            val channelsFirst = inputShape.size == 4 &&
                inputShape[1] == CHANNELS &&
                inputShape[3] != CHANNELS
            val sizeAxis = if (channelsFirst) 2 else 1
            val inputSize = inputShape.getOrElse(sizeAxis) { FramePreprocessor.INPUT_SIZE }
            Log.i(
                TAG,
                "Model loaded: input=${inputShape.joinToString()} " +
                    "layout=${if (channelsFirst) "NCHW" else "NHWC"} " +
                    "output=${outputShape.joinToString()}"
            )

            return PartDetector(
                interpreter = interpreter,
                gpuDelegate = delegate,
                preprocessor = FramePreprocessor(inputSize, channelsFirst),

                decoder = YoloOutputDecoder(labels, confidenceThreshold, iouThreshold),
                outputShape = outputShape,
                labels = labels
            )
        }

        private fun loadLabels(context: Context): List<String> =
            context.assets.open(LABELS_ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
    }
}

package com.danceanon.native.face

import android.content.Context
import com.danceanon.native.inference.FloatRect
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import java.nio.ByteBuffer

/**
 * Production-packaged MediaPipe face locator validated on PLK110 / Android 16.
 *
 * IMAGE mode is intentional. The app does not use MediaPipe temporal identity,
 * and VIDEO mode stalled on the 11th synchronous call in the device benchmark.
 */
class MediaPipeFaceLocator(
    context: Context,
    minDetectionConfidence: Float = DEFAULT_MIN_DETECTION_CONFIDENCE
) : FaceLocator {
    private val detector: FaceDetector

    init {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()
        val options = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinDetectionConfidence(minDetectionConfidence)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        detector = FaceDetector.createFromOptions(context.applicationContext, options)
    }

    @Synchronized
    override fun detectRgbaTopDown(
        rgba: ByteBuffer,
        width: Int,
        height: Int
    ): FaceLocatorResult {
        require(width > 1 && height > 1) { "Invalid face-locator input ${width}x$height" }
        val expectedBytes = width.toLong() * height.toLong() * 4L
        require(
            expectedBytes <= Int.MAX_VALUE &&
                rgba.capacity() >= expectedBytes.toInt() &&
                rgba.limit() >= expectedBytes.toInt()
        ) {
            "Face-locator RGBA buffer is too small for ${width}x$height"
        }

        val readable = rgba.asReadOnlyBuffer().apply { rewind() }
        val image = ByteBufferImageBuilder(
            readable,
            width,
            height,
            MPImage.IMAGE_FORMAT_RGBA
        ).build()
        return try {
            val startNs = System.nanoTime()
            val result = detector.detect(image)
            val inferenceMs = (System.nanoTime() - startNs) / 1_000_000.0
            FaceLocatorResult(
                observations = result.detections().map { detection ->
                    val rect = detection.boundingBox()
                    FaceObservation(
                        bbox = FloatRect(
                            rect.left,
                            rect.top,
                            rect.right,
                            rect.bottom
                        ),
                        confidence = detection.categories().firstOrNull()?.score() ?: 0f
                    )
                },
                inferenceMs = inferenceMs
            )
        } finally {
            image.close()
        }
    }

    @Synchronized
    override fun close() {
        detector.close()
    }

    companion object {
        const val MODEL_ASSET_PATH = "models/face/blaze_face_full_range.tflite"
        const val DEFAULT_MIN_DETECTION_CONFIDENCE = 0.35f
    }
}

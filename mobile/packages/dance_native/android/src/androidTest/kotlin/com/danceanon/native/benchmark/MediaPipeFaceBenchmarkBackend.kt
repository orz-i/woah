package com.danceanon.native.benchmark

import android.content.Context
import com.danceanon.native.inference.FloatRect
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import java.nio.ByteBuffer

internal class MediaPipeFaceBenchmarkBackend(
    context: Context,
    minDetectionConfidence: Float = 0.35f
) : FaceBenchmarkBackend {
    override val name: String = "mediapipe_blazeface_full_range_cpu_image"

    private val detector: FaceDetector

    init {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinDetectionConfidence(minDetectionConfidence)
            // Identity and temporal ownership stay in YOLO/TrackManager. On the
            // OnePlus PLK110 / Android 16 benchmark device, VIDEO mode stalled
            // synchronously on the 11th detectForVideo() call. IMAGE mode is
            // stateless, matches this architecture, and avoids that native graph
            // state entirely.
            .setRunningMode(RunningMode.IMAGE)
            .build()
        detector = FaceDetector.createFromOptions(context, options)
    }

    override fun detect(
        rgbaTopDown: ByteBuffer,
        width: Int,
        height: Int,
        timestampMs: Long
    ): FaceBackendResult {
        val readable = rgbaTopDown.asReadOnlyBuffer().apply { rewind() }
        val mpImage = ByteBufferImageBuilder(
            readable,
            width,
            height,
            MPImage.IMAGE_FORMAT_RGBA
        ).build()
        return try {
            val startNs = System.nanoTime()
            val result = detector.detect(mpImage)
            val inferenceMs = (System.nanoTime() - startNs) / 1_000_000.0
            val detections = result.detections().map { detection ->
                val rect = detection.boundingBox()
                BenchmarkFaceDetection(
                    bboxInModelPixels = FloatRect(rect.left, rect.top, rect.right, rect.bottom),
                    confidence = detection.categories().firstOrNull()?.score() ?: 0f
                )
            }
            FaceBackendResult(detections = detections, inferenceMs = inferenceMs)
        } finally {
            mpImage.close()
        }
    }

    override fun close() {
        detector.close()
    }

    companion object {
        const val MODEL_ASSET = "blaze_face_full_range.tflite"
    }
}

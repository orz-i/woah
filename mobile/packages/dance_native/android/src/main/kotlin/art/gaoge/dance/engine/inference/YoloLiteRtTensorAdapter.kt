package art.gaoge.dance.engine.inference

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/** Model/runtime transport. FrameKernel owns candidate scan, masks and NMS. */
class YoloLiteRtTensorAdapter(
    val output0Shape: List<Int> = emptyList(),
    val output1Shape: List<Int> = emptyList()
) {
    companion object {
        const val PROTO_SIZE = 160
        const val PROTO_CHANNELS = 32
        const val ATTR_COUNT = 116
        const val ANCHOR_COUNT = 8400
        const val PERSON_CLASS_INDEX = 0
    }

    private val spec = intArrayOf(ANCHOR_COUNT, ATTR_COUNT, 4 + PERSON_CLASS_INDEX, 84, PROTO_CHANNELS,
        if (output0Shape == listOf(1, ANCHOR_COUNT, ATTR_COUNT)) 1 else 0,
        if (output1Shape == listOf(1, PROTO_SIZE, PROTO_SIZE, PROTO_CHANNELS)) 1 else 0, 2)
    private var capacity = 32
    private var detectionValues = FloatArray(capacity * 5)
    private var maskValues = ByteBuffer.allocateDirect(capacity * PROTO_SIZE * PROTO_SIZE)
    private val metrics = LongArray(5)

    fun parseDetections(output0Buffer: FloatBuffer, output1Buffer: FloatBuffer,
        preprocess: PreprocessResult, confThreshold: Float = 0.25f, iouThreshold: Float = 0.50f
    ): List<PersonDetection> {
        fun copy(buffer: FloatBuffer): FloatArray = FloatArray(buffer.limit()).also {
            buffer.duplicate().apply { position(0) }.get(it)
        }
        return parseDetections(copy(output0Buffer), copy(output1Buffer), preprocess, confThreshold, iouThreshold)
    }

    @Synchronized
    fun parseDetections(output0: FloatArray, output1: FloatArray, preprocess: PreprocessResult,
        confThreshold: Float = 0.25f, iouThreshold: Float = 0.50f,
        stageTimingsMs: MutableMap<String, Long>? = null
    ): List<PersonDetection> {
        val transform = floatArrayOf(preprocess.scale, preprocess.padLeft, preprocess.padTop,
            preprocess.srcWidth.toFloat(), preprocess.srcHeight.toFloat(), preprocess.inputSize.toFloat(), PROTO_SIZE.toFloat())
        val thresholds = floatArrayOf(confThreshold, iouThreshold, 0.50f)
        val bridgeStart = System.nanoTime()
        var count: Int
        while (true) {
            count = FrameKernel.decodeInstances(output0, output1, spec, transform, thresholds,
                detectionValues, maskValues, metrics)
            if (count >= 0) break
            val required = -count - 1
            check(required > capacity && required <= ANCHOR_COUNT) { "Invalid shared instance capacity: $count" }
            capacity = required
            detectionValues = FloatArray(capacity * 5)
            maskValues = ByteBuffer.allocateDirect(capacity * PROTO_SIZE * PROTO_SIZE)
        }
        stageTimingsMs?.set("frameKernelPostprocess", (System.nanoTime() - bridgeStart) / 1_000_000)
        stageTimingsMs?.set("yoloCandidateScan", metrics[0] / 1_000_000)
        stageTimingsMs?.set("yoloMaskAwareNms", metrics[1] / 1_000_000)
        stageTimingsMs?.set("yoloMaskDecode", metrics[2] / 1_000_000)
        stageTimingsMs?.set("yoloMaskIouScan", metrics[3] / 1_000_000)
        val materializeStart = System.nanoTime()
        val mapper = ModelCoordinateMapper(preprocess.srcWidth, preprocess.srcHeight, preprocess.inputSize, PROTO_SIZE)
        val pixels = PROTO_SIZE * PROTO_SIZE
        val detections = List(count) { i ->
            val offset = i * 5
            val bbox = FloatRect(detectionValues[offset], detectionValues[offset + 1],
                detectionValues[offset + 2], detectionValues[offset + 3])
            // The core workspace is reused next frame. Each returned mask owns
            // its native buffer and cannot alias that mutable workspace.
            val bytes = maskValues.duplicate().apply { position(i * pixels); limit((i + 1) * pixels) }
            val buffer = ByteBuffer.allocateDirect(pixels).apply { put(bytes); rewind() }
            PersonDetection(bbox = bbox, confidence = detectionValues[offset + 4],
                mask = NativeMask(PROTO_SIZE, PROTO_SIZE, buffer, preprocess.srcWidth, preprocess.srcHeight, mapper),
                footY = bbox.bottom)
        }
        stageTimingsMs?.set("yoloMaskMaterialize", (metrics[4] + System.nanoTime() - materializeStart) / 1_000_000)
        return detections
    }
}

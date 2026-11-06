package art.gaoge.dance.engine.inference

import art.gaoge.dance.engine.core.FrameKernel
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameKernelInstanceParityTest {
    @Test
    fun rustQuantizerMatchesFrozenJavaAtAllTransitionsAndRandomFloatBitPatterns() {
        val transitions = ExactMaskByteSigmoid.thresholdsForTest()
        val values = mutableListOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -0.0f, 0.0f)
        transitions.forEach { values += Math.nextDown(it); values += it; values += Math.nextUp(it) }
        var state = 0x13579BDFL
        repeat(250_000) {
            state = (state * 1_664_525L + 1_013_904_223L) and 0xffff_ffffL
            values += Float.fromBits(state.toInt())
        }
        val input = values.toFloatArray()
        assertContentEquals(ByteArray(input.size) { ExactMaskByteSigmoid.historicalMaskByte(input[it]).toByte() },
            FrameKernel.maskBytes(input))
    }

    @Test
    fun rustMatchesFrozenProductionMasksAndGeometryAcrossAllFourLayouts() {
        val anchors = YoloLiteRtTensorAdapter.ANCHOR_COUNT
        val pixels = 160 * 160
        val planar = FloatArray(32 * pixels) { ((it * 17 % 41) - 20) / 23f }
        val preprocess = preprocess(3840, 2160)
        for (transposed in listOf(false, true)) for (nhwc in listOf(false, true)) {
            val head = FloatArray(116 * anchors)
            for (a in 0..3) {
                fun put(attr: Int, value: Float) { head[if (transposed) a * 116 + attr else attr * anchors + a] = value }
                put(0, 0.40f + a * 0.005f); put(1, 0.5f); put(2, 0.22f); put(3, 0.4f)
                put(4, 0.9f - a * 0.1f)
                repeat(32) { c -> put(84 + c, ((c * 11 % 19) - 9) / 13f * if (a == 3) -1 else 1) }
            }
            val proto = if (!nhwc) planar else FloatArray(planar.size) { planar[(it % 32) * pixels + it / 32] }
            val headShape = if (transposed) listOf(1, anchors, 116) else listOf(1, 116, anchors)
            val protoShape = if (nhwc) listOf(1, 160, 160, 32) else listOf(1, 32, 160, 160)
            val reference = ReferenceYoloLiteRtTensorAdapter(headShape, protoShape).parseDetections(head, proto, preprocess)
            val actual = YoloLiteRtTensorAdapter(headShape, protoShape).parseDetections(head, proto, preprocess)
            assertEquals(reference.size, actual.size)
            reference.zip(actual).forEach { (old, current) ->
                assertEquals(old.bbox, current.bbox)
                assertEquals(old.confidence, current.confidence)
                assertContentEquals(bytes(requireNotNull(old.mask)), bytes(requireNotNull(current.mask)))
            }
        }
    }

    @Test
    fun outputGrowthPreservesEveryInstanceAndReturnedMasksOwnTheirBuffers() {
        val anchors = YoloLiteRtTensorAdapter.ANCHOR_COUNT
        val head = FloatArray(116 * anchors)
        repeat(33) { a ->
            head[a] = 12f + a * 18f; head[anchors + a] = 100f
            head[2 * anchors + a] = 10f; head[3 * anchors + a] = 100f
            head[4 * anchors + a] = 0.9f
        }
        val proto = FloatArray(32 * 160 * 160)
        val adapter = YoloLiteRtTensorAdapter()
        val first = adapter.parseDetections(head, proto, preprocess(640, 640))
        assertEquals(33, first.size)
        val mask = requireNotNull(first.first().mask)
        val before = bytes(mask)
        head.fill(0f)
        assertEquals(0, adapter.parseDetections(head, proto, preprocess(640, 640)).size)
        assertContentEquals(before, bytes(mask))
        assertFailsWith<IllegalArgumentException> { adapter.parseDetections(FloatArray(1), proto, preprocess(640, 640)) }
        proto[0] = Float.NaN
        assertFailsWith<IllegalArgumentException> { adapter.parseDetections(head, proto, preprocess(640, 640)) }
    }

    private fun preprocess(w: Int, h: Int): PreprocessResult {
        val scale = minOf(640f / w, 640f / h)
        return PreprocessResult(FloatBuffer.allocate(0), ByteBuffer.allocate(0), scale,
            (640 - w * scale) / 2, (640 - h * scale) / 2, w, h, 640)
    }
    private fun bytes(mask: NativeMask): ByteArray = ByteArray(mask.buffer.limit()).also { mask.buffer.duplicate().apply { position(0) }.get(it) }
}

package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameKernelAssociationParityTest {
    @Test
    fun sharedMatcherPreservesFrozenThresholdTiesOrderAndUnmatchedRows() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun put(value: Int) { digest.update(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        var state = 0x13579BDFL
        repeat(400) { trial ->
            val rows = trial % 9
            val columns = trial % 13
            val costs = Array(rows) { FloatArray(columns) {
                state = (state * 1_664_525L + 1_013_904_223L) and 0xffff_ffffL
                when ((state and 31).toInt()) {
                    0 -> Float.NaN
                    1 -> Float.POSITIVE_INFINITY
                    2 -> 0.5f
                    else -> (state ushr 16).toFloat() / 65535f
                }
            } }
            val threshold = if (trial % 2 == 0) 0.5f else 0.7f
            val actual = HungarianSolver.match(costs, threshold)
            assertEquals(ReferenceHungarianSolver.match(costs, threshold), actual, "trial=$trial")
            put(actual.matches.size)
            actual.matches.forEach { put(it.first); put(it.second) }
        }
        assertEquals("36cc72a47f5b17181987fe0c0dcc23f86fe08755e819ec2b685817e6b7ff637a", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        val greedy = arrayOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.11f, 0.9f))
        assertEquals(listOf(0 to 0), HungarianSolver.match(greedy).matches)
    }

    @Test
    fun previewRectangleAssociationUsesRealIoUForSubpixelAndReorderedPeople() {
        val tiny = FloatRect(0f, 0f, 0.1f, 0.1f)
        assertEquals(listOf(0 to 0), FrameKernel.matchRects(listOf(tiny), listOf(tiny), 0f))
        val a = FloatRect(10f, 20f, 30f, 100f)
        val b = FloatRect(200f, 20f, 300f, 100f)
        assertEquals(listOf(0 to 1, 1 to 0), FrameKernel.matchRects(listOf(a, b), listOf(b, a), 0.7f))
    }
}

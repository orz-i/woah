package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.core.FrameKernel
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameKernelAssociationScoreParityTest {
    @Test fun predictionFirstMetricsPreserveFrozenNativeFloatBits() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun put(value: Int) { digest.update(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        var state = 0x13579BDFL
        fun next(max: Int): Int {
            state = (state * 1664525 + 1013904223) and 0xffffffffL
            return ((state ushr 8) % max).toInt()
        }
        val c = floatArrayOf(.35f, .25f, .25f, .15f, 16f)
        val defaults = FrameKernel.associationDefaults
        assertEquals(c.toList(), listOf(defaults.bounds, defaults.mask, defaults.motion, defaults.direction, defaults.gating))
        repeat(10000) { i ->
            val p = floatArrayOf(next(8000) / 4f, next(5000) / 4f, 10f + next(1200) / 4f, 20f + next(2000) / 4f)
            val v = FloatArray(18)
            v[0] = p[0]; v[1] = p[1]; v[2] = p[0] + p[2]; v[3] = p[1] + p[3]
            v[4] = p[0] + (next(800) - 400) / 4f; v[5] = p[1] + (next(400) - 200) / 4f
            v[6] = v[4] + p[2]; v[7] = v[5] + p[3]
            v[8] = p[0] + (next(1600) - 800) / 4f; v[9] = p[1] + (next(1200) - 600) / 4f
            v[10] = v[8] + p[2] * (next(150) + 50) / 100f; v[11] = v[9] + p[3] * (next(150) + 50) / 100f
            v[12] = next(65536) / 65535f; v[13] = next(6400) / 100f
            v[14] = (next(2400) - 1200) / 4f; v[15] = (next(2400) - 1200) / 4f
            v[16] = (next(800) - 400) / 4f; v[17] = (next(400) - 200) / 4f
            val flags = next(32); val age = next(800001).toLong()
            val expected = ReferenceAssociationScore.evaluate(v, age, flags, c)
            val actual = FrameKernel.associationScore(v, age, flags, c)
            assertEquals(expected.toRawBits(), actual.toRawBits(), "case=$i")
            put(actual.toRawBits())
        }
        for (flags in 0..7) for (owners in 0..3) for (box in listOf(0f, .499f, .5f, .9f)) for (mask in listOf(0f, .449f, .45f, .9f)) {
            val expected = flags and 3 == 3 && owners > 0 && (owners >= 2 || (flags and 4 == 0 && box >= .5f && mask >= .45f))
            val result = FrameKernel.reservationRelease(flags and 1 != 0, flags and 2 != 0, owners, flags and 4 != 0, box, mask)
            assertEquals(expected, result)
            put(if (result) 1 else 0)
        }
        assertEquals("9970b76bf03ed4aeb7f0fcc3df51b8b2a41c1dea8f5cfa0f744c255bfe4fa55d",
            digest.digest().joinToString("") { "%02x".format(it) })
    }
}

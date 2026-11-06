package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameKernelBoxFilterParityTest {
    @Test
    fun motionCovarianceAndGatingMatchFrozenFilterAtEveryFloat32Bit() {
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: Float) { digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()) }
        for (scenario in 0..1) {
            val actual = KalmanFilter(); val old = ReferenceKalmanFilter(); var pts = 1L
            val first = FloatRect(10f, 20f, 90f, 180f)
            actual.init(first, pts); old.init(first, pts)
            repeat(240) { i ->
                pts += listOf(0L, 16667, 33333, 40000, 100000, 500000)[i % 6]
                if (i == 140) pts = 0
                val x = if (scenario == 0) 10f + (i % 120) * 3 else 500f - (i % 120) * 2
                val box = FloatRect(x, 20f + i % 17, x + 80 + (i % 5) / 4f, 180f + i % 17 + (i % 7) / 2f)
                if (i == 200) { actual.init(box, pts); old.init(box, pts) }
                assertEquals(old.predict(pts), actual.predict(pts), "predict=$i")
                val gate = actual.gatingDistance(box)
                assertEquals(old.gatingDistance(box).toRawBits(), gate.toRawBits(), "gating=$i")
                if (i !in 60..89) { actual.update(box, pts); old.update(box, pts) }
                if (i % 29 == 0) { actual.dampenVelocity(0.7f); old.dampenVelocity(0.7f) }
                assertEquals(old.toBBox(), actual.toBBox(), "bounds=$i")
                for (n in 0..7) {
                    assertEquals(old.state[n].toRawBits(), actual.state[n].toRawBits(), "state=$i:$n")
                    for (m in 0..7) assertEquals(old.covariance[n][m].toRawBits(), actual.covariance[n][m].toRawBits(), "cov=$i:$n:$m")
                }
                val result = actual.toBBox()
                listOf(result.left, result.top, result.right, result.bottom, gate).forEach(::put)
                actual.state.forEach(::put); actual.covariance.forEach { it.forEach(::put) }
            }
        }
        assertEquals("e6d1cb740e6c0cf00ae01d954401505b1c8f8351eb771e1398bcf3969e1923fd",
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        assertEquals(null, KalmanFilter.invert4x4(Array(4) { FloatArray(4) }))
    }
}

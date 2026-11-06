package art.gaoge.dance.engine.camera

import art.gaoge.dance.engine.inference.FloatRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameKernelFollowParityTest {
    @Test
    fun ptsCameraMatchesFrozenReferenceAcrossHoldBrakeSeekAndZoom() {
        val digest = MessageDigest.getInstance("SHA-256")
        for (scenario in 0..1) {
            val core = SmoothFollower()
            val reference = ReferenceSmoothFollower()
            val source = if (scenario == 0) 16f / 9f else 0.4f
            val zoom = if (scenario == 0) 1f else 1.9f
            val smooth = if (scenario == 0) 0.1f else 0.15f
            var pts = 0L
            repeat(240) { i ->
                pts += longArrayOf(0, 16_667, 33_333, 40_000, 100_000, 500_000)[i % 6]
                if (i == 140) pts = 0
                if (i == 200) { core.reset(); reference.reset() }
                val x = when { i < 20 -> 0.2f; i < 110 -> 0.8f; i < 180 -> 0.15f; else -> 0.9f }
                val target = if (i in 60..89 || (scenario == 1 && i < 3)) null else FloatRect(x - 0.04f, 0.3f, x + 0.04f, 0.7f)
                val actual = core.cropForFrame(target, pts, source, 9f / 16f, zoom, smooth)
                val old = reference.cropForFrame(target, pts, source, 9f / 16f, zoom, smooth)
                assertEquals(old, actual, "scenario=$scenario frame=$i")
                val a = core.motionState(); val b = reference.motionState()
                val values = listOf(actual.left, actual.top, actual.right, actual.bottom,
                    a.cameraX, a.cameraY, a.targetX, a.targetY, a.velocityX, a.velocityY)
                assertEquals(listOf(b.cameraX, b.cameraY, b.targetX, b.targetY, b.velocityX, b.velocityY),
                    values.drop(4), "state scenario=$scenario frame=$i")
                val bytes = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach(bytes::putFloat); digest.update(bytes.array())
            }
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals("8f276242a6773df19a18a4b7c63af042da1f5d2b1eea8f84d397ce3b1bb6a511", fingerprint)
        println("FOLLOW_VECTOR_SHA256=$fingerprint")
        assertEquals(594 to 1056, ReframeGeometry.exactNineSixteenSize(1920, 1080, 1280))
    }
}

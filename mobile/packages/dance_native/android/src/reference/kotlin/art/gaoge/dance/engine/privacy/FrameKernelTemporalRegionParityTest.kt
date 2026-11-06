package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameKernelTemporalRegionParityTest {
    @Test fun sharedStatePreservesFrozenTemporalAndMotionArithmetic() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun put(value: Float) { digest.update(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(value).array()) }
        var random = 0x1d2e3f4L
        fun next(limit: Int): Int { random = (random * 1664525 + 1013904223) and 0xffffffffL; return ((random ushr 8) % limit).toInt() }
        repeat(32) { sequence ->
            val reference = ReferenceRegionStabilizer()
            val core = FrameKernel.RegionState()
            var left = next(8000) / 4f; var top = next(4000) / 4f
            val width = 100f + next(1000) / 4f; val height = 300f + next(2000) / 4f
            var previous = FloatRect(left, top, left + width, top + height)
            var pts = 0L
            repeat(64) { index ->
                left += (next(80) - 40) / 4f; top += (next(80) - 40) / 4f
                val anchor = FloatRect(left, top, left + width + (next(200) - 100) / 4f,
                    top + height + (next(200) - 100) / 4f)
                val raw = floatArrayOf(left + (next(400) - 200) / 4f, top + (next(400) - 200) / 4f,
                    10f + next(200) / 4f, 10f + next(200) / 4f)
                val kind = next(3); val observed = next(2) == 0; val trusted = next(5) == 0
                pts += if (index > 0 && index % 31 == 0) -1000L else longArrayOf(0,8333,16667,33333,40000,100000,250000,500000)[next(8)]
                val source = when (kind) { 0 -> FacePrivacyRegionSource.DETECTED_FACE; 1 -> FacePrivacyRegionSource.PREDICTED_FACE; else -> FacePrivacyRegionSource.YOLO_HEAD_FALLBACK }
                val expected = reference.stabilize(0, FacePrivacyEllipse(raw[0],raw[1],raw[2],raw[3],source),
                    anchor,pts,observed,trusted,sequence % 2 == 0)
                val actual = core.step(raw,anchor,pts,kind,observed,trusted,sequence % 2 == 0)
                val values = floatArrayOf(expected.centerX,expected.centerY,expected.radiusX,expected.radiusY)
                values.forEachIndexed { i, v -> assertEquals(v.toRawBits(),actual[i].toRawBits(), "seq=$sequence frame=$index field=$i") }
                actual.forEach(::put)
                val motion = ReferencePersonBboxMotionEstimator.estimate(previous,anchor)
                val translation = FrameKernel.anchorTranslation(previous,anchor)
                assertEquals(motion.dx.toRawBits(),translation[0].toRawBits()); assertEquals(motion.dy.toRawBits(),translation[1].toRawBits())
                translation.forEach(::put); previous = anchor
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals("905b408fc4b329704fcb6541e6370aadd77b3f0bc9acc5d3db6a0b2cf219f5a8", hash)
        println("FRAME_KERNEL_TEMPORAL_SHA256=" + hash)
    }

    @Test fun anonymousWeakMeasurementsMatchFrozenContinuityWithoutIdentityWrites() {
        val old = ReferenceClassFallbackContinuity(); val current = FacePrivacyClassFallbackContinuity()
        repeat(80) { i ->
            val input = listOf(FacePrivacyClassFallback(-1000000,0,if (i % 20 == 0) setOf(1,2) else setOf(1),
                FloatRect(100f+i,100f,400f+i,800f+(i%3)*60),
                FacePrivacyEllipse(180f+i,160f+(i%3)*65,35f+(i%5),45f+(i%7),FacePrivacyRegionSource.YOLO_HEAD_FALLBACK),
                bodyMaskGuided = i % 2 == 0))
            assertEquals(old.stabilize(input,i*40000L,i%2==0),current.stabilize(input,i*40000L,i%2==0),"frame=$i")
        }
    }

    @Test fun currentMeasuredFaceGrowthCannotBeReducedBelowCurrentGeometry() {
        val state=FacePrivacyTemporalStabilizer(); val box=FloatRect(0f,0f,200f,400f)
        state.stabilize(1,FacePrivacyEllipse(100f,80f,20f,30f,FacePrivacyRegionSource.DETECTED_FACE),box,0)
        val raw=FacePrivacyEllipse(100f,80f,80f,100f,FacePrivacyRegionSource.DETECTED_FACE)
        val output=state.stabilize(1,raw,box,40000)
        assertTrue(output.radiusX>=raw.radiusX && output.radiusY>=raw.radiusY)
    }
}

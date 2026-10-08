package art.gaoge.dance.engine.clarity

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CropClaritySceneSamplerTest {
    private val bright = CropClaritySceneSampler.LumaEvidence(77f, 8, 222, 0.65f)
    private val dark = CropClaritySceneSampler.LumaEvidence(2f, 8, 8, 0.0f)

    private fun signals(
        luma: CropClaritySceneSampler.LumaEvidence? = bright,
        age: Int = 0,
        overlap: Float = 0f,
        motion: Float = 0f,
        protectedVisible: Boolean = true
    ) = CropClaritySceneSampler.Signals(luma, age, overlap, protectedVisible, motion, if (protectedVisible) 1 else 0)

    @Test fun blackFramesAndStaleInputsAreNeverCaptured() {
        val s = CropClaritySceneSampler(650)
        assertEquals(null, s.consider(1, signals(luma = dark)))
        assertEquals(null, s.consider(2, signals(age = 17)))
        assertEquals(null, s.consider(3, signals(luma = null)))
        assertEquals(1, s.screeningSummary["black_or_empty_frames"])
        assertEquals(2, s.screeningSummary["stale_luma_frames"])
        assertEquals("high_contrast", s.consider(4, signals())?.kind)
    }

    @Test fun overlapUsesDedicatedCategoryAndDoesNotDuplicateNearFrames() {
        val s = CropClaritySceneSampler(650)
        assertEquals("privacy_overlap", s.consider(20, signals(overlap = 0.2f))?.kind)
        assertEquals(null, s.consider(21, signals(overlap = 0.4f)))
        assertEquals("privacy_overlap", s.consider(32, signals(overlap = 0.2f))?.kind)
        assertEquals(null, s.consider(45, signals(overlap = 0.2f)))
        assertEquals(2, s.counts["privacy_overlap"])
    }

    @Test fun fastMotionStartsBoundedFiveFrameBurstWithExactSequence() {
        val s = CropClaritySceneSampler(650)
        assertEquals("content_anchor", s.consider(1, signals(luma =
            CropClaritySceneSampler.LumaEvidence(70f, 48, 80, 0.6f)))?.kind)
        assertEquals("temporal_burst", s.consider(30, signals(motion = 0.04f))?.kind)
        for (i in 31..34) {
            val choice = s.consider(i, signals(motion = 0.02f))
            assertEquals("temporal_burst", choice?.kind)
            assertEquals(i - 30, choice?.burstIndex)
        }
        assertEquals(null, s.consider(35, signals(motion = 0.02f)))
        assertEquals(5, s.counts["temporal_burst"])
        assertEquals(listOf(1, 30, 31, 32, 33, 34), s.choices.map { it.frame })
    }

    @Test fun missingFrameTerminatesTemporalBurstWithoutFalseContiguity() {
        val s = CropClaritySceneSampler(650)
        assertEquals("temporal_burst", s.consider(30, signals(motion = 0.03f))?.kind)
        assertEquals(null, s.consider(32, signals()))
        assertEquals(1, s.counts["temporal_burst"])
    }

    @Test fun lumaSamplerReportsContrastAndRejectsAllBlack() {
        val buffer = ByteBuffer.allocate(16 * 16 * 4)
        for (y in 0 until 16) for (x in 0 until 16) {
            val v = if (x < 8) 0 else 255
            val offset = (y * 16 + x) * 4
            for (c in 0..2) buffer.put(offset + c, v.toByte())
            buffer.put(offset + 3, 255.toByte())
        }
        val result = CropClaritySceneSampler.sampleLuma(buffer, 16, 16)
        assertTrue(requireNotNull(result).meaningful)
        assertTrue(result.contrast >= 200)
        assertEquals(null, CropClaritySceneSampler.sampleLuma(ByteBuffer.allocate(1), 16, 16))
        assertFalse(CropClaritySceneSampler.LumaEvidence(0f, 8, 8, 0f).meaningful)
    }

    @Test fun captureBudgetIsHardCapped() {
        val s = CropClaritySceneSampler(650)
        for (frame in 1..649) s.consider(frame, signals(motion = 0.03f, overlap = 0.2f))
        assertTrue(s.choices.size <= CropClaritySceneSampler.MAX_PAIRS)
        assertEquals(s.choices.size, s.choices.map { it.frame }.distinct().size)
    }
}

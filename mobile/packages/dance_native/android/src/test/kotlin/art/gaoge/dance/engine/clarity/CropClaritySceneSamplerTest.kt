package art.gaoge.dance.engine.clarity

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CropClaritySceneSamplerTest {
    private val bright = CropClaritySceneSampler.LumaEvidence(77f, 8, 222, 0.65f)
    private val dark = CropClaritySceneSampler.LumaEvidence(2f, 8, 8, 0f)

    private fun signals(
        luma: CropClaritySceneSampler.LumaEvidence? = bright,
        age: Int = 0,
        cropCount: Int = 0,
        cropOverlap: Float = 0f,
        motion: Float = 0f,
        sourceCount: Int = 1
    ) = CropClaritySceneSampler.Signals(
        luma = luma, lumaAgeFrames = age,
        cropProtectedCount = cropCount,
        cropProtectedAreaFraction = if (cropCount > 0) 0.07f else 0f,
        cropOverlap = cropOverlap, protagonistMotion = motion,
        sourceProtectedCount = sourceCount
    )

    @Test fun blackFramesAndStaleInputsAreNeverCaptured() {
        val sampler = CropClaritySceneSampler(650)
        assertNull(sampler.consider(1, signals(luma = dark)))
        assertNull(sampler.consider(2, signals(age = 17)))
        assertNull(sampler.consider(3, signals(luma = null)))
        assertEquals(1, sampler.screeningSummary["black_or_empty_frames"])
        assertEquals(2, sampler.screeningSummary["stale_luma_frames"])
        assertNull(sampler.consider(4, signals()))
        assertEquals("high_contrast", sampler.consider(13, signals())?.kind)
    }

    @Test fun timeReservedScenesCannotFillTheLastThirdEarly() {
        val sampler = CropClaritySceneSampler(650)
        val earlyAndMiddle = (1..433).mapNotNull { sampler.consider(it, signals()) }
        assertTrue(earlyAndMiddle.size <= 9) // four scene slots + five burst slots
        assertEquals(0, sampler.phaseCounts["late"])
        val later = (434..649).mapNotNull { sampler.consider(it, signals()) }
        assertEquals(3, later.size)
        assertTrue(later.all { it.samplePhase == "late" })
        assertTrue(later.first().frame >= 434)
        assertTrue(later.last().frame >= 572)
        assertEquals(3, sampler.phaseCounts["late"])
    }

    @Test fun overlapIsQualifiedOnlyByFinalCropAndDoesNotExhaustEarlyWindow() {
        val sampler = CropClaritySceneSampler(650)
        val first = assertNotNull(sampler.consider(20, signals(cropCount = 1, cropOverlap = 0.2f)))
        assertEquals("crop_privacy_overlap", first.kind)
        assertEquals(1, first.cropProtectedCount)
        assertNull(sampler.consider(21, signals(cropCount = 1, cropOverlap = 0.4f)))
        assertNull(sampler.consider(32, signals(cropCount = 1, cropOverlap = 0.4f)))
        assertEquals("crop_privacy_visible", sampler.consider(100, signals(cropCount = 1))?.kind)
        assertNull(sampler.consider(120, signals(cropCount = 1)))
        assertEquals(2, sampler.phaseCounts["early"])
    }

    @Test fun rapidMotionUsesIndependentFiveFrameBudget() {
        val sampler = CropClaritySceneSampler(650)
        assertEquals("content_anchor", sampler.consider(13, signals(luma =
            CropClaritySceneSampler.LumaEvidence(70f, 48, 80, .6f)))?.kind)
        assertEquals("temporal_burst", sampler.consider(30, signals(motion = .04f))?.kind)
        for (frame in 31..34) {
            val selection = assertNotNull(sampler.consider(frame, signals(motion = .03f)))
            assertEquals("temporal_burst", selection.kind)
            assertEquals(frame - 30, selection.burstIndex)
        }
        assertEquals(5, sampler.counts["temporal_burst"])
        assertEquals(1, sampler.phaseCounts["early"])
        assertEquals(listOf(13, 30, 31, 32, 33, 34), sampler.choices.map { it.frame })
    }

    @Test fun droppedOrDarkFrameCannotClaimContiguousBurst() {
        val sampler = CropClaritySceneSampler(650)
        assertNotNull(sampler.consider(30, signals(motion = .03f)))
        assertNull(sampler.consider(31, signals(luma = dark)))
        assertNull(sampler.consider(32, signals()))
        assertEquals(1, sampler.counts["temporal_burst"])
        assertFalse(sampler.choices.zipWithNext().any { (left, right) ->
            left.kind == "temporal_burst" && right.kind == "temporal_burst" && right.frame != left.frame + 1
        })
    }

    @Test fun lateCropPrivacyIsPreferredAtItsReservedSlot() {
        val sampler = CropClaritySceneSampler(650)
        for (frame in 1..649) {
            val cropPrivacy = frame in 507..514
            sampler.consider(frame, signals(
                cropCount = if (cropPrivacy) 1 else 0,
                cropOverlap = if (cropPrivacy) .2f else 0f
            ))
        }
        val late = sampler.choices.filter { it.samplePhase == "late" && it.kind != "temporal_burst" }
        assertEquals(3, late.size)
        assertEquals("crop_privacy_overlap", late[1].kind)
        assertTrue(late[1].frame in 507..514)
        assertEquals("late_content", late[0].kind)
        assertEquals("late_content", late[2].kind)
    }

    @Test fun noLateContentDoesNotFabricateLateSampleOrCoverage() {
        val sampler = CropClaritySceneSampler(650)
        for (frame in 1..649) {
            sampler.consider(frame, signals(luma = if (frame >= 400) dark else bright))
        }
        assertEquals(0, sampler.phaseCounts["late"])
        assertTrue(sampler.screeningSummary.getValue("black_or_empty_frames") > 0)
    }

    @Test fun metadataOverestimateStillAllowsLateFrames() {
        val sampler = CropClaritySceneSampler(650)
        for (frame in 1..649) sampler.consider(frame, signals())
        val late = sampler.choices.filter { it.samplePhase == "late" }
        assertEquals(3, late.size)
        assertTrue(late.last().frame < 649)
    }

    @Test fun lumaSamplerReportsContrastAndRejectsAllBlack() {
        val buffer = ByteBuffer.allocate(16 * 16 * 4)
        for (y in 0 until 16) for (x in 0 until 16) {
            val value = if (x < 8) 0 else 255
            val position = (y * 16 + x) * 4
            for (channel in 0..2) buffer.put(position + channel, value.toByte())
            buffer.put(position + 3, 255.toByte())
        }
        val evidence = assertNotNull(CropClaritySceneSampler.sampleLuma(buffer, 16, 16))
        assertTrue(evidence.meaningful)
        assertTrue(evidence.contrast >= 200)
        assertNull(CropClaritySceneSampler.sampleLuma(ByteBuffer.allocate(1), 16, 16))
        assertFalse(CropClaritySceneSampler.LumaEvidence(0f, 8, 8, 0f).meaningful)
    }

    @Test fun allBudgetsTogetherBoundTotalCaptures() {
        val sampler = CropClaritySceneSampler(650)
        for (frame in 1..649) {
            sampler.consider(frame, signals(cropCount = 1, cropOverlap = .3f, motion = .03f))
        }
        assertEquals(12, sampler.choices.size)
        assertEquals(5, sampler.counts["temporal_burst"])
        assertEquals(mapOf("early" to 2, "middle" to 2, "late" to 3), sampler.phaseCounts)
        assertEquals(sampler.choices.map { it.frame }.distinct().size, sampler.choices.size)
    }
}

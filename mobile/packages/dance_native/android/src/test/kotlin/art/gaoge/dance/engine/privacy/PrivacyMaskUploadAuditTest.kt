package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Geometry/mask-input evidence, not a replacement for a decoded export pixel review. */
class PrivacyMaskUploadAuditTest {
    private fun mask(vararg bytes: Int): NativeMask {
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        for (value in bytes) buffer.put(value.toByte())
        buffer.rewind()
        return NativeMask(
            width = 2,
            height = 2,
            buffer = buffer,
            originalWidth = 1280,
            originalHeight = 720,
            samplingRect = FloatRect(0f, 0.21875f, 1f, 0.78125f)
        )
    }

    @Test
    fun samplesFirstTwelveTrueDetectorMissesAndThenOnlyEveryThirty() {
        val expected = (1..65).filter { it in 1..12 || it == 30 || it == 60 }
        val sampled = (0..65).filter { PrivacyMaskUploadAudit.shouldSampleEmptyInferenceStreak(it) }
        assertEquals(expected, sampled)
    }

    @Test
    fun tightMaskThresholdCountsActualTextureInputPixels() {
        assertEquals(2, PrivacyMaskUploadAudit.contourPixels(mask(0, 127, 128, 255)))
        assertEquals(0, PrivacyMaskUploadAudit.contourPixels(mask(0, 1, 127, 38)))
        assertNull(PrivacyMaskUploadAudit.contourPixels(null))
        assertEquals(
            -1,
            PrivacyMaskUploadAudit.contourPixels(
                mask(255, 255, 255, 255).copy(buffer = ByteBuffer.allocateDirect(2))
            )
        )
    }

    @Test
    fun maskOutsideSourceSamplingRectIsNotMistakenForVisibleProtection() {
        val bytes = ByteBuffer.allocateDirect(16)
        repeat(16) { bytes.put(0.toByte()) }
        // Only top/bottom proto rows carry mask pixels, outside the actual
        // source crop y=0.25..0.75. Bbox history alone cannot prove visibility.
        for (x in 0 until 4) {
            bytes.put(x, 255.toByte())
            bytes.put(12 + x, 255.toByte())
        }
        bytes.rewind()
        val outside = NativeMask(
            width = 4, height = 4, buffer = bytes,
            originalWidth = 1280, originalHeight = 720,
            samplingRect = FloatRect(0f, 0.25f, 1f, 0.75f)
        )
        assertEquals(8, PrivacyMaskUploadAudit.contourPixels(outside))
        assertEquals(
            0, PrivacyMaskUploadAudit.sourceSampledContourCenters(outside, outside.samplingRect),
            "nonzero proto pixels may all be outside GL's source sampling area"
        )
        bytes.put(1 * 4 + 2, 255.toByte())
        assertEquals(
            1, PrivacyMaskUploadAudit.sourceSampledContourCenters(outside, outside.samplingRect)
        )
        assertEquals(
            -1, PrivacyMaskUploadAudit.sourceSampledContourCenters(
                outside, FloatRect(0.5f, 0.5f, 0.2f, 0.8f)
            ),
            "invalid sample geometry must not be counted as valid"
        )
    }

    @Test
    fun emptyDetectionTailReportsTrackedMaskAndActualMergedUploadSeparately() {
        val rootMask = mask(255, 255, 0, 0)
        val tracked = listOf(
            TrackedPerson(
                id = 0, bbox = FloatRect(150f, 626f, 265f, 745f),
                mask = rootMask, confidence = 0.90f,
                state = TrackState.OCCLUDED, observedThisFrame = false,
                framesSinceLastObservation = 12
            ),
            TrackedPerson(
                id = 1, bbox = FloatRect(550f, 180f, 630f, 480f),
                mask = null, confidence = 0.60f,
                state = TrackState.LOST, observedThisFrame = false,
                framesSinceLastObservation = 178
            ),
            TrackedPerson(
                id = 2, bbox = FloatRect(200f, 200f, 300f, 500f),
                mask = null, confidence = 0.95f,
                state = TrackState.ACTIVE, observedThisFrame = true
            )
        )
        val sample = PrivacyMaskUploadAudit.sourceMaskEvidence(
            persons = tracked,
            selectedPersonIds = setOf(0, 1),
            uploadedPrivacyMask = rootMask,
            uploadedOccluderMask = null,
            sourceWidth = 1280,
            sourceHeight = 720,
            renderWidth = 1280,
            renderHeight = 720,
            tightMask = true
        )
        assertEquals(2, sample["selected_root_count"])
        assertEquals(2, sample["uploaded_privacy_mask_contour_pixels_ge_128"])
        assertEquals(2, sample["uploaded_privacy_source_sampling_center_pixels_ge_128"])
        assertNull(sample["uploaded_occluder_mask_contour_pixels_ge_128"])
        assertEquals(listOf(2, 2), sample["uploaded_privacy_mask_size"])
        assertEquals(listOf(1280, 720), sample["uploaded_privacy_mask_source_size"])
        assertTrue(sample["uploaded_privacy_mask_present"] as Boolean)
        @Suppress("UNCHECKED_CAST")
        val roots = sample["selected_root_masks"] as List<Map<String, Any?>>
        assertEquals(listOf(0, 1), roots.map { it["track_id"] })
        assertEquals(listOf(2, null), roots.map { it["mask_contour_pixels_ge_128"] })
        assertEquals(listOf(2, null), roots.map { it["mask_source_sampling_center_pixels_ge_128"] })
        assertEquals(listOf(12, 178), roots.map { it["frames_since_last_observation"] })
        assertEquals(listOf(false, true), roots.map { it["tracked_fallback_mask_expired"] })
        assertEquals(15, sample["fallback_observation_age_budget_frames"])
        assertEquals(false, roots[0]["observed"])
        assertEquals(false, roots[1]["mask_present"])
        assertEquals(
            "GL_TEXTURE_INPUT_ONLY_NO_FINAL_PIXEL_READBACK",
            sample["evidence_scope"]
        )
    }

    @Test
    fun nonNullButEmptyCompositedMaskIsNotReportedAsPrivacyCoverage() {
        val empty = mask(0, 0, 0, 0)
        val sample = PrivacyMaskUploadAudit.sourceMaskEvidence(
            persons = emptyList(),
            selectedPersonIds = setOf(1),
            uploadedPrivacyMask = empty,
            uploadedOccluderMask = null,
            sourceWidth = 1280,
            sourceHeight = 720,
            renderWidth = 810,
            renderHeight = 1440,
            tightMask = true
        )
        assertEquals(0, sample["uploaded_privacy_mask_contour_pixels_ge_128"])
        assertEquals(true, sample["uploaded_privacy_mask_present"])
        assertEquals(810, sample["render_width"])
        assertEquals(1440, sample["render_height"])
        assertEquals(emptyList<Any>(), sample["selected_root_masks"])
    }
}

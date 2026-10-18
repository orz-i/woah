package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.inference.PersonDetection
import art.gaoge.dance.engine.tracking.FreshPrivacyClassEvidence
import art.gaoge.dance.engine.tracking.PrivacySelectionClass
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Source-space bounding boxes and timestamps are anonymized geometry from the
 * two PLK110 exports at 9502027. The 810x1440 and 1280x720 exports had the
 * exact same five source-space decisions. Pixel probabilities are deliberately
 * synthetic: the diagnostics did not include full-frame mask bytes at 30-40 s.
 */
class PrivacyUnknownForegroundSafetyTest {
    private data class RiskFrame(
        val ptsUs: Long,
        val unknownIndex: Int,
        val unselectedIndex: Int,
        val unknownBox: FloatRect,
        val foregroundBox: FloatRect
    )

    private val risks = listOf(
        RiskFrame(30833333L, 2, 3,
            FloatRect(559.5117f, 272.42188f, 607.9883f, 448.82812f),
            FloatRect(566.25f, 251.09375f, 721.25f, 588.90625f)),
        RiskFrame(34900000L, 3, 2,
            FloatRect(585f, 250.15625f, 682.5f, 496.09375f),
            FloatRect(528.75f, 282.96875f, 658.75f, 615.78125f)),
        RiskFrame(39733333L, 2, 3,
            FloatRect(504.64844f, 280.39062f, 589.10156f, 500.85938f),
            FloatRect(518.8281f, 293.59375f, 691.7969f, 623.90625f)),
        RiskFrame(39766666L, 2, 3,
            FloatRect(503.125f, 280.625f, 590.625f, 503.125f),
            FloatRect(523.59375f, 303.59375f, 692.03125f, 623.90625f)),
        RiskFrame(39800000L, 2, 3,
            FloatRect(503.39844f, 281.40625f, 591.60156f, 504.84375f),
            FloatRect(524.53125f, 343.90625f, 692.96875f, 624.84375f))
    )

    private fun mask(
        value: Int,
        fromX: Int,
        toX: Int,
        fromY: Int,
        toY: Int,
        width: Int = 160
    ): NativeMask {
        val buf = ByteBuffer.allocateDirect(width * width)
        repeat(width * width) { buf.put(0.toByte()) }
        for (y in fromY until toY) for (x in fromX until toX) {
            buf.put(y * width + x, value.toByte())
        }
        buf.rewind()
        return NativeMask(width, width, buf, 1280, 720)
    }

    private fun selectedMask(value: Int = 220): NativeMask =
        mask(value, 60, 87, 60, 101)

    private fun foregroundMask(value: Int = 215): NativeMask =
        mask(value, 72, 125, 48, 135)

    private fun pixel(mask: NativeMask, x: Int, y: Int): Int =
        mask.buffer.get(y * mask.width + x).toInt() and 0xFF

    private fun resolve(
        frame: RiskFrame,
        selectedValue: Int = 220,
        foregroundValue: Int = 215,
        foregroundObserved: Boolean = true,
        foregroundTrackBox: FloatRect = frame.foregroundBox,
        foregroundClass: PrivacySelectionClass = PrivacySelectionClass.UNSELECTED,
        candidateConservativeUnknown: Boolean = false,
        foregroundWidth: Int = 160,
        selectedMaskOverride: NativeMask? = null,
        foregroundMaskOverride: NativeMask? = null,
        targetState: TrackState = TrackState.ACTIVE,
        duplicateProtectedEvidence: Boolean = false
    ): ResolvedCompositorMasks {
        val protected = selectedMaskOverride ?: selectedMask(selectedValue)
        val front = foregroundMaskOverride ?: if (foregroundWidth == 160) {
            foregroundMask(foregroundValue)
        } else {
            mask(foregroundValue, 8, 15, 8, 15, width = foregroundWidth)
        }
        // Original privacy root ID 1 is not reclassified from geometric proximity.
        val root = TrackedPerson(
            id = 1,
            bbox = frame.unknownBox,
            mask = protected,
            confidence = 0.95f,
            age = 20,
            state = targetState,
            observedThisFrame = false
        )
        val observedUnselected = TrackedPerson(
            id = 2,
            bbox = foregroundTrackBox,
            mask = front,
            confidence = 0.85f,
            age = 20,
            state = if (foregroundObserved) TrackState.ACTIVE else TrackState.LOST,
            observedThisFrame = foregroundObserved,
            footY = foregroundTrackBox.bottom
        )
        val evidence = mutableListOf(
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = frame.unknownIndex,
                detection = PersonDetection(
                    bbox = frame.unknownBox,
                    confidence = 0.42f,
                    mask = protected,
                    footY = frame.unknownBox.bottom
                ),
                residualTrackIds = emptySet(),
                conservativeUnknown = true
            ),
            FreshPrivacyClassEvidence(
                selectionClass = foregroundClass,
                detectionIndex = frame.unselectedIndex,
                detection = PersonDetection(
                    bbox = frame.foregroundBox,
                    confidence = 0.80f,
                    mask = front,
                    footY = frame.foregroundBox.bottom
                ),
                residualTrackIds = emptySet(),
                conservativeUnknown = candidateConservativeUnknown
            )
        )
        if (duplicateProtectedEvidence) {
            evidence.add(
                FreshPrivacyClassEvidence(
                    selectionClass = PrivacySelectionClass.SELECTED,
                    detectionIndex = 9,
                    detection = PersonDetection(
                        bbox = frame.unknownBox,
                        confidence = 0.42f,
                        mask = protected,
                        footY = frame.unknownBox.bottom
                    ),
                    residualTrackIds = emptySet(),
                    conservativeUnknown = true
                )
            )
        }
        return PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(root, observedUnselected),
            selectedPersonIds = setOf(1),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1,
            freshClassEvidence = evidence,
            preferFreshClassPrimary = true,
            expectedSelectedCount = 1,
            ptsUs = frame.ptsUs
        )
    }

    @Test
    fun fiveDeviceRiskFramesKeepUnknownPrivacyWhenDepthCoreLacksRawPixelOwner() {
        for (frame in risks) {
            val resolved = resolve(frame)
            val privacy = assertNotNull(resolved.privacyMask)
            assertEquals(220, pixel(privacy, 80, 80), "unknown pixels must survive at ${frame.ptsUs}")
            assertFalse(resolved.hasOccluder, "footY alone must not authorize a cut at ${frame.ptsUs}")
        }
    }

    @Test
    fun fiveDeviceRiskFramesPermitOnlyObservedRawProbabilityDominantForeground() {
        for (frame in risks) {
            val resolved = resolve(frame, selectedValue = 160, foregroundValue = 255)
            val privacy = assertNotNull(resolved.privacyMask)
            assertEquals(0, pixel(privacy, 80, 80), "corroborated foreground remains visible")
            assertEquals(160, pixel(privacy, 65, 80), "privacy outside the occluder must survive")
            assertTrue(resolved.hasOccluder, "only verified cut pixels receive a shader hole")
        }
    }

    @Test
    fun lostOrUnmatchedUnselectedTrackCannotCutConservativeUnknownPrivacy() {
        val frame = risks[2]
        val noObservation = assertNotNull(resolve(
            frame, foregroundObserved = false, selectedValue = 160, foregroundValue = 255
        ).privacyMask)
        assertEquals(160, pixel(noObservation, 80, 80))
        val unrelated = assertNotNull(resolve(
            frame,
            foregroundTrackBox = FloatRect(0f, 0f, 100f, 100f),
            selectedValue = 160,
            foregroundValue = 255,
            targetState = TrackState.REACQUIRING
        ).privacyMask)
        assertEquals(160, pixel(unrelated, 80, 80))
    }

    @Test
    fun candidateUnknownClassAndMismatchedMaskDimensionsCannotCutPrivacy() {
        val frame = risks[3]
        val ambiguousClass = assertNotNull(resolve(
            frame, selectedValue = 160, foregroundValue = 255,
            candidateConservativeUnknown = true
        ).privacyMask)
        assertEquals(160, pixel(ambiguousClass, 80, 80))
        val wrongDimensions = assertNotNull(resolve(
            frame, selectedValue = 160, foregroundValue = 255, foregroundWidth = 32
        ).privacyMask)
        assertEquals(160, pixel(wrongDimensions, 80, 80))
    }

    @Test
    fun tinyAndMisalignedMasksCannotCreateUnsupportedUnknownPrivacyHoles() {
        val frame = risks[2]
        val onePixel = mask(220, 80, 81, 80, 81)
        val tinyResult = assertNotNull(resolve(
            frame, foregroundValue = 215, selectedMaskOverride = onePixel
        ).privacyMask)
        assertEquals(220, pixel(tinyResult, 80, 80))
        val front = foregroundMask(255)
        val shiftedSampling = NativeMask(
            width = front.width,
            height = front.height,
            buffer = front.buffer,
            originalWidth = front.originalWidth,
            originalHeight = front.originalHeight,
            samplingRect = FloatRect(0.1f, 0f, 0.9f, 1f)
        )
        val misalignedResult = assertNotNull(resolve(
            frame, selectedValue = 160, foregroundMaskOverride = shiftedSampling
        ).privacyMask)
        assertEquals(160, pixel(misalignedResult, 80, 80))
    }

    @Test
    fun duplicateSelectedEvidenceNeverErasesAlreadyOwnedSelectedPixels() {
        val resolved = resolve(
            risks[4], selectedValue = 220, foregroundValue = 215,
            duplicateProtectedEvidence = true
        )
        val privacy = assertNotNull(resolved.privacyMask)
        assertEquals(220, pixel(privacy, 80, 80))
    }

    @Test
    fun missingObservedFullBodyMaskIsAFailClosedExportConditionOnlyForSelected() {
        val observedMissing = TrackedPerson(
            id = 1,
            bbox = risks[0].unknownBox,
            mask = null,
            confidence = 0.80f,
            observedThisFrame = true,
            state = TrackState.ACTIVE
        )
        assertEquals(
            listOf(1),
            PrivacyOcclusionResolver.observedSelectedWithoutMasks(
                listOf(observedMissing), setOf(1)
            )
        )
        assertTrue(
            PrivacyOcclusionResolver.observedSelectedWithoutMasks(
                listOf(observedMissing), setOf(2)
            ).isEmpty(),
            "FACE_ONLY or nonprotected track must not enter FULL_BODY export guard"
        )
        assertTrue(
            PrivacyOcclusionResolver.observedSelectedWithoutMasks(
                listOf(observedMissing.copy(observedThisFrame = false)), setOf(1)
            ).isEmpty(),
            "unobserved LOST roots are not fresh mask loss by themselves"
        )
        val observedEmpty = observedMissing.copy(mask = mask(0, 60, 87, 60, 101))
        assertEquals(
            listOf(1),
            PrivacyOcclusionResolver.observedSelectedWithoutMasks(
                listOf(observedEmpty), setOf(1)
            ),
            "an all-zero non-null FULL_BODY mask must also fail closed"
        )
    }
}

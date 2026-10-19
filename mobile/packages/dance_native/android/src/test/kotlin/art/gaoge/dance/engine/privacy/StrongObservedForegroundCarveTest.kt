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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrongObservedForegroundCarveTest {
    private fun mask(
        value: Int, left: Int = 60, right: Int = 95, top: Int = 60,
        bottom: Int = 110
    ): NativeMask {
        val buffer = ByteBuffer.allocateDirect(160 * 160)
        repeat(160 * 160) { buffer.put(0.toByte()) }
        for (y in top until bottom) for (x in left until right) {
            buffer.put(y * 160 + x, value.toByte())
        }
        buffer.rewind()
        return NativeMask(160, 160, buffer, 1280, 720)
    }

    private fun pixel(mask: NativeMask, x: Int, y: Int): Int =
        mask.buffer.get(y * mask.width + x).toInt() and 0xFF

    private fun gate() = StrongObservedForegroundCarve.Gate(
        useFreshClassPrimary = true,
        targetConservativeUnknown = true,
        targetResidualTrackIdsEmpty = true,
        targetConfidence = 0.268f,
        candidateFreshUnselected = true,
        candidateConfidence = 0.794f,
        observedUnselected = true,
        observedUnselectedAgeFrames = 1200,
        observedUnselectedConfidence = 0.794f,
        foregroundTrackMatchesCandidate = true,
        bboxOverlapRatio = 0.782f,
        normalizedForegroundFootDelta = 0.558f,
        freshDepthCoreIntersectionPixels = 97
    )

    @Test
    fun onlyTightlyCorroboratedCurrentForegroundCanEnableNewCarve() {
        val valid = gate()
        assertTrue(StrongObservedForegroundCarve.eligible(valid))
        for (bad in listOf(
            valid.copy(useFreshClassPrimary = false),
            valid.copy(targetConservativeUnknown = false),
            valid.copy(targetResidualTrackIdsEmpty = false),
            valid.copy(targetConfidence = 0.46f),
            valid.copy(candidateFreshUnselected = false),
            valid.copy(candidateConfidence = 0.69f),
            valid.copy(observedUnselected = false),
            valid.copy(observedUnselectedAgeFrames = 14),
            valid.copy(observedUnselectedConfidence = 0.69f),
            valid.copy(foregroundTrackMatchesCandidate = false),
            valid.copy(bboxOverlapRatio = 0.49f),
            valid.copy(normalizedForegroundFootDelta = 0.49f),
            valid.copy(freshDepthCoreIntersectionPixels = 8)
        )) {
            assertFalse(StrongObservedForegroundCarve.eligible(bad), "$bad")
        }
    }

    @Test
    fun erodedCurrentInteriorNeverCarvesOutsideObservedForeground() {
        val selected = mask(245)
        val foreground = mask(255, left = 72, right = 125, top = 50, bottom = 135)
        val carved = assertNotNull(StrongObservedForegroundCarve.build(
            selected = selected, freshForeground = foreground,
            observedForeground = foreground, otherSelected = emptyList()
        ))
        assertEquals(255, pixel(carved, 80, 80))
        assertEquals(0, pixel(carved, 65, 80))
        assertEquals(0, pixel(carved, 73, 60)) // tight erosion trims border
        assertEquals(245, pixel(selected, 80, 80)) // original mask untouched
    }

    @Test
    fun anotherSelectedMaskVetoesTheSameForegroundPixels() {
        val selected = mask(245)
        val foreground = mask(255, left = 72, right = 125, top = 50, bottom = 135)
        val otherSelected = mask(255, left = 78, right = 91, top = 72, bottom = 99)
        val carved = assertNotNull(StrongObservedForegroundCarve.build(
            selected = selected, freshForeground = foreground,
            observedForeground = foreground, otherSelected = listOf(otherSelected)
        ))
        assertEquals(0, pixel(carved, 80, 80))
        assertEquals(255, pixel(carved, 76, 80))
        assertEquals(255, pixel(otherSelected, 80, 80))
    }

    @Test
    fun weakOrUnobservedProtagonistPixelsMustKeepSelectedPrivacy() {
        val selected = mask(245)
        val foreground = mask(255, left = 72, right = 125, top = 50, bottom = 135)
        val weakObserved = mask(200, left = 72, right = 125, top = 50, bottom = 135)
        assertNull(StrongObservedForegroundCarve.build(
            selected, foreground, weakObserved, emptyList()
        ))
        assertNull(StrongObservedForegroundCarve.build(
            selected,
            mask(220, left = 72, right = 125, top = 50, bottom = 135),
            foreground, emptyList()
        ))
        assertNull(StrongObservedForegroundCarve.build(
            selected, foreground, foreground,
            listOf(mask(255, left = 60, right = 95, top = 60, bottom = 110))
        ))
    }

    @Test
    fun invalidMaskMapperOrSourceFrameCannotAuthorizeCarve() {
        val selected = mask(245)
        val foreground = mask(255, left = 72, right = 125, top = 50, bottom = 135)
        val misaligned = foreground.copy(originalWidth = 960)
        assertNull(StrongObservedForegroundCarve.build(
            selected, foreground, misaligned, emptyList()
        ))
        assertNull(StrongObservedForegroundCarve.build(
            selected, foreground, foreground, listOf(misaligned)
        ))
        assertNull(StrongObservedForegroundCarve.build(
            selected, foreground.copy(buffer = ByteBuffer.allocateDirect(10)),
            foreground, emptyList()
        ))
    }

    @Test
    fun resolverCanSubtractOnlyStrongForegroundWithoutRemovingUnknownIdentity() {
        val protectedBbox = FloatRect(504.64844f, 280.39062f, 589.10156f, 500.85938f)
        val frontBbox = FloatRect(518.8281f, 293.59375f, 691.7969f, 623.90625f)
        val selectedMask = mask(245)
        val frontMask = mask(255, left = 72, right = 125, top = 50, bottom = 135)
        val protectedIdentity = TrackedPerson(
            id = 1, bbox = protectedBbox, mask = selectedMask,
            confidence = 0.268f, observedThisFrame = true, age = 1200,
            state = TrackState.ACTIVE
        )
        val foregroundRoot = TrackedPerson(
            id = 2, bbox = frontBbox, mask = frontMask, confidence = 0.794f,
            observedThisFrame = true, age = 1200, state = TrackState.ACTIVE,
            footY = frontBbox.bottom
        )
        fun evidence(otherSelected: Boolean): List<FreshPrivacyClassEvidence> {
            val current = mutableListOf(
                FreshPrivacyClassEvidence(
                    PrivacySelectionClass.SELECTED, 2,
                    PersonDetection(protectedBbox, 0.268f, selectedMask,
                        footY = protectedBbox.bottom),
                    residualTrackIds = emptySet(), conservativeUnknown = true
                ),
                FreshPrivacyClassEvidence(
                    PrivacySelectionClass.UNSELECTED, 3,
                    PersonDetection(frontBbox, 0.794f, frontMask,
                        footY = frontBbox.bottom),
                    residualTrackIds = emptySet(), conservativeUnknown = false
                )
            )
            if (otherSelected) {
                current.add(
                    FreshPrivacyClassEvidence(
                        PrivacySelectionClass.SELECTED, 9,
                        PersonDetection(
                            protectedBbox, 0.92f,
                            mask(255, left = 78, right = 91, top = 72, bottom = 99)
                        ),
                        residualTrackIds = emptySet(), conservativeUnknown = false
                    )
                )
            }
            return current
        }
        val result = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(protectedIdentity, foregroundRoot),
            selectedPersonIds = setOf(1),
            freshClassEvidence = evidence(false),
            preferFreshClassPrimary = true,
            expectedSelectedCount = 1,
            applyDilationToPrivacyTargets = false,
            ptsUs = 39_733_333
        )
        val privacy = assertNotNull(result.privacyMask)
        assertEquals(0, pixel(privacy, 80, 80))
        assertEquals(245, pixel(privacy, 65, 80))
        assertTrue(result.hasPrivacy)
        val competing = assertNotNull(PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(protectedIdentity, foregroundRoot),
            selectedPersonIds = setOf(1),
            freshClassEvidence = evidence(true),
            preferFreshClassPrimary = true,
            expectedSelectedCount = 2,
            applyDilationToPrivacyTargets = false,
            ptsUs = 39_733_333
        ).privacyMask)
        assertEquals(255, pixel(competing, 80, 80))
        // Even if a different SELECTED instance is present, its own pixels
        // can never be cleared by carving another selected target.
    }
}

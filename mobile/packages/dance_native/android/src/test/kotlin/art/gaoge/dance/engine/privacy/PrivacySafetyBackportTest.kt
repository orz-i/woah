package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.inference.PersonDetection
import art.gaoge.dance.engine.tracking.FreshPrivacyClassEvidence
import art.gaoge.dance.engine.tracking.PrivacySelectionClass
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Minimal safety backport from Crop Clarity Phase 2.
 * Does not enable any temporal protagonist pixel-carving or class reassignment.
 */
class PrivacySafetyBackportTest {
    private fun rectMask(value: Int, left: Int = 9, right: Int = 20): NativeMask {
        val width = 64
        val height = 64
        val buffer = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        repeat(width * height) { buffer.put(0.toByte()) }
        for (y in 12 until 38) {
            for (x in left until right) buffer.put(y * width + x, value.toByte())
        }
        buffer.rewind()
        return NativeMask(width, height, buffer, 640, 640)
    }

    private fun tracked(
        id: Int = 1, mask: NativeMask? = rectMask(255),
        observed: Boolean = false, observationAge: Int = 0,
        state: TrackState = TrackState.LOST, missed: Int = 1
    ) = TrackedPerson(
        id = id,
        bbox = FloatRect(90f, 120f, 200f, 380f),
        mask = mask,
        confidence = 0.9f,
        missedFrames = missed,
        framesSinceLastObservation = observationAge,
        age = 200,
        state = state,
        observedThisFrame = observed
    )

    private fun resolve(vararg people: TrackedPerson) = PrivacyOcclusionResolver.resolveMasks(
        persons = people.toList(),
        selectedPersonIds = setOf(1),
        maxFallbackObservationAgeFrames = 15,
        applyDilationToPrivacyTargets = false,
        occluderErosionRadius = 0
    )

    @Test
    fun historicalSelectedMaskCannotReviveAfterLostStateCounterResets() {
        val stale = tracked(observationAge = 178, state = TrackState.LOST, missed = 1)
        assertFalse(resolve(stale).hasPrivacy, "178 unseen frames must not be reset by LOST.missedFrames=1")
        assertFalse(resolve(stale.copy(state = TrackState.REACQUIRING)).hasPrivacy)
        assertFalse(resolve(stale.copy(state = TrackState.OCCLUDED)).hasPrivacy)
    }

    @Test
    fun recentlyUnobservedSelectedMaskKeepsBoundedFallback() {
        val recent = tracked(observationAge = 12)
        assertTrue(resolve(recent).hasPrivacy)
        assertNotNull(resolve(recent).privacyMask)
        assertTrue(resolve(recent.copy(framesSinceLastObservation = 15)).hasPrivacy, "age budget is inclusive")
        assertFalse(resolve(recent.copy(framesSinceLastObservation = 16)).hasPrivacy, "expired immediately after age limit")
    }

    @Test
    fun genuinelyObservedProtectedPersonKeepsCurrentMask() {
        val observed = tracked(observed = true, observationAge = 178, state = TrackState.ACTIVE)
        assertTrue(resolve(observed).hasPrivacy, "real current observation is not a historical fallback")
    }

    @Test
    fun currentFreshSelectedDetectionDoesNotInheritExpiredTrackMask() {
        val stale = tracked(observationAge = 178)
        val current = rectMask(255, left = 36, right = 50)
        val evidence = FreshPrivacyClassEvidence(
            selectionClass = PrivacySelectionClass.SELECTED,
            detectionIndex = 0,
            detection = PersonDetection(
                bbox = FloatRect(360f, 120f, 500f, 380f),
                confidence = 0.95f,
                mask = current
            ),
            residualTrackIds = emptySet()
        )
        val result = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(stale),
            selectedPersonIds = setOf(1),
            freshClassEvidence = listOf(evidence),
            preferFreshClassPrimary = true,
            expectedSelectedCount = 1,
            maxFallbackObservationAgeFrames = 15,
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 0
        )
        val rendered = assertNotNull(result.privacyMask)
        assertTrue((rendered.buffer.get(20 * 64 + 40).toInt() and 255) > 0)
        assertEquals(0, rendered.buffer.get(20 * 64 + 12).toInt() and 255)
    }

    @Test
    fun missingCurrentProtectedMaskMustFailClosedButNotOtherTracks() {
        val missing = tracked(observed = true, mask = null, state = TrackState.ACTIVE)
        assertEquals(listOf(1), PrivacyOcclusionResolver.observedSelectedWithoutMasks(listOf(missing), setOf(1)))
        assertTrue(PrivacyOcclusionResolver.observedSelectedWithoutMasks(listOf(missing), setOf(2)).isEmpty())
        assertTrue(PrivacyOcclusionResolver.observedSelectedWithoutMasks(listOf(missing.copy(observedThisFrame = false)), setOf(1)).isEmpty())
    }

    @Test
    fun emptyOrBelowThresholdFullBodyMaskIsNotRenderable() {
        for (value in listOf(0, 64, 127)) {
            val weak = tracked(observed = true, state = TrackState.ACTIVE, mask = rectMask(value))
            assertEquals(listOf(1), PrivacyOcclusionResolver.observedSelectedWithoutMasks(listOf(weak), setOf(1)))
        }
        val boundary = tracked(observed = true, state = TrackState.ACTIVE, mask = rectMask(128))
        assertTrue(PrivacyOcclusionResolver.observedSelectedWithoutMasks(listOf(boundary), setOf(1)).isEmpty())
        val incompleteBuffer = rectMask(255).also { it.buffer.limit(10) }
        assertEquals(
            listOf(1),
            PrivacyOcclusionResolver.observedSelectedWithoutMasks(
                listOf(tracked(observed = true, mask = incompleteBuffer)), setOf(1)
            ),
            "A truncated mask buffer must fail closed, not throw while scanning bytes"
        )
    }
}

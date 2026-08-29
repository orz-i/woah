package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.FreshPrivacyClassEvidence
import com.danceanon.native.tracking.PrivacySelectionClass
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrivacyFreshClassEvidenceTest {

    private fun rectMask(
        width: Int = 64,
        height: Int = 64,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        value: Int = 255
    ): NativeMask {
        val buffer = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        repeat(width * height) { buffer.put(0.toByte()) }
        for (y in top until bottom) {
            for (x in left until right) {
                buffer.put(y * width + x, value.coerceIn(0, 255).toByte())
            }
        }
        buffer.rewind()
        return NativeMask(width, height, buffer, 640, 640)
    }

    @Test
    fun freshSelectedClassEvidenceReplacesStaleSelectedMaskWithoutAssigningIdentity() {
        val staleMask = rectMask(left = 5, top = 10, right = 16, bottom = 40)
        val freshMask = rectMask(left = 40, top = 10, right = 52, bottom = 40)
        val selected = TrackedPerson(
            id = 42,
            bbox = FloatRect(50f, 100f, 160f, 400f),
            mask = staleMask,
            confidence = 0.95f,
            age = 20,
            state = TrackState.REACQUIRING,
            observedThisFrame = false,
            footY = 400f
        )
        val freshEvidence = FreshPrivacyClassEvidence(
            selectionClass = PrivacySelectionClass.SELECTED,
            detectionIndex = 3,
            detection = PersonDetection(
                bbox = FloatRect(400f, 100f, 520f, 400f),
                confidence = 0.95f,
                mask = freshMask,
                footY = 400f
            ),
            residualTrackIds = setOf(42)
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(selected),
            selectedPersonIds = setOf(42),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 0,
            freshClassEvidence = listOf(freshEvidence),
            suppressedSelectedTrackIds = setOf(42)
        )

        val privacy = assertNotNull(resolved.privacyMask)
        assertEquals(0, privacy.buffer.get(20 * 64 + 10).toInt() and 0xFF, "stale selected pixels must be removed")
        assertTrue((privacy.buffer.get(20 * 64 + 45).toInt() and 0xFF) > 0, "fresh selected evidence must drive privacy")
    }

    @Test
    fun freshUnselectedClassEvidenceCanExposeClearPixelsOverStaleSelectedMask() {
        val selectedMask = rectMask(left = 10, top = 10, right = 54, bottom = 54, value = 180)
        val unselectedMask = rectMask(left = 28, top = 18, right = 42, bottom = 48, value = 255)
        val selected = TrackedPerson(
            id = 42,
            bbox = FloatRect(100f, 100f, 540f, 540f),
            mask = selectedMask,
            confidence = 0.95f,
            age = 20,
            state = TrackState.REACQUIRING,
            observedThisFrame = false,
            footY = 540f
        )
        val unselectedEvidence = FreshPrivacyClassEvidence(
            selectionClass = PrivacySelectionClass.UNSELECTED,
            detectionIndex = 4,
            detection = PersonDetection(
                bbox = FloatRect(280f, 180f, 420f, 480f),
                confidence = 0.98f,
                mask = unselectedMask,
                footY = 480f
            ),
            residualTrackIds = setOf(7)
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(selected),
            selectedPersonIds = setOf(42),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 0,
            freshClassEvidence = listOf(unselectedEvidence)
        )

        val privacy = assertNotNull(resolved.privacyMask)
        assertEquals(0, privacy.buffer.get(30 * 64 + 34).toInt() and 0xFF, "clear fresh unselected pixels must not stay anonymized")
        assertTrue((privacy.buffer.get(30 * 64 + 20).toInt() and 0xFF) > 0, "selected privacy outside unselected evidence must remain")
    }

    @Test
    fun temporalSelectedEvidenceAutomaticallyReplacesMatchingStaleGhostOneToOne() {
        val staleMask = rectMask(left = 10, top = 10, right = 26, bottom = 50)
        val freshMask = rectMask(left = 13, top = 10, right = 29, bottom = 50)
        val selected = TrackedPerson(
            id = 42,
            bbox = FloatRect(100f, 100f, 260f, 500f),
            mask = staleMask,
            confidence = 0.95f,
            age = 20,
            state = TrackState.REACQUIRING,
            observedThisFrame = false,
            footY = 500f
        )
        val temporalEvidence = FreshPrivacyClassEvidence(
            selectionClass = PrivacySelectionClass.SELECTED,
            detectionIndex = 2,
            detection = PersonDetection(
                bbox = FloatRect(130f, 100f, 290f, 500f),
                confidence = 0.95f,
                mask = freshMask,
                footY = 500f
            ),
            // Empty residual set denotes temporal class inference, not a
            // deterministic group residual tied to concrete track IDs.
            residualTrackIds = emptySet()
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(selected),
            selectedPersonIds = setOf(42),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 0,
            freshClassEvidence = listOf(temporalEvidence)
        )

        val privacy = assertNotNull(resolved.privacyMask)
        assertEquals(0, privacy.buffer.get(20 * 64 + 11).toInt() and 0xFF, "matched stale ghost must be replaced")
        assertTrue((privacy.buffer.get(20 * 64 + 27).toInt() and 0xFF) > 0, "fresh selected mask must remain")
    }

    @Test
    fun distantTemporalSelectedEvidenceDoesNotDisableUnmatchedStaleFallback() {
        val staleMask = rectMask(left = 5, top = 10, right = 16, bottom = 40)
        val farFreshMask = rectMask(left = 45, top = 10, right = 56, bottom = 40)
        val selected = TrackedPerson(
            id = 42,
            bbox = FloatRect(50f, 100f, 160f, 400f),
            mask = staleMask,
            confidence = 0.95f,
            age = 20,
            state = TrackState.REACQUIRING,
            observedThisFrame = false,
            footY = 400f
        )
        val farEvidence = FreshPrivacyClassEvidence(
            selectionClass = PrivacySelectionClass.SELECTED,
            detectionIndex = 5,
            detection = PersonDetection(
                bbox = FloatRect(450f, 100f, 560f, 400f),
                confidence = 0.95f,
                mask = farFreshMask,
                footY = 400f
            ),
            residualTrackIds = emptySet()
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(selected),
            selectedPersonIds = setOf(42),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 0,
            freshClassEvidence = listOf(farEvidence)
        )

        val privacy = assertNotNull(resolved.privacyMask)
        assertTrue((privacy.buffer.get(20 * 64 + 10).toInt() and 0xFF) > 0, "unmatched stale selected fallback must remain")
        assertTrue((privacy.buffer.get(20 * 64 + 50).toInt() and 0xFF) > 0, "distant fresh selected evidence must also remain")
    }
}

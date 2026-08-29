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

    @Test
    fun freshPrimaryIgnoresWrongTrackedSelectedMaskOnFreshUnselectedPerson() {
        val wrongTrackedMask = rectMask(left = 6, top = 10, right = 18, bottom = 46)
        val freshUnselectedMask = rectMask(left = 6, top = 10, right = 18, bottom = 46)
        val freshSelectedMask = rectMask(left = 42, top = 10, right = 54, bottom = 46)
        val wrongTrackedSelected = TrackedPerson(
            id = 42,
            bbox = FloatRect(60f, 100f, 180f, 460f),
            mask = wrongTrackedMask,
            confidence = 0.98f,
            age = 30,
            state = TrackState.ACTIVE,
            observedThisFrame = true,
            footY = 460f
        )
        val evidence = listOf(
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.UNSELECTED,
                detectionIndex = 0,
                detection = PersonDetection(
                    bbox = FloatRect(60f, 100f, 180f, 460f),
                    confidence = 0.98f,
                    mask = freshUnselectedMask,
                    footY = 460f
                ),
                residualTrackIds = emptySet()
            ),
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = 1,
                detection = PersonDetection(
                    bbox = FloatRect(420f, 100f, 540f, 460f),
                    confidence = 0.98f,
                    mask = freshSelectedMask,
                    footY = 460f
                ),
                residualTrackIds = emptySet()
            )
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(wrongTrackedSelected),
            selectedPersonIds = setOf(42),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 0,
            freshClassEvidence = evidence,
            preferFreshClassPrimary = true,
            expectedSelectedCount = 1
        )

        val privacy = assertNotNull(resolved.privacyMask)
        assertEquals(0, privacy.buffer.get(20 * 64 + 10).toInt() and 0xFF, "fresh unselected person must not inherit a wrong tracked selected mask")
        assertTrue((privacy.buffer.get(20 * 64 + 48).toInt() and 0xFF) > 0, "fresh selected raw mask must be the primary privacy source")
    }

    @Test
    fun freshPrimaryUsesOnlyMissingSelectedCountAsTrackedFallback() {
        val missingMask = rectMask(left = 5, top = 10, right = 17, bottom = 46)
        val duplicateMask = rectMask(left = 42, top = 10, right = 54, bottom = 46)
        val freshMask = rectMask(left = 43, top = 10, right = 55, bottom = 46)
        val missingTrack = TrackedPerson(
            id = 1,
            bbox = FloatRect(50f, 100f, 170f, 460f),
            mask = missingMask,
            confidence = 0.90f,
            state = TrackState.REACQUIRING,
            observedThisFrame = false
        )
        val duplicateTrack = TrackedPerson(
            id = 2,
            bbox = FloatRect(420f, 100f, 540f, 460f),
            mask = duplicateMask,
            confidence = 0.95f,
            state = TrackState.REACQUIRING,
            observedThisFrame = false
        )
        val freshSelected = FreshPrivacyClassEvidence(
            selectionClass = PrivacySelectionClass.SELECTED,
            detectionIndex = 0,
            detection = PersonDetection(
                bbox = FloatRect(430f, 100f, 550f, 460f),
                confidence = 0.98f,
                mask = freshMask
            ),
            residualTrackIds = emptySet()
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(missingTrack, duplicateTrack),
            selectedPersonIds = setOf(1, 2),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 0,
            freshClassEvidence = listOf(freshSelected),
            preferFreshClassPrimary = true,
            expectedSelectedCount = 2
        )

        val privacy = assertNotNull(resolved.privacyMask)
        assertTrue((privacy.buffer.get(20 * 64 + 10).toInt() and 0xFF) > 0, "one genuinely missing selected slot must keep one bounded fallback")
        assertTrue((privacy.buffer.get(20 * 64 + 48).toInt() and 0xFF) > 0, "fresh selected mask must cover the detected slot")
    }

    @Test
    fun freshPrimaryNeverUsesTrackedFallbackOnCurrentFreshUnselectedPerson() {
        val wrongFallbackMask = rectMask(left = 8, top = 10, right = 20, bottom = 46)
        val duplicateSelectedMask = rectMask(left = 43, top = 10, right = 55, bottom = 46)
        val freshUnselectedMask = rectMask(left = 8, top = 10, right = 20, bottom = 46)
        val freshSelectedMask = rectMask(left = 43, top = 10, right = 55, bottom = 46)

        val wrongFallback = TrackedPerson(
            id = 1,
            bbox = FloatRect(80f, 100f, 200f, 460f),
            mask = wrongFallbackMask,
            confidence = 0.95f,
            state = TrackState.REACQUIRING,
            observedThisFrame = false
        )
        val duplicateSelected = TrackedPerson(
            id = 2,
            bbox = FloatRect(430f, 100f, 550f, 460f),
            mask = duplicateSelectedMask,
            confidence = 0.95f,
            state = TrackState.REACQUIRING,
            observedThisFrame = false
        )
        val evidence = listOf(
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.UNSELECTED,
                detectionIndex = 0,
                detection = PersonDetection(
                    bbox = FloatRect(80f, 100f, 200f, 460f),
                    confidence = 0.98f,
                    mask = freshUnselectedMask,
                    footY = 460f
                ),
                residualTrackIds = emptySet()
            ),
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = 1,
                detection = PersonDetection(
                    bbox = FloatRect(430f, 100f, 550f, 460f),
                    confidence = 0.98f,
                    mask = freshSelectedMask,
                    footY = 460f
                ),
                residualTrackIds = emptySet()
            )
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(wrongFallback, duplicateSelected),
            selectedPersonIds = setOf(1, 2),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 0,
            freshClassEvidence = evidence,
            preferFreshClassPrimary = true,
            expectedSelectedCount = 2
        )

        val privacy = assertNotNull(resolved.privacyMask)
        assertEquals(
            0,
            privacy.buffer.get(20 * 64 + 12).toInt() and 0xFF,
            "A stale selected fallback must never create an extra mask on a current fresh unselected person"
        )
        assertTrue((privacy.buffer.get(20 * 64 + 48).toInt() and 0xFF) > 0)
    }

    @Test
    fun freshPrimaryCannotCreatePrivacyWhenUserSelectionIsEmpty() {
        val evidence = FreshPrivacyClassEvidence(
            selectionClass = PrivacySelectionClass.SELECTED,
            detectionIndex = 0,
            detection = PersonDetection(
                bbox = FloatRect(100f, 100f, 220f, 460f),
                confidence = 0.98f,
                mask = rectMask(left = 10, top = 10, right = 22, bottom = 46)
            ),
            residualTrackIds = emptySet(),
            conservativeUnknown = true
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = emptyList(),
            selectedPersonIds = emptySet(),
            freshClassEvidence = listOf(evidence),
            preferFreshClassPrimary = true,
            expectedSelectedCount = 0
        )

        assertTrue(!resolved.hasPrivacy)
        assertEquals(null, resolved.privacyMask)
    }

    @Test
    fun freshPrimaryStrongForegroundUnselectedCarvesEvenWhenInstanceProbabilitiesTie() {
        val selectedMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 245)
        val foregroundMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 245)
        val evidence = listOf(
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = 0,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 410f),
                    confidence = 0.95f,
                    mask = selectedMask,
                    footY = 410f
                ),
                residualTrackIds = emptySet()
            ),
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.UNSELECTED,
                detectionIndex = 1,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 510f),
                    confidence = 0.95f,
                    mask = foregroundMask,
                    footY = 510f
                ),
                residualTrackIds = emptySet()
            )
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = emptyList(),
            selectedPersonIds = setOf(42),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1,
            freshClassEvidence = evidence,
            preferFreshClassPrimary = true,
            expectedSelectedCount = 1
        )

        val privacy = assertNotNull(resolved.privacyMask)
        var nonZeroCount = 0
        for (i in 0 until privacy.buffer.capacity()) {
            if ((privacy.buffer.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }
        assertEquals(
            160,
            nonZeroCount,
            "A clearly foreground fresh unselected instance must occlude the selected privacy core even when YOLO instance probabilities tie"
        )
    }

    @Test
    fun freshPrimaryForegroundUsesRendererVisibleSoftMaskSupport() {
        // 64/255 is below the resolver's historical 0.50 binary threshold but
        // above the GL shader's 0.15 visible-effect onset. The old depth-core
        // path therefore considered these masks non-overlapping even though the
        // user could visibly see privacy on the foreground person.
        val selectedMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 64)
        val foregroundMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 64)
        val evidence = listOf(
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = 0,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 410f),
                    confidence = 0.95f,
                    mask = selectedMask,
                    footY = 410f
                ),
                residualTrackIds = emptySet()
            ),
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.UNSELECTED,
                detectionIndex = 1,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 510f),
                    confidence = 0.95f,
                    mask = foregroundMask,
                    footY = 510f
                ),
                residualTrackIds = emptySet()
            )
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = emptyList(),
            selectedPersonIds = setOf(42),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1,
            freshClassEvidence = evidence,
            preferFreshClassPrimary = true,
            expectedSelectedCount = 1
        )

        val privacy = assertNotNull(resolved.privacyMask)
        var nonZeroCount = 0
        for (i in 0 until privacy.buffer.capacity()) {
            if ((privacy.buffer.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }
        assertEquals(
            160,
            nonZeroCount,
            "Clearly foreground unselected support must be removed across the same soft-mask range the shader visibly renders"
        )
        assertTrue(resolved.hasOccluder, "An already-approved foreground hole should be reinforced at render sampling time")
        val renderOccluder = assertNotNull(resolved.occluderMask)
        assertTrue((renderOccluder.buffer.get(20 * 64 + 20).toInt() and 0xFF) > 0)
    }

    @Test
    fun freshPrimaryBackgroundUnselectedNeverCarvesTiedSelectedMask() {
        val selectedMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 245)
        val backgroundMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 245)
        val evidence = listOf(
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = 0,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 510f),
                    confidence = 0.95f,
                    mask = selectedMask,
                    footY = 510f
                ),
                residualTrackIds = emptySet()
            ),
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.UNSELECTED,
                detectionIndex = 1,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 410f),
                    confidence = 0.95f,
                    mask = backgroundMask,
                    footY = 410f
                ),
                residualTrackIds = emptySet()
            )
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = emptyList(),
            selectedPersonIds = setOf(42),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1,
            freshClassEvidence = evidence,
            preferFreshClassPrimary = true,
            expectedSelectedCount = 1
        )

        val privacy = assertNotNull(resolved.privacyMask)
        var nonZeroCount = 0
        for (i in 0 until privacy.buffer.capacity()) {
            if ((privacy.buffer.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }
        assertEquals(1681, nonZeroCount, "Background unselected evidence must remain unable to carve selected privacy")
        assertTrue(!resolved.hasOccluder)
        assertEquals(null, resolved.occluderMask)
    }

    @Test
    fun freshPrimaryAmbiguousDepthKeepsPrivacyWhenInstanceProbabilitiesTie() {
        val selectedMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 245)
        val ambiguousMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 245)
        val evidence = listOf(
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = 0,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 480f),
                    confidence = 0.95f,
                    mask = selectedMask,
                    footY = 480f
                ),
                residualTrackIds = emptySet()
            ),
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.UNSELECTED,
                detectionIndex = 1,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 500f),
                    confidence = 0.95f,
                    mask = ambiguousMask,
                    footY = 500f
                ),
                residualTrackIds = emptySet()
            )
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = emptyList(),
            selectedPersonIds = setOf(42),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1,
            freshClassEvidence = evidence,
            preferFreshClassPrimary = true,
            expectedSelectedCount = 1
        )

        val privacy = assertNotNull(resolved.privacyMask)
        var nonZeroCount = 0
        for (i in 0 until privacy.buffer.capacity()) {
            if ((privacy.buffer.get(i).toInt() and 0xFF) > 0) nonZeroCount++
        }
        assertEquals(1681, nonZeroCount, "Ambiguous fresh depth must remain privacy-wins")
        assertTrue(!resolved.hasOccluder)
        assertEquals(null, resolved.occluderMask)
    }

    @Test
    fun renderOccluderNeverOverridesAnotherSelectedTarget() {
        val selectedBehindMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 245)
        val selectedForegroundMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 245)
        val unselectedMiddleMask = rectMask(left = 10, top = 10, right = 51, bottom = 51, value = 245)
        val evidence = listOf(
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = 0,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 300f),
                    confidence = 0.95f,
                    mask = selectedBehindMask,
                    footY = 300f
                ),
                residualTrackIds = emptySet()
            ),
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = 1,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 500f),
                    confidence = 0.95f,
                    mask = selectedForegroundMask,
                    footY = 500f
                ),
                residualTrackIds = emptySet()
            ),
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.UNSELECTED,
                detectionIndex = 2,
                detection = PersonDetection(
                    bbox = FloatRect(100f, 100f, 510f, 400f),
                    confidence = 0.95f,
                    mask = unselectedMiddleMask,
                    footY = 400f
                ),
                residualTrackIds = emptySet()
            )
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = emptyList(),
            selectedPersonIds = setOf(41, 42),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1,
            freshClassEvidence = evidence,
            preferFreshClassPrimary = true,
            expectedSelectedCount = 2
        )

        val privacy = assertNotNull(resolved.privacyMask)
        assertTrue((privacy.buffer.get(20 * 64 + 20).toInt() and 0xFF) > 0)
        assertTrue(!resolved.hasOccluder, "The middle unselected person is behind another selected target, so global render carving is forbidden")
        assertEquals(null, resolved.occluderMask)
    }

    @Test
    fun freshPrimaryForegroundUnselectedCarvesTrackedSelectedFallback() {
        val fallbackMask = rectMask(left = 10, top = 10, right = 31, bottom = 51, value = 64)
        val freshSelectedMask = rectMask(left = 43, top = 10, right = 55, bottom = 51, value = 245)
        val foregroundUnselectedMask = rectMask(left = 26, top = 10, right = 41, bottom = 51, value = 64)

        val fallbackSelected = TrackedPerson(
            id = 1,
            bbox = FloatRect(100f, 100f, 300f, 400f),
            mask = fallbackMask,
            confidence = 0.95f,
            age = 30,
            state = TrackState.REACQUIRING,
            observedThisFrame = false,
            missedFrames = 5,
            footY = 400f
        )
        val evidence = listOf(
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = 0,
                detection = PersonDetection(
                    bbox = FloatRect(430f, 100f, 550f, 460f),
                    confidence = 0.95f,
                    mask = freshSelectedMask,
                    footY = 460f
                ),
                residualTrackIds = emptySet()
            ),
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.UNSELECTED,
                detectionIndex = 1,
                detection = PersonDetection(
                    bbox = FloatRect(260f, 100f, 380f, 460f),
                    confidence = 0.95f,
                    mask = foregroundUnselectedMask,
                    footY = 460f
                ),
                residualTrackIds = emptySet()
            )
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(fallbackSelected),
            selectedPersonIds = setOf(1, 2),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1,
            freshClassEvidence = evidence,
            preferFreshClassPrimary = true,
            expectedSelectedCount = 2
        )

        val privacy = assertNotNull(resolved.privacyMask)
        assertEquals(
            0,
            privacy.buffer.get(20 * 64 + 28).toInt() and 0xFF,
            "A clear fresh foreground unselected core must carve a stale selected fallback instead of being covered by it"
        )
        assertTrue((privacy.buffer.get(20 * 64 + 15).toInt() and 0xFF) > 0, "fallback privacy outside the foreground core must remain")
        assertTrue((privacy.buffer.get(20 * 64 + 48).toInt() and 0xFF) > 0, "the independently fresh selected target must remain anonymized")
        assertTrue(resolved.hasOccluder)
    }

    @Test
    fun trackedSelectedFallbackKeepsPrivacyWhenFreshDepthIsAmbiguous() {
        val fallbackMask = rectMask(left = 10, top = 10, right = 31, bottom = 51, value = 64)
        val freshSelectedMask = rectMask(left = 43, top = 10, right = 55, bottom = 51, value = 245)
        val ambiguousUnselectedMask = rectMask(left = 26, top = 10, right = 41, bottom = 51, value = 64)

        val fallbackSelected = TrackedPerson(
            id = 1,
            bbox = FloatRect(100f, 100f, 300f, 400f),
            mask = fallbackMask,
            confidence = 0.95f,
            age = 30,
            state = TrackState.REACQUIRING,
            observedThisFrame = false,
            missedFrames = 5,
            footY = 400f
        )
        val evidence = listOf(
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.SELECTED,
                detectionIndex = 0,
                detection = PersonDetection(
                    bbox = FloatRect(430f, 100f, 550f, 460f),
                    confidence = 0.95f,
                    mask = freshSelectedMask,
                    footY = 460f
                ),
                residualTrackIds = emptySet()
            ),
            FreshPrivacyClassEvidence(
                selectionClass = PrivacySelectionClass.UNSELECTED,
                detectionIndex = 1,
                detection = PersonDetection(
                    bbox = FloatRect(264f, 100f, 384f, 420f),
                    confidence = 0.95f,
                    mask = ambiguousUnselectedMask,
                    footY = 420f
                ),
                residualTrackIds = emptySet()
            )
        )

        val resolved = PrivacyOcclusionResolver.resolveMasks(
            persons = listOf(fallbackSelected),
            selectedPersonIds = setOf(1, 2),
            applyDilationToPrivacyTargets = false,
            occluderErosionRadius = 1,
            freshClassEvidence = evidence,
            preferFreshClassPrimary = true,
            expectedSelectedCount = 2
        )

        val privacy = assertNotNull(resolved.privacyMask)
        assertTrue(
            (privacy.buffer.get(20 * 64 + 28).toInt() and 0xFF) > 0,
            "A stale selected fallback must remain privacy-wins when fresh foreground depth is not clearly separated"
        )
    }
}

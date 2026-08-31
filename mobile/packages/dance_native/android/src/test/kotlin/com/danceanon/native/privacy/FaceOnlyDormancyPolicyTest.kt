package com.danceanon.native.privacy

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceOnlyDormancyPolicyTest {
    @Test
    fun `fresh observation always uses direct face mode`() {
        assertEquals(
            FaceOnlyRenderMode.DIRECT,
            FaceOnlyDormancyPolicy.resolveMode(
                observedThisFrame = true,
                lastObservedPtsUs = null,
                ptsUs = 1_000_000L,
                hasTrustedFace = false,
                hasBodyMask = false,
                hasFreshBodyMotionEvidence = false
            )
        )
    }

    @Test
    fun `short YOLO miss keeps direct privacy bridge`() {
        assertEquals(
            FaceOnlyRenderMode.DIRECT,
            FaceOnlyDormancyPolicy.resolveMode(
                observedThisFrame = false,
                lastObservedPtsUs = 1_000_000L,
                ptsUs = 1_100_000L,
                hasTrustedFace = true,
                hasBodyMask = false,
                hasFreshBodyMotionEvidence = false
            )
        )
    }

    @Test
    fun `medium miss uses full body mask compensation when face history exists`() {
        assertEquals(
            FaceOnlyRenderMode.BODY_MASK_COMPENSATED,
            FaceOnlyDormancyPolicy.resolveMode(
                observedThisFrame = false,
                lastObservedPtsUs = 1_000_000L,
                ptsUs = 1_500_000L,
                hasTrustedFace = true,
                hasBodyMask = true,
                hasFreshBodyMotionEvidence = false
            )
        )
    }

    @Test
    fun `medium miss without body mask becomes dormant instead of guessing`() {
        assertEquals(
            FaceOnlyRenderMode.DORMANT,
            FaceOnlyDormancyPolicy.resolveMode(
                observedThisFrame = false,
                lastObservedPtsUs = 1_000_000L,
                ptsUs = 1_500_000L,
                hasTrustedFace = true,
                hasBodyMask = false,
                hasFreshBodyMotionEvidence = false
            )
        )
    }

    @Test
    fun `medium miss without trusted face becomes dormant`() {
        assertEquals(
            FaceOnlyRenderMode.DORMANT,
            FaceOnlyDormancyPolicy.resolveMode(
                observedThisFrame = false,
                lastObservedPtsUs = 1_000_000L,
                ptsUs = 1_500_000L,
                hasTrustedFace = false,
                hasBodyMask = true,
                hasFreshBodyMotionEvidence = false
            )
        )
    }

    @Test
    fun `long unobserved identity stops rendering despite retained body mask`() {
        assertEquals(
            FaceOnlyRenderMode.DORMANT,
            FaceOnlyDormancyPolicy.resolveMode(
                observedThisFrame = false,
                lastObservedPtsUs = 1_000_000L,
                ptsUs = 1_800_001L,
                hasTrustedFace = true,
                hasBodyMask = true,
                hasFreshBodyMotionEvidence = false
            )
        )
    }

    @Test
    fun `missing observation history does not invent face geometry`() {
        assertEquals(
            FaceOnlyRenderMode.DORMANT,
            FaceOnlyDormancyPolicy.resolveMode(
                observedThisFrame = false,
                lastObservedPtsUs = null,
                ptsUs = 1_000_000L,
                hasTrustedFace = true,
                hasBodyMask = true,
                hasFreshBodyMotionEvidence = true
            )
        )
    }

    @Test
    fun `fresh ambiguous body motion can bridge beyond stale timeout without identity commit`() {
        assertEquals(
            FaceOnlyRenderMode.BODY_MASK_COMPENSATED,
            FaceOnlyDormancyPolicy.resolveMode(
                observedThisFrame = false,
                lastObservedPtsUs = 1_000_000L,
                ptsUs = 4_000_000L,
                hasTrustedFace = true,
                hasBodyMask = false,
                hasFreshBodyMotionEvidence = true
            )
        )
    }

    @Test
    fun `fresh body motion never invents a face without trusted face history`() {
        assertEquals(
            FaceOnlyRenderMode.DORMANT,
            FaceOnlyDormancyPolicy.resolveMode(
                observedThisFrame = false,
                lastObservedPtsUs = 1_000_000L,
                ptsUs = 4_000_000L,
                hasTrustedFace = false,
                hasBodyMask = false,
                hasFreshBodyMotionEvidence = true
            )
        )
    }
}

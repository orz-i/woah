package com.danceanon.native.privacy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaceOnlyDormancyPolicyTest {
    @Test
    fun `fresh observation always renders`() {
        assertTrue(
            FaceOnlyDormancyPolicy.shouldRender(
                observedThisFrame = true,
                lastObservedPtsUs = null,
                ptsUs = 1_000_000L
            )
        )
    }

    @Test
    fun `short YOLO miss keeps face privacy bridge`() {
        assertTrue(
            FaceOnlyDormancyPolicy.shouldRender(
                observedThisFrame = false,
                lastObservedPtsUs = 1_000_000L,
                ptsUs = 1_100_000L
            )
        )
    }

    @Test
    fun `long unobserved identity stops rendering stale face`() {
        assertFalse(
            FaceOnlyDormancyPolicy.shouldRender(
                observedThisFrame = false,
                lastObservedPtsUs = 1_000_000L,
                ptsUs = 1_150_001L
            )
        )
    }

    @Test
    fun `missing observation history does not invent face geometry`() {
        assertFalse(
            FaceOnlyDormancyPolicy.shouldRender(
                observedThisFrame = false,
                lastObservedPtsUs = null,
                ptsUs = 1_000_000L
            )
        )
    }
}

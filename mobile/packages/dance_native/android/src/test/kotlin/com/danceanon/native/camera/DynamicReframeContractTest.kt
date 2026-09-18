package com.danceanon.native.camera

import com.danceanon.native.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertTrue

class DynamicReframeContractTest {

    private fun subject(centerX: Float): FloatRect =
        FloatRect(centerX - 0.05f, 0.15f, centerX + 0.05f, 0.90f)

    @Test
    fun movingSubjectChangesFinalCropAndTextureMatrix() {
        val follower = SmoothFollower()
        val first = follower.cropForFrame(
            target = subject(0.20f),
            presentationTimeUs = 0L,
            sourceAspectRatio = 16f / 9f,
            outputAspectRatio = 9f / 16f,
            zoom = 1f,
            smoothFactor = 0.1f
        )
        val firstGl = ReframeGeometry.visualTopLeftToScreenGl(first)
        val firstMatrix = ReframeGeometry.textureMatrixForScreenGlCrop(firstGl)

        var last = first
        for (frame in 1..30) {
            last = follower.cropForFrame(
                target = subject(0.80f),
                presentationTimeUs = frame * 33_333L,
                sourceAspectRatio = 16f / 9f,
                outputAspectRatio = 9f / 16f,
                zoom = 1f,
                smoothFactor = 0.1f
            )
        }
        val lastGl = ReframeGeometry.visualTopLeftToScreenGl(last)
        val lastMatrix = ReframeGeometry.textureMatrixForScreenGlCrop(lastGl)

        assertTrue(
            last.centerX > first.centerX + 0.10f,
            "crop center must visibly follow a protagonist moving left-to-right"
        )
        assertTrue(
            lastMatrix[12] > firstMatrix[12] + 0.10f,
            "final post-crop texture translation must move with the dynamic crop"
        )
    }
}

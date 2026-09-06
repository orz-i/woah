package com.danceanon.native.tracking

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackManagerSampledMaskIoUTest {

    private fun rectMask(
        size: Int = 160,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): NativeMask {
        val buf = ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inside = x in left until right && y in top until bottom
                buf.put(if (inside) 255.toByte() else 0.toByte())
            }
        }
        buf.rewind()
        return NativeMask(size, size, buf, 1920, 1080)
    }

    private fun patternedMask(size: Int = 160, seed: Int): NativeMask {
        val values = intArrayOf(0, 127, 128, 129, 255)
        val buf = ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val value = values[(x * 17 + y * 31 + seed) % values.size]
                buf.put(value.toByte())
            }
        }
        buf.rewind()
        return NativeMask(size, size, buf, 1920, 1080)
    }

    @Test
    fun sampledAssociationIoUPreservesCandidateOrdering() {
        val base = rectMask(left = 32, top = 24, right = 112, bottom = 144)
        val identical = rectMask(left = 32, top = 24, right = 112, bottom = 144)
        val shifted = rectMask(left = 52, top = 24, right = 132, bottom = 144)
        val disjoint = rectMask(left = 120, top = 24, right = 156, bottom = 144)

        val exactIdentical = TrackManager.computeMaskIoU(base, identical)
        val exactShifted = TrackManager.computeMaskIoU(base, shifted)
        val exactDisjoint = TrackManager.computeMaskIoU(base, disjoint)

        val sampledIdentical = TrackManager.computeMaskIoU(base, identical, sampleStride = 4)
        val sampledShifted = TrackManager.computeMaskIoU(base, shifted, sampleStride = 4)
        val sampledDisjoint = TrackManager.computeMaskIoU(base, disjoint, sampleStride = 4)

        assertEquals(1.0f, sampledIdentical)
        assertTrue(exactIdentical > exactShifted && exactShifted > exactDisjoint)
        assertTrue(sampledIdentical > sampledShifted && sampledShifted > sampledDisjoint)
        assertTrue(kotlin.math.abs(sampledShifted - exactShifted) < 0.08f)
    }

    @Test
    fun directWarpedSampleIoUMatchesMaterializedWarpOrdering() {
        val base = rectMask(left = 32, top = 24, right = 112, bottom = 144)
        val prevBox = com.danceanon.native.inference.FloatRect(600f, 250f, 1000f, 950f)
        val predBox = com.danceanon.native.inference.FloatRect(680f, 270f, 1080f, 970f)
        val materialized = TrackManager.warpMask(base, prevBox, predBox, missedFrames = 0)
        val sameMotionCandidate = materialized
        val wrongCandidate = rectMask(left = 20, top = 24, right = 88, bottom = 144)

        val expectedGood = TrackManager.computeMaskIoU(materialized, sameMotionCandidate, sampleStride = 4)
        val expectedBad = TrackManager.computeMaskIoU(materialized, wrongCandidate, sampleStride = 4)
        val directGood = TrackManager.computeWarpedMaskIoU(base, prevBox, predBox, sameMotionCandidate, sampleStride = 4)
        val directBad = TrackManager.computeWarpedMaskIoU(base, prevBox, predBox, wrongCandidate, sampleStride = 4)

        assertTrue(kotlin.math.abs(directGood - expectedGood) < 0.02f)
        assertTrue(kotlin.math.abs(directBad - expectedBad) < 0.05f)
        assertTrue(directGood > directBad)
    }

    @Test
    fun preparedWarpedSamplesMatchDirectIoUExactlyAcrossCandidates() {
        val source = patternedMask(seed = 2)
        val previousBoxes = listOf(
            FloatRect(600f, 250f, 1000f, 950f),
            FloatRect(120f, 80f, 420f, 720f),
            FloatRect(900f, 300f, 1320f, 1010f)
        )
        val predictedBoxes = listOf(
            FloatRect(680f, 270f, 1080f, 970f),
            FloatRect(90f, 110f, 430f, 760f),
            FloatRect(820f, 260f, 1280f, 1040f)
        )
        val candidates = listOf(
            patternedMask(seed = 0),
            patternedMask(seed = 1),
            patternedMask(seed = 3),
            rectMask(left = 20, top = 30, right = 100, bottom = 140),
            rectMask(left = 70, top = 10, right = 155, bottom = 120)
        )

        for (index in previousBoxes.indices) {
            val prepared = TrackManager.prepareWarpedMaskSamples(
                sourceMask = source,
                prevBbox = previousBoxes[index],
                predBbox = predictedBoxes[index],
                sampleStride = 4
            )
            for (candidate in candidates) {
                val direct = TrackManager.computeWarpedMaskIoU(
                    sourceMask = source,
                    prevBbox = previousBoxes[index],
                    predBbox = predictedBoxes[index],
                    candidateMask = candidate,
                    sampleStride = 4
                )
                val cached = TrackManager.computePreparedWarpedMaskIoU(prepared, candidate)
                assertEquals(direct, cached)
            }
        }
    }

    @Test
    fun protectedSelectedGroupCommitRequiresAbsoluteIdentityEvidence() {
        assertTrue(
            !TrackManager.isProtectedGroupIdentityEvidenceSufficient(
                TrackState.REACQUIRING,
                bboxIoU = 0.30f,
                maskIoU = 0.06f
            )
        )
        assertTrue(
            TrackManager.isProtectedGroupIdentityEvidenceSufficient(
                TrackState.REACQUIRING,
                bboxIoU = 0.55f,
                maskIoU = 0.05f
            )
        )
        assertTrue(
            TrackManager.isProtectedGroupIdentityEvidenceSufficient(
                TrackState.ACTIVE,
                bboxIoU = 0.10f,
                maskIoU = 0.35f
            )
        )
        assertTrue(
            !TrackManager.isProtectedGroupIdentityEvidenceSufficient(
                TrackState.LOST,
                bboxIoU = 0.458f,
                maskIoU = 0.20f
            ),
            "LOST protected identity must use strict recovery evidence"
        )
        assertTrue(
            TrackManager.isProtectedGroupIdentityEvidenceSufficient(
                TrackState.LOST,
                bboxIoU = 0.51f,
                maskIoU = 0.20f
            )
        )
    }

    @Test
    fun protectedUnobservedPredictionIsBoundedAroundReliableAnchor() {
        val anchor = FloatRect(100f, 100f, 200f, 400f)
        val runawayPrediction = FloatRect(500f, 0f, 800f, 700f)

        val bounded = TrackManager.boundPredictionAroundAnchor(anchor, runawayPrediction)
        val maxTravel = maxOf(anchor.width, anchor.height) * 0.30f
        val dx = bounded.centerX - anchor.centerX
        val dy = bounded.centerY - anchor.centerY
        val travel = kotlin.math.sqrt(dx * dx + dy * dy)

        assertTrue(travel <= maxTravel + 0.01f)
        assertTrue(bounded.width <= anchor.width * 1.18f + 0.01f)
        assertTrue(bounded.height <= anchor.height * 1.18f + 0.01f)
        assertTrue(bounded.width >= anchor.width * 0.82f - 0.01f)
        assertTrue(bounded.height >= anchor.height * 0.82f - 0.01f)
    }
}

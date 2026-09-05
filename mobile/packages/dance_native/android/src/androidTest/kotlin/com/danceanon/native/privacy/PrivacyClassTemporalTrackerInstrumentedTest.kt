package com.danceanon.native.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.PrivacySelectionClass
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PrivacyClassTemporalTrackerInstrumentedTest {
    @Test
    fun cachedSimilarityPreservesSequenceAndReducesWallTime() {
        val frames = buildFrames()

        repeat(3) {
            runSequence(frames, reuseCache = false)
            runSequence(frames, reuseCache = true)
        }

        val uncachedTimesMs = mutableListOf<Double>()
        val cachedTimesMs = mutableListOf<Double>()
        var expectedSignature: List<List<Any>>? = null
        repeat(9) { trial ->
            val uncached = timedSequence(frames, reuseCache = false)
            val cached = timedSequence(frames, reuseCache = true)
            if (trial == 0) expectedSignature = uncached.second
            assertEquals(expectedSignature, uncached.second)
            assertEquals(expectedSignature, cached.second)
            uncachedTimesMs += uncached.first
            cachedTimesMs += cached.first
        }

        val uncachedMedian = median(uncachedTimesMs)
        val cachedMedian = median(cachedTimesMs)
        Log.i(
            TAG,
            "sequence_uncached_median_ms=$uncachedMedian " +
                "sequence_cached_median_ms=$cachedMedian " +
                "improvement_pct=${(uncachedMedian - cachedMedian) / uncachedMedian * 100.0}"
        )
    }

    @Test
    fun cachedWarpedSupportPreservesSequenceAndReducesWallTimeBeyondSimilarityCache() {
        val frames = buildFrames()
        repeat(3) {
            runSequence(frames, reuseCache = true, reuseWarpedSupport = false)
            runSequence(frames, reuseCache = true, reuseWarpedSupport = true)
        }

        val referenceTimes = mutableListOf<Double>()
        val candidateTimes = mutableListOf<Double>()
        var expectedSignature: List<List<Any>>? = null
        repeat(9) { trial ->
            val reference = timedSequence(
                frames = frames,
                reuseCache = true,
                reuseWarpedSupport = false
            )
            val candidate = timedSequence(
                frames = frames,
                reuseCache = true,
                reuseWarpedSupport = true
            )
            if (trial == 0) expectedSignature = reference.second
            assertEquals(expectedSignature, reference.second)
            assertEquals(expectedSignature, candidate.second)
            referenceTimes += reference.first
            candidateTimes += candidate.first
        }
        val referenceMedian = median(referenceTimes)
        val candidateMedian = median(candidateTimes)
        val improvementPct = (referenceMedian - candidateMedian) / referenceMedian * 100.0
        Log.i(
            TAG,
            "warped_support_reference_median_ms=$referenceMedian " +
                "warped_support_candidate_median_ms=$candidateMedian " +
                "improvement_pct=$improvementPct"
        )
        assertTrue(improvementPct >= 10.0, "Expected >=10% warped-support gain, got $improvementPct%")
    }

    private fun timedSequence(
        frames: List<List<PersonDetection>>,
        reuseCache: Boolean,
        reuseWarpedSupport: Boolean = false
    ): Pair<Double, List<List<Any>>> {
        val startedNs = System.nanoTime()
        val signature = runSequence(frames, reuseCache, reuseWarpedSupport)
        return (System.nanoTime() - startedNs) / 1_000_000.0 to signature
    }

    private fun runSequence(
        frames: List<List<PersonDetection>>,
        reuseCache: Boolean,
        reuseWarpedSupport: Boolean = false
    ): List<List<Any>> {
        val tracker = PrivacyClassTemporalTracker(
            reuseFrameSimilarityCache = reuseCache,
            reuseFrameWarpedMaskSupportCache = reuseWarpedSupport
        )
        return frames.mapIndexed { frameIndex, detections ->
            val hard = if (frameIndex == 0) {
                mapOf(
                    0 to PrivacySelectionClass.SELECTED,
                    1 to PrivacySelectionClass.UNSELECTED
                )
            } else {
                emptyMap()
            }
            tracker.update(
                detections = detections,
                hardClassByDetectionIndex = hard,
                ptsUs = frameIndex * 16_667L
            ).map { evidence ->
                listOf(
                    evidence.selectionClass,
                    evidence.detectionIndex,
                    evidence.conservativeUnknown
                )
            }
        }
    }

    private fun buildFrames(): List<List<PersonDetection>> = List(18) { frame ->
        val selectedLeft = 80 + frame * 7
        val unselectedLeft = 400 - frame * 6
        listOf(
            detection(
                left = selectedLeft.toFloat(),
                right = (selectedLeft + 150).toFloat(),
                maskLeft = 18 + frame,
                maskRight = 58 + frame
            ),
            detection(
                left = unselectedLeft.toFloat(),
                right = (unselectedLeft + 150).toFloat(),
                maskLeft = 100 - frame,
                maskRight = 140 - frame
            )
        )
    }

    private fun detection(
        left: Float,
        right: Float,
        maskLeft: Int,
        maskRight: Int
    ): PersonDetection = PersonDetection(
        bbox = FloatRect(left, 80f, right, 330f),
        confidence = 0.95f,
        mask = maskRect(maskLeft, 26, maskRight, 138)
    )

    private fun maskRect(left: Int, top: Int, right: Int, bottom: Int): NativeMask {
        val buffer = ByteBuffer.allocateDirect(MASK_SIZE * MASK_SIZE)
        for (y in 0 until MASK_SIZE) {
            for (x in 0 until MASK_SIZE) {
                buffer.put(
                    if (x in left until right && y in top until bottom) 255.toByte() else 0.toByte()
                )
            }
        }
        buffer.rewind()
        return NativeMask(
            width = MASK_SIZE,
            height = MASK_SIZE,
            buffer = buffer,
            originalWidth = 640,
            originalHeight = 360
        )
    }

    private fun median(values: List<Double>): Double {
        val ordered = values.sorted()
        return ordered[ordered.size / 2]
    }

    companion object {
        private const val MASK_SIZE = 160
        private const val TAG = "PrivacyClassTemporalBench"
    }
}

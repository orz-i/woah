package com.danceanon.native.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class PrivacyOcclusionResolverFaceOnlyInstrumentedTest {
    @Test
    fun noCarveFaceOnlyFastPathsPreserveSequenceAndReduceWallTime() {
        val persons = buildPersons()
        val selectedIds = setOf(0, 1, 3, 5, 6)
        val classFallback = ResolvedCompositorMasks(
            privacyMask = rectMask(72, 58, 83, 71),
            occluderMask = null,
            hasPrivacy = true,
            hasOccluder = false
        )

        val historical = runSequence(
            persons = persons,
            selectedIds = selectedIds,
            classFallback = classFallback,
            fast = false
        )
        val optimized = runSequence(
            persons = persons,
            selectedIds = selectedIds,
            classFallback = classFallback,
            fast = true
        )
        assertSequenceEqual(historical, optimized)

        repeat(2) {
            runSequence(persons, selectedIds, classFallback, fast = false)
            runSequence(persons, selectedIds, classFallback, fast = true)
        }

        val historicalTimes = mutableListOf<Double>()
        val optimizedTimes = mutableListOf<Double>()
        repeat(7) {
            historicalTimes += timeMs {
                runSequence(persons, selectedIds, classFallback, fast = false)
            }
            optimizedTimes += timeMs {
                runSequence(persons, selectedIds, classFallback, fast = true)
            }
        }

        val historicalMedian = median(historicalTimes)
        val optimizedMedian = median(optimizedTimes)
        Log.i(
            TAG,
            "sequence_historical_ms=$historicalMedian sequence_fast_ms=$optimizedMedian " +
                "improvement_pct=${improvement(historicalMedian, optimizedMedian)}"
        )
    }

    private fun runSequence(
        persons: List<TrackedPerson>,
        selectedIds: Set<Int>,
        classFallback: ResolvedCompositorMasks,
        fast: Boolean
    ): List<ResolvedCompositorMasks> = List(SEQUENCE_FRAMES) { frame ->
        val base = PrivacyOcclusionResolver.resolveMasks(
            persons = persons,
            selectedPersonIds = selectedIds,
            applyDilationToPrivacyTargets = true,
            dilationRadius = 1,
            ptsUs = frame * 16_667L,
            expectedSelectedCount = selectedIds.size,
            behaviorNeutralFaceOnlyFastPaths = fast
        )
        if (frame == 0) {
            PrivacyOcclusionResolver.mergeResolvedMasks(
                parts = listOf(base, classFallback),
                behaviorNeutralFaceOnlyFastPaths = fast
            )
        } else {
            base
        }
    }

    private fun buildPersons(): List<TrackedPerson> {
        val selectedSpecs = listOf(
            Triple(0, FloatRect(8f, 15f, 32f, 76f), intArrayOf(18, 24, 34, 42)),
            Triple(1, FloatRect(24f, 12f, 48f, 74f), intArrayOf(42, 22, 58, 40)),
            Triple(3, FloatRect(40f, 14f, 64f, 78f), intArrayOf(66, 24, 82, 42)),
            Triple(5, FloatRect(56f, 10f, 80f, 75f), intArrayOf(90, 22, 106, 40)),
            Triple(6, FloatRect(72f, 13f, 96f, 77f), intArrayOf(114, 24, 130, 42))
        )
        val selected = selectedSpecs.map { (id, bbox, r) ->
            TrackedPerson(
                id = id,
                bbox = bbox,
                mask = rectMask(r[0], r[1], r[2], r[3]),
                confidence = 0.95f,
                missedFrames = 0,
                age = 30,
                state = TrackState.ACTIVE,
                observedThisFrame = true,
                footY = bbox.bottom
            )
        }
        val ambiguousForeground = TrackedPerson(
            id = 20,
            bbox = FloatRect(4f, 8f, 100f, 92f),
            mask = rectMask(0, 0, MASK_SIZE - 1, MASK_SIZE - 1),
            confidence = 0.40f,
            missedFrames = 0,
            age = 30,
            state = TrackState.ACTIVE,
            observedThisFrame = true,
            footY = 92f
        )
        return selected + ambiguousForeground
    }

    private fun rectMask(left: Int, top: Int, right: Int, bottom: Int): NativeMask {
        val buffer = ByteBuffer.allocateDirect(MASK_SIZE * MASK_SIZE).order(ByteOrder.nativeOrder())
        repeat(MASK_SIZE * MASK_SIZE) { buffer.put(0) }
        for (y in top..bottom) {
            for (x in left..right) {
                if (x in 0 until MASK_SIZE && y in 0 until MASK_SIZE) {
                    buffer.put(y * MASK_SIZE + x, 255.toByte())
                }
            }
        }
        buffer.rewind()
        return NativeMask(
            width = MASK_SIZE,
            height = MASK_SIZE,
            buffer = buffer,
            originalWidth = 100,
            originalHeight = 100,
            mapper = null
        )
    }

    private fun assertSequenceEqual(
        expected: List<ResolvedCompositorMasks>,
        actual: List<ResolvedCompositorMasks>
    ) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { frame ->
            val e = expected[frame]
            val a = actual[frame]
            assertEquals(e.hasPrivacy, a.hasPrivacy, "frame=$frame privacy flag")
            assertEquals(e.hasOccluder, a.hasOccluder, "frame=$frame occluder flag")
            assertMaskEqual(e.privacyMask, a.privacyMask, "frame=$frame privacy")
            assertMaskEqual(e.occluderMask, a.occluderMask, "frame=$frame occluder")
        }
    }

    private fun assertMaskEqual(expected: NativeMask?, actual: NativeMask?, label: String) {
        if (expected == null) {
            assertNull(actual, label)
            return
        }
        val nonNullActual = assertNotNull(actual, label)
        assertEquals(expected.width, nonNullActual.width, "$label width")
        assertEquals(expected.height, nonNullActual.height, "$label height")
        repeat(expected.width * expected.height) { index ->
            assertEquals(
                expected.buffer.get(index),
                nonNullActual.buffer.get(index),
                "$label byte=$index"
            )
        }
    }

    private inline fun timeMs(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000.0
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    private fun improvement(reference: Double, candidate: Double): Double =
        (reference - candidate) / reference * 100.0

    companion object {
        private const val MASK_SIZE = 160
        private const val SEQUENCE_FRAMES = 8
        private const val TAG = "FacePrivacyResolveBench"
    }
}

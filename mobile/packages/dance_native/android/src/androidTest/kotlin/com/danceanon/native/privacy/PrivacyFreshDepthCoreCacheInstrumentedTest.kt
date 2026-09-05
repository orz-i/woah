package com.danceanon.native.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.FreshPrivacyClassEvidence
import com.danceanon.native.tracking.PrivacySelectionClass
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PrivacyFreshDepthCoreCacheInstrumentedTest {
    @Test
    fun perResolveFreshDepthCoreReusePreservesMasksAndReducesWallTime() {
        val selectedIds = (101..105).toSet()
        val selectedMasks = (0 until 5).map { index ->
            rectMask(
                left = 15 + index * 4,
                top = 18,
                right = 125 + index * 3,
                bottom = 140,
                value = 180 + index * 12
            )
        }
        val unselectedMasks = listOf(
            rectMask(left = 52, top = 28, right = 110, bottom = 150, value = 255),
            rectMask(left = 72, top = 22, right = 132, bottom = 145, value = 245)
        )
        val evidence = buildList {
            selectedMasks.forEachIndexed { index, mask ->
                add(
                    FreshPrivacyClassEvidence(
                        selectionClass = PrivacySelectionClass.SELECTED,
                        detectionIndex = index,
                        detection = PersonDetection(
                            bbox = FloatRect(
                                80f + index * 20f,
                                80f,
                                430f + index * 20f,
                                420f
                            ),
                            confidence = 0.96f - index * 0.01f,
                            mask = mask,
                            footY = 420f
                        ),
                        residualTrackIds = setOf(101 + index)
                    )
                )
            }
            unselectedMasks.forEachIndexed { index, mask ->
                add(
                    FreshPrivacyClassEvidence(
                        selectionClass = PrivacySelectionClass.UNSELECTED,
                        detectionIndex = 5 + index,
                        detection = PersonDetection(
                            bbox = FloatRect(
                                175f + index * 35f,
                                100f,
                                455f + index * 35f,
                                560f
                            ),
                            confidence = 0.98f - index * 0.01f,
                            mask = mask,
                            footY = 560f
                        ),
                        residualTrackIds = setOf(7 + index)
                    )
                )
            }
        }

        fun resolve(reuse: Boolean): ResolvedCompositorMasks =
            PrivacyOcclusionResolver.resolveMasks(
                persons = emptyList(),
                selectedPersonIds = selectedIds,
                applyDilationToPrivacyTargets = false,
                occluderErosionRadius = 1,
                freshClassEvidence = evidence,
                preferFreshClassPrimary = true,
                expectedSelectedCount = selectedIds.size,
                behaviorNeutralFaceOnlyFastPaths = true,
                reuseFaceOnlyFreshDepthCores = reuse
            )

        val reference = resolve(reuse = false)
        val cached = resolve(reuse = true)
        assertResolvedEquals(reference, cached)

        repeat(3) {
            resolve(reuse = false)
            resolve(reuse = true)
        }
        val referenceTimes = mutableListOf<Double>()
        val cachedTimes = mutableListOf<Double>()
        repeat(11) {
            referenceTimes += timeMs { resolve(reuse = false) }
            cachedTimes += timeMs { resolve(reuse = true) }
        }
        val referenceMedian = median(referenceTimes)
        val cachedMedian = median(cachedTimes)
        val improvementPct = (referenceMedian - cachedMedian) / referenceMedian * 100.0
        Log.i(
            TAG,
            "fresh_depth_core_reference_ms=$referenceMedian " +
                "fresh_depth_core_cached_ms=$cachedMedian improvement_pct=$improvementPct"
        )
        assertTrue(improvementPct >= 10.0, "Expected >=10% resolver gain, got $improvementPct%")
    }

    private fun rectMask(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        value: Int
    ): NativeMask {
        val width = 160
        val height = 160
        val buffer = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        repeat(width * height) { buffer.put(0.toByte()) }
        for (y in top until bottom) {
            for (x in left until right) {
                buffer.put(y * width + x, value.toByte())
            }
        }
        buffer.rewind()
        return NativeMask(width, height, buffer, 640, 640)
    }

    private fun assertResolvedEquals(expected: ResolvedCompositorMasks, actual: ResolvedCompositorMasks) {
        assertEquals(expected.hasPrivacy, actual.hasPrivacy)
        assertEquals(expected.hasOccluder, actual.hasOccluder)
        assertMaskEquals(expected.privacyMask, actual.privacyMask, "privacy")
        assertMaskEquals(expected.occluderMask, actual.occluderMask, "occluder")
    }

    private fun assertMaskEquals(expected: NativeMask?, actual: NativeMask?, label: String) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, label)
            return
        }
        assertEquals(expected.width, actual.width, "$label width")
        assertEquals(expected.height, actual.height, "$label height")
        for (i in 0 until expected.buffer.capacity()) {
            assertEquals(expected.buffer.get(i), actual.buffer.get(i), "$label byte=$i")
        }
    }

    private inline fun timeMs(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000.0
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        const val TAG = "PrivacyFreshDepthCoreBench"
    }
}

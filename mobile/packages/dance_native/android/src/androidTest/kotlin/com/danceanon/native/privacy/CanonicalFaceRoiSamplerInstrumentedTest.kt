package com.danceanon.native.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CanonicalFaceRoiSamplerInstrumentedTest {
    @Test
    fun opaquePreparedCanonicalInputPreservesBytesAndReducesFiveRoiWallTime() {
        val mapper = ModelCoordinateMapper(
            srcWidth = 1920,
            srcHeight = 1080,
            modelInputSize = MODEL_SIZE,
            protoSize = 160
        )
        val canonical = ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(MODEL_SIZE * MODEL_SIZE) { pixel ->
            val offset = pixel * 4
            canonical.put(offset, ((pixel * 17 + 3) and 0xFF).toByte())
            canonical.put(offset + 1, ((pixel * 29 + 11) and 0xFF).toByte())
            canonical.put(offset + 2, ((pixel * 43 + 19) and 0xFF).toByte())
            canonical.put(offset + 3, 255.toByte())
        }
        val rects = benchmarkRects()
        val historicalOutputs = Array(rects.size) {
            ByteBuffer.allocateDirect(ROI_SIZE * ROI_SIZE * 4)
        }
        val opaqueOutputs = Array(rects.size) {
            ByteBuffer.allocateDirect(ROI_SIZE * ROI_SIZE * 4)
        }
        val historicalWorkspace = CanonicalFaceRoiSampler.Workspace(ROI_SIZE)
        val opaqueWorkspace = CanonicalFaceRoiSampler.Workspace(ROI_SIZE)

        sampleCachedFrame(
            canonical,
            mapper,
            rects,
            historicalOutputs,
            historicalWorkspace,
            opaquePreparedInput = false
        )
        sampleCachedFrame(
            canonical,
            mapper,
            rects,
            opaqueOutputs,
            opaqueWorkspace,
            opaquePreparedInput = true,
            heapStagedOutput = true
        )
        rects.indices.forEach { roi ->
            for (i in 0 until ROI_SIZE * ROI_SIZE * 4) {
                assertEquals(historicalOutputs[roi].get(i), opaqueOutputs[roi].get(i), "roi=$roi byte=$i")
            }
        }

        repeat(3) {
            sampleCachedFrame(
                canonical,
                mapper,
                rects,
                historicalOutputs,
                historicalWorkspace,
                opaquePreparedInput = false
            )
            sampleCachedFrame(
                canonical,
                mapper,
                rects,
                opaqueOutputs,
                opaqueWorkspace,
                opaquePreparedInput = true,
                heapStagedOutput = true
            )
        }

        val historicalTimes = mutableListOf<Double>()
        val opaqueTimes = mutableListOf<Double>()
        repeat(11) {
            historicalTimes += timeMs {
                sampleCachedFrame(
                    canonical,
                    mapper,
                    rects,
                    historicalOutputs,
                    historicalWorkspace,
                    opaquePreparedInput = false
                )
            }
            opaqueTimes += timeMs {
                sampleCachedFrame(
                    canonical,
                    mapper,
                    rects,
                    opaqueOutputs,
                    opaqueWorkspace,
                    opaquePreparedInput = true,
                    heapStagedOutput = true
                )
            }
        }
        val historicalMedian = median(historicalTimes)
        val opaqueMedian = median(opaqueTimes)
        val improvementPct = improvement(historicalMedian, opaqueMedian)
        Log.i(
            TAG,
            "five_roi_prepared_historical_ms=$historicalMedian " +
                "five_roi_prepared_opaque_ms=$opaqueMedian improvement_pct=$improvementPct"
        )
        assertTrue(improvementPct >= 10.0, "Expected >=10% prepared ROI improvement, got $improvementPct%")
    }

    @Test
    fun heapCachedCanonicalInputPreservesBytesAndReducesFiveRoiWallTime() {
        val mapper = ModelCoordinateMapper(
            srcWidth = 1920,
            srcHeight = 1080,
            modelInputSize = MODEL_SIZE,
            protoSize = 160
        )
        val canonical = ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(MODEL_SIZE * MODEL_SIZE) { pixel ->
            val offset = pixel * 4
            canonical.put(offset, ((pixel * 17 + 3) and 0xFF).toByte())
            canonical.put(offset + 1, ((pixel * 29 + 11) and 0xFF).toByte())
            canonical.put(offset + 2, ((pixel * 43 + 19) and 0xFF).toByte())
            canonical.put(offset + 3, ((pixel * 7 + 251) and 0xFF).toByte())
        }
        val rects = benchmarkRects()
        val referenceOutputs = Array(rects.size) {
            ByteBuffer.allocateDirect(ROI_SIZE * ROI_SIZE * 4)
        }
        val cachedOutputs = Array(rects.size) {
            ByteBuffer.allocateDirect(ROI_SIZE * ROI_SIZE * 4)
        }
        val referenceWorkspace = CanonicalFaceRoiSampler.Workspace(ROI_SIZE)
        val cachedWorkspace = CanonicalFaceRoiSampler.Workspace(ROI_SIZE)

        sampleReferenceFrame(canonical, mapper, rects, referenceOutputs, referenceWorkspace)
        sampleCachedFrame(canonical, mapper, rects, cachedOutputs, cachedWorkspace)
        rects.indices.forEach { roi ->
            for (i in 0 until ROI_SIZE * ROI_SIZE * 4) {
                assertEquals(
                    referenceOutputs[roi].get(i),
                    cachedOutputs[roi].get(i),
                    "roi=$roi byte=$i"
                )
            }
        }

        repeat(2) {
            sampleReferenceFrame(canonical, mapper, rects, referenceOutputs, referenceWorkspace)
            sampleCachedFrame(canonical, mapper, rects, cachedOutputs, cachedWorkspace)
        }

        val referenceTimes = mutableListOf<Double>()
        val cachedTimes = mutableListOf<Double>()
        repeat(9) {
            referenceTimes += timeMs {
                sampleReferenceFrame(canonical, mapper, rects, referenceOutputs, referenceWorkspace)
            }
            cachedTimes += timeMs {
                sampleCachedFrame(canonical, mapper, rects, cachedOutputs, cachedWorkspace)
            }
        }
        val referenceMedian = median(referenceTimes)
        val cachedMedian = median(cachedTimes)
        Log.i(
            TAG,
            "five_roi_reference_ms=$referenceMedian five_roi_cached_ms=$cachedMedian " +
                "improvement_pct=${improvement(referenceMedian, cachedMedian)}"
        )
    }

    private fun sampleReferenceFrame(
        canonical: ByteBuffer,
        mapper: ModelCoordinateMapper,
        rects: List<FloatRect>,
        outputs: Array<ByteBuffer>,
        workspace: CanonicalFaceRoiSampler.Workspace
    ) {
        rects.forEachIndexed { index, rect ->
            CanonicalFaceRoiSampler.sampleTopDown(
                canonicalRgbaBottomUp = canonical,
                mapper = mapper,
                sourceRect = rect,
                outputSize = ROI_SIZE,
                output = outputs[index],
                workspace = workspace
            )
        }
    }

    private fun sampleCachedFrame(
        canonical: ByteBuffer,
        mapper: ModelCoordinateMapper,
        rects: List<FloatRect>,
        outputs: Array<ByteBuffer>,
        workspace: CanonicalFaceRoiSampler.Workspace,
        opaquePreparedInput: Boolean = false,
        heapStagedOutput: Boolean = false
    ) {
        val prepared = CanonicalFaceRoiSampler.prepareCanonicalInput(
            canonicalRgbaBottomUp = canonical,
            modelSize = MODEL_SIZE,
            workspace = workspace
        )
        rects.forEachIndexed { index, rect ->
            CanonicalFaceRoiSampler.sampleTopDown(
                canonicalRgbaBottomUp = canonical,
                mapper = mapper,
                sourceRect = rect,
                outputSize = ROI_SIZE,
                output = outputs[index],
                workspace = workspace,
                preparedCanonicalInput = prepared,
                preparedCanonicalInputIsOpaque = opaquePreparedInput,
                preparedCanonicalOutputUsesHeapStaging = heapStagedOutput
            )
        }
    }

    private fun benchmarkRects(): List<FloatRect> = listOf(
        FloatRect(180f, 80f, 520f, 420f),
        FloatRect(500f, 120f, 860f, 480f),
        FloatRect(820f, 70f, 1180f, 430f),
        FloatRect(1120f, 160f, 1500f, 540f),
        FloatRect(1420f, 100f, 1780f, 460f)
    )

    private inline fun timeMs(block: () -> Unit): Double {
        val startedNs = System.nanoTime()
        block()
        return (System.nanoTime() - startedNs) / 1_000_000.0
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    private fun improvement(reference: Double, candidate: Double): Double =
        (reference - candidate) / reference * 100.0

    companion object {
        private const val MODEL_SIZE = 640
        private const val ROI_SIZE = 256
        private const val TAG = "CanonicalFaceRoiBench"
    }
}

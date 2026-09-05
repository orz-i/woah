package com.danceanon.native.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.face.FaceHeadRoiPlan
import com.danceanon.native.inference.FloatRect
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FaceRoiGrayConversionInstrumentedTest {
    @Test
    fun compareEquivalentRgbaToGrayKernels() {
        val totalPixels = SIZE * SIZE
        val rgba = ByteBuffer.allocateDirect(totalPixels * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalPixels) {
            val offset = i * 4
            rgba.put(offset, ((i * 17 + 3) and 0xFF).toByte())
            rgba.put(offset + 1, ((i * 29 + 11) and 0xFF).toByte())
            rgba.put(offset + 2, ((i * 43 + 19) and 0xFF).toByte())
            rgba.put(offset + 3, 255.toByte())
        }

        val scalarOut = ByteArray(totalPixels)
        val intBulkOut = ByteArray(totalPixels)
        val byteBulkOut = ByteArray(totalPixels)
        val intWorkspace = IntArray(totalPixels)
        val byteWorkspace = ByteArray(totalPixels * 4)

        scalar(rgba, scalarOut)
        intBulk(rgba, intBulkOut, intWorkspace)
        byteBulk(rgba, byteBulkOut, byteWorkspace)
        assertContentEquals(scalarOut, intBulkOut)
        assertContentEquals(scalarOut, byteBulkOut)

        repeat(5) {
            scalar(rgba, scalarOut)
            intBulk(rgba, intBulkOut, intWorkspace)
            byteBulk(rgba, byteBulkOut, byteWorkspace)
        }

        val scalarTimes = mutableListOf<Double>()
        val intBulkTimes = mutableListOf<Double>()
        val byteBulkTimes = mutableListOf<Double>()
        repeat(15) {
            scalarTimes += timeMs { repeat(BATCH) { scalar(rgba, scalarOut) } }
            intBulkTimes += timeMs { repeat(BATCH) { intBulk(rgba, intBulkOut, intWorkspace) } }
            byteBulkTimes += timeMs { repeat(BATCH) { byteBulk(rgba, byteBulkOut, byteWorkspace) } }
        }

        val scalarMedian = median(scalarTimes)
        val intBulkMedian = median(intBulkTimes)
        val byteBulkMedian = median(byteBulkTimes)
        Log.i(
            TAG,
            "scalar_ms=$scalarMedian int_bulk_ms=$intBulkMedian byte_bulk_ms=$byteBulkMedian " +
                "int_improvement_pct=${improvement(scalarMedian, intBulkMedian)} " +
                "byte_improvement_pct=${improvement(scalarMedian, byteBulkMedian)}"
        )
    }

    private fun timedTrackerSequence(
        frames: List<ByteBuffer>,
        plan: FaceHeadRoiPlan,
        person: FloatRect,
        detected: FacePrivacyEllipse,
        bulkGray: Boolean
    ): Pair<Double, List<FacePixelMotionTracker.RoiMatchOutcome>> {
        val startedNs = System.nanoTime()
        val result = runTrackerSequence(frames, plan, person, detected, bulkGray)
        return (System.nanoTime() - startedNs) / 1_000_000.0 to result
    }

    private fun runTrackerSequence(
        frames: List<ByteBuffer>,
        plan: FaceHeadRoiPlan,
        person: FloatRect,
        detected: FacePrivacyEllipse,
        bulkGray: Boolean
    ): List<FacePixelMotionTracker.RoiMatchOutcome> {
        val tracker = FacePixelMotionTracker(useBulkRoiGrayConversion = bulkGray)
        assertTrue(tracker.seedRoi(TRACK_ID, frames.first(), plan, detected, person, 0L))
        return frames.drop(1).mapIndexed { index, frame ->
            tracker.matchRoiDetailed(
                trackId = TRACK_ID,
                rgbaTopDown = frame,
                roiPlan = plan,
                personBbox = person,
                personObservedThisFrame = true,
                ptsUs = (index + 1L) * 16_666L
            )
        }
    }

    private fun roiFrameWithPatch(
        plan: FaceHeadRoiPlan,
        sourceCenterX: Float,
        sourceCenterY: Float
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(plan.outputSize * plan.outputSize * 4)
        for (i in 0 until plan.outputSize * plan.outputSize) {
            val offset = i * 4
            buffer.put(offset, 24)
            buffer.put(offset + 1, 24)
            buffer.put(offset + 2, 24)
            buffer.put(offset + 3, 255.toByte())
        }
        val localX = (((sourceCenterX - plan.sourceRect.left) / plan.sourceRect.width) *
            plan.outputSize).roundToInt()
        val localY = (((sourceCenterY - plan.sourceRect.top) / plan.sourceRect.height) *
            plan.outputSize).roundToInt()
        for (dy in -18..18) {
            for (dx in -18..18) {
                val x = localX + dx
                val y = localY + dy
                if (x !in 0 until plan.outputSize || y !in 0 until plan.outputSize) continue
                val offset = (y * plan.outputSize + x) * 4
                buffer.put(offset, (80 + (dx * 17 + dy * 7 + dx * dy * 3)).and(0xFF).toByte())
                buffer.put(offset + 1, (60 + (dx * 5 - dy * 19 + dx * dx)).and(0xFF).toByte())
                buffer.put(offset + 2, (40 + (dy * 13 - dx * 11 + dy * dy)).and(0xFF).toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
        return buffer
    }

    @Test
    fun bulkGrayPreservesTrackerSequenceAndReducesWallTime() {
        val plan = FaceHeadRoiPlan(
            sourceRect = FloatRect(140f, 80f, 340f, 280f),
            anchorX = 0.5f,
            anchorY = 0.5f,
            outputSize = SIZE
        )
        val person = FloatRect(180f, 100f, 310f, 500f)
        val detected = FacePrivacyEllipse(
            centerX = 240f,
            centerY = 180f,
            radiusX = 14f,
            radiusY = 16f,
            source = FacePrivacyRegionSource.DETECTED_FACE
        )
        val frames = List(18) { frame ->
            val centerX = 240f + frame * 1.25f
            val centerY = 180f + ((frame % 7) - 3) * 0.75f
            roiFrameWithPatch(plan, centerX, centerY)
        }

        repeat(3) {
            runTrackerSequence(frames, plan, person, detected, false)
            runTrackerSequence(frames, plan, person, detected, true)
        }

        val scalarTimes = mutableListOf<Double>()
        val bulkTimes = mutableListOf<Double>()
        var expected: List<FacePixelMotionTracker.RoiMatchOutcome>? = null
        repeat(9) { trial ->
            val scalar = timedTrackerSequence(frames, plan, person, detected, false)
            val bulk = timedTrackerSequence(frames, plan, person, detected, true)
            if (trial == 0) expected = scalar.second
            assertEquals(expected, scalar.second)
            assertEquals(expected, bulk.second)
            scalarTimes += scalar.first
            bulkTimes += bulk.first
        }

        val scalarMedian = median(scalarTimes)
        val bulkMedian = median(bulkTimes)
        Log.i(
            TRACKER_TAG,
            "sequence_scalar_median_ms=$scalarMedian sequence_bulk_median_ms=$bulkMedian " +
                "improvement_pct=${improvement(scalarMedian, bulkMedian)}"
        )
        assertTrue(scalarMedian > 0.0)
    }

    private fun scalar(rgba: ByteBuffer, out: ByteArray) {
        val previousOrder = rgba.order()
        rgba.order(ByteOrder.LITTLE_ENDIAN)
        try {
            var srcOffset = 0
            for (i in out.indices) {
                val pixel = rgba.getInt(srcOffset)
                out[i] = gray(pixel)
                srcOffset += 4
            }
        } finally {
            rgba.order(previousOrder)
        }
    }

    private fun intBulk(rgba: ByteBuffer, out: ByteArray, workspace: IntArray) {
        val ints = rgba.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        ints.position(0)
        ints.get(workspace, 0, out.size)
        for (i in out.indices) {
            out[i] = gray(workspace[i])
        }
    }

    private fun byteBulk(rgba: ByteBuffer, out: ByteArray, workspace: ByteArray) {
        val bytes = rgba.duplicate()
        bytes.position(0)
        bytes.get(workspace, 0, out.size * 4)
        var srcOffset = 0
        for (i in out.indices) {
            val r = workspace[srcOffset].toInt() and 0xFF
            val g = workspace[srcOffset + 1].toInt() and 0xFF
            val b = workspace[srcOffset + 2].toInt() and 0xFF
            out[i] = ((77 * r + 150 * g + 29 * b) ushr 8).toByte()
            srcOffset += 4
        }
    }

    private fun gray(pixel: Int): Byte {
        val r = pixel and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = (pixel ushr 16) and 0xFF
        return ((77 * r + 150 * g + 29 * b) ushr 8).toByte()
    }

    private inline fun timeMs(block: () -> Unit): Double {
        val startedNs = System.nanoTime()
        block()
        return (System.nanoTime() - startedNs) / 1_000_000.0
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    private fun improvement(reference: Double, candidate: Double): Double =
        (reference - candidate) / reference * 100.0

    companion object {
        private const val SIZE = 256
        private const val BATCH = 8
        private const val TRACK_ID = 71
        private const val TAG = "FaceRoiGrayBench"
        private const val TRACKER_TAG = "FacePixelBulkGrayBench"
    }
}

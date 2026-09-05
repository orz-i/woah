package com.danceanon.native.media

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CanonicalYuvHotspotInstrumentedTest {
    @Test
    fun partitions1080pCanonicalConversionCost() {
        val sourceWidth = 1920
        val sourceHeight = 1080
        val modelSize = 640
        val validHeight = 360
        val padTop = 140

        val yLength = sourceWidth * sourceHeight
        // Typical YUV_420_888 semiplanar exposure: U/V are views with rowStride=1920,
        // pixelStride=2. Keep both full exposed buffers because production snapshots
        // each Image.Plane independently.
        val uvLength = sourceWidth * (sourceHeight / 2)
        val yDirect = directBytes(yLength, seed = 17)
        val uDirect = directBytes(uvLength, seed = 61)
        val vDirect = directBytes(uvLength, seed = 113)
        val yHeap = ByteArray(yLength)
        val uHeap = ByteArray(uvLength)
        val vHeap = ByteArray(uvLength)

        fun snapshotAll() {
            yDirect.duplicate().apply { rewind() }.get(yHeap, 0, yLength)
            uDirect.duplicate().apply { rewind() }.get(uHeap, 0, uvLength)
            vDirect.duplicate().apply { rewind() }.get(vHeap, 0, uvLength)
        }
        snapshotAll()

        val lumaX = axisSamples(modelSize, sourceWidth) { x -> 3.0 * x + 1.0 }
        val lumaY = axisSamples(modelSize, sourceHeight) { y ->
            if (y in padTop until padTop + validHeight) 3.0 * (y - padTop) + 1.0 else 0.0
        }
        val uvX = axisSamples(modelSize, sourceWidth / 2) { x -> (3.0 * x + 1.0) * 0.5 }
        val uvY = axisSamples(modelSize, sourceHeight / 2) { y ->
            if (y in padTop until padTop + validHeight) (3.0 * (y - padTop) + 1.0) * 0.5 else 0.0
        }
        val yAccess = CanonicalYuvToRgba.buildPlaneAccessPlan(lumaX, lumaY, sourceWidth, 1)
        val uAccess = CanonicalYuvToRgba.buildPlaneAccessPlan(uvX, uvY, sourceWidth, 2)
        val vAccess = CanonicalYuvToRgba.buildPlaneAccessPlan(uvX, uvY, sourceWidth, 2)
        val yPlane = CanonicalYuvToRgba.PlaneSnapshot(yHeap, yHeap.size, sourceWidth, 1)
        val uPlane = CanonicalYuvToRgba.PlaneSnapshot(uHeap, uHeap.size, sourceWidth, 2)
        val vPlane = CanonicalYuvToRgba.PlaneSnapshot(vHeap, vHeap.size, sourceWidth, 2)
        val transform = limitedBt709()
        val rgba = IntArray(modelSize * modelSize) { 0xFF727272.toInt() }
        val out = ByteBuffer.allocateDirect(rgba.size * 4).order(ByteOrder.nativeOrder())

        fun sampleAndColor(): Long {
            var checksum = 0L
            for (modelY in padTop until padTop + validHeight) {
                val dstRow = (modelSize - 1 - modelY) * modelSize
                for (modelX in 0 until modelSize) {
                    val y = CanonicalYuvToRgba.sampleSnapshotFast(yPlane, yAccess, modelX, modelY)
                    val u = CanonicalYuvToRgba.sampleSnapshotFast(uPlane, uAccess, modelX, modelY)
                    val v = CanonicalYuvToRgba.sampleSnapshotFast(vPlane, vAccess, modelX, modelY)
                    val packed = CanonicalYuvToRgba.rgbaFromYuv(y, u, v, transform)
                    rgba[dstRow + modelX] = packed
                    checksum += packed.toLong() and 0xffffffffL
                }
            }
            return checksum
        }

        fun sampleAndColorCandidate(): Long {
            var checksum = 0L
            for (modelY in padTop until padTop + validHeight) {
                val dstRow = (modelSize - 1 - modelY) * modelSize
                val yRow = yAccess.y0[modelY]
                for (modelX in 0 until modelSize) {
                    // This specialized path is only valid when the complete
                    // luma plan is nearest-neighbor (all fixed-point weights 0).
                    val y = yPlane.bytes[yRow + yAccess.x0[modelX]].toInt() and 0xff
                    val uv = CanonicalYuvToRgba.sampleUvPairFast(
                        uPlane = uPlane,
                        vPlane = vPlane,
                        access = uAccess,
                        xIndex = modelX,
                        yIndex = modelY
                    )
                    val u = uv ushr 8
                    val v = uv and 0xff
                    val packed = CanonicalYuvToRgba.rgbaFromYuv(y, u, v, transform)
                    rgba[dstRow + modelX] = packed
                    checksum += packed.toLong() and 0xffffffffL
                }
            }
            return checksum
        }

        fun copyOutput() {
            out.clear()
            out.duplicate().apply {
                position(0)
                order(ByteOrder.LITTLE_ENDIAN)
            }.asIntBuffer().put(rgba, 0, rgba.size)
            out.rewind()
        }

        repeat(4) {
            snapshotAll()
            sampleAndColor()
            sampleAndColorCandidate()
            copyOutput()
        }

        val snapshotMs = measuredMedianMs(9) { snapshotAll() }
        var checksum = 0L
        val sampleMs = measuredMedianMs(9) { checksum = sampleAndColor() }
        var candidateChecksum = 0L
        val candidateMs = measuredMedianMs(9) { candidateChecksum = sampleAndColorCandidate() }
        val outputMs = measuredMedianMs(9) { copyOutput() }
        val candidateImprovement = (sampleMs - candidateMs) / sampleMs * 100.0
        Log.i(
            TAG,
            "canonical1080_snapshot_ms=$snapshotMs sample_color_ms=$sampleMs " +
                "candidate_sample_color_ms=$candidateMs candidate_improvement_pct=$candidateImprovement " +
                "output_copy_ms=$outputMs checksum=$checksum candidate_checksum=$candidateChecksum"
        )
        assertTrue(checksum != 0L)
        assertTrue(checksum == candidateChecksum, "Fused/nearest candidate must be packed-RGBA exact")
        assertTrue(
            candidateImprovement >= 10.0,
            "Expected >=10% canonical sample+color gain, got $candidateImprovement%"
        )
    }

    private fun directBytes(length: Int, seed: Int): ByteBuffer =
        ByteBuffer.allocateDirect(length).apply {
            for (i in 0 until length) put(i, ((i * 31 + seed) and 0xff).toByte())
        }

    private fun axisSamples(
        size: Int,
        dimension: Int,
        coordinate: (Int) -> Double
    ): CanonicalYuvToRgba.AxisSamples {
        val i0 = IntArray(size)
        val i1 = IntArray(size)
        val w1 = IntArray(size)
        val maxIndex = (dimension - 1).coerceAtLeast(0)
        for (i in 0 until size) {
            val c = coordinate(i).coerceIn(0.0, maxIndex.toDouble())
            val base = floor(c).toInt()
            i0[i] = base
            i1[i] = (base + 1).coerceAtMost(maxIndex)
            w1[i] = ((c - base) * 256.0).roundToInt().coerceIn(0, 256)
        }
        return CanonicalYuvToRgba.AxisSamples(i0, i1, w1)
    }

    private fun limitedBt709(): CanonicalYuvToRgba.ColorTransform {
        val fp = 256
        fun contribution(coefficient: Int) = IntArray(256) { value -> coefficient * (value - 128) }
        return CanonicalYuvToRgba.ColorTransform(
            yTerms = IntArray(256) { y -> 298 * (y - 16).coerceAtLeast(0) },
            rU = contribution(0),
            rV = contribution(459),
            gU = contribution(-55),
            gV = contribution(-136),
            bU = contribution(541),
            bV = contribution(0),
            rBias = fp / 2,
            gBias = fp / 2,
            bBias = fp / 2
        )
    }

    private inline fun measuredMedianMs(trials: Int, block: () -> Unit): Double {
        val values = DoubleArray(trials)
        for (i in 0 until trials) {
            val start = System.nanoTime()
            block()
            values[i] = (System.nanoTime() - start) / 1_000_000.0
        }
        values.sort()
        return values[values.size / 2]
    }

    private companion object {
        const val TAG = "CanonicalYuvHotspotBench"
    }
}

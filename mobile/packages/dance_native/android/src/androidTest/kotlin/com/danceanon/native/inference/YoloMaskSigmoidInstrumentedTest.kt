package com.danceanon.native.inference

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class YoloMaskSigmoidInstrumentedTest {
    @Test
    fun exactFloatThresholdSigmoidPreservesMaskBytesAndReducesDecodeWallTime() {
        val protoPixels = PROTO_SIZE * PROTO_SIZE
        val values = FloatArray(CHANNELS * protoPixels) { index ->
            val raw = ((index * 1_103_515_245L + 12_345L) ushr 12).toInt() and 0xffff
            (raw - 32_768) / 32_768f
        }
        val coeffs = FloatArray(CHANNELS) { channel ->
            ((channel * 37 + 11) % 29 - 14) / 9f
        }
        val historicalScratch = FloatArray(protoPixels)
        val optimizedScratch = FloatArray(protoPixels)

        val historical = decodeMask(values, coeffs, historicalScratch, useExactClassifier = false)
        val optimized = decodeMask(values, coeffs, optimizedScratch, useExactClassifier = true)
        assertContentEquals(historical, optimized)

        repeat(3) {
            decodeMask(values, coeffs, historicalScratch, useExactClassifier = false)
            decodeMask(values, coeffs, optimizedScratch, useExactClassifier = true)
        }

        val historicalTimes = mutableListOf<Double>()
        val optimizedTimes = mutableListOf<Double>()
        repeat(15) {
            historicalTimes += timeMs {
                decodeMask(values, coeffs, historicalScratch, useExactClassifier = false)
            }
            optimizedTimes += timeMs {
                decodeMask(values, coeffs, optimizedScratch, useExactClassifier = true)
            }
        }

        val historicalMedian = median(historicalTimes)
        val optimizedMedian = median(optimizedTimes)
        val improvement = (historicalMedian - optimizedMedian) / historicalMedian * 100.0
        Log.i(
            TAG,
            "mask_decode_historical_ms=$historicalMedian mask_decode_threshold_ms=$optimizedMedian " +
                "improvement_pct=$improvement"
        )
        assertTrue(
            improvement >= 10.0,
            "Expected >=10% full mask-decode improvement, got $improvement%"
        )
    }

    private fun decodeMask(
        values: FloatArray,
        coeffs: FloatArray,
        scratch: FloatArray,
        useExactClassifier: Boolean
    ): ByteArray {
        val maskBytes = ByteArray(PROTO_SIZE * PROTO_SIZE)
        val x1 = (floor((CAND_X1 / INPUT_SIZE) * PROTO_SIZE).toInt() - 1).coerceIn(0, PROTO_SIZE)
        val y1 = (floor((CAND_Y1 / INPUT_SIZE) * PROTO_SIZE).toInt() - 1).coerceIn(0, PROTO_SIZE)
        val x2 = (ceil((CAND_X2 / INPUT_SIZE) * PROTO_SIZE).toInt() + 1).coerceIn(0, PROTO_SIZE)
        val y2 = (ceil((CAND_Y2 / INPUT_SIZE) * PROTO_SIZE).toInt() + 1).coerceIn(0, PROTO_SIZE)

        for (py in y1 until y2) {
            java.util.Arrays.fill(scratch, py * PROTO_SIZE + x1, py * PROTO_SIZE + x2, 0f)
        }

        val protoPixels = PROTO_SIZE * PROTO_SIZE
        for (c in 0 until CHANNELS) {
            val coeff = coeffs[c]
            val channelBase = c * protoPixels
            for (py in y1 until y2) {
                val pixelRow = py * PROTO_SIZE
                val protoRow = channelBase + pixelRow
                var px = x1
                while (px + 3 < x2) {
                    val pixelIndex = pixelRow + px
                    val protoIndex = protoRow + px
                    scratch[pixelIndex] += coeff * values[protoIndex]
                    scratch[pixelIndex + 1] += coeff * values[protoIndex + 1]
                    scratch[pixelIndex + 2] += coeff * values[protoIndex + 2]
                    scratch[pixelIndex + 3] += coeff * values[protoIndex + 3]
                    px += 4
                }
                while (px < x2) {
                    val pixelIndex = pixelRow + px
                    scratch[pixelIndex] += coeff * values[protoRow + px]
                    px++
                }
            }
        }

        for (py in y1 until y2) {
            val rowOffset = py * PROTO_SIZE
            for (px in x1 until x2) {
                val sum = scratch[rowOffset + px]
                val byteValue = if (useExactClassifier) {
                    ExactMaskByteSigmoid.toByteValue(sum)
                } else {
                    ExactMaskByteSigmoid.historicalMaskByte(sum)
                }
                maskBytes[rowOffset + px] = byteValue.toByte()
            }
        }
        return maskBytes
    }

    private companion object {
        const val TAG = "YoloMaskSigmoidBench"
        const val CHANNELS = 32
        const val PROTO_SIZE = 160
        const val INPUT_SIZE = 640f
        const val CAND_X1 = 150f
        const val CAND_Y1 = 70f
        const val CAND_X2 = 360f
        const val CAND_Y2 = 520f

        inline fun timeMs(block: () -> Unit): Double {
            val start = System.nanoTime()
            block()
            return (System.nanoTime() - start) / 1_000_000.0
        }

        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}

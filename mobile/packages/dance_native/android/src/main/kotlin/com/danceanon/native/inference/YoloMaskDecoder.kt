package com.danceanon.native.inference

import java.nio.FloatBuffer
import java.util.IdentityHashMap

data class RawCandidate(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    val maskCoeffs: FloatArray,
    val syntheticMask: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RawCandidate
        if (x1 != other.x1 || y1 != other.y1 || x2 != other.x2 || y2 != other.y2) return false
        if (confidence != other.confidence) return false
        if (!maskCoeffs.contentEquals(other.maskCoeffs)) return false
        if (syntheticMask != null) {
            if (other.syntheticMask == null) return false
            if (!syntheticMask.contentEquals(other.syntheticMask)) return false
        } else if (other.syntheticMask != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = x1.hashCode()
        result = 31 * result + y1.hashCode()
        result = 31 * result + x2.hashCode()
        result = 31 * result + y2.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + maskCoeffs.contentHashCode()
        result = 31 * result + (syntheticMask?.contentHashCode() ?: 0)
        return result
    }
}

interface ProtoTensorView {
    fun getDotProduct(px: Int, py: Int, coeffs: FloatArray): Float
}

class NchwBufferProtoView(
    private val buffer: FloatBuffer,
    private val channels: Int = 32,
    private val protoSize: Int = 160
) : ProtoTensorView {
    private val protoPixels = protoSize * protoSize
    override fun getDotProduct(px: Int, py: Int, coeffs: FloatArray): Float {
        var sum = 0f
        val pixelOffset = py * protoSize + px
        for (c in 0 until channels) {
            sum += coeffs[c] * buffer.get(c * protoPixels + pixelOffset)
        }
        return sum
    }
}

class NhwcBufferProtoView(
    private val buffer: FloatBuffer,
    private val channels: Int = 32,
    private val protoSize: Int = 160
) : ProtoTensorView {
    override fun getDotProduct(px: Int, py: Int, coeffs: FloatArray): Float {
        var sum = 0f
        val pixelOffset = (py * protoSize + px) * channels
        for (c in 0 until channels) {
            sum += coeffs[c] * buffer.get(pixelOffset + c)
        }
        return sum
    }
}

class ArrayProtoView(
    private val proto: Array<Array<FloatArray>>,
    private val channels: Int = 32
) : ProtoTensorView {
    override fun getDotProduct(px: Int, py: Int, coeffs: FloatArray): Float {
        var sum = 0f
        for (c in 0 until channels) {
            sum += coeffs[c] * proto[c][py][px]
        }
        return sum
    }
}

object YoloMaskDecoder {
    const val DEFAULT_PROTO_SIZE = 160
    const val DEFAULT_INPUT_SIZE = 640
    const val DEFAULT_MASK_IOU_THRESHOLD = 0.50f

    fun decodeCandidateMask(
        cand: RawCandidate,
        protoView: ProtoTensorView?,
        inputSize: Int = DEFAULT_INPUT_SIZE,
        protoSize: Int = DEFAULT_PROTO_SIZE
    ): ByteArray {
        if (cand.syntheticMask != null) {
            return cand.syntheticMask
        }
        val maskBytes = ByteArray(protoSize * protoSize)
        if (protoView == null) {
            val px1 = ((cand.x1 / inputSize) * protoSize).toInt().coerceIn(0, protoSize)
            val py1 = ((cand.y1 / inputSize) * protoSize).toInt().coerceIn(0, protoSize)
            val px2 = ((cand.x2 / inputSize) * protoSize).toInt().coerceIn(0, protoSize)
            val py2 = ((cand.y2 / inputSize) * protoSize).toInt().coerceIn(0, protoSize)
            for (py in py1 until py2) {
                for (px in px1 until px2) {
                    maskBytes[py * protoSize + px] = 255.toByte()
                }
            }
            return maskBytes
        }

        val margin = 1
        val protoX1 = (kotlin.math.floor((cand.x1 / inputSize) * protoSize).toInt() - margin).coerceIn(0, protoSize)
        val protoY1 = (kotlin.math.floor((cand.y1 / inputSize) * protoSize).toInt() - margin).coerceIn(0, protoSize)
        val protoX2 = (kotlin.math.ceil((cand.x2 / inputSize) * protoSize).toInt() + margin).coerceIn(0, protoSize)
        val protoY2 = (kotlin.math.ceil((cand.y2 / inputSize) * protoSize).toInt() + margin).coerceIn(0, protoSize)

        for (py in protoY1 until protoY2) {
            val rowOffset = py * protoSize
            for (px in protoX1 until protoX2) {
                val sum = protoView.getDotProduct(px, py, cand.maskCoeffs)
                val prob = 1.0f / (1.0f + kotlin.math.exp(-sum))
                val byteVal = (prob * 255f).toInt().coerceIn(0, 255).toByte()
                maskBytes[rowOffset + px] = byteVal
            }
        }
        return maskBytes
    }

    fun calculateBboxIoU(a: RawCandidate, b: RawCandidate): Float {
        val interX1 = maxOf(a.x1, b.x1)
        val interY1 = maxOf(a.y1, b.y1)
        val interX2 = minOf(a.x2, b.x2)
        val interY2 = minOf(a.y2, b.y2)

        val interW = maxOf(0f, interX2 - interX1)
        val interH = maxOf(0f, interY2 - interY1)
        val interArea = interW * interH

        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val unionArea = areaA + areaB - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    fun calculateMaskIoU(maskA: ByteArray, maskB: ByteArray): Float {
        var intersection = 0
        var union = 0
        val len = minOf(maskA.size, maskB.size)
        for (i in 0 until len) {
            val a = (maskA[i] != 0.toByte())
            val b = (maskB[i] != 0.toByte())
            if (a && b) intersection++
            if (a || b) union++
        }
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    class CandidateMaskCache(
        private val protoView: ProtoTensorView?,
        private val inputSize: Int = DEFAULT_INPUT_SIZE,
        private val protoSize: Int = DEFAULT_PROTO_SIZE
    ) {
        // Candidates are immutable objects created once per frame and the NMS/
        // final decode paths reuse the same instances. Identity keys avoid
        // hashing all 32 mask coefficients on every cache lookup.
        private val cache = IdentityHashMap<RawCandidate, ByteArray>()

        fun getMask(cand: RawCandidate): ByteArray {
            val cached = cache[cand]
            if (cached != null) return cached
            return decodeCandidateMask(cand, protoView, inputSize, protoSize).also {
                cache[cand] = it
            }
        }
    }

    fun maskAwareNms(
        candidates: List<RawCandidate>,
        protoView: ProtoTensorView?,
        bboxIouThreshold: Float = 0.50f,
        maskIouThreshold: Float = DEFAULT_MASK_IOU_THRESHOLD,
        inputSize: Int = DEFAULT_INPUT_SIZE,
        protoSize: Int = DEFAULT_PROTO_SIZE
    ): Pair<List<RawCandidate>, CandidateMaskCache> {
        val sorted = candidates.sortedByDescending { it.confidence }
        val selected = ArrayList<RawCandidate>(sorted.size)
        val suppressed = BooleanArray(sorted.size)
        val cache = CandidateMaskCache(protoView, inputSize, protoSize)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val current = sorted[i]
            selected.add(current)

            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val next = sorted[j]
                val bboxIoU = calculateBboxIoU(current, next)
                if (bboxIoU > bboxIouThreshold) {
                    val maskCurrent = cache.getMask(current)
                    val maskNext = cache.getMask(next)
                    val maskIoU = calculateMaskIoU(maskCurrent, maskNext)

                    if (maskIoU >= maskIouThreshold) {
                        // High mask overlap: duplicate detection -> suppress
                        suppressed[j] = true
                    }
                }
            }
        }
        return Pair(selected, cache)
    }
}

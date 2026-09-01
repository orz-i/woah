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

/** FloatArray-backed equivalent of [NhwcBufferProtoView]. */
class NhwcArrayProtoView(
    private val values: FloatArray,
    private val channels: Int = 32,
    private val protoSize: Int = 160
) : ProtoTensorView {
    override fun getDotProduct(px: Int, py: Int, coeffs: FloatArray): Float {
        var sum = 0f
        val pixelOffset = (py * protoSize + px) * channels
        for (c in 0 until channels) {
            sum += coeffs[c] * values[pixelOffset + c]
        }
        return sum
    }
}

/**
 * FloatArray-backed equivalent of [NchwBufferProtoView]. LiteRT output tensors
 * are already materialized as FloatArray by TensorBuffer.readFloat(); keeping
 * the proto in that representation avoids millions of absolute FloatBuffer.get
 * calls in the per-pixel mask dot-product hot loop.
 */
class NchwArrayProtoView(
    private val values: FloatArray,
    private val channels: Int = 32,
    private val protoSize: Int = 160
) : ProtoTensorView {
    private val protoPixels = protoSize * protoSize

    override fun getDotProduct(px: Int, py: Int, coeffs: FloatArray): Float {
        var sum = 0f
        val pixelOffset = py * protoSize + px
        for (c in 0 until channels) {
            sum += coeffs[c] * values[c * protoPixels + pixelOffset]
        }
        return sum
    }

    /**
     * Decode only the candidate support while reading each NCHW channel plane
     * sequentially. Every pixel still accumulates channels in the exact 0..31
     * order used by [getDotProduct], but avoids bouncing between 32 distant
     * channel planes for every individual pixel.
     */
    fun decodeCandidateMask(
        cand: RawCandidate,
        inputSize: Int,
        scratch: FloatArray
    ): ByteArray {
        require(scratch.size >= protoPixels)
        val maskBytes = ByteArray(protoPixels)
        val support = YoloMaskDecoder.candidateProtoSupportRect(cand, inputSize, protoSize)

        for (py in support.y1 until support.y2) {
            java.util.Arrays.fill(scratch, py * protoSize + support.x1, py * protoSize + support.x2, 0f)
        }

        for (c in 0 until channels) {
            val coeff = cand.maskCoeffs[c]
            val channelBase = c * protoPixels
            for (py in support.y1 until support.y2) {
                val pixelRow = py * protoSize
                val protoRow = channelBase + pixelRow
                for (px in support.x1 until support.x2) {
                    val pixelIndex = pixelRow + px
                    val protoIndex = protoRow + px
                    scratch[pixelIndex] += coeff * values[protoIndex]
                }
            }
        }

        for (py in support.y1 until support.y2) {
            val rowOffset = py * protoSize
            for (px in support.x1 until support.x2) {
                val prob = 1.0f / (1.0f + kotlin.math.exp(-scratch[rowOffset + px]))
                maskBytes[rowOffset + px] = (prob * 255f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return maskBytes
    }

    /**
     * Production NMS representation. Keep only logits inside the candidate's
     * known proto support so duplicate candidates do not allocate/materialize
     * a full 160x160 soft mask before NMS decides whether they survive.
     * Channel accumulation order remains exactly 0..31 for every pixel.
     */
    fun decodeCandidateLogits(
        cand: RawCandidate,
        inputSize: Int
    ): YoloMaskDecoder.CompactMaskLogits {
        val support = YoloMaskDecoder.candidateProtoSupportRect(cand, inputSize, protoSize)
        val width = support.x2 - support.x1
        val height = support.y2 - support.y1
        val logits = FloatArray(width * height)

        for (c in 0 until channels) {
            val coeff = cand.maskCoeffs[c]
            val channelBase = c * protoPixels
            for (py in support.y1 until support.y2) {
                var srcIndex = channelBase + py * protoSize + support.x1
                var dstIndex = (py - support.y1) * width
                val dstEnd = dstIndex + width
                while (dstIndex < dstEnd) {
                    logits[dstIndex] += coeff * values[srcIndex]
                    dstIndex++
                    srcIndex++
                }
            }
        }
        return YoloMaskDecoder.CompactMaskLogits(support, logits)
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
    internal const val MIN_NONZERO_MASK_LOGIT = -5.537334f

    data class ProtoSupportRect(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int
    )

    data class CompactMaskLogits(
        val support: ProtoSupportRect,
        val logits: FloatArray
    ) {
        val width: Int get() = support.x2 - support.x1
        val height: Int get() = support.y2 - support.y1
    }

    fun candidateProtoSupportRect(
        cand: RawCandidate,
        inputSize: Int = DEFAULT_INPUT_SIZE,
        protoSize: Int = DEFAULT_PROTO_SIZE
    ): ProtoSupportRect {
        val margin = 1
        return ProtoSupportRect(
            x1 = (kotlin.math.floor((cand.x1 / inputSize) * protoSize).toInt() - margin).coerceIn(0, protoSize),
            y1 = (kotlin.math.floor((cand.y1 / inputSize) * protoSize).toInt() - margin).coerceIn(0, protoSize),
            x2 = (kotlin.math.ceil((cand.x2 / inputSize) * protoSize).toInt() + margin).coerceIn(0, protoSize),
            y2 = (kotlin.math.ceil((cand.y2 / inputSize) * protoSize).toInt() + margin).coerceIn(0, protoSize)
        )
    }

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
            val a = maskA[i].toInt()
            val b = maskB[i].toInt()
            if (a != 0 && b != 0) intersection++
            if ((a or b) != 0) union++
        }
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    fun calculateCompactLogitMaskIoU(a: CompactMaskLogits, b: CompactMaskLogits): Float {
        val x1 = minOf(a.support.x1, b.support.x1)
        val y1 = minOf(a.support.y1, b.support.y1)
        val x2 = maxOf(a.support.x2, b.support.x2)
        val y2 = maxOf(a.support.y2, b.support.y2)
        var intersection = 0
        var union = 0

        for (py in y1 until y2) {
            val aRow = if (py >= a.support.y1 && py < a.support.y2) {
                (py - a.support.y1) * a.width
            } else {
                -1
            }
            val bRow = if (py >= b.support.y1 && py < b.support.y2) {
                (py - b.support.y1) * b.width
            } else {
                -1
            }
            for (px in x1 until x2) {
                val activeA = aRow >= 0 && px >= a.support.x1 && px < a.support.x2 &&
                    a.logits[aRow + px - a.support.x1] >= MIN_NONZERO_MASK_LOGIT
                val activeB = bRow >= 0 && px >= b.support.x1 && px < b.support.x2 &&
                    b.logits[bRow + px - b.support.x1] >= MIN_NONZERO_MASK_LOGIT
                if (activeA && activeB) intersection++
                if (activeA || activeB) union++
            }
        }
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    fun materializeCompactMask(
        compact: CompactMaskLogits,
        protoSize: Int = DEFAULT_PROTO_SIZE
    ): ByteArray {
        val maskBytes = ByteArray(protoSize * protoSize)
        var srcIndex = 0
        for (py in compact.support.y1 until compact.support.y2) {
            var dstIndex = py * protoSize + compact.support.x1
            val rowEnd = dstIndex + compact.width
            while (dstIndex < rowEnd) {
                val prob = 1.0f / (1.0f + kotlin.math.exp(-compact.logits[srcIndex]))
                maskBytes[dstIndex] = (prob * 255f).toInt().coerceIn(0, 255).toByte()
                srcIndex++
                dstIndex++
            }
        }
        return maskBytes
    }

    fun calculateMaskIoUWithinCandidateSupport(
        maskA: ByteArray,
        maskB: ByteArray,
        candA: RawCandidate,
        candB: RawCandidate,
        inputSize: Int = DEFAULT_INPUT_SIZE,
        protoSize: Int = DEFAULT_PROTO_SIZE
    ): Float {
        val requiredSize = protoSize * protoSize
        if (maskA.size < requiredSize || maskB.size < requiredSize) {
            return calculateMaskIoU(maskA, maskB)
        }

        val supportA = candidateProtoSupportRect(candA, inputSize, protoSize)
        val supportB = candidateProtoSupportRect(candB, inputSize, protoSize)
        val x1 = minOf(supportA.x1, supportB.x1)
        val y1 = minOf(supportA.y1, supportB.y1)
        val x2 = maxOf(supportA.x2, supportB.x2)
        val y2 = maxOf(supportA.y2, supportB.y2)

        var intersection = 0
        var union = 0
        for (py in y1 until y2) {
            var index = py * protoSize + x1
            val rowEnd = py * protoSize + x2
            while (index < rowEnd) {
                val a = maskA[index].toInt()
                val b = maskB[index].toInt()
                if (a != 0 && b != 0) intersection++
                if ((a or b) != 0) union++
                index++
            }
        }
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    class CandidateMaskCache(
        private val protoView: ProtoTensorView?,
        private val inputSize: Int = DEFAULT_INPUT_SIZE,
        private val protoSize: Int = DEFAULT_PROTO_SIZE,
        private val timings: MaskAwareNmsTimings? = null
    ) {
        // Candidates are immutable objects created once per frame and the NMS/
        // final decode paths reuse the same instances. Identity keys avoid
        // hashing all 32 mask coefficients on every cache lookup.
        private val cache = IdentityHashMap<RawCandidate, ByteArray>()
        private val compactCache = IdentityHashMap<RawCandidate, CompactMaskLogits>()

        private fun getCompactLogits(cand: RawCandidate): CompactMaskLogits? {
            if (cand.syntheticMask != null || protoView !is NchwArrayProtoView) return null
            val cached = compactCache[cand]
            if (cached != null) return cached
            val startNs = System.nanoTime()
            val decoded = protoView.decodeCandidateLogits(cand, inputSize)
            val elapsedNs = System.nanoTime() - startNs
            if (timings != null) {
                timings.maskDecodeNs += elapsedNs
                timings.maskLogitDecodeNs += elapsedNs
            }
            compactCache[cand] = decoded
            return decoded
        }

        fun getMask(cand: RawCandidate): ByteArray {
            val cached = cache[cand]
            if (cached != null) return cached
            val compact = getCompactLogits(cand)
            if (compact != null) {
                val startNs = System.nanoTime()
                val materialized = materializeCompactMask(compact, protoSize)
                val elapsedNs = System.nanoTime() - startNs
                if (timings != null) {
                    timings.maskDecodeNs += elapsedNs
                    timings.maskSoftMaterializeNs += elapsedNs
                }
                cache[cand] = materialized
                return materialized
            }
            val startNs = System.nanoTime()
            val decoded = decodeCandidateMask(cand, protoView, inputSize, protoSize)
            if (timings != null) {
                timings.maskDecodeNs += System.nanoTime() - startNs
            }
            return decoded.also {
                cache[cand] = it
            }
        }

        fun calculateCandidateMaskIoU(a: RawCandidate, b: RawCandidate): Float {
            val compactA = getCompactLogits(a)
            val compactB = getCompactLogits(b)
            val startNs = System.nanoTime()
            val result = if (compactA != null && compactB != null) {
                calculateCompactLogitMaskIoU(compactA, compactB)
            } else {
                val maskA = getMask(a)
                val maskB = getMask(b)
                if (protoView != null && a.syntheticMask == null && b.syntheticMask == null) {
                    calculateMaskIoUWithinCandidateSupport(maskA, maskB, a, b, inputSize, protoSize)
                } else {
                    calculateMaskIoU(maskA, maskB)
                }
            }
            if (timings != null) {
                timings.maskIouNs += System.nanoTime() - startNs
            }
            return result
        }
    }

    data class MaskAwareNmsTimings(
        var maskDecodeNs: Long = 0L,
        var maskLogitDecodeNs: Long = 0L,
        var maskSoftMaterializeNs: Long = 0L,
        var maskIouNs: Long = 0L
    )

    fun maskAwareNms(
        candidates: List<RawCandidate>,
        protoView: ProtoTensorView?,
        bboxIouThreshold: Float = 0.50f,
        maskIouThreshold: Float = DEFAULT_MASK_IOU_THRESHOLD,
        inputSize: Int = DEFAULT_INPUT_SIZE,
        protoSize: Int = DEFAULT_PROTO_SIZE,
        timings: MaskAwareNmsTimings? = null
    ): Pair<List<RawCandidate>, CandidateMaskCache> {
        val sorted = candidates.sortedByDescending { it.confidence }
        val selected = ArrayList<RawCandidate>(sorted.size)
        val suppressed = BooleanArray(sorted.size)
        val cache = CandidateMaskCache(protoView, inputSize, protoSize, timings)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val current = sorted[i]
            selected.add(current)

            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val next = sorted[j]
                val bboxIoU = calculateBboxIoU(current, next)
                if (bboxIoU > bboxIouThreshold) {
                    val maskIoU = cache.calculateCandidateMaskIoU(current, next)

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

package art.gaoge.dance.engine.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YoloRawTopSupportProbeTest {
    private val protoSize = 16
    private val inputSize = 640
    private val candidate = RawCandidate(
        x1 = 160f, y1 = 160f, x2 = 480f, y2 = 480f,
        confidence = 0.8f, maskCoeffs = FloatArray(32)
    )

    private fun probe(logitAt: (Int, Int) -> Float): Pair<ByteArray, YoloRawTopSupportAudit> {
        val proto = object : ProtoTensorView {
            override fun getDotProduct(px: Int, py: Int, coeffs: FloatArray): Float = logitAt(px, py)
        }
        val decoded = YoloMaskDecoder.decodeCandidateMask(candidate, proto, inputSize, protoSize)
        val audit = YoloRawTopSupportProbe.summarize(
            candidate, proto, decoded, inputSize, protoSize
        ) ?: error("valid candidate must produce an audit")
        return decoded to audit
    }

    @Test
    fun positivePrototypeAboveBoundingBoxIsClippedFromActualInstanceMask() {
        val (decoded, audit) = probe { _, y -> if (y < 5) 8f else -8f }
        // A 160..480 model box maps to [3,13) including the decoder's 1px margin.
        assertEquals(30, audit.outsideSampled)
        assertEquals(30, audit.outsideGe128)
        assertEquals(30, audit.outsideGe192)
        assertEquals(30, audit.outsideGe224)
        assertEquals(100, audit.insideSampled)
        assertEquals(20, audit.insideDecodedGe128)
        assertEquals(12, audit.bandHeightProto)
        for (y in 0 until 3) {
            for (x in 3 until 13) {
                assertEquals(0, decoded[y * protoSize + x].toInt() and 0xFF)
            }
        }
    }

    @Test
    fun absentPrototypeAboveBoxIsDistinguishedFromDecodeClipping() {
        val (_, audit) = probe { _, y -> if (y >= 3) 8f else -8f }
        assertEquals(30, audit.outsideSampled)
        assertEquals(0, audit.outsideGe128)
        assertEquals(0, audit.outsideGe192)
        assertEquals(0, audit.outsideGe224)
        assertEquals(100, audit.insideDecodedGe128)
    }

    @Test
    fun positiveOutsideResponseIsNotProofOfWhichOverlappingPersonOwnsIt() {
        // An overlapping protected person could produce the very same prototype
        // response. The probe returns SCALARS only; no newly unmasked pixels.
        val (decoded, audit) = probe { _, y -> if (y < 3) 8f else -8f }
        assertTrue(audit.outsideGe224 > 0)
        assertTrue(decoded.all { (it.toInt() and 0xFF) < 128 })
        assertEquals(
            setOf("outside_sampled", "outside_ge128", "outside_ge192",
                "outside_ge224", "inside_sampled", "inside_decoded_ge128",
                "band_height_proto"),
            audit.scalarFields().keys
        )
    }

    @Test
    fun successiveBoundingBoxTopContractionCanEraseUnchangedRawPrototype() {
        val proto = object : ProtoTensorView {
            override fun getDotProduct(px: Int, py: Int, coeffs: FloatArray): Float =
                if (py in 3..8) 8f else -8f
        }
        val contracted = candidate.copy(y1 = 320f)
        val oldMask = YoloMaskDecoder.decodeCandidateMask(candidate, proto, inputSize, protoSize)
        val newMask = YoloMaskDecoder.decodeCandidateMask(contracted, proto, inputSize, protoSize)
        val lostPixel = 5 * protoSize + 8
        assertTrue((oldMask[lostPixel].toInt() and 0xFF) >= 224)
        assertEquals(0, newMask[lostPixel].toInt() and 0xFF)
        val audit = YoloRawTopSupportProbe.summarize(
            contracted, proto, newMask, inputSize, protoSize
        ) ?: error("contracted candidate should still have support")
        assertTrue(audit.outsideGe224 > 0)
        // Neither track continuity nor this scalar probe restores the lost pixel.
        assertEquals(0, newMask[lostPixel].toInt() and 0xFF)
    }

    @Test
    fun sourceTopBoundaryCannotBeSampledOutsideTheImage() {
        val atTop = candidate.copy(y1 = 0f)
        val proto = object : ProtoTensorView {
            override fun getDotProduct(px: Int, py: Int, coeffs: FloatArray) = 8f
        }
        val decoded = YoloMaskDecoder.decodeCandidateMask(atTop, proto, inputSize, protoSize)
        val audit = YoloRawTopSupportProbe.summarize(atTop, proto, decoded, inputSize, protoSize)
            ?: error("edge candidate should be valid")
        assertEquals(0, audit.outsideSampled)
        assertEquals(0, audit.outsideGe128)
        assertTrue(audit.insideSampled > 0)
    }

    @Test
    fun invalidAndSyntheticMasksNeverBecomeRawModelEvidence() {
        val proto = object : ProtoTensorView {
            override fun getDotProduct(px: Int, py: Int, coeffs: FloatArray) = 8f
        }
        val decoded = ByteArray(protoSize * protoSize)
        assertNull(YoloRawTopSupportProbe.summarize(candidate, proto, ByteArray(1), inputSize, protoSize))
        assertNull(YoloRawTopSupportProbe.summarize(
            candidate.copy(syntheticMask = decoded), proto, decoded, inputSize, protoSize
        ))
        assertNull(YoloRawTopSupportProbe.summarize(candidate, proto, decoded, 0, protoSize))
    }
}

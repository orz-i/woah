package art.gaoge.dance.engine.inference

/**
 * Debug-only scalar counterfactual: was the model's mask-prototype logit still
 * positive immediately ABOVE the candidate support which the decoder clips?
 *
 * This is not a person label or pixel ownership proof: competing instances can
 * light up the same prototype. Never feed these counts to privacy composition.
 */
data class YoloRawTopSupportAudit(
    val outsideSampled: Int,
    val outsideGe128: Int,
    val outsideGe192: Int,
    val outsideGe224: Int,
    val insideSampled: Int,
    val insideDecodedGe128: Int,
    val bandHeightProto: Int
) {
    fun scalarFields(): Map<String, Int> = mapOf(
        "outside_sampled" to outsideSampled,
        "outside_ge128" to outsideGe128,
        "outside_ge192" to outsideGe192,
        "outside_ge224" to outsideGe224,
        "inside_sampled" to insideSampled,
        "inside_decoded_ge128" to insideDecodedGe128,
        "band_height_proto" to bandHeightProto
    )
}

internal object YoloRawTopSupportProbe {
    private const val UPPER_BAND_PROTO = 12

    fun summarize(
        candidate: RawCandidate,
        protoView: ProtoTensorView,
        decodedMask: ByteArray,
        inputSize: Int,
        protoSize: Int
    ): YoloRawTopSupportAudit? {
        if (inputSize <= 0 || protoSize <= 0 ||
            decodedMask.size != protoSize * protoSize ||
            candidate.syntheticMask != null
        ) return null

        val support = YoloMaskDecoder.candidateProtoSupportRect(candidate, inputSize, protoSize)
        if (support.x1 >= support.x2 || support.y1 >= support.y2) return null

        val outsideStartY = (support.y1 - UPPER_BAND_PROTO).coerceAtLeast(0)
        var outsideGe128 = 0
        var outsideGe192 = 0
        var outsideGe224 = 0
        for (y in outsideStartY until support.y1) {
            for (x in support.x1 until support.x2) {
                val response = ExactMaskByteSigmoid.toByteValue(
                    protoView.getDotProduct(x, y, candidate.maskCoeffs)
                )
                if (response >= 128) outsideGe128++
                if (response >= 192) outsideGe192++
                if (response >= 224) outsideGe224++
            }
        }

        val insideEndY = (support.y1 + UPPER_BAND_PROTO).coerceAtMost(support.y2)
        var insideDecodedGe128 = 0
        for (y in support.y1 until insideEndY) {
            for (x in support.x1 until support.x2) {
                if ((decodedMask[y * protoSize + x].toInt() and 0xFF) >= 128) {
                    insideDecodedGe128++
                }
            }
        }
        val columns = support.x2 - support.x1
        return YoloRawTopSupportAudit(
            outsideSampled = columns * (support.y1 - outsideStartY),
            outsideGe128 = outsideGe128,
            outsideGe192 = outsideGe192,
            outsideGe224 = outsideGe224,
            insideSampled = columns * (insideEndY - support.y1),
            insideDecodedGe128 = insideDecodedGe128,
            bandHeightProto = UPPER_BAND_PROTO
        )
    }
}

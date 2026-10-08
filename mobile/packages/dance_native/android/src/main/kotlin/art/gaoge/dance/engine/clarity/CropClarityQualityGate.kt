package art.gaoge.dance.engine.clarity

/** Debug-only, one-shot quality gate. Production output is never changed by this policy. */
object CropClarityQualityGate {
    const val ENABLE_MARKER = "crop_clarity_ab.enable"
    const val MAX_PAIRS = 4

    fun safeScale(requestedScale: Double): Double =
        if (requestedScale.isFinite()) requestedScale.coerceIn(1.0, 2.0) else 1.0

    fun strength(requestedScale: Double): Float =
        ((safeScale(requestedScale) - 1.0) * 0.55).toFloat().coerceIn(0f, 0.55f)

    fun eligible(debug: Boolean, postCrop: Boolean, hasPrivacyTargets: Boolean, requestedScale: Double): Boolean =
        debug && postCrop && hasPrivacyTargets && strength(requestedScale) > 0.001f

    /**
     * First, adjacent middle frames, and one near the estimated end.
     * The metadata duration can round up by 1+ frames (650 planned vs 649
     * decoded on a real device). Leave a bounded 2..6-frame end margin rather
     * than requesting an estimated frame that the decoder may never produce.
     * Never exceed four captures, even for very short clips.
     */
    fun sampleFrames(nominalFrames: Int): Set<Int> {
        val frames = nominalFrames.coerceAtLeast(1)
        val middle = (frames + 1) / 2
        val endMargin = ((frames + 199L) / 200L).coerceIn(2L, 6L).toInt()
        val nearEnd = (frames - endMargin).coerceAtLeast(1)
        return linkedSetOf(1, middle, (middle + 1).coerceAtMost(frames), nearEnd)
    }

    fun missingFrames(expectedFrames: Set<Int>, capturedFrames: Collection<Int>): List<Int> =
        (expectedFrames - capturedFrames.toSet()).sorted()
}

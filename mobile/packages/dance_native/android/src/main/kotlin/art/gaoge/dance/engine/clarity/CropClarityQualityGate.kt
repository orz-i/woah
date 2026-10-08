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

    /** First, adjacent middle frames, last. Never exceed four captures. */
    fun sampleFrames(nominalFrames: Int): Set<Int> {
        val last = nominalFrames.coerceAtLeast(1)
        val middle = (last + 1) / 2
        return linkedSetOf(1, middle, (middle + 1).coerceAtMost(last), last)
    }
}

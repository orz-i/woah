package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.inference.NativeMask
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Very narrow, source-proto foreground-depth correction for a low-confidence
 * conservative-unknown selected instance. The caller must first corroborate a
 * truly observed, stable, UNSELECTED root against the current fresh detection.
 *
 * It NEVER changes identity, privacy class, tracked masks or detection boxes.
 * It only proposes the tightly eroded current foreground interior where both
 * current fresh and observed unselected masks are strong and no other fresh
 * SELECTED detection has even ordinary contour support. Geometry mismatches
 * fail closed and return no carve.
 */
internal object StrongObservedForegroundCarve {
    private const val STRONG_FOREGROUND_BYTE = 224
    private const val SELECTED_CONTOUR_BYTE = 128
    private const val MIN_ERODED_CORE_PIXELS = 9

    internal data class Gate(
        val useFreshClassPrimary: Boolean,
        val targetConservativeUnknown: Boolean,
        val targetResidualTrackIdsEmpty: Boolean,
        val targetConfidence: Float,
        val candidateFreshUnselected: Boolean,
        val candidateConfidence: Float,
        val observedUnselected: Boolean,
        val observedUnselectedAgeFrames: Int,
        val observedUnselectedConfidence: Float,
        val foregroundTrackMatchesCandidate: Boolean,
        val bboxOverlapRatio: Float,
        val normalizedForegroundFootDelta: Float,
        val freshDepthCoreIntersectionPixels: Int
    )

    internal fun eligible(gate: Gate): Boolean =
        gate.useFreshClassPrimary &&
            gate.targetConservativeUnknown &&
            gate.targetResidualTrackIdsEmpty &&
            gate.targetConfidence in 0f..0.45f &&
            gate.candidateFreshUnselected &&
            gate.candidateConfidence in 0.70f..1f &&
            gate.observedUnselected &&
            gate.observedUnselectedAgeFrames >= 15 &&
            gate.observedUnselectedConfidence in 0.70f..1f &&
            gate.foregroundTrackMatchesCandidate &&
            gate.bboxOverlapRatio >= 0.50f &&
            gate.normalizedForegroundFootDelta >= 0.50f &&
            gate.freshDepthCoreIntersectionPixels >= MIN_ERODED_CORE_PIXELS

    internal fun build(
        selected: NativeMask,
        freshForeground: NativeMask,
        observedForeground: NativeMask,
        otherSelected: List<NativeMask>
    ): NativeMask? {
        if (selected.width <= 0 || selected.height <= 0) return null
        val total = selected.width.toLong() * selected.height
        if (total > Int.MAX_VALUE || selected.buffer.capacity().toLong() < total) return null
        fun aligned(mask: NativeMask): Boolean =
            mask.width == selected.width &&
                mask.height == selected.height &&
                mask.originalWidth == selected.originalWidth &&
                mask.originalHeight == selected.originalHeight &&
                mask.mapper == selected.mapper &&
                mask.samplingRect == selected.samplingRect &&
                mask.buffer.capacity().toLong() >= total

        if (!aligned(freshForeground) || !aligned(observedForeground) ||
            otherSelected.any { !aligned(it) }
        ) return null

        val proposed = ByteBuffer.allocateDirect(total.toInt()).order(ByteOrder.nativeOrder())
        var possible = 0
        for (i in 0 until total.toInt()) {
            val selectedPixel = selected.buffer.get(i).toInt() and 0xFF
            val currentPixel = freshForeground.buffer.get(i).toInt() and 0xFF
            val observedPixel = observedForeground.buffer.get(i).toInt() and 0xFF
            val belongsToAnotherSelected = otherSelected.any {
                (it.buffer.get(i).toInt() and 0xFF) >= SELECTED_CONTOUR_BYTE
            }
            val strongCurrentForeground =
                selectedPixel >= SELECTED_CONTOUR_BYTE &&
                    currentPixel >= STRONG_FOREGROUND_BYTE &&
                    observedPixel >= STRONG_FOREGROUND_BYTE &&
                    !belongsToAnotherSelected
            proposed.put(if (strongCurrentForeground) 255.toByte() else 0.toByte())
            if (strongCurrentForeground) possible++
        }
        if (possible < MIN_ERODED_CORE_PIXELS) return null
        proposed.rewind()
        val candidate = selected.copy(buffer = proposed)
        val eroded = MaskPrivacyProcessor.erode(candidate, radius = 1)
        // Preserve the sampling rect after erosion; the morphology helper
        // does not carry this field across every legacy call site.
        val restored = eroded.copy(samplingRect = selected.samplingRect)
        var approved = 0
        for (i in 0 until total.toInt()) {
            if ((restored.buffer.get(i).toInt() and 0xFF) >= SELECTED_CONTOUR_BYTE) {
                approved++
            }
        }
        return if (approved >= MIN_ERODED_CORE_PIXELS) restored else null
    }
}

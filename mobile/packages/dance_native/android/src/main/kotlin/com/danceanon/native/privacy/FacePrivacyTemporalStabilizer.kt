package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import kotlin.math.exp
import kotlin.math.max

/**
 * Smooths FACE_ONLY geometry without carrying stale detector identity evidence.
 *
 * Detector misses still use the current YOLO-owned head center. Only the last
 * trusted *size ratios* are retained so fallback can stay conservative without
 * abruptly jumping to the much larger generic head ellipse.
 */
class FacePrivacyTemporalStabilizer {
    private data class State(
        val output: FacePrivacyEllipse,
        val detectedRadiusXRatio: Float?,
        val detectedRadiusYRatio: Float?,
        val lastPtsUs: Long
    )

    private val stateByTrackId = mutableMapOf<Int, State>()

    fun retainTracks(trackIds: Set<Int>) {
        stateByTrackId.keys.retainAll(trackIds)
    }

    fun stabilize(
        trackId: Int,
        rawRegion: FacePrivacyEllipse,
        personBbox: FloatRect,
        ptsUs: Long
    ): FacePrivacyEllipse {
        if (personBbox.width <= 1f || personBbox.height <= 1f) return rawRegion

        val previous = stateByTrackId[trackId]
        val detectedRadiusXRatio = updateDetectedRatio(
            previous?.detectedRadiusXRatio,
            if (rawRegion.source == FacePrivacyRegionSource.DETECTED_FACE) {
                rawRegion.radiusX / personBbox.width
            } else null
        )
        val detectedRadiusYRatio = updateDetectedRatio(
            previous?.detectedRadiusYRatio,
            if (rawRegion.source == FacePrivacyRegionSource.DETECTED_FACE) {
                rawRegion.radiusY / personBbox.height
            } else null
        )

        val target = if (
            rawRegion.source == FacePrivacyRegionSource.YOLO_HEAD_FALLBACK &&
            detectedRadiusXRatio != null && detectedRadiusYRatio != null
        ) {
            val referenceRadiusX = detectedRadiusXRatio * personBbox.width * FALLBACK_REFERENCE_EXPANSION
            val referenceRadiusY = detectedRadiusYRatio * personBbox.height * FALLBACK_REFERENCE_EXPANSION
            rawRegion.copy(
                radiusX = max(
                    referenceRadiusX,
                    max(
                        personBbox.width * FALLBACK_MIN_RADIUS_X_FROM_WIDTH,
                        personBbox.height * FALLBACK_MIN_RADIUS_X_FROM_HEIGHT
                    )
                ).coerceAtMost(rawRegion.radiusX),
                radiusY = max(
                    referenceRadiusY,
                    max(
                        personBbox.width * FALLBACK_MIN_RADIUS_Y_FROM_WIDTH,
                        personBbox.height * FALLBACK_MIN_RADIUS_Y_FROM_HEIGHT
                    )
                ).coerceAtMost(rawRegion.radiusY)
            )
        } else {
            rawRegion
        }

        val output = if (previous == null || ptsUs < previous.lastPtsUs) {
            target
        } else {
            val dtSeconds = ((ptsUs - previous.lastPtsUs) / 1_000_000.0)
                .coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
            val sizeTau = when (target.source) {
                FacePrivacyRegionSource.DETECTED_FACE -> {
                    if (target.radiusX < previous.output.radiusX || target.radiusY < previous.output.radiusY) {
                        DETECTED_SHRINK_TIME_CONSTANT_SECONDS
                    } else {
                        DETECTED_GROW_TIME_CONSTANT_SECONDS
                    }
                }
                FacePrivacyRegionSource.PREDICTED_FACE -> PREDICTED_SIZE_TIME_CONSTANT_SECONDS
                FacePrivacyRegionSource.YOLO_HEAD_FALLBACK -> FALLBACK_SIZE_TIME_CONSTANT_SECONDS
            }
            val sizeAlpha = alpha(dtSeconds, sizeTau)

            val smoothedRadiusX = lerp(previous.output.radiusX, target.radiusX, sizeAlpha)
            val smoothedRadiusY = lerp(previous.output.radiusY, target.radiusY, sizeAlpha)

            // Position is privacy-critical and must follow the newest detector /
            // YOLO-owned head geometry immediately. Only size is stabilized.
            // Center smoothing looked pleasant on static fixtures but created a
            // visible trailing sticker on fast 60 fps dance motion.
            FacePrivacyEllipse(
                centerX = target.centerX,
                centerY = target.centerY,
                radiusX = max(smoothedRadiusX, target.radiusX * PRIVACY_TARGET_FLOOR),
                radiusY = max(smoothedRadiusY, target.radiusY * PRIVACY_TARGET_FLOOR),
                source = target.source
            )
        }

        stateByTrackId[trackId] = State(
            output = output,
            detectedRadiusXRatio = detectedRadiusXRatio,
            detectedRadiusYRatio = detectedRadiusYRatio,
            lastPtsUs = ptsUs
        )
        return output
    }

    private fun updateDetectedRatio(previous: Float?, observed: Float?): Float? {
        if (observed == null || !observed.isFinite() || observed <= 0f) return previous
        return if (previous == null) observed else lerp(previous, observed, DETECTED_REFERENCE_ALPHA)
    }

    private fun alpha(dtSeconds: Double, timeConstantSeconds: Double): Float =
        (1.0 - exp(-dtSeconds / timeConstantSeconds)).toFloat().coerceIn(0f, 1f)

    private fun lerp(a: Float, b: Float, alpha: Float): Float = a + (b - a) * alpha

    companion object {
        private const val FALLBACK_REFERENCE_EXPANSION = 1.45f
        private const val FALLBACK_MIN_RADIUS_X_FROM_WIDTH = 0.26f
        private const val FALLBACK_MIN_RADIUS_X_FROM_HEIGHT = 0.055f
        private const val FALLBACK_MIN_RADIUS_Y_FROM_WIDTH = 0.30f
        private const val FALLBACK_MIN_RADIUS_Y_FROM_HEIGHT = 0.075f

        private const val DETECTED_REFERENCE_ALPHA = 0.25f
        private const val PRIVACY_TARGET_FLOOR = 0.90f
        private const val MIN_DT_SECONDS = 1.0 / 120.0
        private const val MAX_DT_SECONDS = 0.25
        private const val DETECTED_GROW_TIME_CONSTANT_SECONDS = 0.075
        private const val DETECTED_SHRINK_TIME_CONSTANT_SECONDS = 0.24
        private const val PREDICTED_SIZE_TIME_CONSTANT_SECONDS = 0.15
        private const val FALLBACK_SIZE_TIME_CONSTANT_SECONDS = 0.18
    }
}

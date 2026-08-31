package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Smooths FACE_ONLY geometry without carrying stale detector identity evidence.
 *
 * Detector misses still use the current YOLO-owned head center. The last trusted
 * face size is retained in source pixels and may scale only within a narrow
 * short-term range. This prevents an occlusion/merge-expanded person bbox from
 * turning a normal face sticker into a head-and-shoulders sticker.
 */
class FacePrivacyTemporalStabilizer {
    private data class State(
        val output: FacePrivacyEllipse,
        val detectedRadiusX: Float?,
        val detectedRadiusY: Float?,
        val detectedPersonWidth: Float?,
        val detectedPersonHeight: Float?,
        val personBbox: FloatRect,
        val personObservedThisFrame: Boolean,
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
        ptsUs: Long,
        personObservedThisFrame: Boolean = true,
        trustedCurrentPixelCenter: Boolean = false
    ): FacePrivacyEllipse {
        if (personBbox.width <= 1f || personBbox.height <= 1f) return rawRegion

        val previous = stateByTrackId[trackId]
        val detectedRadiusX = updateDetectedReference(
            previous?.detectedRadiusX,
            if (rawRegion.source == FacePrivacyRegionSource.DETECTED_FACE) {
                rawRegion.radiusX
            } else null
        )
        val detectedRadiusY = updateDetectedReference(
            previous?.detectedRadiusY,
            if (rawRegion.source == FacePrivacyRegionSource.DETECTED_FACE) {
                rawRegion.radiusY
            } else null
        )
        val detectedPersonWidth = updateDetectedReference(
            previous?.detectedPersonWidth,
            if (rawRegion.source == FacePrivacyRegionSource.DETECTED_FACE) personBbox.width else null
        )
        val detectedPersonHeight = updateDetectedReference(
            previous?.detectedPersonHeight,
            if (rawRegion.source == FacePrivacyRegionSource.DETECTED_FACE) personBbox.height else null
        )

        val target = if (
            rawRegion.source == FacePrivacyRegionSource.YOLO_HEAD_FALLBACK &&
            detectedRadiusX != null && detectedRadiusY != null &&
            detectedPersonWidth != null && detectedPersonHeight != null
        ) {
            val widthRatio = (personBbox.width / detectedPersonWidth.coerceAtLeast(1f)).coerceAtLeast(0.1f)
            val heightRatio = (personBbox.height / detectedPersonHeight.coerceAtLeast(1f)).coerceAtLeast(0.1f)
            val boundedScale = sqrt(widthRatio * heightRatio)
                .coerceIn(FALLBACK_MIN_TRUSTED_SCALE, FALLBACK_MAX_TRUSTED_SCALE)
            val referenceRadiusX = detectedRadiusX * boundedScale * FALLBACK_REFERENCE_EXPANSION
            val referenceRadiusY = detectedRadiusY * boundedScale * FALLBACK_REFERENCE_EXPANSION
            rawRegion.copy(
                radiusX = max(
                    referenceRadiusX,
                    detectedRadiusX * FALLBACK_MIN_TRUSTED_EXPANSION
                ).coerceAtMost(rawRegion.radiusX),
                radiusY = max(
                    referenceRadiusY,
                    detectedRadiusY * FALLBACK_MIN_TRUSTED_EXPANSION
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

            // Follow whole-person translation immediately, but bound only the
            // *residual* face motion relative to that translation. Real-video
            // telemetry showed 90-197 px one-frame sticker jumps while reliable
            // person-box center motion stayed much smaller. Those jumps are not
            // plausible articulated head motion at 60 fps; they come from
            // DETECTED/PREDICTED/FALLBACK geometry switches or a bad local face
            // candidate. A simple center low-pass caused trailing in earlier
            // tests, so do not smooth normal motion: clamp only an implausible
            // residual and preserve the direction of the newest evidence.
            val rawDtSeconds = (ptsUs - previous.lastPtsUs).coerceAtLeast(0L) / 1_000_000.0
            val personTranslation = PersonBboxMotionEstimator.estimate(
                previous = previous.personBbox,
                current = personBbox
            )
            val rawPersonDx = personTranslation.dx
            val rawPersonDy = personTranslation.dy
            val rawPersonStep = sqrt(rawPersonDx * rawPersonDx + rawPersonDy * rawPersonDy)
            val referenceRadius = max(
                max(previous.output.radiusX, previous.output.radiusY),
                max(target.radiusX, target.radiusY)
            )
            val trustWholePersonStep = personObservedThisFrame && previous.personObservedThisFrame
            val maxUnobservedPersonStep = max(
                POSITION_MIN_UNOBSERVED_PERSON_STEP_PX,
                referenceRadius * POSITION_MAX_UNOBSERVED_PERSON_RADIUS_STEP
            )
            val personStepScale = if (
                !trustWholePersonStep &&
                rawDtSeconds <= POSITION_GATE_MAX_DT_SECONDS &&
                rawPersonStep > maxUnobservedPersonStep &&
                rawPersonStep > 1e-3f
            ) {
                maxUnobservedPersonStep / rawPersonStep
            } else {
                1f
            }
            val personDx = rawPersonDx * personStepScale
            val personDy = rawPersonDy * personStepScale
            val expectedCenterX = previous.output.centerX + personDx
            val expectedCenterY = previous.output.centerY + personDy
            val residualDx = target.centerX - expectedCenterX
            val residualDy = target.centerY - expectedCenterY
            val residualDistance = sqrt(residualDx * residualDx + residualDy * residualDy)
            val maxResidualStep = max(POSITION_MIN_RESIDUAL_STEP_PX, referenceRadius * POSITION_MAX_RADIUS_STEP)
            val clampPosition = !trustedCurrentPixelCenter &&
                rawDtSeconds <= POSITION_GATE_MAX_DT_SECONDS &&
                residualDistance > maxResidualStep && residualDistance > 1e-3f
            val centerScale = if (clampPosition) maxResidualStep / residualDistance else 1f

            FacePrivacyEllipse(
                centerX = if (clampPosition) expectedCenterX + residualDx * centerScale else target.centerX,
                centerY = if (clampPosition) expectedCenterY + residualDy * centerScale else target.centerY,
                radiusX = max(smoothedRadiusX, target.radiusX * PRIVACY_TARGET_FLOOR),
                radiusY = max(smoothedRadiusY, target.radiusY * PRIVACY_TARGET_FLOOR),
                source = target.source
            )
        }

        stateByTrackId[trackId] = State(
            output = output,
            detectedRadiusX = detectedRadiusX,
            detectedRadiusY = detectedRadiusY,
            detectedPersonWidth = detectedPersonWidth,
            detectedPersonHeight = detectedPersonHeight,
            personBbox = personBbox,
            personObservedThisFrame = personObservedThisFrame,
            lastPtsUs = ptsUs
        )
        return output
    }

    private fun updateDetectedReference(previous: Float?, observed: Float?): Float? {
        if (observed == null || !observed.isFinite() || observed <= 0f) return previous
        return if (previous == null) observed else lerp(previous, observed, DETECTED_REFERENCE_ALPHA)
    }

    private fun alpha(dtSeconds: Double, timeConstantSeconds: Double): Float =
        (1.0 - exp(-dtSeconds / timeConstantSeconds)).toFloat().coerceIn(0f, 1f)

    private fun lerp(a: Float, b: Float, alpha: Float): Float = a + (b - a) * alpha

    companion object {
        private const val FALLBACK_REFERENCE_EXPANSION = 1.24f
        private const val FALLBACK_MIN_TRUSTED_EXPANSION = 1.10f
        private const val FALLBACK_MIN_TRUSTED_SCALE = 0.90f
        private const val FALLBACK_MAX_TRUSTED_SCALE = 1.12f

        private const val DETECTED_REFERENCE_ALPHA = 0.25f
        private const val PRIVACY_TARGET_FLOOR = 0.90f
        private const val POSITION_MIN_RESIDUAL_STEP_PX = 10f
        private const val POSITION_MAX_RADIUS_STEP = 0.80f
        private const val POSITION_MIN_UNOBSERVED_PERSON_STEP_PX = 12f
        private const val POSITION_MAX_UNOBSERVED_PERSON_RADIUS_STEP = 0.65f
        private const val POSITION_GATE_MAX_DT_SECONDS = 0.10
        private const val MIN_DT_SECONDS = 1.0 / 120.0
        private const val MAX_DT_SECONDS = 0.25
        private const val DETECTED_GROW_TIME_CONSTANT_SECONDS = 0.075
        private const val DETECTED_SHRINK_TIME_CONSTANT_SECONDS = 0.24
        private const val PREDICTED_SIZE_TIME_CONSTANT_SECONDS = 0.15
        private const val FALLBACK_SIZE_TIME_CONSTANT_SECONDS = 0.18
    }
}

package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import kotlin.math.abs
import kotlin.math.max

data class PersonBboxTranslation(
    val dx: Float,
    val dy: Float
)

/**
 * Extracts short-term whole-person translation from detector boxes without
 * treating one-sided box-shape jitter as physical body motion.
 *
 * A real translation moves both opposite edges of an axis in roughly the same
 * direction and by roughly the same amount. Pose/segmentation coverage changes
 * frequently move only one edge. When opposite edges disagree beyond a bounded
 * fraction of the current box dimension, use the quieter edge instead of the
 * box center (or a permanently chosen top/bottom edge).
 */
object PersonBboxMotionEstimator {
    private const val MIN_EDGE_AGREEMENT_PX = 8f
    // Real-device failures include a ~58 px one-edge jump on a roughly 400 px
    // person box. Keep the agreement window below that shape-jitter regime while
    // still tolerating small edge asymmetry from ordinary detector noise.
    private const val EDGE_AGREEMENT_DIMENSION_RATIO = 0.07f

    fun estimate(previous: FloatRect, current: FloatRect): PersonBboxTranslation {
        val referenceWidth = max((previous.width + current.width) * 0.5f, 1f)
        val referenceHeight = max((previous.height + current.height) * 0.5f, 1f)

        val leftDx = current.left - previous.left
        val rightDx = current.right - previous.right
        val topDy = current.top - previous.top
        val bottomDy = current.bottom - previous.bottom

        return PersonBboxTranslation(
            dx = resolveAxisTranslation(leftDx, rightDx, referenceWidth),
            dy = resolveAxisTranslation(topDy, bottomDy, referenceHeight)
        )
    }

    private fun resolveAxisTranslation(
        firstEdgeDelta: Float,
        secondEdgeDelta: Float,
        referenceDimension: Float
    ): Float {
        val agreementTolerance = max(
            MIN_EDGE_AGREEMENT_PX,
            referenceDimension * EDGE_AGREEMENT_DIMENSION_RATIO
        )
        if (abs(firstEdgeDelta - secondEdgeDelta) <= agreementTolerance) {
            return (firstEdgeDelta + secondEdgeDelta) * 0.5f
        }

        // One edge is changing the detector-box extent more than the other.
        // The quieter edge is the safer short-term translation cue.
        return if (abs(firstEdgeDelta) <= abs(secondEdgeDelta)) {
            firstEdgeDelta
        } else {
            secondEdgeDelta
        }
    }
}

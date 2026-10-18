package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect

/**
 * Three-frame evidence gate for a previously observed, unprotected follow target
 * whose fresh detector box was quarantined by a DIFFERENT occlusion group.
 *
 * TrackManager must first establish that the target is a recently LOST,
 * non-privacy-selected, non-identity-protected follow root and that no group
 * owns its identity. This class never chooses a new identity or touches masks;
 * it only confirms that the same uncommitted detection geometry is uniquely
 * attributable to the existing root over several real detector frames.
 */
internal class FollowReservedLostRecovery {
    data class Evidence(
        val detectionIndex: Int,
        val box: FloatRect,
        val confidence: Float,
        val predictedIoU: Float,
        val lastObservedIoU: Float,
        val maskIoU: Float,
        val strongestOtherTrackIoU: Float,
        val strongestOtherDetectionIoU: Float
    )

    companion object {
        const val MAX_OBSERVATION_AGE_FRAMES = 6
        private const val MIN_CONFIDENCE = 0.70f
        private const val MIN_PREDICTED_IOU = 0.72f
        private const val MIN_LAST_OBSERVED_IOU = 0.70f
        private const val MIN_MASK_IOU = 0.20f
        private const val MAX_OTHER_IOU = 0.20f
        // Detector evidence can remain stable when an unobserved Kalman box
        // drifts. This alternate gate is STRONGER on the observed anchor,
        // mask, confidence and uniqueness; it is not a generic lower IoU.
        private const val ANCHORED_MIN_PREDICTED_IOU = 0.60f
        private const val ANCHORED_MIN_LAST_OBSERVED_IOU = 0.85f
        private const val ANCHORED_MIN_MASK_IOU = 0.55f
        private const val ANCHORED_MIN_CONFIDENCE = 0.80f
        private const val ANCHORED_MAX_OTHER_IOU = 0.15f
        // A confirmed reserved recovery may be continued on the very next
        // observed frame, never from a stale/uncertain candidate.
        private const val CONTINUE_MIN_CONFIDENCE = 0.60f
        private const val CONTINUE_MIN_PREDICTED_IOU = 0.58f
        private const val CONTINUE_MIN_LAST_OBSERVED_IOU = 0.78f
        private const val CONTINUE_MIN_MASK_IOU = 0.40f
        private const val CONTINUE_MAX_OTHER_IOU = 0.15f
        private const val MIN_CONTINUITY_IOU = 0.70f
        private const val MAX_VOTE_GAP_US = 100_000L
        private const val MIN_OBSERVATION_SPAN_US = 50_000L
        private const val MIN_VOTES = 3

        fun isEligibleTarget(
            state: TrackState,
            framesSinceLastObservation: Int,
            hasObservedMask: Boolean,
            identityProtected: Boolean,
            privacySelected: Boolean,
            facePrivacySelected: Boolean,
            belongsToAnyGroup: Boolean
        ): Boolean =
            state == TrackState.LOST &&
                framesSinceLastObservation in 1..MAX_OBSERVATION_AGE_FRAMES &&
                hasObservedMask &&
                !identityProtected && !privacySelected && !facePrivacySelected &&
                !belongsToAnyGroup

        private fun validEvidence(evidence: Evidence): Boolean =
            evidence.box.width.isFinite() && evidence.box.height.isFinite() &&
                evidence.box.width > 0f && evidence.box.height > 0f &&
                evidence.confidence.isFinite() &&
                evidence.predictedIoU.isFinite() && evidence.lastObservedIoU.isFinite() &&
                evidence.maskIoU.isFinite() &&
                evidence.strongestOtherTrackIoU.isFinite() &&
                evidence.strongestOtherDetectionIoU.isFinite()

        private fun competingEvidenceBelow(evidence: Evidence, limit: Float): Boolean =
            evidence.strongestOtherTrackIoU >= 0f &&
                evidence.strongestOtherDetectionIoU >= 0f &&
                evidence.strongestOtherTrackIoU < limit &&
                evidence.strongestOtherDetectionIoU < limit

        fun isStrictEvidence(evidence: Evidence): Boolean {
            if (!validEvidence(evidence)) return false
            val ordinary = evidence.confidence >= MIN_CONFIDENCE &&
                evidence.predictedIoU >= MIN_PREDICTED_IOU &&
                evidence.lastObservedIoU >= MIN_LAST_OBSERVED_IOU &&
                evidence.maskIoU >= MIN_MASK_IOU &&
                competingEvidenceBelow(evidence, MAX_OTHER_IOU)
            val anchored = evidence.confidence >= ANCHORED_MIN_CONFIDENCE &&
                evidence.predictedIoU >= ANCHORED_MIN_PREDICTED_IOU &&
                evidence.lastObservedIoU >= ANCHORED_MIN_LAST_OBSERVED_IOU &&
                evidence.maskIoU >= ANCHORED_MIN_MASK_IOU &&
                competingEvidenceBelow(evidence, ANCHORED_MAX_OTHER_IOU)
            return ordinary || anchored
        }

        fun isRecentVerifiedContinuation(
            lastConfirmedPtsUs: Long,
            ptsUs: Long,
            observedOnPreviousFrame: Boolean
        ): Boolean = observedOnPreviousFrame && lastConfirmedPtsUs >= 0L &&
            ptsUs > lastConfirmedPtsUs &&
            ptsUs - lastConfirmedPtsUs <= MAX_VOTE_GAP_US

        fun isContinuationEvidence(evidence: Evidence): Boolean =
            validEvidence(evidence) &&
                evidence.confidence >= CONTINUE_MIN_CONFIDENCE &&
                evidence.predictedIoU >= CONTINUE_MIN_PREDICTED_IOU &&
                evidence.lastObservedIoU >= CONTINUE_MIN_LAST_OBSERVED_IOU &&
                evidence.maskIoU >= CONTINUE_MIN_MASK_IOU &&
                competingEvidenceBelow(evidence, CONTINUE_MAX_OTHER_IOU)
    }

    private var votes = 0
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var lastBox: FloatRect? = null

    val pendingVotes: Int get() = votes

    fun reset() {
        votes = 0
        firstPtsUs = -1L
        lastPtsUs = -1L
        lastBox = null
    }

    fun observe(evidence: Evidence?, ptsUs: Long): Int? {
        if (evidence == null || !isStrictEvidence(evidence)) {
            reset()
            return null
        }
        val previous = lastBox
        val continuing = previous != null &&
            lastPtsUs >= 0L && ptsUs > lastPtsUs &&
            ptsUs - lastPtsUs <= MAX_VOTE_GAP_US &&
            TrackManager.computeBBoxIoU(previous, evidence.box) >= MIN_CONTINUITY_IOU
        if (continuing) {
            votes++
        } else {
            votes = 1
            firstPtsUs = ptsUs
        }
        lastPtsUs = ptsUs
        lastBox = evidence.box
        if (votes < MIN_VOTES || ptsUs - firstPtsUs < MIN_OBSERVATION_SPAN_US) return null
        val confirmed = evidence.detectionIndex
        reset()
        return confirmed
    }
}

package com.danceanon.native.privacy

enum class FaceOnlyRenderMode {
    DIRECT,
    BODY_MASK_COMPENSATED,
    DORMANT
}

/**
 * FACE_ONLY render-lifecycle policy for a YOLO-owned identity.
 *
 * Identity retention and privacy rendering are deliberately separate. A protected
 * TrackManager identity may remain LOST/REACQUIRING for later same-ID recovery,
 * but a face sticker must not keep following stale predicted geometry for seconds
 * after YOLO stopped observing that person. Short misses are still bridged so a
 * one-frame detector dropout does not flicker privacy.
 */
object FaceOnlyDormancyPolicy {
    const val MAX_DIRECT_UNOBSERVED_AGE_US = 150_000L
    const val MAX_BODY_COMPENSATION_AGE_US = 800_000L

    fun resolveMode(
        observedThisFrame: Boolean,
        lastObservedPtsUs: Long?,
        ptsUs: Long,
        hasTrustedFace: Boolean,
        hasBodyMask: Boolean,
        hasFreshBodyMotionEvidence: Boolean = false
    ): FaceOnlyRenderMode {
        if (observedThisFrame) return FaceOnlyRenderMode.DIRECT
        val lastObserved = lastObservedPtsUs ?: return FaceOnlyRenderMode.DORMANT
        val ageUs = ptsUs - lastObserved
        if (ageUs < 0L) return FaceOnlyRenderMode.DORMANT
        if (ageUs <= MAX_DIRECT_UNOBSERVED_AGE_US) return FaceOnlyRenderMode.DIRECT

        // A current-frame reciprocal-best YOLO detection can keep supplying body
        // motion even when strict identity commitment is intentionally deferred.
        // This is not stale prediction: the segmentation is fresh this frame and
        // is allowed to move only an already-trusted face anchor. Identity state
        // remains untouched inside TrackManager.
        if (hasTrustedFace && hasFreshBodyMotionEvidence) {
            return FaceOnlyRenderMode.BODY_MASK_COMPENSATED
        }

        if (ageUs > MAX_BODY_COMPENSATION_AGE_US) return FaceOnlyRenderMode.DORMANT

        // Medium-duration dance-motion gaps are common even while the full-body
        // segmentation/track geometry is still useful. Keep the last trusted
        // face alive only when both pieces of evidence exist. The body mask may
        // move the face geometry, but it never becomes identity evidence and the
        // hard upper age bound prevents the former multi-second floating sticker.
        return if (hasTrustedFace && hasBodyMask) {
            FaceOnlyRenderMode.BODY_MASK_COMPENSATED
        } else {
            FaceOnlyRenderMode.DORMANT
        }
    }
}

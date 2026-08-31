package com.danceanon.native.privacy

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
    const val MAX_UNOBSERVED_RENDER_AGE_US = 150_000L

    fun shouldRender(
        observedThisFrame: Boolean,
        lastObservedPtsUs: Long?,
        ptsUs: Long
    ): Boolean {
        if (observedThisFrame) return true
        val lastObserved = lastObservedPtsUs ?: return false
        val ageUs = ptsUs - lastObserved
        return ageUs in 0L..MAX_UNOBSERVED_RENDER_AGE_US
    }
}

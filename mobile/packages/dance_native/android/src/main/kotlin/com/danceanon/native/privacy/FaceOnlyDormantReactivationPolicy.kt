package com.danceanon.native.privacy

/**
 * Safety boundary for reactivating a dormant FACE_ONLY identity.
 *
 * TrackManager remains the identity authority. Current-frame protected motion
 * evidence may only open an identity-local face-detector probe; it must never
 * directly restore a sticker from a hidden anchor. A successful probe may reuse
 * the hidden anchor's trusted source-space size, but not stale body scale or age
 * expansion.
 */
internal object FaceOnlyDormantReactivationPolicy {
    fun shouldProbe(
        wasDormant: Boolean,
        observedThisFrame: Boolean,
        hasFreshBodyMotion: Boolean,
        hasTrustedFace: Boolean
    ): Boolean =
        wasDormant &&
            !observedThisFrame &&
            hasFreshBodyMotion &&
            hasTrustedFace

    fun preserveTrustedSize(
        detected: FacePrivacyEllipse,
        trustedRadiusX: Float,
        trustedRadiusY: Float
    ): FacePrivacyEllipse {
        if (detected.source != FacePrivacyRegionSource.DETECTED_FACE) return detected
        return detected.copy(
            radiusX = trustedRadiusX.coerceAtLeast(1f),
            radiusY = trustedRadiusY.coerceAtLeast(1f)
        )
    }
}

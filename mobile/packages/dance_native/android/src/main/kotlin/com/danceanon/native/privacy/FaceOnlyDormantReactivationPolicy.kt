package com.danceanon.native.privacy

import kotlin.math.max
import kotlin.math.sqrt

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

    fun isProbeTranslationSafe(
        dx: Float,
        dy: Float,
        trustedRadiusX: Float,
        trustedRadiusY: Float,
        minTranslationPx: Float = 24f,
        maxFaceDiameterTranslation: Float = 0.50f
    ): Boolean {
        if (!dx.isFinite() || !dy.isFinite()) return false
        val trustedDiameter = max(trustedRadiusX, trustedRadiusY).coerceAtLeast(1f) * 2f
        val maxTranslation = max(minTranslationPx, trustedDiameter * maxFaceDiameterTranslation)
        return sqrt(dx * dx + dy * dy) <= maxTranslation
    }
}

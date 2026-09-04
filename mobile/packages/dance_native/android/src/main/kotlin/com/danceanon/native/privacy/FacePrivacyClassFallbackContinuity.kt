package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect

/**
 * Render-only temporal continuity for anonymous FACE_ONLY class fallbacks.
 *
 * Exact identity remains unresolved. A unique selected residual owner is used
 * only as a stable key for a private geometry state; nothing here is written
 * back to TrackManager or to the normal per-ID face cache.
 */
internal class FacePrivacyClassFallbackContinuity {
    private data class State(
        val output: FacePrivacyEllipse,
        val personBbox: FloatRect
    )

    private val stabilizer = FacePrivacyTemporalStabilizer()
    private val stateByOwnerId = mutableMapOf<Int, State>()

    fun stabilize(
        fallbacks: List<FacePrivacyClassFallback>,
        ptsUs: Long,
        canonicalizeReferenceGeometry: Boolean
    ): List<FacePrivacyClassFallback> {
        val uniqueOwnerIds = fallbacks
            .mapNotNull { it.residualTrackIds.singleOrNull() }
            .toSet()
        stabilizer.retainTracks(uniqueOwnerIds)
        stateByOwnerId.keys.retainAll(uniqueOwnerIds)

        return fallbacks.map { fallback ->
            val ownerId = fallback.residualTrackIds.singleOrNull() ?: return@map fallback
            val previous = stateByOwnerId[ownerId]
            val strengthAwareRegion = if (!fallback.bodyMaskGuided && previous != null) {
                // When the current mask cannot make a local head-like case, the
                // body-proportion head center is precisely the weak measurement
                // that caused 40-67 px guided/raw toggles in device logs. Keep
                // continuity by following only robust whole-person translation
                // from the fresh detection. A later mask-guided head measurement
                // may correct this position through the existing residual gate.
                val translation = PersonBboxMotionEstimator.estimate(
                    previous = previous.personBbox,
                    current = fallback.personBbox
                )
                fallback.region.copy(
                    centerX = previous.output.centerX + translation.dx,
                    centerY = previous.output.centerY + translation.dy
                )
            } else {
                fallback.region
            }
            fallback.copy(
                region = stabilizer.stabilize(
                    trackId = ownerId,
                    rawRegion = strengthAwareRegion,
                    personBbox = fallback.personBbox,
                    ptsUs = ptsUs,
                    personObservedThisFrame = true,
                    // Even mask-guided class evidence is weaker than an exact
                    // current face-pixel match. Keep the existing residual gate
                    // active so guided/raw availability changes cannot jump.
                    trustedCurrentPixelCenter = false,
                    canonicalizeReferenceGeometry = canonicalizeReferenceGeometry
                )
            ).also { resolved ->
                stateByOwnerId[ownerId] = State(
                    output = resolved.region,
                    personBbox = fallback.personBbox
                )
            }
        }
    }
}

package art.gaoge.dance.engine.privacy

/** Anonymous render history is isolated from identity and ordinary per-ID state. */
internal class FacePrivacyClassFallbackContinuity {
    private val stabilizer = FacePrivacyTemporalStabilizer()
    fun stabilize(fallbacks: List<FacePrivacyClassFallback>, ptsUs: Long,
        canonicalizeReferenceGeometry: Boolean): List<FacePrivacyClassFallback> {
        stabilizer.retainTracks(fallbacks.mapNotNull { it.residualTrackIds.singleOrNull() }.toSet())
        return fallbacks.map { fallback ->
            val owner = fallback.residualTrackIds.singleOrNull() ?: return@map fallback
            fallback.copy(region = stabilizer.stabilize(owner, fallback.region, fallback.personBbox, ptsUs,
                canonicalizeReferenceGeometry = canonicalizeReferenceGeometry,
                translationOnly = !fallback.bodyMaskGuided))
        }
    }
}

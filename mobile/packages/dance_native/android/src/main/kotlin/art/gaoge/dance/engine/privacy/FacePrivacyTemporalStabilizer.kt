package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect

/** Per-key transport/lifetime only. Temporal geometry and references live in Rust. */
class FacePrivacyTemporalStabilizer {
    private val states = mutableMapOf<Int, FrameKernel.RegionState>()
    fun retainTracks(trackIds: Set<Int>) { states.keys.retainAll(trackIds) }
    fun stabilize(
        trackId: Int, rawRegion: FacePrivacyEllipse, personBbox: FloatRect, ptsUs: Long,
        personObservedThisFrame: Boolean = true, trustedCurrentPixelCenter: Boolean = false,
        canonicalizeReferenceGeometry: Boolean = false, translationOnly: Boolean = false
    ): FacePrivacyEllipse {
        val kind = when (rawRegion.source) {
            FacePrivacyRegionSource.DETECTED_FACE -> 0
            FacePrivacyRegionSource.PREDICTED_FACE -> 1
            FacePrivacyRegionSource.YOLO_HEAD_FALLBACK -> 2
        }
        val result = states.getOrPut(trackId) { FrameKernel.RegionState() }.step(
            floatArrayOf(rawRegion.centerX, rawRegion.centerY, rawRegion.radiusX, rawRegion.radiusY),
            personBbox, ptsUs, kind, observed = personObservedThisFrame,
            trustedCenter = trustedCurrentPixelCenter, canonical = canonicalizeReferenceGeometry,
            translationOnly = translationOnly, coverCurrent = kind == 0)
        return rawRegion.copy(centerX = result[0], centerY = result[1], radiusX = result[2], radiusY = result[3])
    }
}

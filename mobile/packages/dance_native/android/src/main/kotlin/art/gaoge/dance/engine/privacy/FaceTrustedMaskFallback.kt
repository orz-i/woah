package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask

internal data class FacePrivacyTrustedGeometry(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val trustedPersonBbox: FloatRect
)

/**
 * Uses an old trusted face only as a local seed while current body-mask pixels
 * remain the actual geometric evidence. The stale face center is never rendered
 * directly: if the current segmentation cannot find a head-like local silhouette,
 * this helper returns null and the caller falls back to its existing policy.
 */
internal object FaceTrustedMaskFallback {
    fun resolve(
        mask: NativeMask?, currentPersonBbox: FloatRect,
        trusted: FacePrivacyTrustedGeometry, radiusExpansion: Float
    ): FacePrivacyEllipse? = FrameKernelFaceGeometry.trusted(mask, currentPersonBbox, trusted, radiusExpansion)
}

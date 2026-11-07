package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.face.FacePoint
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask

/**
 * Uses the already-owned YOLO person segmentation as local geometric evidence
 * for FACE_ONLY fallback. It never assigns identity and never renders the body
 * mask itself; it only nudges a face/head seed toward current foreground pixels.
 *
 * The search is intentionally local around the existing face seed. This avoids
 * raised hands/arms or neighboring silhouettes stealing the fallback center while
 * still allowing the head to move inside a comparatively stable person bbox.
 */
object BodyMaskFaceHeadEstimator {
    fun estimate(
        mask: NativeMask?, personBbox: FloatRect,
        seedCenterX: Float, seedCenterY: Float, seedRadiusX: Float, seedRadiusY: Float
    ): FacePoint? = FrameKernelFaceGeometry.estimate(mask, personBbox, seedCenterX, seedCenterY, seedRadiusX, seedRadiusY)
}

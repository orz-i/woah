package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.face.FaceHeadRoiPlan
import art.gaoge.dance.engine.face.FaceRoiCandidateSelection
import art.gaoge.dance.engine.inference.FloatRect

enum class FacePrivacyRegionSource {
    DETECTED_FACE,
    PREDICTED_FACE,
    YOLO_HEAD_FALLBACK
}

data class FacePrivacyEllipse(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val source: FacePrivacyRegionSource
)

/**
 * Converts face-location evidence into a conservative source-space privacy region.
 *
 * The face detector never owns identity. A detected region is only accepted after
 * FaceRoiCandidateSelector has already attached it to a YOLO person. If that
 * selection is absent (miss or ambiguity), privacy falls back to a YOLO-derived
 * head ellipse rather than disappearing.
 */
object FacePrivacyRegionResolver {
    fun resolve(
        personBbox: FloatRect,
        roiPlan: FaceHeadRoiPlan?,
        selectedFace: FaceRoiCandidateSelection?
    ): FacePrivacyEllipse? {
        return FrameKernelFaceGeometry.resolve(personBbox, roiPlan, selectedFace)
    }
}

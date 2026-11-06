package art.gaoge.dance.engine.inference

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.core.FrameKernel
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Extra detail for crowded, fully protected scenes; never infers a user's exemptions. */
internal object HighResolutionPrivacyCoverage {
    fun hasDetailResolution(width: Int, height: Int): Boolean = max(width, height) >= 1920 && min(width, height) >= 1080

    fun applies(
        sourceWidth: Int,
        sourceHeight: Int,
        initialIds: Set<Int>,
        bodyIds: Set<Int>,
        faceIds: Set<Int>,
        followsPerson: Boolean
    ): Boolean = hasDetailResolution(sourceWidth, sourceHeight) && initialIds.size > 1 && !followsPerson &&
        (FrameKernel.sceneScope(initialIds, bodyIds, faceIds, null) and 3) != 0

    fun views(width: Int, height: Int): List<FloatRect> {
        return FrameKernel.detailViews(width, height)
    }

    // Inference readback is converted from bottom-up rows. Select a visual
    // top-origin crop in the corresponding bottom-origin texture coordinates.
    fun textureWindow(view: FloatRect, width: Int, height: Int): FloatRect = FloatRect(
        view.left / width, 1f - view.bottom / height, view.right / width, 1f - view.top / height
    )

    /** Cropped foreground people must not become extra anonymous heads at a tile boundary. */
    fun isComplete(detection: PersonDetection, view: FloatRect, width: Int, height: Int): Boolean {
        return FrameKernel.detailComplete(detection.bbox, view, width, height)
    }

    /** Reproject each local mask into the canonical full-scene proto space before tracking. */
    fun toScene(detection: PersonDetection, view: FloatRect, mapper: ModelCoordinateMapper): PersonDetection {
        val localMask = detection.mask
        val mask = localMask?.let {
            val local = requireNotNull(it.mapper)
            val pixels = ByteBuffer.allocateDirect(mapper.protoSize * mapper.protoSize)
            FrameKernel.reprojectMask(it.buffer, pixels, local, mapper, view)
            NativeMask(mapper.protoSize, mapper.protoSize, pixels, mapper.srcWidth, mapper.srcHeight, mapper)
        }
        val box = detection.bbox.offset(view.left, view.top)
        return detection.copy(bbox = box, footY = box.bottom, mask = mask)
    }

    /** Keep the stable whole-frame measurements; add only previously uncovered people. */
    fun merge(primary: List<PersonDetection>, extra: List<PersonDetection>): List<PersonDetection> {
        val merged = primary.toMutableList()
        for (candidate in extra.sortedByDescending { it.confidence }) {
            if (merged.none { intersectionOverUnion(it.bbox, candidate.bbox) >= 0.45f }) merged += candidate
        }
        return merged.sortedBy { it.bbox.centerX }
    }

    class FaceObservations {
        private var nextId = -2_000_000

        /** All people are protected. Fresh IDs prevent even a short ambiguous
         * body association from borrowing a neighbor's face/ROI cache.
         * ACTIVE still permits actual face localization on every observation.
         */
        fun current(detections: List<PersonDetection>, heroId: Int? = null, observedHero: FloatRect? = null): List<art.gaoge.dance.engine.tracking.TrackedPerson> {
            val heroIndex = FrameKernel.currentHeroIndex(detections.map { it.bbox }, if (heroId != null) observedHero else null)
            return detections.mapIndexed { index, detection ->
                if (nextId == Int.MIN_VALUE) nextId = -2_000_000
                if (nextId == heroId) nextId--
                art.gaoge.dance.engine.tracking.TrackedPerson(
                    id = if (index == heroIndex) requireNotNull(heroId) else nextId--,
                    bbox = detection.bbox, mask = detection.mask, confidence = detection.confidence,
                    state = art.gaoge.dance.engine.tracking.TrackState.ACTIVE, observedThisFrame = true, footY = detection.footY
                )
            }
        }
    }

    /** These IDs may omit a guessed head, but never a current detected face. */
    fun coveredHeadFallbackIds(persons: List<art.gaoge.dance.engine.tracking.TrackedPerson>): Set<Int> {
        val detections = persons.map { PersonDetection(it.bbox, it.confidence, it.mask, it.footY ?: it.bbox.bottom) }
        return detections.indices.filter { i ->
            detections.indices.any { j -> i != j && isHeadFullyOccluded(detections[i], detections[j]) }
        }.map { persons[it].id }.toSet()
    }

    private fun isHeadFullyOccluded(candidate: PersonDetection, other: PersonDetection): Boolean {
        if (other.bbox.width * other.bbox.height < candidate.bbox.width * candidate.bbox.height * 2f) return false
        val mask = candidate.mask ?: return false
        val occluder = other.mask ?: return false
        val mapper = mask.mapper ?: return false
        if (mask.width != occluder.width || mask.height != occluder.height ||
            mask.originalWidth != occluder.originalWidth || mask.originalHeight != occluder.originalHeight) return false
        val head = art.gaoge.dance.engine.privacy.FacePrivacyRegionResolver.resolve(candidate.bbox, null, null)
            ?: return false
        val cx = mapper.sourceToProtoX(head.centerX)
        val cy = mapper.sourceToProtoY(head.centerY)
        val rx = (mapper.sourceToProtoX(head.centerX + head.radiusX) - cx) * 0.6f
        val ry = (mapper.sourceToProtoY(head.centerY + head.radiusY) - cy) * 0.6f
        if (rx <= 0 || ry <= 0) return false
        var samples = 0
        for (y in floor(cy - ry).toInt().coerceAtLeast(0) until ceil(cy + ry).toInt().coerceAtMost(mask.height)) {
            for (x in floor(cx - rx).toInt().coerceAtLeast(0) until ceil(cx + rx).toInt().coerceAtMost(mask.width)) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                if (dx * dx + dy * dy > 1f) continue
                val index = y * mask.width + x
                // This is only a fallback hint: segmentation does not own faces.
                // The face processor still accepts current detected/pixel evidence.
                if ((occluder.buffer.get(index).toInt() and 255) < 128) return false
                samples++
            }
        }
        return samples >= 4
    }

    private fun intersectionOverUnion(a: FloatRect, b: FloatRect): Float {
        return FrameKernel.iou(a, b)
    }
}

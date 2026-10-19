package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.tracking.TrackedPerson

/**
 * Debug-only caller's source-space evidence for the exact privacy mask submitted
 * to the GL compositor. No readback, image/video capture, or identity transfer.
 *
 * Do not interpret a nonzero mask as proof of protected output pixels: shader
 * execution, image visibility and the final encoder must be verified separately.
 */
object PrivacyMaskUploadAudit {
    private const val FULL_BODY_TIGHT_MASK_THRESHOLD = 128

    fun shouldSampleEmptyInferenceStreak(streak: Int): Boolean =
        streak in 1..12 || (streak > 12 && streak % 30 == 0)

    /** Null means no mask, -1 means malformed mask bytes, >=0 is exact proto count. */
    fun contourPixels(mask: NativeMask?): Int? {
        if (mask == null) return null
        if (mask.width <= 0 || mask.height <= 0) return -1
        val count = mask.width.toLong() * mask.height.toLong()
        if (count > Int.MAX_VALUE || mask.buffer.capacity().toLong() < count) return -1
        var pixels = 0
        for (i in 0 until count.toInt()) {
            if ((mask.buffer.get(i).toInt() and 0xFF) >= FULL_BODY_TIGHT_MASK_THRESHOLD) {
                pixels++
            }
        }
        return pixels
    }

    /**
     * Counts thresholded proto-texel centers that fall inside the source
     * sampling rectangle actually used by GL. This is only source-space
     * support evidence: linear filtering and final video pixels are not read.
     */
    fun sourceSampledContourCenters(mask: NativeMask?, sampling: FloatRect?): Int? {
        if (mask == null) return null
        if (mask.width <= 0 || mask.height <= 0) return -1
        val n = mask.width.toLong() * mask.height.toLong()
        if (n > Int.MAX_VALUE || mask.buffer.capacity().toLong() < n) return -1
        if (sampling == null ||
            !sampling.left.isFinite() || !sampling.right.isFinite() ||
            !sampling.top.isFinite() || !sampling.bottom.isFinite() ||
            sampling.right <= sampling.left || sampling.bottom <= sampling.top
        ) return -1
        var count = 0
        for (y in 0 until mask.height) {
            val py = (y + 0.5f) / mask.height
            if (py < sampling.top || py > sampling.bottom) continue
            for (x in 0 until mask.width) {
                val px = (x + 0.5f) / mask.width
                if (px < sampling.left || px > sampling.right) continue
                if ((mask.buffer.get(y * mask.width + x).toInt() and 0xFF) >=
                    FULL_BODY_TIGHT_MASK_THRESHOLD
                ) count++
            }
        }
        return count
    }

    private fun sourceSamplingRect(mask: NativeMask): FloatRect {
        mask.samplingRect?.let { return it }
        // Mirrors GlRenderer.defaultLetterboxSamplingRect for source content.
        val width = mask.originalWidth.coerceAtLeast(1).toFloat()
        val height = mask.originalHeight.coerceAtLeast(1).toFloat()
        val longest = maxOf(width, height)
        val fractionW = width / longest
        val fractionH = height / longest
        return FloatRect(
            (1f - fractionW) / 2f, (1f - fractionH) / 2f,
            (1f + fractionW) / 2f, (1f + fractionH) / 2f
        )
    }

    fun sourceMaskEvidence(
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        uploadedPrivacyMask: NativeMask?,
        uploadedOccluderMask: NativeMask?,
        sourceWidth: Int,
        sourceHeight: Int,
        renderWidth: Int,
        renderHeight: Int,
        tightMask: Boolean,
        uploadedPrivacySamplingRect: FloatRect? = null,
        uploadedOccluderSamplingRect: FloatRect? = null
    ): Map<String, Any?> {
        val selectedRoots = persons
            .filter { selectedPersonIds.contains(it.id) }
            .sortedBy { it.id }
            .map { track ->
                mapOf(
                    "track_id" to track.id,
                    "state" to track.state.name,
                    "observed" to track.observedThisFrame,
                    "frames_since_last_observation" to track.framesSinceLastObservation,
                    "bbox_source_pixels" to listOf(
                        track.bbox.left, track.bbox.top, track.bbox.right, track.bbox.bottom
                    ),
                    "mask_present" to (track.mask != null),
                    "mask_contour_pixels_ge_128" to contourPixels(track.mask),
                    "mask_source_sampling_center_pixels_ge_128" to
                        sourceSampledContourCenters(
                            track.mask, track.mask?.let(::sourceSamplingRect)
                        )
                )
            }
        return mapOf(
            "source_width" to sourceWidth,
            "source_height" to sourceHeight,
            "render_width" to renderWidth,
            "render_height" to renderHeight,
            "tight_mask" to tightMask,
            "selected_root_count" to selectedPersonIds.size,
            "selected_root_masks" to selectedRoots,
            "uploaded_privacy_mask_present" to (uploadedPrivacyMask != null),
            "uploaded_privacy_mask_contour_pixels_ge_128" to contourPixels(uploadedPrivacyMask),
            "uploaded_privacy_source_sampling_center_pixels_ge_128" to
                sourceSampledContourCenters(
                    uploadedPrivacyMask,
                    uploadedPrivacySamplingRect ?: uploadedPrivacyMask?.let(::sourceSamplingRect)
                ),
            "uploaded_privacy_mask_size" to uploadedPrivacyMask?.let {
                listOf(it.width, it.height)
            },
            "uploaded_privacy_mask_source_size" to uploadedPrivacyMask?.let {
                listOf(it.originalWidth, it.originalHeight)
            },
            "uploaded_privacy_mask_sampling_rect" to
                (uploadedPrivacySamplingRect ?: uploadedPrivacyMask?.let(::sourceSamplingRect))?.let {
                    listOf(it.left, it.top, it.right, it.bottom)
                },
            "uploaded_occluder_mask_present" to (uploadedOccluderMask != null),
            "uploaded_occluder_mask_contour_pixels_ge_128" to contourPixels(uploadedOccluderMask),
            "uploaded_occluder_source_sampling_center_pixels_ge_128" to
                sourceSampledContourCenters(
                    uploadedOccluderMask,
                    uploadedOccluderSamplingRect ?: uploadedOccluderMask?.let(::sourceSamplingRect)
                ),
            "evidence_scope" to "GL_TEXTURE_INPUT_ONLY_NO_FINAL_PIXEL_READBACK"
        )
    }
}

package com.danceanon.native.privacy

import com.danceanon.native.inference.PersonDetection

/**
 * Unified privacy safety contract for segmentation masks.
 * Ensures uniform dilation and anti-under-anonymization policies across
 * AnalyzePipeline, PreviewPipeline, and ExportPipeline.
 */
interface PrivacySegmentationProcessor {
    fun applyPrivacySafety(detections: List<PersonDetection>): List<PersonDetection>

    companion object {
        val DEFAULT: PrivacySegmentationProcessor = DefaultPrivacySegmentationProcessor(dilationRadius = 1)
    }
}

class DefaultPrivacySegmentationProcessor(
    private val dilationRadius: Int = 1
) : PrivacySegmentationProcessor {

    override fun applyPrivacySafety(detections: List<PersonDetection>): List<PersonDetection> {
        if (detections.isEmpty()) return detections
        return detections.map { det ->
            val origMask = det.mask
            if (origMask != null) {
                val safeMask = MaskPrivacyProcessor.dilate(origMask, radius = dilationRadius)
                det.copy(mask = safeMask)
            } else {
                det
            }
        }
    }
}

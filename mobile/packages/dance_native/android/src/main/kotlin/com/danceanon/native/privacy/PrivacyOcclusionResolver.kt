package com.danceanon.native.privacy

import com.danceanon.native.diagnostics.NativeDiagnostics
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ResolvedCompositorMasks(
    val privacyMask: NativeMask?,
    val occluderMask: NativeMask?,
    val hasPrivacy: Boolean,
    val hasOccluder: Boolean
)

object PrivacyOcclusionResolver {

    /**
     * Resolves selected privacy targets and explicit foreground occluders into
     * an effective, hole-free privacy mask.
     * Enforces:
     * 1. ACTIVE selected targets are NEVER subtracted by unselected persons.
     * 2. OCCLUDED selected targets only subtract freshly observed explicit foreground occluders (occludedByTrackIds).
     * 3. REACQUIRING targets NEVER subtract occluders (stale occluders cleared, privacy wins).
     * 4. Single dilation on selected targets, ZERO dilation on unselected occluders.
     * 5. Multi-selected targets are evaluated per-target, then combined by union.
     */
    fun resolveMasks(
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        applyDilationToPrivacyTargets: Boolean = true,
        dilationRadius: Int = 1,
        ptsUs: Long = 0L
    ): ResolvedCompositorMasks {
        val selectedPersons = persons.filter { selectedPersonIds.contains(it.id) && it.mask != null }
        if (selectedPersons.isEmpty()) {
            return ResolvedCompositorMasks(
                privacyMask = null,
                occluderMask = null,
                hasPrivacy = false,
                hasOccluder = false
            )
        }

        val unselectedPersonsMap = persons.filter { !selectedPersonIds.contains(it.id) && it.mask != null }
            .associateBy { it.id }

        val effectiveSelectedMasks = mutableListOf<NativeMask>()

        for (target in selectedPersons) {
            val rawMask = target.mask ?: continue
            val dilatedMask = if (applyDilationToPrivacyTargets && dilationRadius > 0) {
                MaskPrivacyProcessor.dilate(rawMask, radius = dilationRadius)
            } else {
                rawMask
            }

            val effectiveMask = when (target.state) {
                TrackState.ACTIVE, TrackState.NEW -> {
                    // ACTIVE foreground target: strictly NO subtraction from any unselected person
                    dilatedMask
                }
                TrackState.OCCLUDED -> {
                    // OCCLUDED target: only subtract explicit freshly observed foreground occluders
                    val explicitOccluders = target.occludedByTrackIds.mapNotNull { unselectedPersonsMap[it]?.mask }
                    if (explicitOccluders.isNotEmpty()) {
                        val mergedOccluder = mergeMasks(explicitOccluders)
                        computeEffectivePrivacyMask(dilatedMask, mergedOccluder) ?: dilatedMask
                    } else {
                        dilatedMask
                    }
                }
                TrackState.REACQUIRING -> {
                    // REACQUIRING target: active overlap has ended, strictly NO subtraction to avoid stale hole
                    dilatedMask
                }
                TrackState.LOST, TrackState.REMOVED -> {
                    // LOST track: no subtraction to avoid privacy holes
                    dilatedMask
                }
            }

            // Telemetry & under-coverage verification
            val rawArea = countMaskPixels(rawMask)
            val dilatedArea = countMaskPixels(dilatedMask)
            val effArea = countMaskPixels(effectiveMask)
            val removedArea = (dilatedArea - effArea).coerceAtLeast(0)
            val coverageRatio = if (dilatedArea > 0) effArea.toFloat() / dilatedArea.toFloat() else 1.0f

            if (target.state == TrackState.OCCLUDED && coverageRatio < 0.85f) {
                NativeDiagnostics.event(
                    level = "INFO",
                    component = "PrivacyOcclusionResolver",
                    event = "PRIVACY_COVERAGE_DROP",
                    fields = mapOf(
                        "target_id" to target.id,
                        "state" to target.state.name,
                        "coverage_ratio" to coverageRatio,
                        "raw_area" to rawArea,
                        "dilated_area" to dilatedArea,
                        "effective_area" to effArea,
                        "removed_area" to removedArea,
                        "occluder_ids" to target.occludedByTrackIds.toList(),
                        "pts_us" to ptsUs
                    )
                )
            }

            effectiveSelectedMasks.add(effectiveMask)
        }

        val mergedPrivacy = mergeMasks(effectiveSelectedMasks)

        return ResolvedCompositorMasks(
            privacyMask = mergedPrivacy,
            occluderMask = null,
            hasPrivacy = (mergedPrivacy != null),
            hasOccluder = false
        )
    }

    /**
     * Computes the union (element-wise maximum) of a list of masks.
     */
    fun mergeMasks(masks: List<NativeMask>): NativeMask? {
        if (masks.isEmpty()) return null
        if (masks.size == 1) return masks[0]

        val first = masks[0]
        val totalPixels = first.width * first.height
        val mergedBuf = ByteBuffer.allocateDirect(totalPixels).order(ByteOrder.nativeOrder())
        val tempBytes = ByteArray(totalPixels)

        for (mask in masks) {
            val buf = mask.buffer
            buf.rewind()
            val len = minOf(totalPixels, buf.capacity())
            for (i in 0 until len) {
                val b = buf.get(i)
                if ((b.toInt() and 0xFF) > (tempBytes[i].toInt() and 0xFF)) {
                    tempBytes[i] = b
                }
            }
            buf.rewind()
        }

        mergedBuf.put(tempBytes)
        mergedBuf.rewind()

        return NativeMask(
            width = first.width,
            height = first.height,
            buffer = mergedBuf,
            originalWidth = first.originalWidth,
            originalHeight = first.originalHeight,
            mapper = first.mapper,
            samplingRect = first.samplingRect
        )
    }

    /**
     * Computes the software effective privacy mask:
     * effectivePrivacy = privacyMask * (1.0 - occluderMask)
     */
    fun computeEffectivePrivacyMask(
        privacyMask: NativeMask?,
        occluderMask: NativeMask?
    ): NativeMask? {
        if (privacyMask == null) return null
        if (occluderMask == null) return privacyMask

        val width = privacyMask.width
        val height = privacyMask.height
        val totalPixels = width * height

        val privBuf = privacyMask.buffer
        val occBuf = occluderMask.buffer
        privBuf.rewind()
        occBuf.rewind()

        val dstBuf = ByteBuffer.allocateDirect(totalPixels).order(ByteOrder.nativeOrder())
        val tempBytes = ByteArray(totalPixels)

        for (i in 0 until totalPixels) {
            val pVal = privBuf.get(i).toInt() and 0xFF
            val oVal = if (i < occBuf.capacity()) (occBuf.get(i).toInt() and 0xFF) else 0

            // If occluder is solid foreground (>= 128), subtract it from privacy
            val effectiveVal = if (oVal >= 128) {
                0
            } else {
                pVal
            }
            tempBytes[i] = effectiveVal.toByte()
        }

        privBuf.rewind()
        occBuf.rewind()

        dstBuf.put(tempBytes)
        dstBuf.rewind()

        return NativeMask(
            width = width,
            height = height,
            buffer = dstBuf,
            originalWidth = privacyMask.originalWidth,
            originalHeight = privacyMask.originalHeight,
            mapper = privacyMask.mapper,
            samplingRect = privacyMask.samplingRect
        )
    }

    fun countMaskPixels(mask: NativeMask?): Int {
        if (mask == null) return 0
        val buf = mask.buffer
        buf.rewind()
        var count = 0
        val len = buf.capacity()
        for (i in 0 until len) {
            if ((buf.get(i).toInt() and 0xFF) > 128) {
                count++
            }
        }
        buf.rewind()
        return count
    }
}

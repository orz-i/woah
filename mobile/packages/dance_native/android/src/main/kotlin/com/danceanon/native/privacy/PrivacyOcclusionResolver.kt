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
     * 2. OCCLUDED selected targets only subtract explicit unselected occluders (occludedByTrackIds).
     * 3. Single dilation on selected targets, ZERO dilation on unselected occluders.
     * 4. Multi-selected targets are evaluated per-target, then combined by union.
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
                TrackState.OCCLUDED, TrackState.REACQUIRING -> {
                    // Only subtract explicit unselected occluders
                    val explicitOccluders = target.occludedByTrackIds.mapNotNull { unselectedPersonsMap[it]?.mask }
                    if (explicitOccluders.isNotEmpty()) {
                        val mergedOccluder = mergeMasks(explicitOccluders)
                        computeEffectivePrivacyMask(dilatedMask, mergedOccluder) ?: dilatedMask
                    } else {
                        dilatedMask
                    }
                }
                TrackState.LOST, TrackState.REMOVED -> {
                    // LOST track: no explicit occluder subtraction to avoid under-coverage
                    dilatedMask
                }
            }

            // Telemetry & under-coverage verification
            val rawArea = countMaskPixels(rawMask)
            val dilatedArea = countMaskPixels(dilatedMask)
            val effArea = countMaskPixels(effectiveMask)
            val coverageRatio = if (dilatedArea > 0) effArea.toFloat() / dilatedArea.toFloat() else 1.0f

            if (target.state == TrackState.ACTIVE && coverageRatio < 0.85f) {
                NativeDiagnostics.event(
                    level = "WARN",
                    component = "PrivacyOcclusionResolver",
                    event = "PRIVACY_UNDERCOVERAGE",
                    fields = mapOf(
                        "target_id" to target.id,
                        "state" to target.state.name,
                        "coverage_ratio" to coverageRatio,
                        "raw_area" to rawArea,
                        "dilated_area" to dilatedArea,
                        "effective_area" to effArea,
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

        val totalPixels = privacyMask.width * privacyMask.height
        val pBuf = privacyMask.buffer
        val oBuf = occluderMask.buffer
        pBuf.rewind()
        oBuf.rewind()

        val outBuf = ByteBuffer.allocateDirect(totalPixels).order(ByteOrder.nativeOrder())
        val tempBytes = ByteArray(totalPixels)

        val len = minOf(totalPixels, pBuf.capacity(), oBuf.capacity())
        for (i in 0 until len) {
            val pVal = (pBuf.get(i).toInt() and 0xFF) / 255f
            val oVal = (oBuf.get(i).toInt() and 0xFF) / 255f
            val effective = (pVal * (1.0f - oVal)).coerceIn(0f, 1f)
            tempBytes[i] = (effective * 255f).toInt().coerceIn(0, 255).toByte()
        }

        pBuf.rewind()
        oBuf.rewind()

        outBuf.put(tempBytes)
        outBuf.rewind()

        return NativeMask(
            width = privacyMask.width,
            height = privacyMask.height,
            buffer = outBuf,
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
        val total = mask.width * mask.height
        val len = minOf(total, buf.capacity())
        for (i in 0 until len) {
            if ((buf.get(i).toInt() and 0xFF) > 128) {
                count++
            }
        }
        buf.rewind()
        return count
    }
}

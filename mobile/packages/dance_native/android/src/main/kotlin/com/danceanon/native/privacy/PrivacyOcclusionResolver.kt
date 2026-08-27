package com.danceanon.native.privacy

import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
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
     * Resolves selected privacy targets and unselected visible occluders into
     * two separate merged masks with isolated privacy safety dilation.
     */
    fun resolveMasks(
        persons: List<TrackedPerson>,
        selectedPersonIds: Set<Int>,
        applyDilationToPrivacyTargets: Boolean = true,
        dilationRadius: Int = 1
    ): ResolvedCompositorMasks {
        val selectedPersons = persons.filter { selectedPersonIds.contains(it.id) && it.mask != null }
        val unselectedPersons = persons.filter { !selectedPersonIds.contains(it.id) && it.mask != null }

        if (selectedPersons.isEmpty()) {
            return ResolvedCompositorMasks(
                privacyMask = null,
                occluderMask = null,
                hasPrivacy = false,
                hasOccluder = false
            )
        }

        // Privacy targets receive safety dilation
        val privacyMasks = selectedPersons.mapNotNull { person ->
            val orig = person.mask ?: return@mapNotNull null
            if (applyDilationToPrivacyTargets && dilationRadius > 0) {
                MaskPrivacyProcessor.dilate(orig, radius = dilationRadius)
            } else {
                orig
            }
        }

        // Occluder targets strictly preserve raw organic boundaries without dilation
        val occluderMasks = unselectedPersons.mapNotNull { it.mask }

        val privacyMask = mergeMasks(privacyMasks)
        val occluderMask = mergeMasks(occluderMasks)

        return ResolvedCompositorMasks(
            privacyMask = privacyMask,
            occluderMask = occluderMask,
            hasPrivacy = privacyMask != null,
            hasOccluder = occluderMask != null
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
        for (i in 0 until totalPixels) {
            val pVal = (pBuf.get(i).toInt() and 0xFF) / 255f
            val oVal = if (i < oBuf.capacity()) (oBuf.get(i).toInt() and 0xFF) / 255f else 0f
            val effective = pVal * (1.0f - oVal)
            val byteVal = (effective * 255f).toInt().coerceIn(0, 255).toByte()
            outBuf.put(byteVal)
        }
        pBuf.rewind()
        oBuf.rewind()
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
}

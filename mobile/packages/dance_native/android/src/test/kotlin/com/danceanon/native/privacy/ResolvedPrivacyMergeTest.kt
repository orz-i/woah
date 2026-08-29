package com.danceanon.native.privacy

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.NativeMask
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ResolvedPrivacyMergeTest {
    @Test
    fun `one selected target hole cannot carve another selected target privacy`() {
        // Group A originally owned pixels 0,1,2, but its resolver approved holes
        // at 1 and 2. Group B still owns pixel 1. The globally safe occluder must
        // therefore keep only pixel 2 as a hole.
        val groupA = ResolvedCompositorMasks(
            privacyMask = mask(255, 0, 0, 0),
            occluderMask = mask(0, 255, 255, 0),
            hasPrivacy = true,
            hasOccluder = true
        )
        val groupB = ResolvedCompositorMasks(
            privacyMask = mask(0, 255, 0, 0),
            occluderMask = null,
            hasPrivacy = true,
            hasOccluder = false
        )

        val merged = PrivacyOcclusionResolver.mergeResolvedMasks(listOf(groupA, groupB))
        val privacy = assertNotNull(merged.privacyMask)
        val occluder = assertNotNull(merged.occluderMask)

        assertEquals(listOf(255, 255, 0, 0), bytes(privacy))
        assertEquals(listOf(0, 0, 255, 0), bytes(occluder))

        val shaderEquivalent = assertNotNull(
            PrivacyOcclusionResolver.computeEffectivePrivacyMask(privacy, occluder)
        )
        assertEquals(listOf(255, 255, 0, 0), bytes(shaderEquivalent))
    }

    @Test
    fun `empty resolved list stays empty`() {
        val merged = PrivacyOcclusionResolver.mergeResolvedMasks(emptyList())
        assertNull(merged.privacyMask)
        assertNull(merged.occluderMask)
        assertEquals(false, merged.hasPrivacy)
        assertEquals(false, merged.hasOccluder)
    }

    @Test
    fun `incompatible resolved mask contracts fail closed`() {
        val mapper = ModelCoordinateMapper(100, 100, 640, 160)
        val first = mask(255, 0, 0, 0, mapper = mapper, originalWidth = 100)
        val incompatible = mask(0, 255, 0, 0, mapper = mapper.copy(srcWidth = 120), originalWidth = 120)

        assertFailsWith<IllegalArgumentException> {
            PrivacyOcclusionResolver.mergeResolvedMasks(
                listOf(
                    ResolvedCompositorMasks(first, null, true, false),
                    ResolvedCompositorMasks(incompatible, null, true, false)
                )
            )
        }
    }

    @Test
    fun `inconsistent resolved flags fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            PrivacyOcclusionResolver.mergeResolvedMasks(
                listOf(ResolvedCompositorMasks(mask(255, 0, 0, 0), null, false, false))
            )
        }
    }

    private fun mask(
        vararg values: Int,
        mapper: ModelCoordinateMapper? = null,
        originalWidth: Int = 100
    ): NativeMask {
        val buffer = ByteBuffer.allocateDirect(values.size).order(ByteOrder.nativeOrder())
        values.forEach { buffer.put(it.coerceIn(0, 255).toByte()) }
        buffer.rewind()
        return NativeMask(
            width = values.size,
            height = 1,
            buffer = buffer,
            originalWidth = originalWidth,
            originalHeight = 100,
            mapper = mapper
        )
    }

    private fun bytes(mask: NativeMask): List<Int> =
        (0 until mask.width * mask.height).map { mask.buffer.get(it).toInt() and 0xFF }
}

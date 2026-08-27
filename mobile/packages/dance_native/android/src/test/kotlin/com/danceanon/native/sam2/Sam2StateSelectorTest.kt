package com.danceanon.native.sam2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Sam2StateSelectorTest {

    @Test
    fun testSelectionParitySequential() {
        val state = Sam2VideoState(objectId = 1)
        val dummyMem = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        val dummyPos = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        val dummyPtr = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)
        val bundle = Sam2LiteRtStaticStateBundle()

        // Frame 0: conditioning frame
        state.addConditioningFrame(0, dummyMem, dummyPos, dummyPtr)

        // Frame 1
        val sel1 = Sam2StateSelector.selectForFrame(state, 1, 40, bundle)
        assertEquals(listOf(0), sel1.memoryFrameIndices)
        assertEquals(1, sel1.memoryCount)
        assertEquals(6L, sel1.paddedTPosIndices[0])
        assertEquals(listOf(0), sel1.objPtrFrameIndices)
        assertEquals(4, sel1.numObjPtrTokens)

        // Sequentially simulate frames 1 to 6
        for (f in 1..6) {
            state.addNonConditioningFrame(f, dummyMem, dummyPos, dummyPtr)
        }

        // Frame 7: exactly 7 memories (Frame 0 + Frames 1..6)
        val sel7 = Sam2StateSelector.selectForFrame(state, 7, 40, bundle)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), sel7.memoryFrameIndices)
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L, 0L), sel7.paddedTPosIndices.toList())
        assertEquals(listOf(0, 6, 5, 4, 3, 2, 1), sel7.objPtrFrameIndices)
        assertEquals(28, sel7.numObjPtrTokens)

        // Frame 8 (after adding frame 7)
        state.addNonConditioningFrame(7, dummyMem, dummyPos, dummyPtr)
        val sel8 = Sam2StateSelector.selectForFrame(state, 8, 40, bundle)
        assertEquals(listOf(0, 2, 3, 4, 5, 6, 7), sel8.memoryFrameIndices)
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L, 0L), sel8.paddedTPosIndices.toList())
        assertEquals(listOf(0, 7, 6, 5, 4, 3, 2, 1), sel8.objPtrFrameIndices)
        assertEquals(32, sel8.numObjPtrTokens)

        // Advance to frame 19
        for (f in 8..19) {
            state.addNonConditioningFrame(f, dummyMem, dummyPos, dummyPtr)
        }

        // Frame 20: 7 memories (Frame 0 + Frames 14..19), 16 ptrs (Frame 0 + Frames 19 down to 5)
        val sel20 = Sam2StateSelector.selectForFrame(state, 20, 40, bundle)
        assertEquals(listOf(0, 14, 15, 16, 17, 18, 19), sel20.memoryFrameIndices)
        assertEquals(16, sel20.objPtrFrameIndices.size)
        assertEquals(0, sel20.objPtrFrameIndices[0])
        assertEquals(19, sel20.objPtrFrameIndices[1])
        assertEquals(5, sel20.objPtrFrameIndices[15])
        assertEquals(64, sel20.numObjPtrTokens)
    }

    @Test
    fun testBoundedMemoryOver100Frames() {
        val state = Sam2VideoState(objectId = 1)
        val dummyMem = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        val dummyPos = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        val dummyPtr = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)
        val bundle = Sam2LiteRtStaticStateBundle()

        state.addConditioningFrame(0, dummyMem, dummyPos, dummyPtr)

        for (f in 1..100) {
            state.addNonConditioningFrame(f, dummyMem, dummyPos, dummyPtr)
            assertTrue(state.nonCondFrameOutputs.size <= 6, "nonCondFrameOutputs size  exceeded 6")
            assertTrue(state.nonCondObjPtrs.size <= 16, "nonCondObjPtrs size  exceeded 16")
        }

        // State memory must remain strictly bounded O(1)
        val memoryAt100 = state.computeStateMemoryBytes()
        // 1 cond mem + 6 non-cond mem + 1 cond ptr + 16 non-cond ptrs
        // MemoryFrame: (262144 + 262144) * 4 = 2,097,152 bytes * 7 = 14,680,064 bytes
        // ObjPtr: 256 * 4 = 1,024 bytes * 17 = 17,408 bytes
        // Total active slots = ~14.7MB
        assertTrue(memoryAt100 < 20_000_000L, "State memory ( bytes) exceeded 20MB bound")

        // Advance another 100 frames
        for (f in 101..200) {
            state.addNonConditioningFrame(f, dummyMem, dummyPos, dummyPtr)
        }

        val memoryAt200 = state.computeStateMemoryBytes()
        assertEquals(memoryAt100, memoryAt200, "State memory grew between frame 100 and frame 200 (not O(1))")
    }

    @Test
    fun testEmptyMaskReturnsNullStrictBbox() {
        val emptyMask = FloatArray(100 * 100) { 0.0f }
        val bbox = Sam2MaskPostprocessor.computeBboxFromMaskStrict(emptyMask, 100, 100)
        assertNull(bbox)
    }

    @Test
    fun testValidMaskReturnsAccurateStrictBbox() {
        val mask = FloatArray(100 * 100) { 0.0f }
        for (y in 40..59) {
            for (x in 30..49) {
                mask[y * 100 + x] = 0.9f
            }
        }
        val bbox = Sam2MaskPostprocessor.computeBboxFromMaskStrict(mask, 100, 100)
        assertNotNull(bbox)
        assertTrue(bbox.left <= 30f)
        assertTrue(bbox.top <= 40f)
        assertTrue(bbox.right >= 49f)
        assertTrue(bbox.bottom >= 59f)
    }

    @Test
    fun testStateMemoryBytesCalculation() {
        val state = Sam2VideoState(objectId = 1)
        val dummyMem = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        val dummyPos = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        val dummyPtr = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)

        state.addConditioningFrame(0, dummyMem, dummyPos, dummyPtr)
        state.addNonConditioningFrame(1, dummyMem, dummyPos, dummyPtr)

        val bytes = state.computeStateMemoryBytes()
        // 2 MemoryFrameSlots (2 * 2,097,152) + 2 ObjPtrSlots (2 * 1024) = 4,196,352 bytes
        assertEquals(4196352L, bytes)
    }
}

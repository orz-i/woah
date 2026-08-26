package com.danceanon.native.sam2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Sam2OnnxStateSelectorTest {

    @Test
    fun testSelectionParityAcrossFrames() {
        val state = Sam2OnnxVideoState(objectId = 1)
        val dummyMem = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        val dummyPos = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        val dummyPtr = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)

        // Frame 0: conditioning frame
        state.addConditioningFrame(0, dummyMem, dummyPos, dummyPtr)

        // Frame 1
        val sel1 = Sam2OnnxStateSelector.selectForFrame(state, 1, 40)
        assertEquals(listOf(0), sel1.memoryFrameIndices)
        assertEquals(1, sel1.memoryTPosIndices.size)
        assertEquals(6L, sel1.memoryTPosIndices[0])
        assertEquals(listOf(0), sel1.objPtrFrameIndices)
        assertEquals(4, sel1.numObjPtrTokens)

        // Populate frames 1 to 38
        for (f in 1..38) {
            state.addNonConditioningFrame(f, dummyMem, dummyPos, dummyPtr)
        }

        // Frame 7: exactly 7 memories (Frame 0 + Frames 1..6)
        val sel7 = Sam2OnnxStateSelector.selectForFrame(state, 7, 40)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), sel7.memoryFrameIndices)
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L, 0L), sel7.memoryTPosIndices.toList())
        assertEquals(listOf(0, 6, 5, 4, 3, 2, 1), sel7.objPtrFrameIndices)
        assertEquals(28, sel7.numObjPtrTokens)

        // Frame 8: FIFO eviction of oldest non-cond (Frame 0 + Frames 2..7)
        val sel8 = Sam2OnnxStateSelector.selectForFrame(state, 8, 40)
        assertEquals(listOf(0, 2, 3, 4, 5, 6, 7), sel8.memoryFrameIndices)
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L, 0L), sel8.memoryTPosIndices.toList())
        assertEquals(listOf(0, 7, 6, 5, 4, 3, 2, 1), sel8.objPtrFrameIndices)
        assertEquals(32, sel8.numObjPtrTokens)

        // Frame 20: 7 memories (Frame 0 + Frames 14..19), 16 ptrs (Frame 0 + Frames 19 down to 5)
        state.addNonConditioningFrame(39, dummyMem, dummyPos, dummyPtr)
        val sel20 = Sam2OnnxStateSelector.selectForFrame(state, 20, 40)
        assertEquals(listOf(0, 14, 15, 16, 17, 18, 19), sel20.memoryFrameIndices)
        assertEquals(16, sel20.objPtrFrameIndices.size)
        assertEquals(0, sel20.objPtrFrameIndices[0])
        assertEquals(19, sel20.objPtrFrameIndices[1])
        assertEquals(5, sel20.objPtrFrameIndices[15])
        assertEquals(64, sel20.numObjPtrTokens)
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
        // Fill a 20x20 box from (30, 40) to (49, 59)
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
        val state = Sam2OnnxVideoState(objectId = 1)
        val dummyMem = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        val dummyPos = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
        val dummyPtr = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)

        state.addConditioningFrame(0, dummyMem, dummyPos, dummyPtr)
        state.addNonConditioningFrame(1, dummyMem, dummyPos, dummyPtr)

        val bytes = state.computeStateMemoryBytes()
        // Each slot: (262144 + 262144 + 256) * 4 = 2,098,176 bytes
        assertEquals(2 * 2098176L, bytes)
    }
}

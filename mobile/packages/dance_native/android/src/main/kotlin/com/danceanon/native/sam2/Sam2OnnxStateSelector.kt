package com.danceanon.native.sam2

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Result of memory and pointer selection for a given frame step.
 */
data class SelectedStateBundle(
    val memoryFeaturesBuffer: FloatBuffer,
    val memoryPosBuffer: FloatBuffer,
    val memoryTPosIndices: LongArray,
    val objPtrsBuffer: FloatBuffer,
    val memoryCount: Int,
    val memoryFrameIndices: List<Int>,
    val objPtrCount: Int,
    val objPtrFrameIndices: List<Int>,
    val numObjPtrTokens: Int
)

/**
 * Kotlin implementation of SAM2 video state selector.
 * Guarantees exact behavioral parity with Desktop Python SAM2 selection.
 */
object Sam2OnnxStateSelector {

    fun selectForFrame(
        state: Sam2OnnxVideoState,
        frameIndex: Int,
        numFrames: Int = 40
    ): SelectedStateBundle {
        val selectedMemSlots = mutableListOf<Sam2MemorySlot>()
        val selectedTPosIndices = mutableListOf<Long>()
        val selectedMemFrameIndices = mutableListOf<Int>()

        // 1. Selected conditioning frames (all have t_pos = 0)
        val sortedCondKeys = state.condFrameOutputs.keys.sorted()
        for (k in sortedCondKeys) {
            val slot = state.condFrameOutputs[k] ?: continue
            selectedMemSlots.add(slot)
            selectedTPosIndices.add((state.numMaskMem - 0 - 1).toLong()) // 6
            selectedMemFrameIndices.add(k)
        }

        // 2. Non-conditioning frames (t_pos from 1 to numMaskMem - 1)
        val stride = 1
        for (tPos in 1 until state.numMaskMem) {
            val tRel = state.numMaskMem - tPos
            val prevFrameIdx = if (tRel == 1) {
                frameIndex - 1
            } else {
                ((frameIndex - 2) / stride) * stride - (tRel - 2) * stride
            }

            val slot = state.nonCondFrameOutputs[prevFrameIdx]
            if (slot != null) {
                selectedMemSlots.add(slot)
                selectedTPosIndices.add((state.numMaskMem - tPos - 1).toLong())
                selectedMemFrameIndices.add(prevFrameIdx)
            }
        }

        // 3. Object pointers
        val maxPtrs = kotlin.math.min(numFrames, state.maxObjPtrs)
        val selectedPtrSlots = mutableListOf<FloatArray>()
        val selectedPtrFrameIndices = mutableListOf<Int>()

        // Past / current conditioning pointers
        for (k in sortedCondKeys) {
            if (k <= frameIndex) {
                val slot = state.condFrameOutputs[k] ?: continue
                selectedPtrSlots.add(slot.objPtr)
                selectedPtrFrameIndices.add(k)
            }
        }

        // Past non-conditioning pointers
        for (tDiff in 1 until maxPtrs) {
            val t = frameIndex - tDiff
            if (t < 0) break
            val slot = state.nonCondFrameOutputs[t]
            if (slot != null) {
                selectedPtrSlots.add(slot.objPtr)
                selectedPtrFrameIndices.add(t)
            }
        }

        val numMem = selectedMemSlots.size
        val numPtrs = selectedPtrSlots.size

        // Build direct buffers for ONNX inputs
        val memFeatElems = Sam2TensorContract.MEM_FEAT_ELEMS // 64 * 64 * 64
        val memPosElems = Sam2TensorContract.MEM_FEAT_ELEMS  // 64 * 64 * 64
        val ptrElems = Sam2TensorContract.OBJ_PTR_ELEMS      // 256

        val memFeatBuffer = ByteBuffer.allocateDirect(numMem * memFeatElems * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val memPosBuffer = ByteBuffer.allocateDirect(numMem * memPosElems * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        for (slot in selectedMemSlots) {
            memFeatBuffer.put(slot.memoryFeatures)
            memPosBuffer.put(slot.memoryPosEnc)
        }
        memFeatBuffer.flip()
        memPosBuffer.flip()

        val ptrBuffer = ByteBuffer.allocateDirect(numPtrs * ptrElems * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (ptr in selectedPtrSlots) {
            ptrBuffer.put(ptr)
        }
        ptrBuffer.flip()

        val numTokensPerPtr = state.hiddenDim / state.memDim // 4
        val numObjPtrTokens = numPtrs * numTokensPerPtr

        return SelectedStateBundle(
            memoryFeaturesBuffer = memFeatBuffer,
            memoryPosBuffer = memPosBuffer,
            memoryTPosIndices = selectedTPosIndices.toLongArray(),
            objPtrsBuffer = ptrBuffer,
            memoryCount = numMem,
            memoryFrameIndices = selectedMemFrameIndices,
            objPtrCount = numPtrs,
            objPtrFrameIndices = selectedPtrFrameIndices,
            numObjPtrTokens = numObjPtrTokens
        )
    }
}

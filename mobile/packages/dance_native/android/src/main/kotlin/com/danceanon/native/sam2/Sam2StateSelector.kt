package com.danceanon.native.sam2

import java.util.Arrays

/**
 * Static state bundle populated by [Sam2StateSelector] for LiteRT temporal step execution.
 * Pre-allocated to guarantee ZERO per-frame heap allocations.
 */
class Sam2LiteRtStaticStateBundle {
    val paddedMemFeats = FloatArray(Sam2TensorContract.NUM_MASKMEM * Sam2TensorContract.MEM_FEAT_ELEMS)
    val paddedMemPos = FloatArray(Sam2TensorContract.NUM_MASKMEM * Sam2TensorContract.MEM_FEAT_ELEMS)
    val paddedTPosIndices = LongArray(Sam2TensorContract.NUM_MASKMEM)
    val paddedObjPtrs = FloatArray(Sam2TensorContract.MAX_OBJ_PTRS * Sam2TensorContract.OBJ_PTR_ELEMS)
    val attnMask = FloatArray(Sam2TensorContract.TOTAL_MEM_TOKENS)

    var memoryCount: Int = 0
    val memoryFrameIndices = mutableListOf<Int>()
    var objPtrCount: Int = 0
    val objPtrFrameIndices = mutableListOf<Int>()
    var numObjPtrTokens: Int = 0

    fun reset() {
        memoryCount = 0
        memoryFrameIndices.clear()
        objPtrCount = 0
        objPtrFrameIndices.clear()
        numObjPtrTokens = 0
    }
}

/**
 * Zero-allocation SAM2 video state selector for LiteRT.
 * Populates fixed-size padded arrays and attention masks for the static temporal contract.
 */
object Sam2StateSelector {

    private const val MASK_PAD_VALUE = -10000.0f
    private const val MASK_VALID_VALUE = 0.0f
    private const val MEM_FEAT_ELEMS = Sam2TensorContract.MEM_FEAT_ELEMS // 262,144
    private const val MEM_POS_ELEMS = Sam2TensorContract.MEM_FEAT_ELEMS  // 262,144
    private const val OBJ_PTR_ELEMS = Sam2TensorContract.OBJ_PTR_ELEMS  // 256
    private const val TOKENS_PER_MEM = 4096
    private const val TOKENS_PER_PTR = 4
    private const val PTR_TOKEN_OFFSET = Sam2TensorContract.NUM_MASKMEM * TOKENS_PER_MEM // 28,672

    fun selectForFrame(
        state: Sam2VideoState,
        frameIndex: Int,
        numFrames: Int = 40,
        bundle: Sam2LiteRtStaticStateBundle
    ): Sam2LiteRtStaticStateBundle {
        bundle.reset()

        val selectedMemSlots = mutableListOf<Sam2MemorySlot>()
        val selectedTPosIndices = mutableListOf<Long>()

        // 1. Selected conditioning frames (t_pos = 0 -> index = 6)
        val sortedCondKeys = state.condFrameOutputs.keys.sorted()
        for (k in sortedCondKeys) {
            val slot = state.condFrameOutputs[k] ?: continue
            selectedMemSlots.add(slot)
            selectedTPosIndices.add((state.numMaskMem - 0 - 1).toLong())
            bundle.memoryFrameIndices.add(k)
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
                bundle.memoryFrameIndices.add(prevFrameIdx)
            }
        }

        // 3. Object pointers
        val maxPtrs = kotlin.math.min(numFrames, state.maxObjPtrs)
        val selectedPtrSlots = mutableListOf<FloatArray>()

        // Past / current conditioning pointers
        for (k in sortedCondKeys) {
            if (k <= frameIndex) {
                val slot = state.condFrameOutputs[k] ?: continue
                selectedPtrSlots.add(slot.objPtr)
                bundle.objPtrFrameIndices.add(k)
            }
        }

        // Past non-conditioning pointers
        for (tDiff in 1 until maxPtrs) {
            val t = frameIndex - tDiff
            if (t < 0) break
            val slot = state.nonCondFrameOutputs[t]
            if (slot != null) {
                selectedPtrSlots.add(slot.objPtr)
                bundle.objPtrFrameIndices.add(t)
            }
        }

        val numMem = selectedMemSlots.size
        val numPtrs = selectedPtrSlots.size
        bundle.memoryCount = numMem
        bundle.objPtrCount = numPtrs
        bundle.numObjPtrTokens = numPtrs * (state.hiddenDim / state.memDim)

        // Initialize attention mask: default all to -10000.0f
        Arrays.fill(bundle.attnMask, MASK_PAD_VALUE)

        // 4. Fill memory slots and mask
        for (i in 0 until Sam2TensorContract.NUM_MASKMEM) {
            val featOffset = i * MEM_FEAT_ELEMS
            val posOffset = i * MEM_POS_ELEMS
            val tokenOffset = i * TOKENS_PER_MEM

            if (i < numMem) {
                val slot = selectedMemSlots[i]
                System.arraycopy(slot.memoryFeatures, 0, bundle.paddedMemFeats, featOffset, MEM_FEAT_ELEMS)
                System.arraycopy(slot.memoryPosEnc, 0, bundle.paddedMemPos, posOffset, MEM_POS_ELEMS)
                bundle.paddedTPosIndices[i] = selectedTPosIndices[i]
                Arrays.fill(bundle.attnMask, tokenOffset, tokenOffset + TOKENS_PER_MEM, MASK_VALID_VALUE)
            } else {
                Arrays.fill(bundle.paddedMemFeats, featOffset, featOffset + MEM_FEAT_ELEMS, 0.0f)
                Arrays.fill(bundle.paddedMemPos, posOffset, posOffset + MEM_POS_ELEMS, 0.0f)
                bundle.paddedTPosIndices[i] = 0L
            }
        }

        // 5. Fill obj pointer slots and mask
        for (j in 0 until Sam2TensorContract.MAX_OBJ_PTRS) {
            val ptrOffset = j * OBJ_PTR_ELEMS
            val tokenOffset = PTR_TOKEN_OFFSET + j * TOKENS_PER_PTR

            if (j < numPtrs) {
                val ptr = selectedPtrSlots[j]
                System.arraycopy(ptr, 0, bundle.paddedObjPtrs, ptrOffset, OBJ_PTR_ELEMS)
                Arrays.fill(bundle.attnMask, tokenOffset, tokenOffset + TOKENS_PER_PTR, MASK_VALID_VALUE)
            } else {
                Arrays.fill(bundle.paddedObjPtrs, ptrOffset, ptrOffset + OBJ_PTR_ELEMS, 0.0f)
            }
        }

        return bundle
    }
}

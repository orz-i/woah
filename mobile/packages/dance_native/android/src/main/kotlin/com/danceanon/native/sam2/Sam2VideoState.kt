package com.danceanon.native.sam2

import com.danceanon.native.inference.FloatRect
import java.util.ArrayDeque

/**
 * Initialization request for SAM2 tracking on Frame 0.
 */
data class Sam2InitRequest(
    val frame: android.graphics.Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val objectId: Int,
    val bbox: FloatRect
)

/**
 * Output tracking result for a single frame.
 */
data class Sam2TrackResult(
    val frameIndex: Int,
    val objectId: Int,
    val softMask: FloatArray,
    val bbox: FloatRect,
    val maskArea: Float,
    val inferenceMs: Long,
    val isValid: Boolean = true
)

/**
 * Memory record for a single frame.
 * All arrays are flat FP32 arrays representing:
 * - memoryFeatures: [1, 64, 64, 64] -> 262,144 floats
 * - memoryPosEnc: [1, 64, 64, 64] -> 262,144 floats
 * - objPtr: [1, 256] -> 256 floats
 */
class Sam2MemorySlot(
    var frameIndex: Int = -1,
    val memoryFeatures: FloatArray = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS),
    val memoryPosEnc: FloatArray = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS),
    val objPtr: FloatArray = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)
) {
    val memorySizeBytes: Long = (memoryFeatures.size + memoryPosEnc.size + objPtr.size) * 4L
}

/**
 * Persistent state for a single tracked object across video frames with zero-allocation slot recycling.
 */
class Sam2VideoState(
    val objectId: Int,
    val numMaskMem: Int = Sam2TensorContract.NUM_MASKMEM,
    val memDim: Int = Sam2TensorContract.MEM_DIM,
    val hiddenDim: Int = Sam2TensorContract.HIDDEN_DIM,
    val maxObjPtrs: Int = Sam2TensorContract.MAX_OBJ_PTRS
) {
    val condFrameOutputs = mutableMapOf<Int, Sam2MemorySlot>()
    val nonCondFrameOutputs = mutableMapOf<Int, Sam2MemorySlot>()
    private val slotPool = ArrayDeque<Sam2MemorySlot>()
    var isInitialized = false

    private fun obtainSlot(frameIndex: Int): Sam2MemorySlot {
        val slot = if (slotPool.isNotEmpty()) slotPool.pop() else Sam2MemorySlot()
        slot.frameIndex = frameIndex
        return slot
    }

    private fun recycleSlot(slot: Sam2MemorySlot) {
        slot.frameIndex = -1
        slotPool.push(slot)
    }

    fun addConditioningFrame(
        frameIndex: Int,
        memoryFeatures: FloatArray,
        memoryPosEnc: FloatArray,
        objPtr: FloatArray
    ) {
        val existing = condFrameOutputs.remove(frameIndex)
        if (existing != null) recycleSlot(existing)

        val slot = obtainSlot(frameIndex)
        System.arraycopy(memoryFeatures, 0, slot.memoryFeatures, 0, slot.memoryFeatures.size)
        System.arraycopy(memoryPosEnc, 0, slot.memoryPosEnc, 0, slot.memoryPosEnc.size)
        System.arraycopy(objPtr, 0, slot.objPtr, 0, slot.objPtr.size)

        condFrameOutputs[frameIndex] = slot
        isInitialized = true
    }

    fun addNonConditioningFrame(
        frameIndex: Int,
        memoryFeatures: FloatArray,
        memoryPosEnc: FloatArray,
        objPtr: FloatArray
    ) {
        val existing = nonCondFrameOutputs.remove(frameIndex)
        if (existing != null) recycleSlot(existing)

        val slot = obtainSlot(frameIndex)
        System.arraycopy(memoryFeatures, 0, slot.memoryFeatures, 0, slot.memoryFeatures.size)
        System.arraycopy(memoryPosEnc, 0, slot.memoryPosEnc, 0, slot.memoryPosEnc.size)
        System.arraycopy(objPtr, 0, slot.objPtr, 0, slot.objPtr.size)

        nonCondFrameOutputs[frameIndex] = slot
    }

    fun computeStateMemoryBytes(): Long {
        var total = 0L
        for (slot in condFrameOutputs.values) {
            total += slot.memorySizeBytes
        }
        for (slot in nonCondFrameOutputs.values) {
            total += slot.memorySizeBytes
        }
        for (slot in slotPool) {
            total += slot.memorySizeBytes
        }
        return total
    }

    fun reset() {
        for (slot in condFrameOutputs.values) recycleSlot(slot)
        for (slot in nonCondFrameOutputs.values) recycleSlot(slot)
        condFrameOutputs.clear()
        nonCondFrameOutputs.clear()
        isInitialized = false
    }
}

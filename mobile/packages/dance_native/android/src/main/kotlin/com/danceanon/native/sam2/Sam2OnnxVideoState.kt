package com.danceanon.native.sam2

/**
 * Memory record for a single frame.
 * All arrays are flat FP32 arrays representing:
 * - memoryFeatures: [1, 64, 64, 64] -> 262,144 floats
 * - memoryPosEnc: [1, 64, 64, 64] -> 262,144 floats
 * - objPtr: [1, 256] -> 256 floats
 */
class Sam2MemorySlot(
    val frameIndex: Int,
    val memoryFeatures: FloatArray,
    val memoryPosEnc: FloatArray,
    val objPtr: FloatArray
) {
    val memorySizeBytes: Long = (memoryFeatures.size + memoryPosEnc.size + objPtr.size) * 4L
}

/**
 * Persistent state for a single tracked object across video frames.
 */
class Sam2OnnxVideoState(
    val objectId: Int,
    val numMaskMem: Int = Sam2TensorContract.NUM_MASKMEM,
    val memDim: Int = Sam2TensorContract.MEM_DIM,
    val hiddenDim: Int = Sam2TensorContract.HIDDEN_DIM,
    val maxObjPtrs: Int = Sam2TensorContract.MAX_OBJ_PTRS
) {
    val condFrameOutputs = mutableMapOf<Int, Sam2MemorySlot>()
    val nonCondFrameOutputs = mutableMapOf<Int, Sam2MemorySlot>()
    var isInitialized = false

    fun addConditioningFrame(
        frameIndex: Int,
        memoryFeatures: FloatArray,
        memoryPosEnc: FloatArray,
        objPtr: FloatArray
    ) {
        condFrameOutputs[frameIndex] = Sam2MemorySlot(
            frameIndex,
            memoryFeatures,
            memoryPosEnc,
            objPtr
        )
        isInitialized = true
    }

    fun addNonConditioningFrame(
        frameIndex: Int,
        memoryFeatures: FloatArray,
        memoryPosEnc: FloatArray,
        objPtr: FloatArray
    ) {
        nonCondFrameOutputs[frameIndex] = Sam2MemorySlot(
            frameIndex,
            memoryFeatures,
            memoryPosEnc,
            objPtr
        )
    }

    fun computeStateMemoryBytes(): Long {
        var total = 0L
        for (slot in condFrameOutputs.values) {
            total += slot.memorySizeBytes
        }
        for (slot in nonCondFrameOutputs.values) {
            total += slot.memorySizeBytes
        }
        return total
    }

    fun reset() {
        condFrameOutputs.clear()
        nonCondFrameOutputs.clear()
        isInitialized = false
    }
}

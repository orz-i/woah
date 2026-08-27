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
 * Memory record for heavy spatial-temporal features (~2MB per slot).
 * Flat FP32 arrays:
 * - memoryFeatures: [1, 64, 64, 64] -> 262,144 floats
 * - memoryPosEnc: [1, 64, 64, 64] -> 262,144 floats
 */
class MemoryFrameSlot(
    var frameIndex: Int = -1,
    val memoryFeatures: FloatArray = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS),
    val memoryPosEnc: FloatArray = FloatArray(Sam2TensorContract.MEM_FEAT_ELEMS)
) {
    val memorySizeBytes: Long = (memoryFeatures.size + memoryPosEnc.size) * 4L
}

/**
 * Lightweight object pointer record (~1KB per slot).
 * Flat FP32 array:
 * - objPtr: [1, 256] -> 256 floats
 */
class ObjPtrSlot(
    var frameIndex: Int = -1,
    val objPtr: FloatArray = FloatArray(Sam2TensorContract.OBJ_PTR_ELEMS)
) {
    val memorySizeBytes: Long = objPtr.size * 4L
}

/**
 * Persistent state for a single tracked object across video frames with strictly bounded O(1) memory
 * and zero per-frame heap allocations.
 */
class Sam2VideoState(
    val objectId: Int,
    val numMaskMem: Int = Sam2TensorContract.NUM_MASKMEM,
    val memDim: Int = Sam2TensorContract.MEM_DIM,
    val hiddenDim: Int = Sam2TensorContract.HIDDEN_DIM,
    val maxObjPtrs: Int = Sam2TensorContract.MAX_OBJ_PTRS
) {
    val maxNonCondMemFrames: Int = numMaskMem - 1 // 6

    val condFrameOutputs = mutableMapOf<Int, MemoryFrameSlot>()
    val condObjPtrs = mutableMapOf<Int, ObjPtrSlot>()

    val nonCondFrameOutputs = LinkedHashMap<Int, MemoryFrameSlot>()
    val nonCondObjPtrs = LinkedHashMap<Int, ObjPtrSlot>()

    private val memSlotPool = ArrayDeque<MemoryFrameSlot>()
    private val ptrSlotPool = ArrayDeque<ObjPtrSlot>()

    var isInitialized = false

    private fun obtainMemSlot(frameIndex: Int): MemoryFrameSlot {
        val slot = if (memSlotPool.isNotEmpty()) memSlotPool.pop() else MemoryFrameSlot()
        slot.frameIndex = frameIndex
        return slot
    }

    private fun recycleMemSlot(slot: MemoryFrameSlot) {
        slot.frameIndex = -1
        memSlotPool.push(slot)
    }

    private fun obtainPtrSlot(frameIndex: Int): ObjPtrSlot {
        val slot = if (ptrSlotPool.isNotEmpty()) ptrSlotPool.pop() else ObjPtrSlot()
        slot.frameIndex = frameIndex
        return slot
    }

    private fun recyclePtrSlot(slot: ObjPtrSlot) {
        slot.frameIndex = -1
        ptrSlotPool.push(slot)
    }

    fun addConditioningFrame(
        frameIndex: Int,
        memoryFeatures: FloatArray,
        memoryPosEnc: FloatArray,
        objPtr: FloatArray
    ) {
        val existingMem = condFrameOutputs.remove(frameIndex)
        if (existingMem != null) recycleMemSlot(existingMem)

        val existingPtr = condObjPtrs.remove(frameIndex)
        if (existingPtr != null) recyclePtrSlot(existingPtr)

        val memSlot = obtainMemSlot(frameIndex)
        System.arraycopy(memoryFeatures, 0, memSlot.memoryFeatures, 0, memSlot.memoryFeatures.size)
        System.arraycopy(memoryPosEnc, 0, memSlot.memoryPosEnc, 0, memSlot.memoryPosEnc.size)
        condFrameOutputs[frameIndex] = memSlot

        val ptrSlot = obtainPtrSlot(frameIndex)
        System.arraycopy(objPtr, 0, ptrSlot.objPtr, 0, ptrSlot.objPtr.size)
        condObjPtrs[frameIndex] = ptrSlot

        isInitialized = true
    }

    fun addNonConditioningFrame(
        frameIndex: Int,
        memoryFeatures: FloatArray,
        memoryPosEnc: FloatArray,
        objPtr: FloatArray
    ) {
        // 1. Manage nonCondFrameOutputs with bounded FIFO eviction
        val existingMem = nonCondFrameOutputs.remove(frameIndex)
        if (existingMem != null) recycleMemSlot(existingMem)

        while (nonCondFrameOutputs.size >= maxNonCondMemFrames) {
            val oldestKey = nonCondFrameOutputs.keys.first()
            val oldestSlot = nonCondFrameOutputs.remove(oldestKey)
            if (oldestSlot != null) recycleMemSlot(oldestSlot)
        }

        val memSlot = obtainMemSlot(frameIndex)
        System.arraycopy(memoryFeatures, 0, memSlot.memoryFeatures, 0, memSlot.memoryFeatures.size)
        System.arraycopy(memoryPosEnc, 0, memSlot.memoryPosEnc, 0, memSlot.memoryPosEnc.size)
        nonCondFrameOutputs[frameIndex] = memSlot

        // 2. Manage nonCondObjPtrs with bounded FIFO eviction
        val existingPtr = nonCondObjPtrs.remove(frameIndex)
        if (existingPtr != null) recyclePtrSlot(existingPtr)

        while (nonCondObjPtrs.size >= maxObjPtrs) {
            val oldestKey = nonCondObjPtrs.keys.first()
            val oldestSlot = nonCondObjPtrs.remove(oldestKey)
            if (oldestSlot != null) recyclePtrSlot(oldestSlot)
        }

        val ptrSlot = obtainPtrSlot(frameIndex)
        System.arraycopy(objPtr, 0, ptrSlot.objPtr, 0, ptrSlot.objPtr.size)
        nonCondObjPtrs[frameIndex] = ptrSlot
    }

    fun computeStateMemoryBytes(): Long {
        var total = 0L
        for (slot in condFrameOutputs.values) total += slot.memorySizeBytes
        for (slot in nonCondFrameOutputs.values) total += slot.memorySizeBytes
        for (slot in condObjPtrs.values) total += slot.memorySizeBytes
        for (slot in nonCondObjPtrs.values) total += slot.memorySizeBytes
        for (slot in memSlotPool) total += slot.memorySizeBytes
        for (slot in ptrSlotPool) total += slot.memorySizeBytes
        return total
    }

    fun reset() {
        for (slot in condFrameOutputs.values) recycleMemSlot(slot)
        for (slot in nonCondFrameOutputs.values) recycleMemSlot(slot)
        for (slot in condObjPtrs.values) recyclePtrSlot(slot)
        for (slot in nonCondObjPtrs.values) recyclePtrSlot(slot)
        condFrameOutputs.clear()
        nonCondFrameOutputs.clear()
        condObjPtrs.clear()
        nonCondObjPtrs.clear()
        isInitialized = false
    }
}

package com.danceanon.native.sam2

import com.danceanon.native.inference.FloatRect

/**
 * Persistent memory slot for historical temporal conditioning.
 */
class Sam2MemorySlot(
    val frameIndex: Int,
    val memoryFeatures: FloatArray, // [64, 64, 64]
    val memoryPosEnc: FloatArray,   // [64, 64, 64]
    val objPtr: FloatArray          // [256]
)

/**
 * Persistent state for a tracked object in video propagation.
 */
class Sam2VideoState(
    val objectId: Int,
    val maxMemorySlots: Int = Sam2TensorContract.NUM_MASKMEM
) {
    val memorySlots = mutableListOf<Sam2MemorySlot>()
    var lastObjPtr: FloatArray? = null
    var isInitialized = false

    fun addMemorySlot(slot: Sam2MemorySlot) {
        if (memorySlots.size >= maxMemorySlots) {
            // FIFO eviction of non-conditioning frames (keep slot 0 if conditioning)
            if (memorySlots.size > 1) {
                memorySlots.removeAt(1)
            } else {
                memorySlots.removeAt(0)
            }
        }
        memorySlots.add(slot)
        lastObjPtr = slot.objPtr
    }

    fun reset() {
        memorySlots.clear()
        lastObjPtr = null
        isInitialized = false
    }
}

data class Sam2InitRequest(
    val frame: android.graphics.Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val objectId: Int,
    val bbox: FloatRect
)

data class Sam2TrackResult(
    val frameIndex: Int,
    val objectId: Int,
    val softMask: FloatArray,
    val bbox: FloatRect,
    val maskArea: Float,
    val inferenceMs: Long
)


package com.danceanon.native.inference

import java.nio.ByteBuffer

data class FloatRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

data class NativeMask(
    val width: Int,
    val height: Int,
    val buffer: ByteBuffer,
    val originalWidth: Int,
    val originalHeight: Int,
    val mapper: com.danceanon.native.geometry.ModelCoordinateMapper? = null,
    val roiInProto: FloatRect? = null,
    val samplingRect: FloatRect? = null
)


data class PersonDetection(
    val bbox: FloatRect,
    val confidence: Float,
    val mask: NativeMask? = null,
    val footY: Float = bbox.bottom
)

data class SegmentationFrame(
    val timestampUs: Long,
    val persons: List<PersonDetection>,
    val inferenceTimeMs: Long
)

interface Segmenter : AutoCloseable {
    suspend fun initialize()
    suspend fun segment(
        rgbBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rotation: Int,
        timestampUs: Long = 0
    ): SegmentationFrame
    override fun close()
}

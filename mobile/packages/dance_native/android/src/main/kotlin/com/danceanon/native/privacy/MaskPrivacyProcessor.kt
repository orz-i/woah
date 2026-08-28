package com.danceanon.native.privacy

import com.danceanon.native.inference.NativeMask
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MaskPrivacyProcessor {

    /**
     * Dilates the binary/grayscale mask by [radius] pixels to eliminate edge under-anonymization
     * caused by fast dance movements or OpenGL linear interpolation.
     */
    fun dilate(mask: NativeMask, radius: Int = 1): NativeMask {
        if (radius <= 0) return mask
        val w = mask.width
        val h = mask.height
        val srcBuf = mask.buffer
        srcBuf.rewind()

        val srcArray = ByteArray(w * h)
        srcBuf.get(srcArray)
        srcBuf.rewind()

        val dstArray = ByteArray(w * h)

        for (y in 0 until h) {
            val yMin = (y - radius).coerceAtLeast(0)
            val yMax = (y + radius).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val xMin = (x - radius).coerceAtLeast(0)
                val xMax = (x + radius).coerceAtMost(w - 1)

                var maxVal: Byte = 0
                for (ny in yMin..yMax) {
                    val rowOffset = ny * w
                    for (nx in xMin..xMax) {
                        val b = srcArray[rowOffset + nx]
                        if ((b.toInt() and 0xFF) > (maxVal.toInt() and 0xFF)) {
                            maxVal = b
                        }
                    }
                }
                dstArray[y * w + x] = maxVal
            }
        }

        val dstBuf = ByteBuffer.allocateDirect(w * h).apply {
            order(ByteOrder.nativeOrder())
            put(dstArray)
            rewind()
        }

        return NativeMask(
            width = w,
            height = h,
            buffer = dstBuf,
            originalWidth = mask.originalWidth,
            originalHeight = mask.originalHeight,
            mapper = mask.mapper,
            roiInProto = mask.roiInProto
        )
    }

    /**
     * Erodes the binary/grayscale mask by [radius] pixels to extract the solid core of an occluder
     * while preserving a safety privacy margin around target boundaries.
     */
    fun erode(mask: NativeMask, radius: Int = 1): NativeMask {
        if (radius <= 0) return mask
        val w = mask.width
        val h = mask.height
        val srcBuf = mask.buffer
        srcBuf.rewind()

        val srcArray = ByteArray(w * h)
        srcBuf.get(srcArray)
        srcBuf.rewind()

        val dstArray = ByteArray(w * h)

        for (y in 0 until h) {
            val yMin = (y - radius).coerceAtLeast(0)
            val yMax = (y + radius).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val xMin = (x - radius).coerceAtLeast(0)
                val xMax = (x + radius).coerceAtMost(w - 1)

                var minVal: Byte = 255.toByte()
                for (ny in yMin..yMax) {
                    val rowOffset = ny * w
                    for (nx in xMin..xMax) {
                        val b = srcArray[rowOffset + nx]
                        if ((b.toInt() and 0xFF) < (minVal.toInt() and 0xFF)) {
                            minVal = b
                        }
                    }
                }
                dstArray[y * w + x] = minVal
            }
        }

        val dstBuf = ByteBuffer.allocateDirect(w * h).apply {
            order(ByteOrder.nativeOrder())
            put(dstArray)
            rewind()
        }

        return NativeMask(
            width = w,
            height = h,
            buffer = dstBuf,
            originalWidth = mask.originalWidth,
            originalHeight = mask.originalHeight,
            mapper = mask.mapper,
            roiInProto = mask.roiInProto
        )
    }
}

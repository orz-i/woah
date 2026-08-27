package com.danceanon.native.sam2

import android.graphics.Bitmap
import com.danceanon.native.inference.FloatRect

/**
 * High-level coordinator interface for SAM2 video temporal tracking.
 * Implemented strictly by [Sam2LiteRtVideoTracker] using App-bundled LiteRT 2.1.5.
 */
interface ISam2VideoTracker : AutoCloseable {
    fun initialize(request: Sam2InitRequest): Sam2TrackResult
    fun initializeWithRgba(rgbaBuffer: java.nio.ByteBuffer, width: Int, height: Int, objectId: Int, bbox: FloatRect): Sam2TrackResult
    fun step(frame: Bitmap, frameIndex: Int): List<Sam2TrackResult>
    fun stepWithRgba(rgbaBuffer: java.nio.ByteBuffer, frameIndex: Int): List<Sam2TrackResult>
    fun reset()
}




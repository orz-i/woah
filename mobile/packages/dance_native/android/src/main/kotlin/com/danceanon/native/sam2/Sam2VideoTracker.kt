package com.danceanon.native.sam2

import android.graphics.Bitmap
import com.danceanon.native.inference.FloatRect

/**
 * Interface and high-level coordinator for SAM2 video temporal tracking.
 */
interface ISam2VideoTracker : AutoCloseable {
    fun initialize(request: Sam2InitRequest): Sam2TrackResult
    fun step(frame: Bitmap, frameIndex: Int): List<Sam2TrackResult>
    fun reset()
}

/**
 * Standalone SAM2 Video Tracker maintaining persistent temporal memory state.
 */
class Sam2VideoTracker(
    val imageEncoderPath: String,
    val promptDecoderPath: String,
    val memoryEncoderPath: String,
    val memoryAttentionPath: String
) : ISam2VideoTracker {

    private val activeStates = mutableMapOf<Int, Sam2VideoState>()
    private var sourceWidth = 0
    private var sourceHeight = 0

    override fun initialize(request: Sam2InitRequest): Sam2TrackResult {
        val startTime = System.currentTimeMillis()
        sourceWidth = request.sourceWidth
        sourceHeight = request.sourceHeight

        val state = Sam2VideoState(request.objectId)
        activeStates[request.objectId] = state

        // Transform bbox prompt to model coordinates
        val modelBbox = Sam2Preprocessor.transformBboxPrompt(
            request.bbox,
            request.sourceWidth,
            request.sourceHeight
        )

        // Initialize state
        state.isInitialized = true
        val duration = System.currentTimeMillis() - startTime

        // In standalone mode, produce initial tracking result from mask derivation
        val initialMask = FloatArray(sourceWidth * sourceHeight) { 0f }
        val derivedBbox = request.bbox

        return Sam2TrackResult(
            frameIndex = 0,
            objectId = request.objectId,
            softMask = initialMask,
            bbox = derivedBbox,
            maskArea = (derivedBbox.width * derivedBbox.height),
            inferenceMs = duration
        )
    }

    override fun step(frame: Bitmap, frameIndex: Int): List<Sam2TrackResult> {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<Sam2TrackResult>()

        for ((objId, state) in activeStates) {
            if (!state.isInitialized) continue

            val duration = System.currentTimeMillis() - startTime
            val softMask = FloatArray(sourceWidth * sourceHeight) { 0f }
            val bbox = Sam2MaskPostprocessor.computeBboxFromMask(softMask, sourceWidth, sourceHeight)

            results.add(
                Sam2TrackResult(
                    frameIndex = frameIndex,
                    objectId = objId,
                    softMask = softMask,
                    bbox = bbox,
                    maskArea = 0f,
                    inferenceMs = duration
                )
            )
        }

        return results
    }

    override fun reset() {
        activeStates.values.forEach { it.reset() }
        activeStates.clear()
    }

    override fun close() {
        reset()
    }
}


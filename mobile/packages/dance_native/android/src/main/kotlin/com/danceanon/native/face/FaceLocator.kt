package com.danceanon.native.face

import java.nio.ByteBuffer

data class FaceLocatorResult(
    val observations: List<FaceObservation>,
    val inferenceMs: Double
)

/**
 * Positional face evidence only. Implementations must never manufacture or own
 * product identity; YOLO/TrackManager remains the sole person-ID authority.
 */
interface FaceLocator : AutoCloseable {
    fun detectRgbaTopDown(
        rgba: ByteBuffer,
        width: Int,
        height: Int
    ): FaceLocatorResult
}

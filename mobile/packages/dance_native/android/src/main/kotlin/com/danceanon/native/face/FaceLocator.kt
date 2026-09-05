package com.danceanon.native.face

import java.nio.ByteBuffer

data class FaceLocatorResult(
    val observations: List<FaceObservation>,
    val inferenceMs: Double
)

data class FaceLocatorRequest(
    val rgba: ByteBuffer,
    val width: Int,
    val height: Int
)

/**
 * Positional face evidence only. Implementations must never manufacture or own
 * product identity; YOLO/TrackManager remains the sole person-ID authority.
 */
interface FaceLocator : AutoCloseable {
    val supportsParallelBatch: Boolean
        get() = false

    fun detectRgbaTopDown(
        rgba: ByteBuffer,
        width: Int,
        height: Int
    ): FaceLocatorResult

    /**
     * Behavior-preserving batch boundary. Implementations that cannot execute
     * safely in parallel keep the historical request order by default.
     */
    fun detectBatchRgbaTopDown(requests: List<FaceLocatorRequest>): List<FaceLocatorResult> =
        requests.map { request ->
            detectRgbaTopDown(
                rgba = request.rgba,
                width = request.width,
                height = request.height
            )
        }
}

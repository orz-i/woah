package com.danceanon.native.bridge

/**
 * Unified exception hierarchy for all native operations across video decoding,
 * inference, tracking, rendering, audio processing, and muxing.
 */
class DanceNativeException(
    val code: String,
    detailMessage: String,
    cause: Throwable? = null
) : RuntimeException("[$code] $detailMessage", cause) {

    companion object {
        const val VIDEO_OPEN_FAILED = "VIDEO_OPEN_FAILED"
        const val VIDEO_TRACK_NOT_FOUND = "VIDEO_TRACK_NOT_FOUND"
        const val VIDEO_CODEC_UNSUPPORTED = "VIDEO_CODEC_UNSUPPORTED"

        const val MODEL_NOT_FOUND = "MODEL_NOT_FOUND"
        const val MODEL_INIT_FAILED = "MODEL_INIT_FAILED"
        const val MODEL_INFERENCE_FAILED = "MODEL_INFERENCE_FAILED"
        const val MODEL_OUTPUT_INVALID = "MODEL_OUTPUT_INVALID"

        const val ANALYSIS_FAILED = "ANALYSIS_FAILED"
        const val ANALYSIS_CACHE_MISSING = "ANALYSIS_CACHE_MISSING"
        const val CACHE_NOT_FOUND = "CACHE_NOT_FOUND"

        const val DECODE_FRAME_FAILED = "DECODE_FRAME_FAILED"
        const val RENDER_FAILED = "RENDER_FAILED"

        const val ENCODER_UNSUPPORTED = "ENCODER_UNSUPPORTED"
        const val ENCODER_FAILED = "ENCODER_FAILED"

        const val MUXER_FAILED = "MUXER_FAILED"
        const val AUDIO_TRACK_FAILED = "AUDIO_TRACK_FAILED"

        const val EXPORT_FAILED = "EXPORT_FAILED"
        const val EXPORT_CANCELLED = "EXPORT_CANCELLED"

        const val OUT_OF_STORAGE = "OUT_OF_STORAGE"
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
        const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
        const val SAM2_RUNTIME_NOT_VALIDATED = "SAM2_RUNTIME_NOT_VALIDATED"
        const val SAM2_GPU_UNAVAILABLE = "SAM2_GPU_UNAVAILABLE"
        const val FRAME_DECODE_TIMEOUT = "FRAME_DECODE_TIMEOUT"
        const val DIAGNOSTIC_SNAPSHOT_TIMEOUT = "DIAGNOSTIC_SNAPSHOT_TIMEOUT"
    }
}

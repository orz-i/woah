package art.gaoge.dance.engine.diagnostics

import art.gaoge.dance.engine.bridge.DanceNativeException
import art.gaoge.dance.engine.bridge.FlutterError

/** Error projection only: never use this on successful business URI/path values. */
object NativeDiagnosticPrivacy {
    private val codes = setOf(
        "VIDEO_OPEN_FAILED", "VIDEO_TRACK_NOT_FOUND", "VIDEO_CODEC_UNSUPPORTED",
        "MODEL_NOT_FOUND", "MODEL_INIT_FAILED", "MODEL_INFERENCE_FAILED", "MODEL_OUTPUT_INVALID",
        "ANALYSIS_FAILED", "ANALYSIS_CACHE_MISSING", "CACHE_NOT_FOUND",
        "DECODE_FRAME_FAILED", "RENDER_FAILED", "ENCODER_UNSUPPORTED", "ENCODER_FAILED",
        "MUXER_FAILED", "AUDIO_TRACK_FAILED", "EXPORT_FAILED", "EXPORT_CANCELLED",
        "EXPORT_INTERRUPTED", "EXPORT_BUSY", "JOB_NOT_FOUND", "OUT_OF_STORAGE",
        "INVALID_ARGUMENT", "INVALID_ARGS", "NOT_IMPLEMENTED", "FRAME_DECODE_TIMEOUT",
        "DIAGNOSTIC_SNAPSHOT_TIMEOUT", "PLUGIN_DETACHED", "SAVE_FAILED", "SAVE_CANCELLED",
        "SAVE_ROLLBACK_FAILED", "STICKER_PERSIST_FAILED", "SHARE_VIDEO_FAILED",
        "OPEN_VIDEO_FAILED", "THUMBNAIL_FAILED", "NO_CONTEXT", "BUNDLE_FAILED", "SHARE_FAILED",
        "FOLLOW_TARGET_UNRESOLVED", "PREVIEW_PRIVACY_UNRESOLVED", "EXPORT_PRIVACY_UNRESOLVED",
        "NATIVE_OPERATION_FAILED"
    )
    fun safeCode(code: String?): String =
        code?.takeIf { it in codes } ?: "NATIVE_OPERATION_FAILED"

    fun code(error: Throwable): String = safeCode(when (error) {
        is DanceNativeException -> error.code
        is FlutterError -> error.code
        is art.gaoge.dance.engine.bridge.comparison.FlutterError -> error.code
        else -> null
    })

    fun message(code: String?, stage: String = "native"): String =
        "stage=${safeStage(stage)}; code=${safeCode(code)}"

    fun type(error: Throwable): String = when (error) {
        is java.util.concurrent.CancellationException -> "CANCELLED"
        is SecurityException -> "PERMISSION"
        is java.io.IOException -> "IO"
        is IllegalArgumentException -> "ARGUMENT"
        is IllegalStateException -> "STATE"
        is OutOfMemoryError -> "MEMORY"
        else -> "NATIVE"
    }

    fun reason(error: Throwable, stage: String): String =
        "${safeStage(stage)}:${type(error)}:${code(error)}"

    private fun safeStage(stage: String): String = stage.takeIf { it in stages } ?: "native"
    private val stages = setOf("native", "capabilities", "probe", "analysis", "preview",
        "export", "cancel", "status", "release", "comparison", "inference", "decode",
        "model", "diagnostics", "gallery", "sticker", "share", "open", "thumbnail")

    fun pigeon(error: Throwable, stage: String): FlutterError =
        FlutterError(code(error), message(code(error), stage), null)

    fun comparisonMessage(value: String?): String? {
        if (value == null) return null
        return value.takeIf { it in setOf(
            "上次导出已中断，可以重试", "无法启动导出，请回到应用后重试",
            "存储空间不足，请释放空间后重试", "导出未完成，请重试或重新关联源视频",
            "视频已导出，保存相册失败，可以重试保存"
        ) } ?: "导出未完成，请重试或重新关联源视频"
    }

    fun comparison(error: Throwable): art.gaoge.dance.engine.bridge.comparison.FlutterError =
        art.gaoge.dance.engine.bridge.comparison.FlutterError(
            code(error), message(code(error), "comparison"), null)
}

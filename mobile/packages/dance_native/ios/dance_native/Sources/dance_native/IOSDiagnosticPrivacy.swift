import Foundation

/// Applies only to diagnostics and failed-operation replies, never successful media URIs.
enum IOSDiagnosticPrivacy {
  enum Stage: String {
    case native, probe, analysis, preview, export, comparison, playback, inference, media, diagnostics
  }
  private static let codes: Set<String> = [
    "IOS_DIAGNOSTIC_PRIVACY_SMOKE_FAILED",
    "CACHE_OWNER_CLOSED", "CACHE_ID_COLLISION", "CACHE_NOT_RETAINED", "JOB_NOT_FOUND",
    "INVALID_VIDEO", "INVALID_TRIM_RANGE", "DECODER_UNAVAILABLE", "ENCODER_UNAVAILABLE",
    "DECODE_FAILED", "FRAME_CONVERSION_FAILED", "AUDIO_DECODER_UNAVAILABLE",
    "AUDIO_WRITER_UNAVAILABLE", "AUDIO_TIMING_FAILED", "PLATFORM_NOT_SUPPORTED",
    "ANALYSIS_CACHE_FAILED",
    "CACHE_NOT_FOUND",
    "COMPARISON_EXPORT_AUDIO",
    "COMPARISON_EXPORT_CANCELLED",
    "COMPARISON_EXPORT_FAILED",
    "COMPARISON_EXPORT_INVALID",
    "COMPARISON_EXPORT_STATE",
    "COMPARISON_EXPORT_TIMING",
    "CROP_CLARITY_RUNTIME_FAILED",
    "CROP_CLARITY_TENSOR_CONTRACT_MISMATCH",
    "DECODE_FRAME_FAILED",
    "EXPORT_BUSY",
    "EXPORT_FAILED",
    "EXPORT_PRIVACY_UNRESOLVED",
    "FOLLOW_TARGET_UNRESOLVED",
    "INFERENCE_PREPROCESS_FAILED",
    "INVALID_ARGS",
    "INVALID_ARGUMENT",
    "INVALID_CACHE_ID",
    "INVALID_CACHE_PATH",
    "INVALID_INFERENCE_FRAME",
    "IOS_CACHE_LIFECYCLE_SMOKE_FAILED",
    "IOS_EXPORT_PHASE4_SMOKE_FAILED",
    "IOS_GOLDEN_TRACE_PHASE5_SMOKE_FAILED",
    "IOS_INFERENCE_RUNTIME_FAILED",
    "IOS_INFERENCE_RUNTIME_UNAVAILABLE",
    "IOS_METAL_PHASE3_SMOKE_FAILED",
    "IOS_PHASE1_FIXTURE_DECODE_FAILED",
    "IOS_PHASE1_FIXTURE_NOT_FOUND",
    "IOS_PHASE5_FACE_PRIVACY_SMOKE_FAILED",
    "IOS_PHASE5_GOLDEN_TRACE_INVALID",
    "IOS_PHASE5_GOLDEN_TRACE_MISMATCH",
    "IOS_PHASE5_GOLDEN_TRACE_MISSING",
    "IOS_PHASE5_GOLDEN_TRACE_SCHEMA_UNSUPPORTED",
    "IOS_PHASE6_PRIVACY_CLASS_SMOKE_FAILED",
    "IOS_PRIVACY_CLASS_PHASE6_SMOKE_FAILED",
    "IOS_YOLO_PHASE1_BUNDLED_PROBE_FAILED",
    "IOS_YOLO_PHASE1_PROBE_FAILED",
    "METAL_PIPELINE_FAILED",
    "METAL_RENDER_FAILED",
    "METAL_SHADER_COMPILE_FAILED",
    "METAL_SMOKE_DEVICE_UNAVAILABLE",
    "METAL_SMOKE_IMAGE_FAILED",
    "METAL_SMOKE_PIXEL_MISMATCH",
    "METAL_SMOKE_READBACK_FAILED",
    "METAL_UNAVAILABLE",
    "MODEL_NOT_FOUND",
    "OPEN_VIDEO_FAILED",
    "PHOTO_LIBRARY_PERMISSION_DENIED",
    "PRESENTATION_UNAVAILABLE",
    "PREVIEW_FRAME_CONVERSION_FAILED",
    "PREVIEW_PRIVACY_UNRESOLVED",
    "PREVIEW_SAVE_FAILED",
    "SAVE_VIDEO_FAILED",
    "SHARE_VIDEO_FAILED",
    "STICKER_PERSIST_FAILED",
    "THUMBNAIL_FAILED",
    "VIDEO_FILE_NOT_FOUND",
    "VIDEO_OPEN_FAILED",
    "VIDEO_TRACK_NOT_FOUND",
    "YOLO_TENSOR_CONTRACT_MISMATCH",
    "NATIVE_OPERATION_FAILED",
    "EXPORT_INTERRUPTED",
    "COMPARISON_OPERATION_FAILED"
  ]

  static func safeCode(_ code: String?) -> String {
    guard let code, codes.contains(code) else { return "NATIVE_OPERATION_FAILED" }
    return code
  }

  static func message(code: String?, stage: Stage) -> String {
    "stage=\(stage.rawValue); code=\(safeCode(code))"
  }

  static func pigeon(_ error: Error, stage: Stage, fallbackCode: String = "NATIVE_OPERATION_FAILED") -> PigeonError {
    let code = safeCode((error as? PigeonError)?.code ??
      (error as? IOSComparisonExportError)?.code ?? fallbackCode)
    // Do not inspect NSError.userInfo, nested descriptions, stack or arbitrary object descriptions.
    return PigeonError(code: code, message: message(code: code, stage: stage), details: nil)
  }

  static func comparisonMessage(_ value: String?) -> String? {
    guard let value else { return nil }
    // Existing actionable fixed messages are retained; legacy arbitrary errors are projected.
    let fixed: Set<String> = [
      "视频已导出，记录保存失败；请保留本地文件",
      "iOS 已停止后台导出，回到应用后可以重试",
      "视频已导出；未获得添加相册权限，可授权后再次保存",
      "视频已导出，暂时无法记录相册保存状态，可以再次保存",
      "已保存到相册，保存记录暂不可用",
      "存储空间不足，请释放空间后重试", "上次导出已中断，可以重试",
      "视频已导出，保存相册失败，可以再次保存", "视频已导出，保存相册失败，可以重试保存",
      "视频暂时无法播放。", "导出未完成，请重试或重新关联源视频",
    ]
    return fixed.contains(value) ? value : "导出未完成，请重试或重新关联源视频"
  }
}

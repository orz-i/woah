import Foundation

enum IOSDiagnosticPrivacySmoke {
  static func run(plugin: DanceNativePlugin) async throws -> [String: Any] {
    func require(_ value: Bool, _ check: String) throws {
      if !value { throw PigeonError(code: "IOS_DIAGNOSTIC_PRIVACY_SMOKE_FAILED",
        message: check, details: nil) }
    }
    let secrets = ["/Users/测试 用户/私密 舞蹈.mp4", "file:///tmp/私密%20舞蹈.mp4",
      "content://vendor/primary%3ADCIM%2F私密.mp4", "C:\\Users\\私密 视频.mp4",
      "\\\\server\\share\\私密.mp4", "私密 舞蹈.mp4"]
    for secret in secrets {
      let nested = NSError(domain: secret, code: 42, userInfo: [
        NSLocalizedDescriptionKey: secret,
        NSUnderlyingErrorKey: NSError(domain: secret, code: 7,
          userInfo: [NSLocalizedDescriptionKey: secret]),
        "nested": [secret, ["message": secret]],
      ])
      for error in [nested as Error,
        PigeonError(code: "EXPORT_BUSY", message: secret, details: ["nested": [secret]])] {
        let safe = IOSDiagnosticPrivacy.pigeon(error, stage: .export)
        let encoded = try JSONSerialization.data(withJSONObject: [
          "code": safe.code, "message": safe.message ?? "",
        ])
        let text = String(decoding: encoded, as: UTF8.self)
        try require(!text.contains("私密") && safe.details == nil, "nested_error_projection")
        try require(text.contains("stage=export"), "stage_preserved")
      }
      try require(IOSDiagnosticPrivacy.safeCode(secret) == "NATIVE_OPERATION_FAILED", "unknown_code")
      try require(IOSDiagnosticPrivacy.comparisonMessage(secret) ==
        "导出未完成，请重试或重新关联源视频", "legacy_error_projection")
    }
    for code in ["CACHE_OWNER_CLOSED", "CACHE_ID_COLLISION", "CACHE_NOT_RETAINED",
      "JOB_NOT_FOUND", "INVALID_VIDEO", "INVALID_TRIM_RANGE", "DECODER_UNAVAILABLE",
      "ENCODER_UNAVAILABLE", "DECODE_FAILED", "FRAME_CONVERSION_FAILED",
      "AUDIO_DECODER_UNAVAILABLE", "AUDIO_WRITER_UNAVAILABLE", "AUDIO_TIMING_FAILED",
      "PLATFORM_NOT_SUPPORTED"] {
      try require(IOSDiagnosticPrivacy.safeCode(code) == code, "known_code")
    }
    let comparison = IOSDiagnosticPrivacy.pigeon(IOSComparisonExportError(
      code: "COMPARISON_EXPORT_AUDIO", message: secrets[0]), stage: .comparison)
    try require(comparison.code == "COMPARISON_EXPORT_AUDIO" && comparison.details == nil,
      "comparison_code")
    for text in ["视频已导出，记录保存失败；请保留本地文件",
      "iOS 已停止后台导出，回到应用后可以重试",
      "视频已导出；未获得添加相册权限，可授权后再次保存",
      "视频已导出，暂时无法记录相册保存状态，可以再次保存",
      "已保存到相册，保存记录暂不可用"] {
      try require(IOSDiagnosticPrivacy.comparisonMessage(text) == text, "recovery_guidance")
    }
    let root = FileManager.default.temporaryDirectory
      .appendingPathComponent("woah_privacy_smoke_\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: root) }
    let missing = root.appendingPathComponent("私密 舞蹈.mp4").path
    do {
      _ = try await plugin.probeVideo(uri: missing)
      try require(false, "missing_fixture_must_fail")
    } catch let safe as PigeonError {
      try require(safe.details == nil && !(safe.message ?? "").contains(missing) &&
        (safe.message ?? "").contains("stage=probe"), "real_channel_boundary")
    }
    let status = try await plugin.getJobStatus(jobId: secrets[0])
    try require(status.jobId == secrets[0] && status.errorCode == "JOB_NOT_FOUND" &&
      !(status.errorMessage ?? "").contains("私密"), "status_business_id_unchanged")
    try require(IOSVideoProbe.assetURL(from: missing).path == missing, "business_uri_unchanged")
    return ["checks": 14, "nested_error_redacted": true, "actual_channel_boundary": true,
      "known_codes_preserved": true, "business_uri_unchanged": true]
  }
}

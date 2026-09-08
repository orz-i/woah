import Flutter
import UIKit

public class DanceNativePlugin: NSObject, FlutterPlugin, DanceNativeApi {
  private let mediaBridge = IOSMediaLibraryBridge()
  private let yoloRunner = IOSYoloRunner()
  private let analysisCache = IOSAnalysisCache()
  private lazy var analyzePipeline = IOSAnalyzePipeline(
    cache: analysisCache,
    runner: yoloRunner
  )

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "dance_native", binaryMessenger: registrar.messenger())
    let instance = DanceNativePlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
    let buildInfoChannel = FlutterMethodChannel(
      name: "art.gaoge.dance/build_info",
      binaryMessenger: registrar.messenger()
    )
    registrar.addMethodCallDelegate(instance, channel: buildInfoChannel)
    DanceNativeApiSetup.setUp(binaryMessenger: registrar.messenger(), api: instance)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "getPlatformVersion":
      result("iOS " + UIDevice.current.systemVersion)
    case "getBuildInfo":
      result(Self.buildInfo())
    case "saveVideoToGallery":
      guard let arguments = call.arguments as? [String: Any],
            let filePath = arguments["filePath"] as? String,
            !filePath.isEmpty else {
        result(FlutterError(
          code: "INVALID_ARGS",
          message: "filePath is required.",
          details: nil
        ))
        return
      }
      Task {
        do {
          let uri = try await mediaBridge.saveVideoToGallery(filePath: filePath)
          await MainActor.run { result(uri) }
        } catch {
          let flutterError = Self.flutterError(from: error, fallbackCode: "SAVE_VIDEO_FAILED")
          await MainActor.run { result(flutterError) }
        }
      }
    case "shareVideo":
      guard let arguments = call.arguments as? [String: Any],
            let publicUri = arguments["publicUri"] as? String,
            !publicUri.isEmpty else {
        result(FlutterError(
          code: "INVALID_ARGS",
          message: "publicUri is required.",
          details: nil
        ))
        return
      }
      Task {
        do {
          try await mediaBridge.shareVideo(publicUri: publicUri)
          await MainActor.run { result(nil) }
        } catch {
          let flutterError = Self.flutterError(from: error, fallbackCode: "SHARE_VIDEO_FAILED")
          await MainActor.run { result(flutterError) }
        }
      }
    case "openVideo":
      guard let arguments = call.arguments as? [String: Any],
            let publicUri = arguments["publicUri"] as? String,
            !publicUri.isEmpty else {
        result(FlutterError(
          code: "INVALID_ARGS",
          message: "publicUri is required.",
          details: nil
        ))
        return
      }
      Task {
        do {
          try await mediaBridge.openVideo(publicUri: publicUri)
          await MainActor.run { result(nil) }
        } catch {
          let flutterError = Self.flutterError(from: error, fallbackCode: "OPEN_VIDEO_FAILED")
          await MainActor.run { result(flutterError) }
        }
      }
    case "getVideoFrameThumbnails":
      guard let arguments = call.arguments as? [String: Any],
            let videoUri = arguments["videoUri"] as? String,
            let rawTimestamps = arguments["timestampsMs"] as? [Any] else {
        result(FlutterError(
          code: "INVALID_ARGS",
          message: "videoUri and timestampsMs are required.",
          details: nil
        ))
        return
      }
      let timestampsMs = rawTimestamps.compactMap {
        ($0 as? NSNumber)?.int64Value
      }
      guard timestampsMs.count == rawTimestamps.count else {
        result(FlutterError(
          code: "INVALID_ARGS",
          message: "timestampsMs must contain only integer timestamps.",
          details: nil
        ))
        return
      }
      Task {
        do {
          let paths = try await mediaBridge.createTrimThumbnails(
            videoUri: videoUri,
            timestampsMs: timestampsMs
          )
          await MainActor.run { result(paths) }
        } catch {
          let flutterError = Self.flutterError(from: error, fallbackCode: "THUMBNAIL_FAILED")
          await MainActor.run { result(flutterError) }
        }
      }
    case "runIOSYoloPhase1Probe":
      guard let arguments = call.arguments as? [String: Any],
            let videoUri = arguments["videoUri"] as? String,
            !videoUri.isEmpty else {
        result(FlutterError(
          code: "INVALID_ARGS",
          message: "videoUri is required.",
          details: nil
        ))
        return
      }
      let timestampMs = (arguments["timestampMs"] as? NSNumber)?.int64Value ?? 0
      let backendName = (arguments["backend"] as? String) ?? "auto"
      let requestedBackend: IOSYoloBackend?
      if backendName == "auto" {
        requestedBackend = nil
      } else if let backend = IOSYoloBackend(rawValue: backendName) {
        requestedBackend = backend
      } else {
        result(FlutterError(
          code: "INVALID_ARGS",
          message: "backend must be auto, tflite_coreml, tflite_metal, or tflite_xnnpack.",
          details: nil
        ))
        return
      }
      Task {
        do {
          let report = try await IOSYoloPhase1Probe.run(
            videoUri: videoUri,
            timestampMs: timestampMs,
            requestedBackend: requestedBackend,
            runner: yoloRunner
          )
          await MainActor.run { result(report) }
        } catch {
          let flutterError = Self.flutterError(
            from: error,
            fallbackCode: "IOS_YOLO_PHASE1_PROBE_FAILED"
          )
          await MainActor.run { result(flutterError) }
        }
      }
    case "runIOSYoloPhase1BundledProbe":
      let arguments = call.arguments as? [String: Any]
      let backendName = (arguments?["backend"] as? String) ?? "auto"
      let requestedBackend: IOSYoloBackend?
      if backendName == "auto" {
        requestedBackend = nil
      } else if let backend = IOSYoloBackend(rawValue: backendName) {
        requestedBackend = backend
      } else {
        result(FlutterError(
          code: "INVALID_ARGS",
          message: "backend must be auto, tflite_coreml, tflite_metal, or tflite_xnnpack.",
          details: nil
        ))
        return
      }
      Task {
        do {
          let report = try IOSYoloPhase1Probe.runBundledFixture(
            requestedBackend: requestedBackend,
            runner: yoloRunner
          )
          await MainActor.run { result(report) }
        } catch {
          let flutterError = Self.flutterError(
            from: error,
            fallbackCode: "IOS_YOLO_PHASE1_BUNDLED_PROBE_FAILED"
          )
          await MainActor.run { result(flutterError) }
        }
      }
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  // MARK: - DanceNativeApi Protocol Implementation

  func getCapabilities() async throws -> NativeCapabilitiesDto {
    return IOSDeviceCapabilities.detect()
  }

  func probeVideo(uri: String) async throws -> VideoInfoDto {
    return try await IOSVideoProbe.probe(uri: uri)
  }

  func analyzeVideo(request: AnalyzeRequestDto) async throws -> AnalyzeResultDto {
    return try await analyzePipeline.analyze(request: request)
  }

  func getPreviewFrame(request: PreviewRequestDto) async throws -> PreviewFrameDto {
    throw PigeonError(code: "PLATFORM_NOT_SUPPORTED", message: "iOS preview rendering pipeline will be supported in future releases", details: nil)
  }

  func startExport(request: ExportRequestDto) async throws -> String {
    throw PigeonError(code: "PLATFORM_NOT_SUPPORTED", message: "iOS export pipeline is not implemented yet", details: nil)
  }

  func cancelJob(jobId: String) async throws {
    // Graceful no-op on iOS stub
  }

  func getJobStatus(jobId: String) async throws -> JobStatusDto {
    return JobStatusDto(
      jobId: jobId,
      state: "failed",
      currentFrame: 0,
      totalFrames: 0,
      fps: 0,
      progress: 0,
      outputUri: nil,
      errorCode: "PLATFORM_NOT_SUPPORTED",
      errorMessage: "iOS export pipeline is not implemented yet"
    )
  }

  func releaseProject(projectId: String) async throws {
    guard !projectId.isEmpty else { return }
    try analysisCache.clearAnalysisCache(cacheId: projectId)
  }

  private static func buildInfo() -> [String: Any] {
    let bundle = Bundle.main
    var info: [String: Any] = [
      "versionName": bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0",
      "versionCode": bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1",
    ]
#if DEBUG
    info["buildType"] = "debug"
#else
    info["buildType"] = "release"
#endif

    let commitCandidates: [String?] = [
      bundle.object(forInfoDictionaryKey: "WoahGitCommit") as? String,
      ProcessInfo.processInfo.environment["WOAH_GIT_COMMIT"],
    ]
    if let commit = commitCandidates
      .compactMap({ $0?.trimmingCharacters(in: .whitespacesAndNewlines) })
      .first(where: { !$0.isEmpty && !$0.contains("$(") }) {
      info["gitCommit"] = commit
    }
    return info
  }

  private static func flutterError(
    from error: Error,
    fallbackCode: String
  ) -> FlutterError {
    if let pigeonError = error as? PigeonError {
      return FlutterError(
        code: pigeonError.code,
        message: pigeonError.message,
        details: pigeonError.details
      )
    }
    return FlutterError(
      code: fallbackCode,
      message: String(describing: error),
      details: nil
    )
  }
}

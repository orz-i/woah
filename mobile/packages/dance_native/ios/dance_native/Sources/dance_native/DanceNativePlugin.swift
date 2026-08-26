import Flutter
import UIKit
import AVFoundation

public class DanceNativePlugin: NSObject, FlutterPlugin, DanceNativeApi {

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "dance_native", binaryMessenger: registrar.messenger())
    let instance = DanceNativePlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
    DanceNativeApiSetup.setUp(binaryMessenger: registrar.messenger(), api: instance)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "getPlatformVersion":
      result("iOS " + UIDevice.current.systemVersion)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  // MARK: - DanceNativeApi Protocol Implementation

  public func getCapabilities() async throws -> NativeCapabilitiesDto {
    return NativeCapabilitiesDto(
      platform: "ios",
      osVersion: UIDevice.current.systemVersion,
      gpuSupported: true,
      h264Encoder: true,
      hevcEncoder: true,
      maxEncodeWidth: 3840,
      maxEncodeHeight: 2160,
      cpuCores: Int64(ProcessInfo.processInfo.processorCount),
      recommendedProfile: "balanced",
      supportedProfiles: ["balanced"],
      inferenceBackends: ["coreml", "mps"]
    )
  }

  public func probeVideo(uri: String) async throws -> VideoInfoDto {
    let url: URL
    if uri.hasPrefix("file://") {
      url = URL(fileURLWithPath: String(uri.dropFirst(7)))
    } else if let parsed = URL(string: uri) {
      url = parsed
    } else {
      url = URL(fileURLWithPath: uri)
    }

    let asset = AVURLAsset(url: url)
    guard let track = try await asset.loadTracks(withMediaType: .video).first else {
      throw PigeonError(code: "VIDEO_TRACK_NOT_FOUND", message: "No video track found in \(uri)", details: nil)
    }

    let naturalSize = try await track.load(.naturalSize)
    let nominalFrameRate = try await track.load(.nominalFrameRate)
    let duration = try await asset.load(.duration)
    let durationMs = Int64(CMTimeGetSeconds(duration) * 1000.0)
    let audioTracks = try await asset.loadTracks(withMediaType: .audio)

    let width = Int64(naturalSize.width)
    let height = Int64(naturalSize.height)

    return VideoInfoDto(
      codedWidth: width,
      codedHeight: height,
      displayWidth: width,
      displayHeight: height,
      fps: Double(nominalFrameRate),
      durationMs: durationMs,
      rotation: 0,
      videoCodec: "video/avc",
      audioCodec: audioTracks.isEmpty ? nil : "audio/mp4a-latm",
      hasAudio: !audioTracks.isEmpty
    )
  }

  public func analyzeVideo(request: AnalyzeRequestDto) async throws -> AnalyzeResultDto {
    throw PigeonError(code: "NOT_IMPLEMENTED", message: "iOS CoreML analysis pipeline is in development", details: nil)
  }

  public func getPreviewFrame(request: PreviewRequestDto) async throws -> PreviewFrameDto {
    throw PigeonError(code: "NOT_IMPLEMENTED", message: "iOS preview pipeline is in development", details: nil)
  }

  public func startExport(request: ExportRequestDto) async throws -> String {
    throw PigeonError(code: "NOT_IMPLEMENTED", message: "iOS export pipeline is in development", details: nil)
  }

  public func cancelJob(jobId: String) async throws {
    // No-op for stub
  }

  public func getJobStatus(jobId: String) async throws -> JobStatusDto {
    return JobStatusDto(
      jobId: jobId,
      state: "failed",
      currentFrame: 0,
      totalFrames: 0,
      fps: 0,
      progress: 0,
      outputUri: nil,
      errorCode: "NOT_IMPLEMENTED",
      errorMessage: "iOS background export pipeline is in development"
    )
  }

  public func releaseProject(projectId: String) async throws {
    // No-op
  }
}

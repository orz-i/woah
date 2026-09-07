import AVFoundation
import Foundation

enum IOSVideoProbe {
  static func assetURL(from uri: String) -> URL {
    if let parsed = URL(string: uri),
       let scheme = parsed.scheme,
       !scheme.isEmpty {
      return parsed
    }
    return URL(fileURLWithPath: uri)
  }

  static func probe(uri: String) async throws -> VideoInfoDto {
    let url = assetURL(from: uri)
    let securityScoped = url.isFileURL && url.startAccessingSecurityScopedResource()
    defer {
      if securityScoped {
        url.stopAccessingSecurityScopedResource()
      }
    }

    let asset = AVURLAsset(url: url)
    guard let track = try await asset.loadTracks(withMediaType: .video).first else {
      throw PigeonError(
        code: "VIDEO_TRACK_NOT_FOUND",
        message: "No video track found in \(uri)",
        details: nil
      )
    }

    let naturalSize = try await track.load(.naturalSize)
    let transform = try await track.load(.preferredTransform)
    let nominalFrameRate = try await track.load(.nominalFrameRate)
    let duration = try await asset.load(.duration)
    let videoFormatDescriptions = try await track.load(.formatDescriptions)
    let audioTracks = try await asset.loadTracks(withMediaType: .audio)

    let codedWidth = Int64(abs(naturalSize.width).rounded())
    let codedHeight = Int64(abs(naturalSize.height).rounded())
    let displayRect = CGRect(origin: .zero, size: naturalSize)
      .applying(transform)
      .standardized
    let displayWidth = Int64(abs(displayRect.width).rounded())
    let displayHeight = Int64(abs(displayRect.height).rounded())

    var fps = Double(nominalFrameRate)
    if !fps.isFinite || fps <= 0,
       let minFrameDuration = try? await track.load(.minFrameDuration) {
      let secondsPerFrame = CMTimeGetSeconds(minFrameDuration)
      if secondsPerFrame.isFinite && secondsPerFrame > 0 {
        fps = 1.0 / secondsPerFrame
      }
    }
    if !fps.isFinite || fps < 0 {
      fps = 0
    }

    let durationSeconds = CMTimeGetSeconds(duration)
    let durationMs: Int64
    if durationSeconds.isFinite && durationSeconds > 0 {
      durationMs = Int64((durationSeconds * 1000.0).rounded())
    } else {
      durationMs = 0
    }

    let videoCodec = mimeType(
      for: videoFormatDescriptions.first,
      mediaKind: "video"
    ) ?? "video/unknown"

    var audioCodec: String?
    if let audioTrack = audioTracks.first,
       let audioDescriptions = try? await audioTrack.load(.formatDescriptions) {
      audioCodec = mimeType(for: audioDescriptions.first, mediaKind: "audio")
    }

    return VideoInfoDto(
      codedWidth: codedWidth,
      codedHeight: codedHeight,
      displayWidth: max(1, displayWidth),
      displayHeight: max(1, displayHeight),
      fps: fps,
      durationMs: durationMs,
      rotation: rotationDegrees(for: transform),
      videoCodec: videoCodec,
      audioCodec: audioCodec,
      hasAudio: !audioTracks.isEmpty
    )
  }

  private static func rotationDegrees(for transform: CGAffineTransform) -> Int64 {
    let radians = atan2(Double(transform.b), Double(transform.a))
    let degrees = radians * 180.0 / Double.pi
    let snapped = Int((degrees / 90.0).rounded()) * 90
    let normalized = (snapped % 360 + 360) % 360
    return Int64(normalized)
  }

  private static func mimeType(
    for description: CMFormatDescription?,
    mediaKind: String
  ) -> String? {
    guard let description = description else { return nil }
    let code = CMFormatDescriptionGetMediaSubType(description)
    let fourCC = fourCCString(code).lowercased()
    switch (mediaKind, fourCC) {
    case ("video", "avc1"), ("video", "avc3"):
      return "video/avc"
    case ("video", "hvc1"), ("video", "hev1"):
      return "video/hevc"
    case ("video", "av01"):
      return "video/av01"
    case ("video", "vp09"):
      return "video/x-vnd.on2.vp9"
    case ("audio", "mp4a"):
      return "audio/mp4a-latm"
    case ("audio", "alac"):
      return "audio/alac"
    case ("audio", "ac-3"):
      return "audio/ac3"
    case ("audio", "ec-3"):
      return "audio/eac3"
    default:
      guard !fourCC.isEmpty else { return nil }
      return "\(mediaKind)/\(fourCC)"
    }
  }

  private static func fourCCString(_ code: FourCharCode) -> String {
    let bytes: [UInt8] = [
      UInt8((code >> 24) & 0xff),
      UInt8((code >> 16) & 0xff),
      UInt8((code >> 8) & 0xff),
      UInt8(code & 0xff),
    ]
    return String(bytes: bytes, encoding: .ascii) ?? ""
  }
}

import Foundation
@_implementationOnly import FrameKernelC

struct IOSComparisonMusicCandidate {
  let offsetUs: Int64
  let score: Double
  let consistentWindows: Int64
  let supportedSeconds: Double
  let residualMs: Double
  var timeScale: Double = 1
}
struct IOSComparisonMusicResult {
  let status: String
  let message: String
  let candidates: [IOSComparisonMusicCandidate]
}
private final class MusicCancellation {
  let isCancelled: () -> Bool
  init(_ isCancelled: @escaping () -> Bool) { self.isCancelled = isCancelled }
}

/// Native transport and product messages; all matching decisions are Rust.
enum IOSComparisonMusicMatcher {
  static func match(reference: IOSComparisonMusicFeatures, target: IOSComparisonMusicFeatures,
    isCancelled: () -> Bool = { false }) throws -> IOSComparisonMusicResult {
    try iosComparisonMusicCheck(isCancelled)
    let result = withoutActuallyEscaping(isCancelled) { closure in
      let box = MusicCancellation(closure)
      let callback: @convention(c) (UnsafeMutableRawPointer?) -> UInt32 = { pointer in
        guard let pointer else { return 0 }
        return Unmanaged<MusicCancellation>.fromOpaque(pointer).takeUnretainedValue().isCancelled() ? 0 : 1
      }
      return withExtendedLifetime(box) {
        reference.payload.withUnsafeBytes { r in target.payload.withUnsafeBytes { t in
          frame_kernel_audio_align(r.bindMemory(to: UInt8.self).baseAddress, r.count,
            t.bindMemory(to: UInt8.self).baseAddress, t.count, callback, Unmanaged.passUnretained(box).toOpaque())
        } }
      }
    }
    try iosComparisonMusicCheck(isCancelled)
    let status: String, message: String
    switch result.status {
    case 0: status = "matched"; message = "已按共同音乐的速率和位置对齐，可随时手动校准。"
    case 1: status = "no_match"; message = "没有找到可靠的共同音乐，可以手动校准。"
    case 2: status = "inconsistent"; message = "未找到稳定的音乐对应关系，请用前后两组音乐点手动校准。"
    case 3: status = "ambiguous"; message = "找到多个相似乐段，已保留最佳位置，可手动校准。"
    case 4: throw IOSComparisonMusicError.cancelled
    default: throw IOSComparisonMusicError.decode("音乐特征无效，请手动校准。")
    }
    let candidates = [result.candidates.0, result.candidates.1, result.candidates.2].prefix(Int(result.count)).map {
      IOSComparisonMusicCandidate(offsetUs: $0.offset, score: $0.score, consistentWindows: Int64($0.windows),
        supportedSeconds: $0.support, residualMs: $0.residual, timeScale: $0.scale)
    }
    return IOSComparisonMusicResult(status: status, message: message, candidates: candidates)
  }
}

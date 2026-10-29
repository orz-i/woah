import Foundation

final class IOSComparisonMusicAligner {
  /// Synchronous background entry point. Cancellation throws instead of
  /// publishing an obsolete candidate into a newer comparison session.
  func align(referenceURL: URL, targetURL: URL,
    isCancelled: () -> Bool) throws -> MusicAlignmentDto {
    let decoder = IOSComparisonMusicPcmDecoder()
    do {
      let reference = try decoder.features(url: referenceURL, isCancelled: isCancelled)
      let target = try decoder.features(url: targetURL, isCancelled: isCancelled)
      let result = try IOSComparisonMusicMatcher.match(reference: reference, target: target, isCancelled: isCancelled)
      try iosComparisonMusicCheck(isCancelled)
      return MusicAlignmentDto(status: result.status, message: result.message, candidates: result.candidates.map {
        MusicCandidateDto(offsetUs: $0.offsetUs, score: $0.score,
          consistentWindows: $0.consistentWindows, supportedSeconds: $0.supportedSeconds,
          residualMs: $0.residualMs, timeScale: $0.timeScale)
      })
    } catch IOSComparisonMusicError.noAudio {
      try iosComparisonMusicCheck(isCancelled)
      return MusicAlignmentDto(status: "no_audio", message: "视频没有音轨，请手动校准。", candidates: [])
    } catch IOSComparisonMusicError.tooLong {
      try iosComparisonMusicCheck(isCancelled)
      return MusicAlignmentDto(status: "unsupported", message: IOSComparisonMusicError.tooLong.localizedDescription, candidates: [])
    } catch IOSComparisonMusicError.discontinuousAudio {
      try iosComparisonMusicCheck(isCancelled)
      return MusicAlignmentDto(status: "inconsistent", message: IOSComparisonMusicError.discontinuousAudio.localizedDescription, candidates: [])
    }
  }
}

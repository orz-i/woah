import AVFoundation
import Foundation

/// Visible source-track presentation timestamps. Compressed reads can arrive in
/// decode order and retain media PTS before an edit-list shift; use output timing
/// in the asset's presentation coordinates before sorting for frame adjacency.
struct IOSComparisonFrameIndex: Sendable {
  let presentationTimesUs: [Int64]

  init(presentationTimesUs: [Int64]) {
    self.presentationTimesUs = Array(Set(presentationTimesUs)).sorted()
  }

  static func read(url: URL, isCancelled: () -> Bool) throws -> Self {
    let scoped = url.isFileURL && url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    if isCancelled() { throw CancellationError() }
    let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
    guard let track = asset.tracks(withMediaType: .video).first else {
      throw NSError(domain: "WoahComparison", code: 1,
        userInfo: [NSLocalizedDescriptionKey: "视频没有可读取的画面轨道。"])
    }
    let reader = try AVAssetReader(asset: asset)
    defer { reader.cancelReading() }
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else {
      throw NSError(domain: "WoahComparison", code: 2,
        userInfo: [NSLocalizedDescriptionKey: "无法读取视频帧时间。"])
    }
    reader.add(output)
    guard reader.startReading() else { throw reader.error ?? CancellationError() }
    let range = track.timeRange
    var values: [Int64] = []
    while true {
      if isCancelled() { throw CancellationError() }
      let found = try autoreleasepool { () throws -> Bool in
        guard let sample = output.copyNextSampleBuffer() else { return false }
        let count = CMSampleBufferGetNumSamples(sample)
        guard count > 0 else { return true } // Empty edit / decoder marker.
        var timings = Array(repeating: CMSampleTimingInfo(), count: count)
        var needed = 0
        let timingStatus = timings.withUnsafeMutableBufferPointer {
          CMSampleBufferGetOutputSampleTimingInfoArray(sample, entryCount: count,
            arrayToFill: $0.baseAddress, entriesNeededOut: &needed)
        }
        guard timingStatus == noErr, needed == 1 || needed == count else {
          throw NSError(domain: "WoahComparison", code: 3,
            userInfo: [NSLocalizedDescriptionKey: "视频帧时间不可用。"])
        }
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[String: Any]]
        for index in 0..<count {
          if let attachments, index < attachments.count,
             (attachments[index][kCMSampleAttachmentKey_DoNotDisplay as String] as? NSNumber)?.boolValue == true {
            continue
          }
          let timing = timings[needed == 1 ? 0 : index]
          let pts = needed == 1 && index > 0
            ? CMTimeAdd(timing.presentationTimeStamp, CMTimeMultiplyByFloat64(timing.duration, multiplier: Double(index)))
            : timing.presentationTimeStamp
          guard pts.isNumeric, CMTimeCompare(pts, range.start) >= 0,
                CMTimeCompare(pts, CMTimeRangeGetEnd(range)) < 0 else { continue }
          let converted = CMTimeConvertScale(pts, timescale: 1_000_000, method: .roundHalfAwayFromZero)
          guard converted.isNumeric, abs(Double(converted.value)) < Double(Int64.max) / 4 else { continue }
          values.append(converted.value)
        }
        return true
      }
      if !found { break }
    }
    if isCancelled() { throw CancellationError() }
    guard reader.status == .completed else { throw reader.error ?? CancellationError() }
    guard !values.isEmpty else {
      throw NSError(domain: "WoahComparison", code: 4,
        userInfo: [NSLocalizedDescriptionKey: "视频没有可供逐帧查看的样本。"])
    }
    return Self(presentationTimesUs: values)
  }

  func frame(atOrBefore timeUs: Int64) -> Int64? {
    IOSComparisonTimeCore.frameIndex(presentationTimesUs, time: timeUs).map { presentationTimesUs[$0] }
  }

  func frame(atOrBefore time: CMTime) -> Int64? {
    IOSComparisonTimeCore.map(time).flatMap { frame(atOrBefore: $0) }
  }

  func adjacent(to timeUs: Int64, forward: Bool, startUs: Int64, endUs: Int64) -> Int64? {
    IOSComparisonTimeCore.frameIndex(presentationTimesUs, time: timeUs,
      start: startUs, end: endUs, mode: forward ? 1 : 2).map { presentationTimesUs[$0] }
  }
}

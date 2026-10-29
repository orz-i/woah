import AVFoundation
import Foundation

// swiftc -swift-version 5 IOSComparisonFrameIndex.swift tests/comparison_frame_index/main.swift -o /tmp/woah-frame-index
// /tmp/woah-frame-index /path/to/comparison-fixtures/teacher.mp4
// The iOS smoke teacher fixture contains B frames, a shifted edit list, and VFR
// gaps. This gate compares compressed indexing against independently decoded PTS.
precondition(CommandLine.arguments.count == 2, "Pass the iOS smoke teacher fixture path")
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
let track = asset.tracks(withMediaType: .video).first!
precondition(track.segments.contains {
  !$0.isEmpty && CMTimeCompare($0.timeMapping.source.start, $0.timeMapping.target.start) != 0
}, "Fixture must exercise an edit-list shift")

func samples(decoded: Bool) throws -> [CMTime] {
  let reader = try AVAssetReader(asset: asset)
  defer { reader.cancelReading() }
  let output = AVAssetReaderTrackOutput(track: track, outputSettings: decoded
    ? [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA] : nil)
  reader.add(output)
  precondition(reader.startReading())
  var times: [CMTime] = []
  while let sample = output.copyNextSampleBuffer() {
    guard CMSampleBufferGetNumSamples(sample) > 0 else { continue }
    times.append(decoded ? CMSampleBufferGetOutputPresentationTimeStamp(sample)
      : CMSampleBufferGetPresentationTimeStamp(sample))
  }
  precondition(reader.status == .completed, "Fixture did not decode completely")
  return times
}
let mediaTimes = try samples(decoded: false)
precondition(zip(mediaTimes, mediaTimes.dropFirst()).contains { CMTimeCompare($0.0, $0.1) > 0 },
  "Fixture must exercise B-frame decode-order reordering")
let visible = try samples(decoded: true).map {
  CMTimeConvertScale($0, timescale: 1_000_000, method: .roundHalfAwayFromZero).value
}.sorted()
let indexed = try IOSComparisonFrameIndex.read(url: url, isCancelled: { false })
precondition(indexed.presentationTimesUs == visible,
  "Compressed output-timing index differs from independently decoded visible PTS")
precondition(indexed.presentationTimesUs.first == 0)
precondition(indexed.frame(atOrBefore: 280_000) == 266_667)
precondition(indexed.adjacent(to: 280_000, forward: true, startUs: 0, endUs: 23_000_000) == 333_333)
precondition(!indexed.presentationTimesUs.contains(300_000), "An undisplayed media PTS leaked into the index")
let targetURL = url.deletingLastPathComponent().appendingPathComponent("learner.mp4")
let targetIndex = try IOSComparisonFrameIndex.read(url: targetURL, isCancelled: { false })
// The real fixture's target frame 80 starts at 8/3s. Keep the reference's 11/6s
// rational precision through .8x + 1.2s before the index's final us conversion.
let mappedBoundary = CMTimeAdd(CMTimeMultiplyByFloat64(CMTime(value: 11, timescale: 6), multiplier: 0.8),
  CMTime(value: 1_200_000, timescale: 1_000_000))
precondition(targetIndex.frame(atOrBefore: mappedBoundary) == 2_666_667,
  "Rational target frame boundary selected the preceding visible frame")
precondition(targetIndex.frame(atOrBefore: Int64(2_666_666)) != 2_666_667,
  "Fixture must distinguish the early-rounded boundary")
print("Comparison B-frame/edit-list/VFR index matches decoded visible PTS (\(visible.count) frames)")
print("Comparison exact 11/6s reference -> 8/3s target frame boundary passed")

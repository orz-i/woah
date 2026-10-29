import AVFoundation
import Foundation

// Native reader/format gate, separate from the portable matching suite.
// swiftc -O IOSComparisonMusicFeatures.swift IOSComparisonMusicPcmDecoder.swift \
//   tests/comparison_music_pcm/main.swift -o music-pcm-tests

let directory = FileManager.default.temporaryDirectory
  .appendingPathComponent("woah-music-pcm-\(UUID().uuidString)", isDirectory: true)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: directory) }

func writePCM(rate: Double, channels: AVAudioChannelCount, name: String) throws -> URL {
  let url = directory.appendingPathComponent(name).appendingPathExtension("caf")
  let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: channels)!
  let file = try AVAudioFile(forWriting: url, settings: format.settings,
    commonFormat: .pcmFormatFloat32, interleaved: false)
  let count = AVAudioFrameCount(rate * 2)
  let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: count)!
  buffer.frameLength = count
  for c in 0..<Int(channels) {
    for i in 0..<Int(count) {
      let t = Double(i) / rate
      // Stereo uses different signals, so the reader must actually mix channels.
      let frequency = c == 0 ? 440.0 : 660.0
      buffer.floatChannelData![c][i] = Float(0.3 * sin(t * frequency * 2 * .pi))
    }
  }
  try file.write(from: buffer)
  return url
}

let decoder = IOSComparisonMusicPcmDecoder()
for rate in [16_000.0, 44_100, 48_000] {
  for channels: AVAudioChannelCount in [1, 2] {
    let url = try writePCM(rate: rate, channels: channels, name: "\(Int(rate))-\(channels)")
    let f = try decoder.features(url: url, isCancelled: { false })
    precondition(abs(f.startUs - 32_000) <= 100, "Wrong origin at \(rate) Hz: \(f.startUs)")
    precondition((193...195).contains(f.frames.count), "Resample lost tail: \(rate), \(f.frames.count)")
    precondition(abs(f.endUs - 1_972_000) < 20_000)
    precondition(f.frames.contains { !$0.peaks.isEmpty }, "No decoded signal")
    print("PASS AVAssetReader \(Int(rate)) Hz, \(channels) channel(s) -> mono 16 kHz")
  }
}

let fileURL = try writePCM(rate: 48_000, channels: 2, name: "source")
let source = AVURLAsset(url: fileURL)
let sourceTrack = source.tracks(withMediaType: .audio)[0]
let delayed = AVMutableComposition()
let delayedTrack = delayed.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
try delayedTrack.insertTimeRange(CMTimeRange(start: .zero, duration: CMTime(seconds: 2, preferredTimescale: 48_000)),
  of: sourceTrack, at: CMTime(seconds: 3.25, preferredTimescale: 48_000))
let delayedFeatures = try decoder.features(asset: delayed, isCancelled: { false })
// AVAssetReader can render the initial empty edit as timestamped silence.
// Locate actual signal rather than requiring the first decoded sample to be
// non-silent. FFT windows extend 32 ms before/after their center timestamp.
let firstSignalUs = delayedFeatures.frames.first { !$0.peaks.isEmpty }?.timeUs
precondition(firstSignalUs.map { (3_218_000...3_300_000).contains($0) } == true,
  "Audio edit-list signal moved on the source timeline: \(String(describing: firstSignalUs))")
precondition(delayedFeatures.endUs >= 5_210_000,
  "Audio edit-list tail was concatenated: \(delayedFeatures.endUs)")
print("PASS nonzero edit-list audio presentation origin")

let gapped = AVMutableComposition()
let gappedTrack = gapped.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
let oneSecond = CMTimeRange(start: .zero, duration: CMTime(seconds: 1, preferredTimescale: 48_000))
try gappedTrack.insertTimeRange(oneSecond, of: sourceTrack, at: .zero)
try gappedTrack.insertTimeRange(oneSecond, of: sourceTrack, at: CMTime(seconds: 1.5, preferredTimescale: 48_000))
do {
  let f = try decoder.features(asset: gapped, isCancelled: { false })
  // Some reader codecs explicitly render an empty edit as timestamped silence;
  // that remains a valid source timeline. Neither path may concatenate the gap.
  precondition(f.endUs >= 2_450_000, "Discontinuous audio edit was concatenated")
} catch IOSComparisonMusicError.discontinuousAudio {}
print("PASS audio edit gap is preserved or rejected")

do {
  _ = try decoder.features(asset: AVMutableComposition(), isCancelled: { false })
  fatalError("Missing audio accepted")
} catch IOSComparisonMusicError.noAudio {}
do {
  _ = try decoder.features(url: fileURL, isCancelled: { true })
  fatalError("Cancellation ignored")
} catch IOSComparisonMusicError.cancelled {}
var checks = 0
do {
  _ = try decoder.features(url: fileURL, isCancelled: { checks += 1; return checks > 3 })
  fatalError("Cancellation during decoding ignored")
} catch IOSComparisonMusicError.cancelled {}
print("PASS missing audio, immediate and in-flight decode cancellation")
print("Music native PCM regression suite passed")

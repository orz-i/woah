// Standalone regression: compile with IOSComparisonMusicFeatures.swift using
// xcrun swiftc, then run the resulting executable. No Flutter or media fixtures.
import Foundation

@main
struct ComparisonMusicFeaturesRegression {
  static func main() throws {
    let origin: Int64 = 123_000
    let silence = IOSComparisonMusicFeatureBuilder(originUs: origin)
    for _ in 0..<1184 { try silence.add(0) }
    let silent = silence.finish()
    precondition(silent.frames.count == 2)
    precondition(silent.frames.map(\.timeUs) == [155_000, 165_000])
    precondition(silent.frames.allSatisfy {
      $0.spectrum.allSatisfy { $0 == 0 } &&
      $0.onset.allSatisfy { $0 == 0 } &&
      $0.pitchSpectrum.allSatisfy { $0 == 0 } && $0.peaks.isEmpty
    })

    let tone = IOSComparisonMusicFeatureBuilder(originUs: origin)
    for sample in 0..<2624 {
      let phase: Double = 2.0 * Double.pi * 440.0 * Double(sample) / 16_000.0
      try tone.add(Float(0.5 * sin(phase)))
    }
    let features = tone.finish()
    precondition(features.frames.count == 11)
    for frame in features.frames {
      // 440 Hz lies closest to FFT bin 28 at 16 kHz / 1024.
      precondition(frame.peaks.first == 28)
      precondition(frame.spectrum.count == 16 && frame.onset.count == 16)
      precondition(frame.pitchSpectrum.count == 48)
      precondition((frame.spectrum + frame.onset + frame.pitchSpectrum).allSatisfy(\.isFinite))
    }
    precondition(features.endUs - features.startUs == 110_000)
    do {
      try tone.add(.nan)
      preconditionFailure("Non-finite PCM must be rejected")
    } catch IOSComparisonMusicError.decode { }
    print("IOS_COMPARISON_MUSIC_FEATURES_REGRESSION=PASS")
  }
}

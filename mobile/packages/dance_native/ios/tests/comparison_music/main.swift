import Foundation

// Portable music-core regression suite. Run with an optimized Swift compiler:
// swiftc -O IOSComparisonMusicFeatures.swift IOSComparisonMusicMatcher.swift \
//   IOSComparisonMusicRateMatcher.swift tests/comparison_music/main.swift -o music-tests

struct MusicRandom {
  var state: UInt64
  mutating func next() -> Double {
    state = state &* 6364136223846793005 &+ 1442695040888963407
    return Double(state >> 11) / Double(UInt64(1) << 53)
  }
}

func music(_ seconds: Double, rate: Double = 1, preservePitch: Bool = true,
  seed: UInt64 = 7, noisy: Bool = false) -> [Float] {
  var random = MusicRandom(state: seed)
  let notes = (0..<640).map { _ in 160 + floor(random.next() * 800) }
  return (0..<Int(seconds * 16_000)).map { i in
    let t = Double(i) / 16_000
    let beat = min(notes.count - 1, Int(t * rate * 8))
    let phase = t * (preservePitch ? 1 : rate) * notes[beat] * 2 * .pi
    let e = pow(1 - (t * rate * 8).truncatingRemainder(dividingBy: 1), 0.4)
    let tone = e * (0.25 * sin(phase) + 0.12 * sin(phase * 1.5) + 0.07 * sin(phase * 2.23))
    let noise = noisy ? 0.03 * (random.next() * 2 - 1) * exp(-(t * 4).truncatingRemainder(dividingBy: 1) * 20) : 0
    return Float(tone + noise)
  }
}

func features(_ pcm: [Float], originUs: Int64 = 0) throws -> IOSComparisonMusicFeatures {
  let builder = IOSComparisonMusicFeatureBuilder(originUs: originUs)
  for value in pcm { try builder.add(value) }
  return builder.finish()
}

func match(_ reference: [Float], _ target: [Float], referenceOrigin: Int64 = 0,
  targetOrigin: Int64 = 0) throws -> IOSComparisonMusicResult {
  try IOSComparisonMusicMatcher.match(reference: features(reference, originUs: referenceOrigin),
    target: features(target, originUs: targetOrigin))
}

func requireMatch(_ result: IOSComparisonMusicResult, _ offset: Int64, tolerance: Int64 = 30_000) {
  precondition(result.status == "matched", "Unexpected result: \(result)")
  precondition(abs(result.candidates[0].offsetUs - offset) < tolerance, "Unexpected mapping: \(result)")
}

let song = music(12, noisy: true)
var random = MusicRandom(state: 18)
let delayed = [Float](repeating: 0, count: 16_000) + song.map { $0 * 0.55 + Float(random.next() - 0.5) * 0.003 }
requireMatch(try match(song, delayed), 1_000_000)
requireMatch(try match([Float](repeating: 0, count: 16_000) + song, song,
  referenceOrigin: 30_000, targetOrigin: 10_000), -1_020_000)
let originFeatures = try features(Array(song.prefix(16_000)), originUs: 3_400_000)
precondition(originFeatures.startUs == 3_432_000)
print("PASS constant offsets, gain, noise, nonzero source PTS")

let full = music(18, noisy: true)
let trimmedReference = Array(full[(2 * 16_000)..<(13 * 16_000)])
let trimmedTarget = [Float](repeating: 0, count: 11_200) + Array(full[(5 * 16_000)..<(16 * 16_000)])
requireMatch(try match(trimmedReference, trimmedTarget), -2_300_000)
let background = music(12, seed: 290, noisy: true)
let recorded = [Float](repeating: 0, count: 24_000) + song.indices.map { i in
  0.42 * song[i] + (i >= 640 ? 0.20 * song[i - 640] : 0) +
    (i >= 1760 ? 0.10 * song[i - 1760] : 0) + 0.16 * background[i]
}
requireMatch(try match(song, recorded), 1_500_000)
print("PASS partial overlap and room reflections")

let silent = try match(song, [Float](repeating: 0, count: song.count))
precondition(silent.status == "no_match")
for seed: UInt64 in [124, 281, 901] {
  let unrelated = try match(song, music(12, seed: seed, noisy: true))
  precondition(unrelated.status != "matched", "Unrelated beat grid accepted: \(unrelated)")
}
let motif = music(4, noisy: true)
let repeats = try match(Array(repeating: motif, count: 3).flatMap { $0 },
  [Float](repeating: 0, count: 16_000) + Array(repeating: motif, count: 4).flatMap { $0 })
precondition(repeats.status == "ambiguous" && repeats.candidates.count >= 2, "\(repeats)")
var fragment = music(12, seed: 902, noisy: true)
fragment.replaceSubrange((4 * 16_000)..<(7 * 16_000), with: song[(4 * 16_000)..<(7 * 16_000)])
let insufficient = try match(song, fragment)
precondition(insufficient.status != "matched", "One shared fragment accepted: \(insufficient)")
print("PASS silence, unrelated same-beat songs, repeated sections, insufficient support")

let rateOriginal = try features(music(20), originUs: 30_000)
for (rate, pitch) in [(1.007, true), (1.25, true), (0.75, true), (0.83, true),
  (1.25, false), (0.75, false), (0.5, true), (2.0, false)] {
  let target = [Float](repeating: 0, count: 11_200) + music(20 / rate, rate: rate, preservePitch: pitch).map { $0 * 0.6 }
  let result = try IOSComparisonMusicMatcher.match(reference: rateOriginal,
    target: features(target, originUs: 90_000))
  precondition(result.status == "matched", "rate=\(rate) pitch=\(pitch): \(result)")
  let best = result.candidates[0]
  let scale = 1 / rate
  let intercept = 90_000 + 700_000 - scale * 30_000
  for t in [2_000_000.0, 10_000_000, 18_000_000] {
    precondition(abs(best.timeScale * t + Double(best.offsetUs) - (scale * t + intercept)) < 35_000,
      "rate=\(rate) pitch=\(pitch) t=\(t): \(result)")
  }
  print("PASS continuous rate \(rate), pitch preserved \(pitch)")
}
let nonzeroTarget = [Float](repeating: 0, count: 11_200) + music(16, rate: 1.25, preservePitch: false)
let negativeIntercept = try match(music(20), nonzeroTarget, referenceOrigin: 5_000_000, targetOrigin: 90_000)
precondition(negativeIntercept.status == "matched", "\(negativeIntercept)")
precondition(negativeIntercept.candidates[0].offsetUs < 0)
for t in [5_000_000.0, 15_000_000, 23_000_000] {
  let c = negativeIntercept.candidates[0]
  precondition(abs(c.timeScale * t + Double(c.offsetUs) - (0.8 * t + 790_000 - 0.8 * 5_000_000)) < 35_000)
}
let faster = music(16, rate: 1.25)
let tempoJump = music(10) + Array(faster[(8 * 16_000)..<(16 * 16_000)])
let inconsistent = try match(music(20), tempoJump)
precondition(inconsistent.status != "matched", "Tempo jump accepted: \(inconsistent)")
print("PASS negative affine intercept, source origins, changing tempo rejection")

let sample = try features(motif)
do {
  _ = try IOSComparisonMusicMatcher.match(reference: sample, target: sample, isCancelled: { true })
  fatalError("Cancellation ignored")
} catch IOSComparisonMusicError.cancelled {}
var checks = 0
do {
  _ = try IOSComparisonMusicMatcher.match(reference: rateOriginal, target: rateOriginal, isCancelled: {
    checks += 1; return checks > 2
  })
  fatalError("In-flight cancellation ignored")
} catch IOSComparisonMusicError.cancelled {}
var clock = IOSComparisonMusicPCMContinuity()
try clock.consume(timeUs: 5_000_000, sampleCount: 1600)
try clock.consume(timeUs: 5_100_005, sampleCount: 1600)
do {
  try clock.consume(timeUs: 5_500_000, sampleCount: 1600)
  fatalError("PTS gap was flattened")
} catch IOSComparisonMusicError.discontinuousAudio {}
do {
  try clock.consume(timeUs: 4_000_000, sampleCount: 1600)
  fatalError("Backwards PTS was flattened")
} catch IOSComparisonMusicError.discontinuousAudio {}
do {
  try IOSComparisonMusicFeatureBuilder(originUs: 0).add(.nan)
  fatalError("Non-finite PCM accepted")
} catch IOSComparisonMusicError.decode {}
print("PASS cancellation, PCM PTS continuity, non-finite PCM rejection")
print("Music Swift core regression suite passed")

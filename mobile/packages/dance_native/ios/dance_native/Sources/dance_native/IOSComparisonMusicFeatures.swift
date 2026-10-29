import Foundation

enum IOSComparisonMusicError: Error, LocalizedError {
  case cancelled
  case tooLong
  case discontinuousAudio
  case noAudio
  case decode(String)

  var errorDescription: String? {
    switch self {
    case .cancelled: return "匹配已取消"
    case .tooLong: return "自动匹配暂支持十分钟内的视频；可以使用手动校准。"
    case .discontinuousAudio: return "音轨时间不连续，请手动校准。"
    case .noAudio: return "视频没有音轨"
    case .decode(let message): return message
    }
  }
}

struct IOSComparisonMusicFrame {
  let timeUs: Int64
  var spectrum: [Float]
  var onset: [Float]
  let peaks: [Int]
  var pitchSpectrum: [Float]
}

struct IOSComparisonMusicFeatures {
  var frames: [IOSComparisonMusicFrame]
  var startUs: Int64 { frames.first?.timeUs ?? 0 }
  var endUs: Int64 { (frames.last?.timeUs ?? 0) + 10_000 }
}

/// Same 16 kHz / 10 ms music features as Android. Origin is the first decoded
/// audio presentation timestamp, never the video start or nominal frame rate.
final class IOSComparisonMusicFeatureBuilder {
  static let rate = 16_000
  static let hop = 160
  static let fft = 1024
  static let maxFrames = 60_000

  private let originUs: Int64
  private var ring = [Double](repeating: 0, count: 1024)
  private var real = [Double](repeating: 0, count: 1024)
  private var imaginary = [Double](repeating: 0, count: 1024)
  private let window = (0..<1024).map { 0.5 - 0.5 * cos(2 * .pi * Double($0) / 1023) }
  private let cosine = (0..<512).map { cos(-2 * .pi * Double($0) / 1024) }
  private let sine = (0..<512).map { sin(-2 * .pi * Double($0) / 1024) }
  private let reverse: [Int] = (0..<1024).map { input in
    var value = input
    var result = 0
    for _ in 0..<10 { result = (result << 1) | (value & 1); value >>= 1 }
    return result
  }
  private let edges = [2, 4, 6, 9, 13, 19, 27, 38, 54, 76, 108, 152, 214, 300, 380, 450, 512]
  private let pitchBins = (0..<48).map { 4 * pow(2, Double($0) / 8) }
  private var frames: [IOSComparisonMusicFrame] = []
  private var samples: Int64 = 0
  private var previous = [Float](repeating: 0, count: 16)

  init(originUs: Int64) { self.originUs = originUs }

  func add(_ value: Float) throws {
    guard value.isFinite else { throw IOSComparisonMusicError.decode("音频样本无效，请手动校准。") }
    ring[Int(samples % Int64(Self.fft))] = Double(value)
    samples += 1
    if samples >= Self.fft && (samples - Int64(Self.fft)) % Int64(Self.hop) == 0 {
      try extract()
    }
  }

  private func extract() throws {
    guard frames.count < Self.maxFrames else { throw IOSComparisonMusicError.tooLong }
    let first = Int(samples % Int64(Self.fft))
    for i in 0..<Self.fft {
      real[reverse[i]] = ring[(first + i) % Self.fft] * window[i]
      imaginary[i] = 0
    }
    var length = 2
    while length <= Self.fft {
      let half = length / 2
      let stride = Self.fft / length
      for start in Swift.stride(from: 0, to: Self.fft, by: length) {
        for j in 0..<half {
          let p = start + j
          let q = p + half
          let c = cosine[j * stride]
          let s = sine[j * stride]
          let r = real[q] * c - imaginary[q] * s
          let im = real[q] * s + imaginary[q] * c
          real[q] = real[p] - r
          imaginary[q] = imaginary[p] - im
          real[p] += r
          imaginary[p] += im
        }
      }
      length *= 2
    }
    let power = (0..<(Self.fft / 2)).map { real[$0] * real[$0] + imaginary[$0] * imaginary[$0] }
    let bands: [Float] = (0..<16).map { band in
      let sum = power[edges[band]..<edges[band + 1]].reduce(0, +)
      return Float(log(1 + sum / Double(edges[band + 1] - edges[band]) * 100))
    }
    let onset = (0..<16).map { max(0, bands[$0] - previous[$0]) }
    previous = bands
    let mean = power.reduce(0, +) / Double(power.count)
    let peaks = Array((4..<500).filter {
      power[$0] > power[$0 - 1] && power[$0] >= power[$0 + 1] && power[$0] > mean * 3
    }.sorted { power[$0] == power[$1] ? $0 < $1 : power[$0] > power[$1] }.prefix(4))
    let pitch: [Float] = (0..<48).map { b in
      let center = pitchBins[b]
      let lo = max(1, Int(floor(center / pow(2, 1.0 / 8))))
      let hi = min(power.count - 1, Int(ceil(center * pow(2, 1.0 / 8))))
      var sum = 0.0
      var weight = 0.0
      for i in lo...hi {
        let w = max(0, 1 - abs(log(Double(i) / center) / log(2) * 8))
        sum += power[i] * w
        weight += w
      }
      return Float(log(1 + sum / max(0.01, weight) * 100))
    }
    let timeUs = originUs + (samples - Int64(Self.fft / 2)) * 1_000_000 / Int64(Self.rate)
    frames.append(IOSComparisonMusicFrame(timeUs: timeUs, spectrum: bands,
      onset: onset, peaks: peaks, pitchSpectrum: pitch))
  }

  func finish() -> IOSComparisonMusicFeatures {
    guard !frames.isEmpty else { return IOSComparisonMusicFeatures(frames: []) }
    var means = [Double](repeating: 0, count: 16)
    var deviations = means
    var pitchMean = [Double](repeating: 0, count: 48)
    var pitchDev = pitchMean
    for row in frames {
      for b in 0..<16 { means[b] += Double(row.spectrum[b]) }
      for b in 0..<48 { pitchMean[b] += Double(row.pitchSpectrum[b]) }
    }
    for b in 0..<16 { means[b] /= Double(frames.count) }
    for b in 0..<48 { pitchMean[b] /= Double(frames.count) }
    for row in frames {
      for b in 0..<16 { deviations[b] += pow(Double(row.spectrum[b]) - means[b], 2) }
      for b in 0..<48 { pitchDev[b] += pow(Double(row.pitchSpectrum[b]) - pitchMean[b], 2) }
    }
    for b in 0..<16 { deviations[b] = max(0.25, sqrt(deviations[b] / Double(frames.count))) }
    for b in 0..<48 { pitchDev[b] = max(0.25, sqrt(pitchDev[b] / Double(frames.count))) }
    for i in frames.indices {
      for b in 0..<48 { frames[i].pitchSpectrum[b] = Float((Double(frames[i].pitchSpectrum[b]) - pitchMean[b]) / pitchDev[b]) }
      for b in 0..<16 { frames[i].spectrum[b] = Float((Double(frames[i].spectrum[b]) - means[b]) / deviations[b]) }
      normalize(&frames[i].pitchSpectrum)
      normalize(&frames[i].spectrum)
      normalize(&frames[i].onset)
    }
    return IOSComparisonMusicFeatures(frames: frames)
  }

  private func normalize(_ values: inout [Float]) {
    let norm = sqrt(values.reduce(0) { $0 + Double($1) * Double($1) })
    if norm > 1e-8 { for i in values.indices { values[i] = Float(Double(values[i]) / norm) } }
  }
}

func iosComparisonMusicCheck(_ isCancelled: () -> Bool) throws {
  if isCancelled() { throw IOSComparisonMusicError.cancelled }
}

/// Kotlin roundToInt/roundToLong semantics, including negative half values.
func iosComparisonMusicRound(_ value: Double) -> Int64 { Int64(floor(value + 0.5)) }

/// Small PTS jitter is accepted like Android; a gap or backwards edit must never
/// be flattened into one contiguous PCM stream and confidently auto-applied.
struct IOSComparisonMusicPCMContinuity {
  private var expectedUs: Int64?

  mutating func consume(timeUs: Int64, sampleCount: Int) throws {
    guard sampleCount > 0 else { return }
    if let expectedUs, abs(timeUs - expectedUs) > 20_000 {
      throw IOSComparisonMusicError.discontinuousAudio
    }
    expectedUs = timeUs + Int64(sampleCount) * 1_000_000 / Int64(IOSComparisonMusicFeatureBuilder.rate)
  }
}

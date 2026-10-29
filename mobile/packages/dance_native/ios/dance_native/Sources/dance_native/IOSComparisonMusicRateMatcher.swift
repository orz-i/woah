import Foundation

/// Pairwise affine music search, including pitch-preserved and pitch-shifted
/// versions. Coarse rates only generate seeds; regression estimates the rate.
enum IOSComparisonMusicRateMatcher {
  private static let distances = [8, 16, 28, 44]
  private struct Seed {
    var scale: Double
    var offset: Int64
    let pitch: Bool
    let votes: Int
  }
  private struct Votes {
    var count = 0
    var x = 0.0
    var y = 0.0
    var xx = 0.0
    var xy = 0.0
    var first = Double.infinity
    var last = -Double.infinity

    mutating func add(_ reference: Int64, _ target: Int64) {
      let a = Double(reference) / 1e6
      let b = Double(target) / 1e6
      count += 1; x += a; y += b; xx += a * a; xy += a * b
      first = min(first, a); last = max(last, a)
    }

    func seed(around: Double, pitch: Bool) -> Seed? {
      guard count >= 12, last - first >= 4 else { return nil }
      let denominator = xx - x * x / Double(count)
      guard denominator >= 1e-6 else { return nil }
      let scale = (xy - x * y / Double(count)) / denominator
      guard (0.48...2.02).contains(scale), abs(scale - around) <= 0.04 else { return nil }
      let bounded = min(2, max(0.5, scale))
      return Seed(scale: bounded, offset: iosComparisonMusicRound((y - bounded * x) / Double(count) * 1e6), pitch: pitch, votes: count)
    }
  }

  private static func key(_ a: Int, _ b: Int, _ d: Int) -> Int { (a << 16) | (b << 8) | d }

  static func match(_ t: IOSComparisonMusicFeatures, _ l: IOSComparisonMusicFeatures,
    _ isCancelled: () -> Bool) throws -> IOSComparisonMusicResult? {
    guard min(t.endUs - t.startUs, l.endUs - l.startUs) >= 6_000_000,
          t.frames.count >= 600, l.frames.count >= 600 else { return nil }
    var index: [Int: [Int64]] = [:]
    for i in stride(from: 0, to: t.frames.count, by: 4) {
      for p in t.frames[i].peaks.prefix(3) {
        for (d, dt) in distances.enumerated() {
          guard i + dt < t.frames.count, let other = t.frames[i + dt].peaks.first else { continue }
          let hash = key(p / 4, other / 4, d)
          if (index[hash]?.count ?? 0) < 80 { index[hash, default: []].append(t.frames[i].timeUs) }
        }
      }
    }
    guard index.count >= 12 else { return nil }
    var seeds: [Seed] = []
    for step in 0...60 {
      try iosComparisonMusicCheck(isCancelled)
      let scale = 0.5 + Double(step) * 0.025
      for pitch in [false, true] {
        if pitch && step == 20 { continue }
        let frequencyScale = pitch ? scale : 1
        var histogram: [Int64: Votes] = [:]
        for i in stride(from: 0, to: l.frames.count, by: 8) {
          if i % 1024 == 0 { try iosComparisonMusicCheck(isCancelled) }
          let row = l.frames[i]
          for p in row.peaks.prefix(3) {
            for (d, dt) in distances.enumerated() {
              let j = i + Int(iosComparisonMusicRound(Double(dt) * scale))
              guard j < l.frames.count, let other = l.frames[j].peaks.first else { continue }
              let a = Int(iosComparisonMusicRound(Double(p) * frequencyScale / 4))
              let b = Int(iosComparisonMusicRound(Double(other) * frequencyScale / 4))
              for da in -1...1 { for db in -1...1 {
                guard (0...255).contains(a + da), (0...255).contains(b + db) else { continue }
                for reference in index[key(a + da, b + db, d)] ?? [] {
                  let lag = iosComparisonMusicRound((Double(row.timeUs) - scale * Double(reference)) / 100_000)
                  histogram[lag, default: Votes()].add(reference, row.timeUs)
                }
              } }
            }
          }
        }
        for row in histogram.sorted(by: { $0.value.count == $1.value.count ? $0.key < $1.key : $0.value.count > $1.value.count }).prefix(4) {
          if let seed = row.value.seed(around: scale, pitch: pitch) { seeds.append(seed) }
        }
      }
    }
    var scored: [(seed: Seed, score: Double)] = []
    for seed in seeds {
      try iosComparisonMusicCheck(isCancelled)
      let value = score(t, l, seed, 100_000)
      if value >= 0.35 { scored.append((seed, value)) }
    }
    scored.sort { $0.score == $1.score ? $0.seed.offset < $1.seed.offset : $0.score > $1.score }
    var coarse: [Seed] = []
    for item in scored where !coarse.contains(where: {
      abs($0.scale - item.seed.scale) < 0.003 && abs($0.offset - item.seed.offset) < 120_000 && $0.pitch == item.seed.pitch
    }) {
      coarse.append(item.seed)
      if coarse.count == 8 { break }
    }
    var candidates: [IOSComparisonMusicCandidate] = []
    for seed in coarse {
      if let candidate = try refine(t, l, seed, isCancelled) { candidates.append(candidate) }
    }
    candidates.sort { $0.score == $1.score ? $0.offsetUs < $1.offsetUs : $0.score > $1.score }
    var results: [IOSComparisonMusicCandidate] = []
    for item in candidates where !results.contains(where: {
      abs($0.timeScale - item.timeScale) < 0.003 && abs($0.offsetUs - item.offsetUs) < 150_000
    }) {
      results.append(item)
      if results.count == 3 { break }
    }
    guard let best = results.first else { return nil }
    if best.score < 0.64 || best.consistentWindows < 4 || best.residualMs > 35 || best.supportedSeconds < 6 {
      return IOSComparisonMusicResult(status: "inconsistent", message: "未找到稳定的音乐速率，请用前后两组音乐点手动校准。", candidates: results)
    }
    if results.dropFirst().contains(where: { $0.score >= best.score - 0.08 && $0.consistentWindows >= 4 }) {
      return IOSComparisonMusicResult(status: "ambiguous", message: "找到多个相似乐段，已保留最佳位置，可手动校准。", candidates: results)
    }
    return IOSComparisonMusicResult(status: "matched", message: "已按共同音乐的速率和位置对齐。", candidates: results)
  }

  private static func range(_ t: IOSComparisonMusicFeatures, _ l: IOSComparisonMusicFeatures,
    _ s: Seed) -> (start: Int64, end: Int64) {
    (max(t.startUs, Int64(ceil(Double(l.startUs - s.offset) / s.scale))),
      min(t.endUs, Int64(floor(Double(l.endUs - s.offset) / s.scale))))
  }

  private static func score(_ t: IOSComparisonMusicFeatures, _ l: IOSComparisonMusicFeatures,
    _ s: Seed, _ stepUs: Int64, _ from: Int64? = nil, _ until: Int64? = nil) -> Double {
    let overlap = range(t, l, s)
    let from = from ?? overlap.start
    let until = until ?? overlap.end
    if until - from < 500_000 { return -1 }
    var time = max(from, overlap.start)
    let end = min(until, overlap.end)
    var sum = 0.0
    var count = 0
    let shift = -log(s.scale) / log(2) * 8
    while time < end {
      let i = Int(iosComparisonMusicRound(Double(time - t.startUs) / 10_000))
      let j = Int(iosComparisonMusicRound((Double(time) * s.scale + Double(s.offset - l.startUs)) / 10_000))
      if t.frames.indices.contains(i), l.frames.indices.contains(j) {
        let a = t.frames[i]
        let b = l.frames[j]
        var spectral = 0.0
        var onset = 0.0
        for band in 0..<16 { onset += Double(a.onset[band] * b.onset[band]) }
        if s.pitch {
          guard a.pitchSpectrum.count == 48, b.pitchSpectrum.count == 48 else { return -1 }
          var na = 0.0
          var nb = 0.0
          for band in 0..<48 {
            let p = Double(band) + shift
            let k = Int(floor(p))
            guard (0...46).contains(k) else { continue }
            let f = p - Double(k)
            let x = Double(a.pitchSpectrum[band])
            let y = Double(b.pitchSpectrum[k]) * (1 - f) + Double(b.pitchSpectrum[k + 1]) * f
            spectral += x * y; na += x * x; nb += y * y
          }
          spectral /= max(1e-8, sqrt(na * nb))
          sum += 0.9 * spectral + 0.1 * onset
        } else {
          for band in 0..<16 { spectral += Double(a.spectrum[band] * b.spectrum[band]) }
          sum += 0.75 * spectral + 0.25 * onset
        }
        count += 1
      }
      time += stepUs
    }
    return count < 5 ? -1 : min(1, max(0, sum / Double(count)))
  }

  private static func refine(_ t: IOSComparisonMusicFeatures, _ l: IOSComparisonMusicFeatures,
    _ initial: Seed, _ isCancelled: () -> Bool) throws -> IOSComparisonMusicCandidate? {
    var s = initial
    var pairs: [(x: Double, y: Double)] = []
    for _ in 0..<3 {
      try iosComparisonMusicCheck(isCancelled)
      let overlap = range(t, l, s)
      guard overlap.end - overlap.start >= 6_000_000 else { return nil }
      pairs = []
      for k in 0..<5 {
        let center = overlap.start + (overlap.end - overlap.start) * Int64(2 * k + 1) / 10
        let half = min(1_000_000, (overlap.end - overlap.start) / 12)
        var best = -1.0
        var deltaBest: Int64 = 0
        for delta in stride(from: Int64(-150_000), through: 150_000, by: 10_000) {
          var moved = s; moved.offset += delta
          let value = score(t, l, moved, 40_000, center - half, center + half)
          if value > best { best = value; deltaBest = delta }
        }
        if best >= 0.50 && abs(deltaBest) < 150_000 {
          pairs.append((Double(center) / 1e6, (Double(center) * s.scale + Double(s.offset + deltaBest)) / 1e6))
        }
      }
      guard pairs.count >= 4 else { return nil }
      let mx = pairs.reduce(0) { $0 + $1.x } / Double(pairs.count)
      let my = pairs.reduce(0) { $0 + $1.y } / Double(pairs.count)
      let denominator = pairs.reduce(0) { $0 + pow($1.x - mx, 2) }
      guard denominator > 1e-8 else { return nil }
      let scale = pairs.reduce(0) { $0 + ($1.x - mx) * ($1.y - my) } / denominator
      guard (0.495...2.005).contains(scale) else { return nil }
      s.scale = min(2, max(0.5, scale))
      s.offset = iosComparisonMusicRound((my - s.scale * mx) * 1e6)
    }
    var residual = pairs.map { abs($0.y - (s.scale * $0.x + Double(s.offset) / 1e6)) * 1000 }.max() ?? 1e6
    let overlap = range(t, l, s)
    var verified: Int64 = 0
    // Interleaved holdout windows differ from the five regression anchors.
    for k in 1...4 {
      try iosComparisonMusicCheck(isCancelled)
      let center = overlap.start + (overlap.end - overlap.start) * Int64(k) / 5
      let half = min(500_000, (overlap.end - overlap.start) / 24)
      var best = -1.0
      var deltaBest: Int64 = 0
      for delta in stride(from: Int64(-100_000), through: 100_000, by: 10_000) {
        var moved = s; moved.offset += delta
        let value = score(t, l, moved, 20_000, center - half, center + half)
        if value > best { best = value; deltaBest = delta }
      }
      if best >= 0.5 && abs(deltaBest) <= 30_000 { verified += 1 }
      residual = max(residual, Double(abs(deltaBest)) / 1000)
    }
    return IOSComparisonMusicCandidate(offsetUs: s.offset, score: score(t, l, s, 20_000),
      consistentWindows: verified, supportedSeconds: Double(overlap.end - overlap.start) / 1e6,
      residualMs: residual, timeScale: s.scale)
  }
}

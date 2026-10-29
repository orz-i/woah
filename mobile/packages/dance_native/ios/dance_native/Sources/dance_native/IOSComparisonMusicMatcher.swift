import Foundation

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

/// The mapping is targetUs = referenceUs * timeScale + offsetUs.
/// Only common music is evidence; pose, viewing speed and video FPS are absent.
enum IOSComparisonMusicMatcher {
  private struct Landmark { let hash: Int; let timeUs: Int64 }

  private static func landmarks(_ f: IOSComparisonMusicFeatures) -> [Landmark] {
    var result: [Landmark] = []
    for i in stride(from: 0, to: f.frames.count, by: 4) {
      let row = f.frames[i]
      for anchor in row.peaks.prefix(3) {
        var fanout = 0
        for distance in [8, 16, 28, 44, 64] {
          guard i + distance < f.frames.count,
                let target = f.frames[i + distance].peaks.first else { continue }
          result.append(Landmark(hash: ((anchor / 2) << 16) | ((target / 2) << 8) | (distance / 2), timeUs: row.timeUs))
          fanout += 1
          if fanout == 4 { break }
        }
      }
    }
    return result
  }

  static func match(reference: IOSComparisonMusicFeatures, target: IOSComparisonMusicFeatures,
    isCancelled: () -> Bool = { false }) throws -> IOSComparisonMusicResult {
    try iosComparisonMusicCheck(isCancelled)
    let constant = try matchConstant(reference, target, isCancelled)
    let result: IOSComparisonMusicResult
    if constant.status == "matched" { result = constant }
    else { result = try IOSComparisonMusicRateMatcher.match(reference, target, isCancelled) ?? constant }
    try iosComparisonMusicCheck(isCancelled)
    return result
  }

  private static func matchConstant(_ t: IOSComparisonMusicFeatures, _ l: IOSComparisonMusicFeatures,
    _ isCancelled: () -> Bool) throws -> IOSComparisonMusicResult {
    guard t.frames.count >= 200, l.frames.count >= 200 else {
      return IOSComparisonMusicResult(status: "no_match", message: "共同音乐太短或声音不足，请手动校准。", candidates: [])
    }
    let teacher = landmarks(t)
    let learner = landmarks(l)
    guard Set(teacher.map(\.hash)).count >= 12, Set(learner.map(\.hash)).count >= 12 else {
      return IOSComparisonMusicResult(status: "no_match", message: "声音缺少可辨认的变化，请手动校准。", candidates: [])
    }
    var index: [Int: [Int64]] = [:]
    for row in teacher where (index[row.hash]?.count ?? 0) < 80 {
      index[row.hash, default: []].append(row.timeUs)
    }
    var histogram: [Int64: Int] = [:]
    for (i, row) in learner.enumerated() {
      if i % 256 == 0 { try iosComparisonMusicCheck(isCancelled) }
      let a = row.hash >> 16
      let b = (row.hash >> 8) & 255
      let dt = row.hash & 255
      for da in -1...1 { for db in -1...1 { for d in -1...1 {
        guard (0...255).contains(a + da), (0...255).contains(b + db), (0...255).contains(dt + d) else { continue }
        let key = ((a + da) << 16) | ((b + db) << 8) | (dt + d)
        for time in index[key] ?? [] {
          let lag = Int64((Double(row.timeUs - time) / 20_000).rounded(.toNearestOrEven)) * 20_000
          histogram[lag, default: 0] += 1
        }
      } } }
    }
    var offsets: [Int64] = []
    for (offset, votes) in histogram.sorted(by: { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }) {
      if votes < 6 { break }
      if !offsets.contains(where: { abs($0 - offset) < 180_000 }) { offsets.append(offset) }
      if offsets.count >= 8 { break }
    }
    var candidates: [IOSComparisonMusicCandidate] = []
    for offset in offsets {
      try iosComparisonMusicCheck(isCancelled)
      var bestOffset = offset
      var best = -1.0
      for delta in stride(from: Int64(-120_000), through: 120_000, by: 10_000) {
        let value = score(t, l, offset + delta)
        if value > best { best = value; bestOffset = offset + delta }
      }
      let start = max(t.startUs, l.startUs - bestOffset)
      let end = min(t.endUs, l.endUs - bestOffset)
      guard end - start >= 2_000_000, best >= 0.35 else { continue }
      let window = (end - start) / 3
      var estimates: [Int64] = []
      for i in 0..<3 {
        try iosComparisonMusicCheck(isCancelled)
        var localOffset = bestOffset
        var localScore = -1.0
        for delta in stride(from: Int64(-100_000), through: 100_000, by: 10_000) {
          let value = score(t, l, bestOffset + delta, start + Int64(i) * window, start + Int64(i + 1) * window)
          if value > localScore { localScore = value; localOffset = bestOffset + delta }
        }
        if localScore >= 0.45 { estimates.append(localOffset) }
      }
      let residual = estimates.map { Double(abs($0 - bestOffset)) / 1000 }.max() ?? 1e6
      candidates.append(IOSComparisonMusicCandidate(offsetUs: bestOffset, score: best,
        consistentWindows: Int64(estimates.filter { abs($0 - bestOffset) <= 30_000 }.count),
        supportedSeconds: Double(end - start) / 1e6, residualMs: residual))
    }
    candidates.sort { $0.score == $1.score ? $0.offsetUs < $1.offsetUs : $0.score > $1.score }
    var selected: [IOSComparisonMusicCandidate] = []
    for item in candidates where !selected.contains(where: { abs($0.offsetUs - item.offsetUs) < 150_000 }) {
      selected.append(item)
      if selected.count == 3 { break }
    }
    guard let best = selected.first else {
      return IOSComparisonMusicResult(status: "no_match", message: "没有找到可靠的共同音乐，可以手动校准。", candidates: [])
    }
    let runner = selected.dropFirst().first?.score ?? 0
    let stableRoomRecording = best.score >= 0.54 && best.supportedSeconds >= 8 &&
      best.consistentWindows == 3 && best.residualMs <= 20 && best.score - runner >= 0.15
    if best.score < 0.64 && !stableRoomRecording {
      return IOSComparisonMusicResult(status: "no_match", message: "共同音乐证据不足，请试听候选或手动校准。", candidates: selected)
    }
    if best.residualMs > 60 {
      return IOSComparisonMusicResult(status: "inconsistent", message: "不同片段的音乐位置不一致，可能存在变速或剪辑，请手动选择对比位置。", candidates: selected)
    }
    if best.consistentWindows < 3 {
      return IOSComparisonMusicResult(status: "ambiguous", message: "部分乐段可以对齐，请试听候选或手动校准。", candidates: selected)
    }
    if selected.dropFirst().contains(where: { $0.score >= best.score - 0.08 && $0.score > 0.60 }) {
      return IOSComparisonMusicResult(status: "ambiguous", message: "找到多个相似乐段，请试听并选择对应位置。", candidates: selected)
    }
    return IOSComparisonMusicResult(status: "matched", message: "已按共同音乐对齐，可随时手动校准。", candidates: selected)
  }

  private static func score(_ t: IOSComparisonMusicFeatures, _ l: IOSComparisonMusicFeatures,
    _ offset: Int64, _ from: Int64? = nil, _ until: Int64? = nil) -> Double {
    let start = from ?? max(t.startUs, l.startUs - offset)
    let end = until ?? min(t.endUs, l.endUs - offset)
    if end - start < 500_000 { return -1 }
    let first = max(0, Int(ceil(Double(start - t.startUs) / 10_000)))
    let last = min(t.frames.count, Int(ceil(Double(end - t.startUs) / 10_000)))
    guard first < last else { return -1 }
    var value = 0.0
    var count = 0
    for i in stride(from: first, to: last, by: 2) {
      let j = Int((Double(t.frames[i].timeUs + offset - l.startUs) / 10_000).rounded(.toNearestOrEven))
      guard l.frames.indices.contains(j) else { continue }
      let a = t.frames[i]
      let b = l.frames[j]
      var spectral = 0.0
      var onset = 0.0
      for band in 0..<16 {
        spectral += Double(a.spectrum[band] * b.spectrum[band])
        onset += Double(a.onset[band] * b.onset[band])
      }
      value += 0.75 * spectral + 0.25 * onset
      count += 1
    }
    return count >= 25 ? min(1, max(0, value / Double(count))) : -1
  }
}

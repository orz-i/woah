import Foundation

enum IOSComparisonModelError: LocalizedError {
  case invalidMapping
  case invalidSourceInterval
  case noOverlap
  case invalidRange
  case invalidPicture
  case invalidLayout

  var errorDescription: String? {
    switch self {
    case .invalidMapping: return "音乐时间映射无效。"
    case .invalidSourceInterval: return "视频没有有效的源时间区间。"
    case .noOverlap: return "两个视频没有共同时间区间。"
    case .invalidRange: return "选段已超出当前视频的共同区间。"
    case .invalidPicture: return "画面缩放或平移参数无效。"
    case .invalidLayout: return "对比画面尺寸或间距无效。"
    }
  }
}

struct IOSComparisonSourceInterval: Equatable {
  let startUs: Int64
  let endUs: Int64

  init(startUs: Int64, endUs: Int64) throws {
    guard startUs >= 0, endUs >= startUs, endUs <= 86_400_000_000 else {
      throw IOSComparisonModelError.invalidSourceInterval
    }
    self.startUs = startUs
    self.endUs = endUs
  }
}

/// Music alone defines correspondence. Viewing speed and movement are absent.
struct IOSComparisonMusicMapping {
  let offsetUs: Int64
  let timeScale: Double

  init(offsetUs: Int64, timeScale: Double = 1) throws {
    guard (-172_800_000_000...172_800_000_000).contains(offsetUs),
          timeScale.isFinite, (0.5...2).contains(timeScale) else {
      throw IOSComparisonModelError.invalidMapping
    }
    self.offsetUs = offsetUs
    self.timeScale = timeScale
  }

  func targetTimeUs(referenceUs: Int64) -> Int64 {
    IOSComparisonTimeCore.map(referenceUs, scale: timeScale, offset: offsetUs)
  }
}

struct IOSComparisonOverlap {
  let referenceStartUs: Int64
  let referenceEndUs: Int64
  let target: IOSComparisonSourceInterval
  let mapping: IOSComparisonMusicMapping
  var durationUs: Int64 { referenceEndUs - referenceStartUs }

  func sourceRange(startUs: Int64 = 0, endUs: Int64? = nil) throws -> IOSComparisonSourceInterval {
    let end = endUs ?? durationUs
    guard startUs >= 0, end > startUs, end <= durationUs else {
      throw IOSComparisonModelError.invalidRange
    }
    return try IOSComparisonSourceInterval(startUs: referenceStartUs + startUs,
      endUs: referenceStartUs + end)
  }
}

enum IOSComparisonTimeMapping {
  static func overlap(reference: IOSComparisonSourceInterval, target: IOSComparisonSourceInterval,
    offsetUs: Int64, timeScale: Double = 1) throws -> IOSComparisonOverlap {
    let mapping = try IOSComparisonMusicMapping(offsetUs: offsetUs, timeScale: timeScale)
    guard let (start, end) = IOSComparisonTimeCore.overlap(rs: reference.startUs,
      re: reference.endUs, ts: target.startUs, te: target.endUs,
      scale: timeScale, offset: offsetUs) else {
      throw IOSComparisonModelError.noOverlap
    }
    return IOSComparisonOverlap(referenceStartUs: start, referenceEndUs: end,
      target: target, mapping: mapping)
  }
}

struct IOSComparisonViewport: Equatable {
  let x: Int
  let top: Int
  let width: Int
  let height: Int
}

struct IOSComparisonQuad: Equatable {
  let scaleX: Float
  let scaleY: Float
  let shiftX: Float
  let shiftY: Float
}

/// Screen-space pan. Mirroring affects the picture, never the pan direction.
struct IOSComparisonPicture: Equatable {
  let zoom: Double
  let panX: Double
  let panY: Double
  static let identity = try! IOSComparisonPicture()

  init(zoom: Double = 1, panX: Double = 0, panY: Double = 0) throws {
    guard zoom.isFinite, (1...4).contains(zoom), panX.isFinite,
          (-1...1).contains(panX), panY.isFinite, (-1...1).contains(panY) else {
      throw IOSComparisonModelError.invalidPicture
    }
    self.zoom = zoom
    self.panX = panX
    self.panY = panY
  }

  func quad(pane: IOSComparisonViewport, fit: IOSComparisonViewport) -> IOSComparisonQuad {
    IOSComparisonTimeCore.quad(pane, fit, zoom: zoom, panX: panX, panY: panY)
  }
}

/// Integer panes and Float quads intentionally match ComparisonGlLayout/Picture.
struct IOSComparisonLayout {
  let width: Int
  let height: Int
  let gap: Int
  let sideBySide: Bool

  init(width: Int, height: Int, gap: Int, sideBySide: Bool) throws {
    guard (2...8192).contains(width), (2...8192).contains(height),
          gap >= 0, gap < (sideBySide ? width - 1 : height - 1) else {
      throw IOSComparisonModelError.invalidLayout
    }
    self.width = width
    self.height = height
    self.gap = gap
    self.sideBySide = sideBySide
  }

  func pane(reference: Bool) -> IOSComparisonViewport {
    IOSComparisonTimeCore.pane(width: width, height: height, gap: gap, reference: reference, sideBySide: sideBySide)
  }

  func fit(reference: Bool, aspect: Double) -> IOSComparisonViewport {
    IOSComparisonTimeCore.fit(pane(reference: reference), aspect: aspect)
  }
}

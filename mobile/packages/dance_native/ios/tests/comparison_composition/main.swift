import Foundation

// Standalone: swiftc IOSComparisonModels.swift tests/comparison_composition/main.swift -o comparison_models
func near(_ actual: Double, _ expected: Double, _ tolerance: Double = 0.001) {
  precondition(abs(actual - expected) <= tolerance, "Expected \(expected), got \(actual)")
}

func rejects(_ body: () throws -> Void) {
  do { try body(); fatalError("Expected validation failure") } catch { }
}

func source(_ start: Int64, _ end: Int64) throws -> IOSComparisonSourceInterval {
  try IOSComparisonSourceInterval(startUs: start, endUs: end)
}

let later = try IOSComparisonTimeMapping.overlap(reference: source(0, 12_000_000),
  target: source(0, 15_000_000), offsetUs: 3_000_000)
precondition(later.referenceStartUs == 0 && later.durationUs == 12_000_000)
precondition(later.mapping.targetTimeUs(referenceUs: 10_000_000) == 13_000_000)
let earlier = try IOSComparisonTimeMapping.overlap(reference: source(0, 15_000_000),
  target: source(0, 12_000_000), offsetUs: -3_000_000)
precondition(earlier.referenceStartUs == 3_000_000 && earlier.durationUs == 12_000_000)
let editList = try IOSComparisonTimeMapping.overlap(reference: source(2_000_001, 14_000_001),
  target: source(5_000_001, 20_000_001), offsetUs: 3_000_000)
precondition(editList.referenceStartUs == 2_000_001)
precondition(editList.referenceEndUs == 14_000_001)
let selected = try editList.sourceRange(startUs: 1_000_000, endUs: 3_000_000)
precondition(selected.startUs == 3_000_001 && selected.endUs == 5_000_001)
rejects { _ = try editList.sourceRange(startUs: -1) }
rejects { _ = try editList.sourceRange(startUs: 3, endUs: 3) }
rejects { _ = try editList.sourceRange(endUs: editList.durationUs + 1) }
rejects { _ = try IOSComparisonTimeMapping.overlap(reference: source(0, 3), target: source(0, 3), offsetUs: 5) }
rejects { _ = try IOSComparisonTimeMapping.overlap(reference: source(0, 0), target: source(0, 0), offsetUs: 0) }
rejects { _ = try source(-1, 3) }
rejects { _ = try source(0, 86_400_000_001) }
rejects { _ = try IOSComparisonMusicMapping(offsetUs: Int64.min) }
for invalid: Double in [.nan, .infinity, 0.49, 2.01] {
  rejects { _ = try IOSComparisonMusicMapping(offsetUs: 0, timeScale: invalid) }
}

// The Android integer-sample end is 6us. A continuous edit ending there would
// ask AVFoundation for target source 12us past its exclusive 11us end.
let bounded = try IOSComparisonTimeMapping.overlap(reference: source(0, 20),
  target: source(0, 11), offsetUs: 0, timeScale: 2)
precondition(bounded.referenceEndUs == 5 && bounded.durationUs == 5)
precondition(bounded.mapping.targetTimeUs(referenceUs: bounded.referenceEndUs) == 10)
let half = try IOSComparisonMusicMapping(offsetUs: -2, timeScale: 0.5)
precondition(half.targetTimeUs(referenceUs: 1) == -1) // round source product before adding offset
for scale in [0.5, 0.731, 1.0, 1.001, 1.999, 2.0] {
  for offset: Int64 in [-3_000_001, -1, 0, 1, 3_000_001] {
    let overlap = try IOSComparisonTimeMapping.overlap(reference: source(123, 13_000_001),
      target: source(456, 17_000_003), offsetUs: offset, timeScale: scale)
    precondition(overlap.mapping.targetTimeUs(referenceUs: overlap.referenceStartUs) >= 456)
    precondition(overlap.mapping.targetTimeUs(referenceUs: overlap.referenceEndUs) <= 17_000_003)
    let androidEnd = min(13_000_001, Int64(floor(Double(17_000_003 - 1 - offset) / scale)) + 1)
    precondition(androidEnd - overlap.referenceEndUs >= 0 && androidEnd - overlap.referenceEndUs <= 1)
    let range = try overlap.sourceRange(startUs: 1, endUs: overlap.durationUs - 1)
    precondition(range.startUs == overlap.referenceStartUs + 1)
  }
}

// Exact Android fit rules, including asymmetric odd pixels and Float rounding.
for size in [(1080, 1400), (1381, 715)] {
  for sideBySide in [false, true] {
    let layout = try IOSComparisonLayout(width: size.0, height: size.1, gap: 8, sideBySide: sideBySide)
    let a = layout.pane(reference: true), b = layout.pane(reference: false)
    precondition(sideBySide ? b.x - a.x - a.width == 8 : b.top - a.top - a.height == 8)
    for reference in [false, true] {
      let pane = layout.pane(reference: reference)
      for aspect in [1920.0 / 1080, 1080.0 / 1920] {
        let fit = layout.fit(reference: reference, aspect: aspect)
        let quad = IOSComparisonPicture.identity.quad(pane: pane, fit: fit)
        let left = Double(pane.x) + (1 + Double(quad.shiftX) - Double(quad.scaleX)) * Double(pane.width) / 2
        let top = Double(pane.top) + (1 - Double(quad.shiftY) - Double(quad.scaleY)) * Double(pane.height) / 2
        near(left, Double(fit.x)); near(top, Double(fit.top))
        near(Double(quad.scaleX) * Double(pane.width), Double(fit.width))
        near(Double(quad.scaleY) * Double(pane.height), Double(fit.height))
      }
    }
  }
}
let square = IOSComparisonViewport(x: 0, top: 0, width: 100, height: 100)
let portraitFit = IOSComparisonViewport(x: 25, top: 0, width: 50, height: 100)
let zoom = try IOSComparisonPicture(zoom: 2)
precondition(zoom.quad(pane: square, fit: portraitFit) == IOSComparisonQuad(scaleX: 1, scaleY: 2, shiftX: 0, shiftY: 0))
let pan = try IOSComparisonPicture(zoom: 2, panX: 0.5, panY: -0.25)
let panned = pan.quad(pane: square, fit: portraitFit)
near(Double(panned.shiftX), 0); near(Double(panned.shiftY), 0.25)
let wideFit = IOSComparisonViewport(x: 0, top: 25, width: 100, height: 50)
let wide = pan.quad(pane: square, fit: wideFit)
near(Double(wide.shiftX), 0.5); near(Double(wide.shiftY), 0)
let edge = try IOSComparisonPicture(zoom: 4, panX: 1)
  .quad(pane: square, fit: IOSComparisonViewport(x: 45, top: 0, width: 10, height: 100))
near((1 + Double(edge.shiftX) - Double(edge.scaleX)) * 50, 60)
near((1 + Double(edge.shiftX) + Double(edge.scaleX)) * 50, 100)
for parameters in [(Double.nan, 0.0, 0.0), (4.01, 0, 0), (2, Double.infinity, 0), (2, 0, -1.01)] {
  rejects { _ = try IOSComparisonPicture(zoom: parameters.0, panX: parameters.1, panY: parameters.2) }
}
rejects { _ = try IOSComparisonLayout(width: 1, height: 100, gap: 8, sideBySide: true) }
rejects { _ = try IOSComparisonLayout(width: 100, height: 100, gap: 99, sideBySide: false) }
print("Comparison composition pure time/geometry tests passed")

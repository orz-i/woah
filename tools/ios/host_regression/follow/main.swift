import Foundation
import CryptoKit
func check(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
var data = Data()
var maxReferenceDelta: Float = 0
for scenario in 0...1 {
  let core = IOSFrameKernel.Follower()
  let adapter = IOSSubjectReframer()
  let reference = ReferenceIOSSubjectReframer()
  let source: Float = scenario == 0 ? 16 / 9 : 0.4
  let zoom: Float = scenario == 0 ? 1 : 1.9
  let smooth: Float = scenario == 0 ? 0.1 : 0.15
  var pts: Int64 = 0
  for i in 0..<240 {
    pts += [Int64(0), 16667, 33333, 40000, 100000, 500000][i % 6]
    if i == 140 { pts = 0 }
    if i == 200 { core.reset(); adapter.reset(); reference.reset() }
    let x: Float = i < 20 ? 0.2 : (i < 110 ? 0.8 : (i < 180 ? 0.15 : 0.9))
    let target: SIMD4<Float>? = (60...89).contains(i) || (scenario == 1 && i < 3)
      ? nil : SIMD4<Float>(x - 0.04, 0.3, x + 0.04, 0.7)
    let crop = core.crop(target: target, ptsUs: pts, sourceAspect: source, outputAspect: 9 / 16, zoom: zoom, smooth: smooth)
    let wrapped = adapter.crop(target: target, presentationTimeUs: pts, sourceAspectRatio: source,
      outputAspectRatio: 9 / 16, zoom: zoom, smoothFactor: smooth)
    let old = reference.crop(target: target, presentationTimeUs: pts, sourceAspectRatio: source,
      outputAspectRatio: 9 / 16, zoom: zoom, smoothFactor: smooth)
    check(crop == wrapped, "native camera adapter differs")
    for k in 0..<4 { maxReferenceDelta = max(maxReferenceDelta, abs(crop[k]-old[k])) }
    for value in [crop.x,crop.y,crop.z,crop.w] + core.motionState() {
      var bits = value.bitPattern.littleEndian
      withUnsafeBytes(of: &bits) { data.append(contentsOf: $0) }
    }
  }
}
let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
check(digest == "8f276242a6773df19a18a4b7c63af042da1f5d2b1eea8f84d397ce3b1bb6a511", "cross-host follow fingerprint: \(digest)")
check(maxReferenceDelta <= 0.000001, "reference camera drift: \(maxReferenceDelta)")
let size = IOSSubjectReframer.exactNineSixteenSize(sourceWidth: 1920, sourceHeight: 1080, maxHeight: 1280)
check(size?.width == 594 && size?.height == 1056, "encoder-even crop size")
print("FOLLOW_VECTOR_SHA256=\(digest)")
print("FOLLOW_MAX_OLD_SWIFT_DELTA=\(maxReferenceDelta)")
print("FOLLOW_REFERENCE_PARITY=PASS")

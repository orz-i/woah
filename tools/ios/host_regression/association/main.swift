import Foundation
import CryptoKit
import FrameKernelC
func check(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
var state: UInt32 = 0x13579bdf
var bytes = Data()
func append(_ value: UInt32) {
  var value = value.littleEndian
  withUnsafeBytes(of: &value) { bytes.append(contentsOf: $0) }
}
for trial in 0..<400 {
  let nr = trial % 9, nc = trial % 13
  let costs: [Float] = (0..<(nr*nc)).map { _ in
    state = state &* 1664525 &+ 1013904223
    switch state & 31 {
    case 0: return .nan
    case 1: return .infinity
    case 2: return 0.5
    default: return Float(state >> 16) / 65535
    }
  }
  var pairs = [FrameKernelMatchPair](repeating: FrameKernelMatchPair(), count: min(nr,nc))
  let n = costs.withUnsafeBufferPointer { c in pairs.withUnsafeMutableBufferPointer { out in
    frame_kernel_match_costs(c.baseAddress, c.count, UInt32(nr), UInt32(nc),
      trial % 2 == 0 ? 0.5 : 0.7, out.baseAddress, out.count)
  } }
  check(n >= 0, "cost matrix rejected")
  append(UInt32(n))
  for pair in pairs.prefix(Int(n)) { append(pair.row); append(pair.column) }
}
let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
check(digest == "36cc72a47f5b17181987fe0c0dcc23f86fe08755e819ec2b685817e6b7ff637a", "cross-host association fingerprint: \(digest)")
// Rectangle/identity adapters are also compared to the frozen Swift reference.
var random: UInt32 = 1234567
for trial in 0..<500 {
  func coord() -> Float { random = random &* 1664525 &+ 1013904223; return Float(random % 3000) / 2 }
  let detections = (0..<(trial % 12)).map { _ -> IOSYoloDetection in
    let x=coord(), y=coord()
    return IOSYoloDetection(x1:x, y1:y, x2:x+200, y2:y+300, confidence:0.9, mask:[])
  }
  let cached = (0..<(trial % 9)).map { i -> IOSCachedPerson in
    let x=Double(coord())/1920, y=Double(coord())/1080
    return IOSCachedPerson(id:i*3+1, bbox:IOSCachedBBox(left:x, top:y, right:x+200/1920, bottom:y+300/1080))
  }
  let metadata = IOSAnalysisMetadata(persons:cached)
  let actual = try IOSPreviewIdentityMatcher.assign(detections:detections, metadata:metadata, frameWidth:1920, frameHeight:1080)
  let old = ReferenceIOSPreviewIdentityMatcher.assign(detections:detections, metadata:metadata, frameWidth:1920, frameHeight:1080)
  check(actual.map(\.id) == old.map(\.id), "identity association differs at \(trial)")
}
let tiny = SIMD4<Float>(0,0,0.1,0.1)
let matched = try IOSFrameKernel.matchRects(rows:[tiny], columns:[tiny], maxCost:0)
check(matched.count == 1 && matched[0].row == 0 && matched[0].column == 0, "subpixel IoU")
print("ASSOCIATION_VECTOR_SHA256=\(digest)")
print("ASSOCIATION_SWIFT_REFERENCE_PARITY=PASS")

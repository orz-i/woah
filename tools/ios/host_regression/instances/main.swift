import Foundation
import CryptoKit
func check(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
let anchors = IOSYoloPostprocessor.anchorCount
let pixels = 160 * 160
let planar = (0..<32*pixels).map { Float32(($0 * 17 % 41) - 20) / 23 }
let scale: Float32 = 640 / 3840
let prep = IOSYoloPreprocessResult(input: [], scale: scale, padLeft: 0, padTop: 140,
  sourceWidth: 3840, sourceHeight: 2160, inputSize: 640)
var layoutFingerprints = Set<String>()
var differences = 0
for transposed in [false, true] { for nhwc in [false, true] {
  var head = [Float32](repeating: 0, count: 116 * anchors)
  for a in 0...3 {
    func put(_ attribute: Int, _ value: Float32) { head[transposed ? a*116+attribute : attribute*anchors+a] = value }
    put(0, 0.40 + Float32(a)*0.005); put(1, 0.5); put(2, 0.22); put(3, 0.4)
    put(4, 0.9 - Float32(a)*0.1)
    for c in 0..<32 { put(84+c, Float32((c*11%19)-9)/13 * (a == 3 ? -1 : 1)) }
  }
  let proto = nhwc ? (0..<planar.count).map { planar[($0%32)*pixels+$0/32] } : planar
  let headShape = transposed ? [1, anchors, 116] : [1, 116, anchors]
  let protoShape = nhwc ? [1, 160, 160, 32] : [1, 32, 160, 160]
  let decoder = IOSYoloPostprocessor(output0Shape: headShape, output1Shape: protoShape)
  let current = try decoder.parse(output0: head, output1: proto, preprocess: prep)
  let old = try ReferenceIOSYoloPostprocessor(output0Shape: headShape, output1Shape: protoShape)
    .parse(output0: head, output1: proto, preprocess: prep)
  check(current.count == old.count && !current.isEmpty, "instance count")
  var data = Data()
  for (a,b) in zip(current,old) {
    check([a.x1,a.y1,a.x2,a.y2,a.confidence] == [b.x1,b.y1,b.x2,b.y2,b.confidence], "geometry or confidence")
    check(a.mask.count == b.mask.count, "mask size")
    for (x,y) in zip(a.mask,b.mask) {
      if x != y { differences += 1 }
      check(abs(Int(x)-Int(y)) <= 1, "mask change exceeds Float32 quantization boundary")
      check((x >= 128) == (y >= 128), "binary privacy contour differs in the reference corpus")
    }
    for value in [a.x1,a.y1,a.x2,a.y2,a.confidence] {
      var bits=value.bitPattern.littleEndian
      withUnsafeBytes(of: &bits) { data.append(contentsOf: $0) }
    }
    data.append(contentsOf: a.mask)
  }
  layoutFingerprints.insert(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined())
  let firstMask = current[0].mask
  head = [Float32](repeating: 0, count: head.count)
  check(try decoder.parse(output0: head, output1: proto, preprocess: prep).isEmpty, "empty next frame")
  check(current[0].mask == firstMask, "returned mask aliases reusable core output")
} }
check(layoutFingerprints.count == 1, "layout-dependent output")
print("INSTANCE_LAYOUT_PARITY=PASS")
print("OLD_SWIFT_MASK_BYTE_DELTA_COUNT=\(differences)")
print("INSTANCE_VECTOR_SHA256=\(layoutFingerprints.first!)")

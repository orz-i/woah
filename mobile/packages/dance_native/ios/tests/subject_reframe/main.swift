import Foundation

func requireSize(_ actual: (width: Int, height: Int)?, _ width: Int, _ height: Int) {
  guard let actual, actual.width == width, actual.height == height else {
    fatalError("unexpected 9:16 size: \(String(describing: actual))")
  }
}

requireSize(
  IOSSubjectReframer.exactNineSixteenSize(sourceWidth: 1920, sourceHeight: 1080, maxHeight: 1280),
  594,
  1056
)
requireSize(
  IOSSubjectReframer.exactNineSixteenSize(sourceWidth: 3840, sourceHeight: 2160, maxHeight: 1280),
  720,
  1280
)
precondition(
  IOSSubjectReframer.exactNineSixteenSize(sourceWidth: 17, sourceHeight: 31, maxHeight: 1280) == nil
)

func near(_ actual: Float, _ expected: Float, _ tolerance: Float = 0.00001) {
  precondition(abs(actual - expected) <= tolerance, "Expected \(expected), got \(actual)")
}
func box(_ x: Float, _ y: Float = 0.5) -> SIMD4<Float> {
  SIMD4<Float>(x - 0.04, y - 0.2, x + 0.04, y + 0.2)
}
func crop(_ camera: IOSSubjectReframer, _ x: Float?, _ time: Int64) -> SIMD4<Float> {
  camera.crop(target: x.map { box($0) }, presentationTimeUs: time,
    sourceAspectRatio: 16 / 9, outputAspectRatio: 9 / 16)
}

let centered = crop(IOSSubjectReframer(), 0.7, 0)
near((centered.x + centered.z) / 2, 0.7)
near(centered.z - centered.x, 81 / 256)
near(centered.y, 0)
near(centered.w, 1)
for x: Float in [-0.1, 0, 0.02, 0.98, 1, 1.1] {
  let value = crop(IOSSubjectReframer(), x, 0)
  precondition(value.x >= 0 && value.z <= 1)
  near(value.z - value.x, 81 / 256)
}

let portrait = IOSSubjectReframer().crop(target: box(0.2), presentationTimeUs: 0,
  sourceAspectRatio: 9 / 16, outputAspectRatio: 9 / 16)
precondition(portrait == SIMD4<Float>(0, 0, 1, 1))
let vertical = IOSSubjectReframer().crop(target: box(0.5, 0.2), presentationTimeUs: 0,
  sourceAspectRatio: 0.4, outputAspectRatio: 9 / 16)
near(vertical.x, 0)
near(vertical.z, 1)
near(vertical.y, 0)
near(vertical.w, 0.4 / (9 / 16))

func afterOneSecond(_ fps: Int) -> Float {
  let camera = IOSSubjectReframer()
  var value = crop(camera, 0.2, 0)
  for index in 1...fps {
    value = crop(camera, 0.75, Int64(index) * 1_000_000 / Int64(fps))
  }
  return (value.x + value.z) / 2
}
near(afterOneSecond(15), afterOneSecond(30), 0.012)
near(afterOneSecond(30), afterOneSecond(60), 0.012)

let jitterCamera = IOSSubjectReframer()
let stable = crop(jitterCamera, 0.5, 0)
for index in 1...60 {
  let value = crop(jitterCamera, 0.5 + (index % 2 == 0 ? 0.003 : -0.003), Int64(index) * 33_333)
  near(value.x, stable.x)
}
let lostCamera = IOSSubjectReframer()
let held = crop(lostCamera, 0.7, 0)
for index in 1...120 {
  precondition(crop(lostCamera, nil, Int64(index) * 33_333) == held)
}
let panCamera = IOSSubjectReframer()
_ = crop(panCamera, 0.2, 0)
let next = crop(panCamera, 0.8, 33_333)
precondition((next.x + next.z) / 2 - 0.2 <= 0.8 / 30 + 0.00001)
let reset = crop(panCamera, 0.7, 0)
near((reset.x + reset.z) / 2, 0.7)
let invalid = panCamera.crop(target: box(.nan), presentationTimeUs: 33_333,
  sourceAspectRatio: 16 / 9, outputAspectRatio: 9 / 16)
precondition(invalid == reset)
print("Subject reframe Swift core: geometry, edges, portrait, vertical crop, PTS, jitter, missing target, speed, reset, NaN checks passed")

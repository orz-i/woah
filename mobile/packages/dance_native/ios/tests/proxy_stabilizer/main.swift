import Foundation

func near(_ actual: Float, _ expected: Float, _ tolerance: Float = 0.001) {
  precondition(abs(actual - expected) <= tolerance, "Expected \(expected), got \(actual)")
}

func box(_ centerX: Float, _ centerY: Float = 500) -> SIMD4<Float> {
  SIMD4<Float>(centerX - 50, centerY - 200, centerX + 50, centerY + 200)
}

let jitter = IOSOcclusionProxyStabilizer()
let initial = jitter.stabilize(
  target: box(250),
  presentationTimeUs: 0,
  frameWidth: 1000,
  frameHeight: 1000
)!
for frame in 1...90 {
  let x: Float = frame % 2 == 0 ? 263 : 237
  let current = jitter.stabilize(
    target: box(x),
    presentationTimeUs: Int64(frame) * 33_333,
    frameWidth: 1000,
    frameHeight: 1000
  )!
  near((current.x + current.z) * 0.5, (initial.x + initial.z) * 0.5, 0.001)
}

let sustained = IOSOcclusionProxyStabilizer()
_ = sustained.stabilize(
  target: box(250),
  presentationTimeUs: 0,
  frameWidth: 1000,
  frameHeight: 1000
)
var sustainedValue = box(250)
for frame in 1...15 {
  sustainedValue = sustained.stabilize(
    target: box(263),
    presentationTimeUs: Int64(frame) * 33_333,
    frameWidth: 1000,
    frameHeight: 1000
  )!
}
let sustainedCenter = (sustainedValue.x + sustainedValue.z) * 0.5
precondition(sustainedCenter > 253)
precondition(sustainedCenter < 263)

let breakout = IOSOcclusionProxyStabilizer()
_ = breakout.stabilize(
  target: box(250),
  presentationTimeUs: 0,
  frameWidth: 1000,
  frameHeight: 1000
)
let firstBreakout = breakout.stabilize(
  target: box(320),
  presentationTimeUs: 33_333,
  frameWidth: 1000,
  frameHeight: 1000
)!
precondition((firstBreakout.x + firstBreakout.z) * 0.5 > 250)
var breakoutValue = firstBreakout
for frame in 2...20 {
  breakoutValue = breakout.stabilize(
    target: box(320),
    presentationTimeUs: Int64(frame) * 33_333,
    frameWidth: 1000,
    frameHeight: 1000
  )!
}
let breakoutCenter = (breakoutValue.x + breakoutValue.z) * 0.5
precondition(breakoutCenter > 310)
precondition(breakoutCenter < 320)

breakout.reset()
let resetValue = breakout.stabilize(
  target: box(700),
  presentationTimeUs: 1_000_000,
  frameWidth: 1000,
  frameHeight: 1000
)!
near((resetValue.x + resetValue.z) * 0.5, 700)

func finalCenter(_ fps: Int) -> Float {
  let stabilizer = IOSOcclusionProxyStabilizer()
  _ = stabilizer.stabilize(
    target: box(250),
    presentationTimeUs: 0,
    frameWidth: 1000,
    frameHeight: 1000
  )
  var current = box(250)
  for frame in 1...fps {
    current = stabilizer.stabilize(
      target: box(290),
      presentationTimeUs: Int64(frame) * 1_000_000 / Int64(fps),
      frameWidth: 1000,
      frameHeight: 1000
    )!
  }
  return (current.x + current.z) * 0.5
}

near(finalCenter(30), finalCenter(60), 4)
print("Occlusion proxy stabilizer: jitter hold, sustained drift, breakout, reset and PTS checks passed")

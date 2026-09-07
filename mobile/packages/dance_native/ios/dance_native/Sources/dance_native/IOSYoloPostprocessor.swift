import Foundation

struct IOSYoloDetection {
  let x1: Float32
  let y1: Float32
  let x2: Float32
  let y2: Float32
  let confidence: Float32
  let mask: [UInt8]
}

private struct IOSYoloRawCandidate {
  let x1: Float32
  let y1: Float32
  let x2: Float32
  let y2: Float32
  let confidence: Float32
  let maskCoefficients: [Float32]
}

final class IOSYoloPostprocessor {
  static let protoSize = 160
  static let protoChannels = 32
  static let attributeCount = 116
  static let anchorCount = 8400

  private let output0Shape: [Int]
  private let output1Shape: [Int]

  init(output0Shape: [Int], output1Shape: [Int]) {
    self.output0Shape = output0Shape
    self.output1Shape = output1Shape
  }

  func parse(
    output0: [Float32],
    output1: [Float32],
    preprocess: IOSYoloPreprocessResult,
    confidenceThreshold: Float32 = 0.25,
    bboxIoUThreshold: Float32 = 0.50,
    maskIoUThreshold: Float32 = 0.50
  ) throws -> [IOSYoloDetection] {
    guard output0.count == Self.attributeCount * Self.anchorCount else {
      throw contractError("Unexpected YOLO detection tensor element count: \(output0.count)")
    }
    guard output1.count == Self.protoChannels * Self.protoSize * Self.protoSize else {
      throw contractError("Unexpected YOLO proto tensor element count: \(output1.count)")
    }

    let candidates = collectCandidates(
      output0: output0,
      preprocess: preprocess,
      threshold: confidenceThreshold
    )
    let sorted = candidates.sorted { $0.confidence > $1.confidence }
    var selected: [IOSYoloRawCandidate] = []
    var selectedMasks: [[UInt8]] = []

    for candidate in sorted {
      var suppress = false
      for index in selected.indices {
        let kept = selected[index]
        guard bboxIoU(candidate, kept) > bboxIoUThreshold else { continue }
        let candidateMask = decodeMask(candidate, proto: output1, inputSize: preprocess.inputSize)
        let keptMask = selectedMasks[index]
        let maskIoU = maskIoUWithinSupport(
          candidateMask,
          keptMask,
          candidate,
          kept,
          inputSize: preprocess.inputSize
        )
        if maskIoU >= maskIoUThreshold {
          suppress = true
          break
        }
      }
      if !suppress {
        selected.append(candidate)
        selectedMasks.append(decodeMask(candidate, proto: output1, inputSize: preprocess.inputSize))
      }
    }

    var detections: [IOSYoloDetection] = []
    detections.reserveCapacity(selected.count)
    for index in selected.indices {
      let candidate = selected[index]
      let sourceX1 = clamp(
        (candidate.x1 - preprocess.padLeft) / preprocess.scale,
        0,
        Float32(preprocess.sourceWidth)
      )
      let sourceY1 = clamp(
        (candidate.y1 - preprocess.padTop) / preprocess.scale,
        0,
        Float32(preprocess.sourceHeight)
      )
      let sourceX2 = clamp(
        (candidate.x2 - preprocess.padLeft) / preprocess.scale,
        0,
        Float32(preprocess.sourceWidth)
      )
      let sourceY2 = clamp(
        (candidate.y2 - preprocess.padTop) / preprocess.scale,
        0,
        Float32(preprocess.sourceHeight)
      )
      guard sourceX2 > sourceX1, sourceY2 > sourceY1 else { continue }
      detections.append(IOSYoloDetection(
        x1: sourceX1,
        y1: sourceY1,
        x2: sourceX2,
        y2: sourceY2,
        confidence: candidate.confidence,
        mask: selectedMasks[index]
      ))
    }
    detections.sort { (($0.x1 + $0.x2) * 0.5) < (($1.x1 + $1.x2) * 0.5) }
    return detections
  }

  private func collectCandidates(
    output0: [Float32],
    preprocess: IOSYoloPreprocessResult,
    threshold: Float32
  ) -> [IOSYoloRawCandidate] {
    let isTransposed = output0Shape.count == 3
      && output0Shape[1] == Self.anchorCount
      && output0Shape[2] == Self.attributeCount
    let inputSize = Float32(preprocess.inputSize)
    var result: [IOSYoloRawCandidate] = []

    for anchor in 0..<Self.anchorCount {
      let confidence: Float32
      let attribute: (Int) -> Float32
      if isTransposed {
        let base = anchor * Self.attributeCount
        confidence = output0[base + 4]
        attribute = { output0[base + $0] }
      } else {
        confidence = output0[4 * Self.anchorCount + anchor]
        attribute = { output0[$0 * Self.anchorCount + anchor] }
      }
      guard confidence >= threshold else { continue }

      var centerX = attribute(0)
      var centerY = attribute(1)
      var width = attribute(2)
      var height = attribute(3)
      if centerX <= 2.0, width <= 2.0 {
        centerX *= inputSize
        centerY *= inputSize
        width *= inputSize
        height *= inputSize
      }
      let coefficients = (0..<Self.protoChannels).map { attribute(84 + $0) }
      result.append(IOSYoloRawCandidate(
        x1: centerX - width * 0.5,
        y1: centerY - height * 0.5,
        x2: centerX + width * 0.5,
        y2: centerY + height * 0.5,
        confidence: confidence,
        maskCoefficients: coefficients
      ))
    }
    return result
  }

  private func decodeMask(
    _ candidate: IOSYoloRawCandidate,
    proto: [Float32],
    inputSize: Int
  ) -> [UInt8] {
    let size = Self.protoSize
    let pixels = size * size
    var mask = [UInt8](repeating: 0, count: pixels)
    let support = supportRect(candidate, inputSize: inputSize)
    let isNHWC = output1Shape.count == 4
      && output1Shape[1] == size
      && output1Shape[2] == size
      && output1Shape[3] == Self.protoChannels

    for y in support.y1..<support.y2 {
      for x in support.x1..<support.x2 {
        let pixel = y * size + x
        var sum: Float32 = 0
        for channel in 0..<Self.protoChannels {
          let protoIndex = isNHWC
            ? pixel * Self.protoChannels + channel
            : channel * pixels + pixel
          sum += candidate.maskCoefficients[channel] * proto[protoIndex]
        }
        let probability = 1.0 / (1.0 + exp(-Double(sum)))
        let raw = Int(probability * 255.0)
        mask[pixel] = UInt8(max(0, min(255, raw)))
      }
    }
    return mask
  }

  private func bboxIoU(_ a: IOSYoloRawCandidate, _ b: IOSYoloRawCandidate) -> Float32 {
    let x1 = max(a.x1, b.x1)
    let y1 = max(a.y1, b.y1)
    let x2 = min(a.x2, b.x2)
    let y2 = min(a.y2, b.y2)
    let intersection = max(0, x2 - x1) * max(0, y2 - y1)
    let areaA = max(0, a.x2 - a.x1) * max(0, a.y2 - a.y1)
    let areaB = max(0, b.x2 - b.x1) * max(0, b.y2 - b.y1)
    let union = areaA + areaB - intersection
    return union > 0 ? intersection / union : 0
  }

  private func maskIoUWithinSupport(
    _ a: [UInt8],
    _ b: [UInt8],
    _ candidateA: IOSYoloRawCandidate,
    _ candidateB: IOSYoloRawCandidate,
    inputSize: Int
  ) -> Float32 {
    let supportA = supportRect(candidateA, inputSize: inputSize)
    let supportB = supportRect(candidateB, inputSize: inputSize)
    let x1 = min(supportA.x1, supportB.x1)
    let y1 = min(supportA.y1, supportB.y1)
    let x2 = max(supportA.x2, supportB.x2)
    let y2 = max(supportA.y2, supportB.y2)
    var intersection = 0
    var union = 0
    for y in y1..<y2 {
      for x in x1..<x2 {
        let index = y * Self.protoSize + x
        let presentA = a[index] != 0
        let presentB = b[index] != 0
        if presentA && presentB { intersection += 1 }
        if presentA || presentB { union += 1 }
      }
    }
    return union == 0 ? 0 : Float32(intersection) / Float32(union)
  }

  private func supportRect(
    _ candidate: IOSYoloRawCandidate,
    inputSize: Int
  ) -> (x1: Int, y1: Int, x2: Int, y2: Int) {
    let scale = Float32(Self.protoSize) / Float32(inputSize)
    let margin = 1
    return (
      clampInt(Int(floor(Double(candidate.x1 * scale))) - margin, 0, Self.protoSize),
      clampInt(Int(floor(Double(candidate.y1 * scale))) - margin, 0, Self.protoSize),
      clampInt(Int(ceil(Double(candidate.x2 * scale))) + margin, 0, Self.protoSize),
      clampInt(Int(ceil(Double(candidate.y2 * scale))) + margin, 0, Self.protoSize)
    )
  }

  private func contractError(_ message: String) -> PigeonError {
    PigeonError(code: "YOLO_TENSOR_CONTRACT_MISMATCH", message: message, details: nil)
  }

  private func clamp(_ value: Float32, _ lower: Float32, _ upper: Float32) -> Float32 {
    max(lower, min(upper, value))
  }

  private func clampInt(_ value: Int, _ lower: Int, _ upper: Int) -> Int {
    max(lower, min(upper, value))
  }
}

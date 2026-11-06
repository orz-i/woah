import Foundation

struct IOSYoloDetection {
  let x1: Float32
  let y1: Float32
  let x2: Float32
  let y2: Float32
  let confidence: Float32
  let mask: [UInt8]
}

/// Model contract and DTO adapter; all postprocessing executes in FrameKernel.
final class IOSYoloPostprocessor {
  static let protoSize = 160
  static let protoChannels = 32
  static let attributeCount = 116
  static let anchorCount = 8400
  private let output0Shape: [Int]
  private let output1Shape: [Int]
  private let decoder = IOSFrameKernel.InstanceDecoder()

  init(output0Shape: [Int], output1Shape: [Int]) {
    self.output0Shape = output0Shape
    self.output1Shape = output1Shape
  }

  func parse(output0: [Float32], output1: [Float32], preprocess: IOSYoloPreprocessResult,
    confidenceThreshold: Float32 = 0.25, bboxIoUThreshold: Float32 = 0.50,
    maskIoUThreshold: Float32 = 0.50) throws -> [IOSYoloDetection] {
    try decoder.decode(head: output0, proto: output1, preprocess: preprocess,
      headTransposed: output0Shape == [1, Self.anchorCount, Self.attributeCount],
      protoNhwc: output1Shape == [1, Self.protoSize, Self.protoSize, Self.protoChannels],
      confidence: confidenceThreshold, bboxIoU: bboxIoUThreshold, maskIoU: maskIoUThreshold)
  }
}

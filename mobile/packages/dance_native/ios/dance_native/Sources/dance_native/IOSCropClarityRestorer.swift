import Foundation
import Metal

#if canImport(TensorFlowLite)
import TensorFlowLite
#endif

struct IOSCropClarityRuntimeInfo {
  let initializationMs: Double
  let inferenceMs: Double
}

/// Metal-targeted x2 tile runner for the optional SPAN-derived crop-clarity model.
///
/// Woah never retries a failed neural path on CPU. Delegate creation alone is
/// not treated as proof of full GPU residency; the static operator verifier and
/// a real iPhone profiler gate are required before production promotion. The
/// export pipeline keeps using the deterministic shader until that gate and the
/// zero-copy GPU handoff are explicitly accepted.
final class IOSCropClarityRestorer {
  static let tileSize = 192
  static let outputTileSize = 384
  static let inputFloatCount = tileSize * tileSize * 3
  static let outputFloatCount = outputTileSize * outputTileSize * 3

  private let queue = DispatchQueue(label: "art.gaoge.dance.crop-clarity")
  private(set) var lastRuntimeInfo: IOSCropClarityRuntimeInfo?

#if canImport(TensorFlowLite)
  private var interpreter: Interpreter?
  private var metalDelegate: Delegate?
  private var initializationMs: Double = 0
#endif

  static var modelAvailable: Bool {
    IOSModelResources.cropClarityModelURL() != nil
  }

  func initialize() throws {
    try queue.sync {
#if canImport(TensorFlowLite)
      if interpreter != nil { return }
      guard let modelURL = IOSModelResources.cropClarityModelURL() else {
        throw runtimeError("Crop-clarity LiteRT model is not staged in the iOS resource bundle.")
      }
      guard MTLCreateSystemDefaultDevice() != nil else {
        throw runtimeError("Metal is unavailable for crop-clarity neural inference.")
      }
      let started = CFAbsoluteTimeGetCurrent()
      var options = MetalDelegate.Options()
      options.isPrecisionLossAllowed = true
      options.waitType = .passive
      let delegate = MetalDelegate(options: options)
      let candidate = try Interpreter(
        modelPath: modelURL.path,
        options: Interpreter.Options(),
        delegates: [delegate]
      )
      try candidate.allocateTensors()
      try validateContract(candidate)
      try warmup(candidate)
      interpreter = candidate
      metalDelegate = delegate
      initializationMs = (CFAbsoluteTimeGetCurrent() - started) * 1000.0
#else
      throw runtimeError("TensorFlowLiteSwift/LiteRT runtime is not linked into this iOS build.")
#endif
    }
  }

  func restoreTileRgb(_ input: [Float32]) throws -> [Float32] {
    try queue.sync {
#if canImport(TensorFlowLite)
      if interpreter == nil { try initializeUnlocked() }
      guard let interpreter else {
        throw runtimeError("Crop-clarity interpreter is unavailable.")
      }
      guard input.count == Self.inputFloatCount else {
        throw contractError("Expected \(Self.inputFloatCount) input floats, got \(input.count).")
      }
      try interpreter.copy(Self.data(from: input), toInputAt: 0)
      let started = CFAbsoluteTimeGetCurrent()
      try interpreter.invoke()
      let inferenceMs = (CFAbsoluteTimeGetCurrent() - started) * 1000.0
      let output = try Self.floatArray(from: interpreter.output(at: 0).data)
      guard output.count == Self.outputFloatCount else {
        throw contractError("Expected \(Self.outputFloatCount) output floats, got \(output.count).")
      }
      lastRuntimeInfo = IOSCropClarityRuntimeInfo(
        initializationMs: initializationMs,
        inferenceMs: inferenceMs
      )
      return output
#else
      throw runtimeError("TensorFlowLiteSwift/LiteRT runtime is not linked into this iOS build.")
#endif
    }
  }

#if canImport(TensorFlowLite)
  private func initializeUnlocked() throws {
    guard interpreter == nil else { return }
    guard let modelURL = IOSModelResources.cropClarityModelURL() else {
      throw runtimeError("Crop-clarity LiteRT model is not staged in the iOS resource bundle.")
    }
    guard MTLCreateSystemDefaultDevice() != nil else {
      throw runtimeError("Metal is unavailable for crop-clarity neural inference.")
    }
    let started = CFAbsoluteTimeGetCurrent()
    var options = MetalDelegate.Options()
    options.isPrecisionLossAllowed = true
    options.waitType = .passive
    let delegate = MetalDelegate(options: options)
    let candidate = try Interpreter(
      modelPath: modelURL.path,
      options: Interpreter.Options(),
      delegates: [delegate]
    )
    try candidate.allocateTensors()
    try validateContract(candidate)
    try warmup(candidate)
    interpreter = candidate
    metalDelegate = delegate
    initializationMs = (CFAbsoluteTimeGetCurrent() - started) * 1000.0
  }

  private func warmup(_ interpreter: Interpreter) throws {
    let zeros = [Float32](repeating: 0, count: Self.inputFloatCount)
    try interpreter.copy(Self.data(from: zeros), toInputAt: 0)
    try interpreter.invoke()
    let output = try interpreter.output(at: 0)
    guard output.data.count == Self.outputFloatCount * MemoryLayout<Float32>.size else {
      throw contractError(
        "Warmup output byte count mismatch: \(output.data.count)."
      )
    }
  }

  private func validateContract(_ interpreter: Interpreter) throws {
    guard interpreter.inputTensorCount == 1, interpreter.outputTensorCount == 1 else {
      throw contractError(
        "Expected one input/output tensor, got \(interpreter.inputTensorCount)/\(interpreter.outputTensorCount)."
      )
    }
    let input = try interpreter.input(at: 0)
    let output = try interpreter.output(at: 0)
    guard input.dataType == .float32,
          input.shape.dimensions == [1, Self.tileSize, Self.tileSize, 3] else {
      throw contractError("Unexpected input tensor dtype=\(input.dataType) shape=\(input.shape.dimensions).")
    }
    guard output.dataType == .float32,
          output.shape.dimensions == [1, Self.outputTileSize, Self.outputTileSize, 3] else {
      throw contractError("Unexpected output tensor dtype=\(output.dataType) shape=\(output.shape.dimensions).")
    }
  }

  private static func data(from values: [Float32]) -> Data {
    values.withUnsafeBufferPointer { buffer in
      guard let base = buffer.baseAddress else { return Data() }
      return Data(bytes: base, count: buffer.count * MemoryLayout<Float32>.size)
    }
  }

  private static func floatArray(from data: Data) throws -> [Float32] {
    guard data.count % MemoryLayout<Float32>.size == 0 else {
      throw contractErrorStatic("Float tensor byte count is not divisible by four: \(data.count).")
    }
    var result = [Float32](
      repeating: 0,
      count: data.count / MemoryLayout<Float32>.size
    )
    result.withUnsafeMutableBytes { destination in
      data.copyBytes(to: destination)
    }
    return result
  }

  private static func contractErrorStatic(_ message: String) -> PigeonError {
    PigeonError(code: "CROP_CLARITY_TENSOR_CONTRACT_MISMATCH", message: message, details: nil)
  }
#endif

  private func runtimeError(_ message: String) -> PigeonError {
    PigeonError(code: "CROP_CLARITY_RUNTIME_FAILED", message: message, details: nil)
  }

  private func contractError(_ message: String) -> PigeonError {
    PigeonError(code: "CROP_CLARITY_TENSOR_CONTRACT_MISMATCH", message: message, details: nil)
  }
}

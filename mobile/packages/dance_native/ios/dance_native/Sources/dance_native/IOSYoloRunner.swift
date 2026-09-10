import CoreGraphics
import Foundation
import Metal

#if canImport(TensorFlowLite)
import TensorFlowLite
#endif

enum IOSYoloBackend: String, CaseIterable {
  case coreML = "tflite_coreml"
  case metal = "tflite_metal"
  case xnnpack = "tflite_xnnpack"
}

struct IOSYoloRuntimeInfo {
  let effectiveBackend: IOSYoloBackend
  let initializationMs: Double
  let inferenceMs: Double
  let fallbackReasons: [String]
  let inputShape: [Int]
  let outputShapes: [[Int]]
}

struct IOSYoloInferenceResult {
  let detections: [IOSYoloDetection]
  let runtime: IOSYoloRuntimeInfo
  let preprocess: IOSYoloPreprocessResult
}

enum IOSYoloRuntimeSupport {
  static func candidateBackendNames() -> [String] {
    guard IOSModelResources.yoloModelURL() != nil else { return [] }
#if canImport(TensorFlowLite)
    var result: [String] = []
    if CoreMLDelegate() != nil {
      result.append(IOSYoloBackend.coreML.rawValue)
    }
    if MTLCreateSystemDefaultDevice() != nil {
      result.append(IOSYoloBackend.metal.rawValue)
    }
    result.append(IOSYoloBackend.xnnpack.rawValue)
    return result
#else
    return []
#endif
  }
}

final class IOSYoloRunner {
  static let defaultConfidenceThreshold: Float32 = 0.25

  private let queue = DispatchQueue(label: "art.gaoge.dance.ios-yolo")
  private(set) var lastRuntimeInfo: IOSYoloRuntimeInfo?

#if canImport(TensorFlowLite)
  private var interpreter: Interpreter?
  // TensorFlowLite delegates own native TfLiteDelegate state that must outlive
  // the Interpreter using it. Retain the active delegate explicitly instead of
  // relying on a temporary array passed to Interpreter.init.
  private var activeDelegate: Delegate?
  private var effectiveBackend: IOSYoloBackend?
  private var initializationMs: Double = 0
  private var fallbackReasons: [String] = []
  private var outputShapes: [[Int]] = []
  private var configuredPreference: IOSYoloBackend?
#endif

  func run(
    image: CGImage,
    preferredBackend: IOSYoloBackend? = nil,
    confidenceThreshold: Float32 = IOSYoloRunner.defaultConfidenceThreshold
  ) throws -> IOSYoloInferenceResult {
    try queue.sync {
      let preprocess = try IOSYoloPreprocessor.process(image: image)
#if canImport(TensorFlowLite)
      return try runWithTensorFlowLite(
        preprocess: preprocess,
        preferredBackend: preferredBackend,
        confidenceThreshold: confidenceThreshold
      )
#else
      throw PigeonError(
        code: "IOS_INFERENCE_RUNTIME_UNAVAILABLE",
        message: "TensorFlowLiteSwift/LiteRT runtime is not linked into this iOS build.",
        details: nil
      )
#endif
    }
  }

#if canImport(TensorFlowLite)
  private func runWithTensorFlowLite(
    preprocess: IOSYoloPreprocessResult,
    preferredBackend: IOSYoloBackend?,
    confidenceThreshold: Float32
  ) throws -> IOSYoloInferenceResult {
    let requestedOrder: [IOSYoloBackend] = preferredBackend.map { [$0] }
      ?? [.coreML, .metal, .xnnpack]
    if interpreter == nil || configuredPreference != preferredBackend {
      interpreter = nil
      activeDelegate = nil
      effectiveBackend = nil
      fallbackReasons = []
      try initializeInterpreter(
        preferredBackends: requestedOrder,
        configuredPreference: preferredBackend
      )
    }

    var backendQueue = preferredBackend == nil
      ? remainingBackends(after: effectiveBackend)
      : []
    while true {
      do {
        guard let interpreter = interpreter,
              let backend = effectiveBackend else {
          throw runtimeError("YOLO interpreter was not initialized.")
        }
        let inputData = Self.data(from: preprocess.input)
        try interpreter.copy(inputData, toInputAt: 0)
        let start = CFAbsoluteTimeGetCurrent()
        try interpreter.invoke()
        let inferenceMs = (CFAbsoluteTimeGetCurrent() - start) * 1000.0
        let output0 = try Self.floatArray(from: interpreter.output(at: 0).data)
        let output1 = try Self.floatArray(from: interpreter.output(at: 1).data)
        let postprocessor = IOSYoloPostprocessor(
          output0Shape: outputShapes[0],
          output1Shape: outputShapes[1]
        )
        let detections = try postprocessor.parse(
          output0: output0,
          output1: output1,
          preprocess: preprocess,
          confidenceThreshold: confidenceThreshold
        )
        let info = IOSYoloRuntimeInfo(
          effectiveBackend: backend,
          initializationMs: initializationMs,
          inferenceMs: inferenceMs,
          fallbackReasons: fallbackReasons,
          inputShape: [1, 3, 640, 640],
          outputShapes: outputShapes
        )
        lastRuntimeInfo = info
        return IOSYoloInferenceResult(
          detections: detections,
          runtime: info,
          preprocess: preprocess
        )
      } catch {
        guard let next = backendQueue.first else { throw error }
        backendQueue.removeFirst()
        if let failed = effectiveBackend {
          fallbackReasons.append("\(failed.rawValue): \(String(describing: error))")
        }
        interpreter = nil
        activeDelegate = nil
        effectiveBackend = nil
        try initializeInterpreter(
          preferredBackends: [next] + backendQueue,
          configuredPreference: nil
        )
        backendQueue = remainingBackends(after: effectiveBackend)
      }
    }
  }

  private func initializeInterpreter(
    preferredBackends: [IOSYoloBackend] = [.coreML, .metal, .xnnpack],
    configuredPreference: IOSYoloBackend? = nil
  ) throws {
    guard let modelURL = IOSModelResources.yoloModelURL() else {
      throw PigeonError(
        code: "MODEL_NOT_FOUND",
        message: "The staged iOS YOLO model resource is missing. Run sync_ios_yolo_model.py before building.",
        details: nil
      )
    }

    let initializationStart = CFAbsoluteTimeGetCurrent()
    var lastError: Error?
    for backend in preferredBackends {
      do {
        let candidate = try makeInterpreter(modelPath: modelURL.path, backend: backend)
        try validateContract(candidate.interpreter)
        interpreter = candidate.interpreter
        activeDelegate = candidate.delegate
        effectiveBackend = backend
        self.configuredPreference = configuredPreference
        // Include failed delegate attempts in startup cost; effective backend
        // latency alone would under-report the user-visible auto-fallback cost.
        initializationMs = (CFAbsoluteTimeGetCurrent() - initializationStart) * 1000.0
        return
      } catch {
        lastError = error
        fallbackReasons.append("\(backend.rawValue): \(String(describing: error))")
      }
    }
    throw lastError ?? runtimeError("No iOS YOLO backend could be initialized.")
  }

  private func makeInterpreter(
    modelPath: String,
    backend: IOSYoloBackend
  ) throws -> (interpreter: Interpreter, delegate: Delegate?) {
    var options = Interpreter.Options()
    switch backend {
    case .coreML:
      guard let delegate = CoreMLDelegate() else {
        throw runtimeError("Core ML delegate is unavailable on this device.")
      }
      let interpreter = try Interpreter(
        modelPath: modelPath,
        options: options,
        delegates: [delegate]
      )
      try interpreter.allocateTensors()
      return (interpreter, delegate)
    case .metal:
      guard MTLCreateSystemDefaultDevice() != nil else {
        throw runtimeError("Metal device is unavailable.")
      }
      var delegateOptions = MetalDelegate.Options()
      delegateOptions.isPrecisionLossAllowed = true
      delegateOptions.waitType = .passive
      let delegate = MetalDelegate(options: delegateOptions)
      let interpreter = try Interpreter(
        modelPath: modelPath,
        options: options,
        delegates: [delegate]
      )
      try interpreter.allocateTensors()
      return (interpreter, delegate)
    case .xnnpack:
      options.threadCount = min(4, max(1, ProcessInfo.processInfo.processorCount))
      options.isXNNPackEnabled = true
      let interpreter = try Interpreter(modelPath: modelPath, options: options)
      try interpreter.allocateTensors()
      return (interpreter, nil)
    }
  }

  private func validateContract(_ interpreter: Interpreter) throws {
    guard interpreter.inputTensorCount == 1 else {
      throw contractError("Expected one input tensor, found \(interpreter.inputTensorCount).")
    }
    guard interpreter.outputTensorCount == 2 else {
      throw contractError("Expected two output tensors, found \(interpreter.outputTensorCount).")
    }
    let input = try interpreter.input(at: 0)
    let output0 = try interpreter.output(at: 0)
    let output1 = try interpreter.output(at: 1)
    let inputShape = input.shape.dimensions
    let out0Shape = output0.shape.dimensions
    let out1Shape = output1.shape.dimensions
    guard input.dataType == .float32, inputShape == [1, 3, 640, 640] else {
      throw contractError("Unexpected input tensor: dtype=\(input.dataType) shape=\(inputShape).")
    }
    let validOut0 = out0Shape == [1, 116, 8400] || out0Shape == [1, 8400, 116]
    let validOut1 = out1Shape == [1, 32, 160, 160] || out1Shape == [1, 160, 160, 32]
    guard output0.dataType == .float32, validOut0 else {
      throw contractError("Unexpected detection output tensor: dtype=\(output0.dataType) shape=\(out0Shape).")
    }
    guard output1.dataType == .float32, validOut1 else {
      throw contractError("Unexpected proto output tensor: dtype=\(output1.dataType) shape=\(out1Shape).")
    }
    outputShapes = [out0Shape, out1Shape]
  }

  private func remainingBackends(after backend: IOSYoloBackend?) -> [IOSYoloBackend] {
    let ordered: [IOSYoloBackend] = [.coreML, .metal, .xnnpack]
    guard let backend, let index = ordered.firstIndex(of: backend) else { return ordered }
    return Array(ordered.dropFirst(index + 1))
  }

  private static func data(from values: [Float32]) -> Data {
    values.withUnsafeBufferPointer { buffer in
      guard let base = buffer.baseAddress else { return Data() }
      return Data(bytes: base, count: buffer.count * MemoryLayout<Float32>.size)
    }
  }

  private static func floatArray(from data: Data) throws -> [Float32] {
    guard data.count % MemoryLayout<Float32>.size == 0 else {
      throw PigeonError(
        code: "YOLO_TENSOR_CONTRACT_MISMATCH",
        message: "Float tensor byte count is not divisible by four: \(data.count).",
        details: nil
      )
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
#endif

  private func runtimeError(_ message: String) -> PigeonError {
    PigeonError(code: "IOS_INFERENCE_RUNTIME_FAILED", message: message, details: nil)
  }

  private func contractError(_ message: String) -> PigeonError {
    PigeonError(code: "YOLO_TENSOR_CONTRACT_MISMATCH", message: message, details: nil)
  }
}

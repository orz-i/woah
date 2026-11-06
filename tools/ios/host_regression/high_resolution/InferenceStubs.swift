import CoreGraphics
struct IOSPreviewPerson { let id: Int; let detection: IOSYoloDetection }
enum IOSYoloBackend { case xnnpack }
struct IOSYoloInferenceResult { let detections: [IOSYoloDetection]; let runtime: Int; let preprocess: IOSYoloPreprocessResult }
final class IOSYoloRunner {
  func run(image: CGImage, preferredBackend: IOSYoloBackend) throws -> IOSYoloInferenceResult { fatalError("Host test must not substitute inference") }
}

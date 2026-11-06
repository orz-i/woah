import Foundation

struct PigeonError: Error {
  let code: String
  let message: String?
  let details: Any?
}
struct IOSCachedBBox: Codable { let left: Double; let top: Double; let right: Double; let bottom: Double }
struct IOSCachedPerson: Codable { let id: Int; let bbox: IOSCachedBBox; let confidence: Double }
struct IOSAnalysisMetadata: Codable { let schemaVersion: Int; let sourceUri: String; let persons: [IOSCachedPerson] }
struct IOSYoloDetection {
  let x1: Float32
  let y1: Float32
  let x2: Float32
  let y2: Float32
  let confidence: Float32
  let mask: [UInt8]
}
struct IOSYoloPreprocessResult {
  let input: [Float32]
  let scale: Float32
  let padLeft: Float32
  let padTop: Float32
  let sourceWidth: Int
  let sourceHeight: Int
  let inputSize: Int
}
enum IOSYoloPostprocessor { static let protoSize = 160 }

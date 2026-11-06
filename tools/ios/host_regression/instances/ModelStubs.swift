import Foundation
struct PigeonError: Error {
  let code: String
  let message: String?
  let details: Any?
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

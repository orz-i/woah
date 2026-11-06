import Foundation
struct PigeonError: Error { let code: String; let message: String?; let details: Any? }
struct JobStatusDto {
  var jobId: String
  var state: String
  var currentFrame: Int64
  var totalFrames: Int64
  var fps: Double
  var progress: Double
  var outputUri: String? = nil
  var currentPreviewPath: String? = nil
  var errorCode: String? = nil
  var errorMessage: String? = nil
}

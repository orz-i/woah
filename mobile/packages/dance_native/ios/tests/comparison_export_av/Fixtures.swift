import Foundation

// Compile this harness with -D COMPARISON_EXPORT_STANDALONE. Production builds
// use the generated Pigeon DTOs and the app's stable local-file resolver.
#if COMPARISON_EXPORT_STANDALONE
struct ComparisonPictureDto {
  var zoom: Double
  var panX: Double
  var panY: Double
}
struct ComparisonPracticeDto {
  var mirrorTeacher: Bool
  var mirrorLearner: Bool
  var loopStartUs: Int64?
  var loopEndUs: Int64?
  var loopEnabled: Bool
  var teacherPicture: ComparisonPictureDto?
  var learnerPicture: ComparisonPictureDto?
}
enum IOSComparisonFiles {
  static func resolve(_ uri: String) throws -> URL { URL(fileURLWithPath: uri) }
}
#endif

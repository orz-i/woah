// Test-only Pigeon-free values. These files are outside the plugin source glob.
struct ComparisonPictureDto {
  var zoom: Double
  var panX: Double
  var panY: Double
}

struct ComparisonPracticeDto {
  var mirrorTeacher = false
  var mirrorLearner = false
  var teacherPicture: ComparisonPictureDto?
  var learnerPicture: ComparisonPictureDto?
}

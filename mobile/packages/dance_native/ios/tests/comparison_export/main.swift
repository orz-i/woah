import Foundation

// Portable lifecycle/spec suite:
// swiftc -O IOSComparisonExportSpec.swift tests/comparison_export/main.swift -o comparison-export-tests

func expectFailure(_ body: () throws -> Void) {
  do { try body(); preconditionFailure("Expected rejected export input") }
  catch { precondition(error is IOSComparisonExportError) }
}

let project = #"{"schemaVersion":1,"id":"test-project","teacher":{"uri":"video/teacher.mov"},"learner":{"uri":"video/learner.mov"},"offsetUs":-650000,"timeScale":1.007,"mirrorTeacher":true,"teacherPicture":{"zoom":2,"panX":0.3,"panY":-0.2},"speed":0.25,"loopEnabled":true}"#
let spec = try IOSComparisonExportSpec.fromProject(project, startUs: 100_000, endUs: 1_333_337,
  width: 1280, height: 720, gap: 4, sideBySide: true)
precondition(spec.durationUs == 1_233_337 && spec.timeScale == 1.007)
precondition(spec.mirrorTeacher && !spec.mirrorLearner)
precondition(spec.teacherPicture.zoom == 2 && spec.learnerPicture == .init())
let frozen = try JSONEncoder().encode(spec)
let thawed = try JSONDecoder().decode(IOSComparisonExportSpec.self, from: frozen)
precondition(thawed == spec)
precondition(!String(decoding: frozen, as: UTF8.self).contains("speed"))
precondition(!String(decoding: frozen, as: UTF8.self).contains("loopEnabled"))
let defaults = #"{"schemaVersion":1,"id":"defaults","teacher":{"uri":"video/a"},"learner":{"uri":"video/b"},"offsetUs":0}"#
let defaultSpec = try IOSComparisonExportSpec.fromProject(defaults, startUs: 0, endUs: 1,
  width: 16, height: 16, gap: 1, sideBySide: false)
precondition(defaultSpec.timeScale == 1 && defaultSpec.durationUs == 1)
for (start, end, width, height, gap) in [
  (-1 as Int64, 10 as Int64, 1280, 720, 4), (10, 10, 1280, 720, 4),
  (0, 86_400_000_001, 1280, 720, 4), (0, 10, 1279, 720, 4),
  (0, 10, 1280, 1922, 4), (0, 10, 16, 16, 16), (0, 10, 16, 16, 0),
] {
  expectFailure {
    _ = try IOSComparisonExportSpec.fromProject(defaults, startUs: start, endUs: end,
      width: width, height: height, gap: gap, sideBySide: true)
  }
}
for corrupted in [project.replacingOccurrences(of: "test-project", with: "../escape"),
  project.replacingOccurrences(of: "\"schemaVersion\":1", with: "\"schemaVersion\":2"),
  project.replacingOccurrences(of: "\"timeScale\":1.007", with: "\"timeScale\":2.01"),
  project.replacingOccurrences(of: "\"zoom\":2", with: "\"zoom\":0.5"),
  project.replacingOccurrences(of: "\"panX\":0.3", with: "\"panX\":1.1")] {
  expectFailure {
    _ = try IOSComparisonExportSpec.fromProject(corrupted, startUs: 0, endUs: 10,
      width: 1280, height: 720, gap: 4, sideBySide: true)
  }
}
print("PASS frozen spec, legacy defaults, exact microseconds and invalid project/range/geometry")

var record = IOSComparisonExportRecord(id: "export-test", spec: spec)
record.state = "encoding"
record.progress = 0.42
let roundTrip = try JSONDecoder().decode(IOSComparisonExportRecord.self,
  from: JSONEncoder().encode(record))
try roundTrip.validate()
let interrupted = roundTrip.recovered(outputExists: false)
precondition(interrupted.state == "interrupted" && interrupted.spec == spec && interrupted.progress == 0.42)
let committed = roundTrip.recovered(outputExists: true)
precondition(committed.state == "completed" && committed.progress == 1)
precondition(committed.recovered(outputExists: false).state == "interrupted")
record.state = "publishing"
let interruptedPermission = record.recovered(outputExists: true)
precondition(interruptedPermission.state == "completed" && !interruptedPermission.photoSaveInFlight)
precondition(interruptedPermission.error == nil)
record.photoSaveInFlight = true
record.pendingAssetIdentifier = "persisted-placeholder/L0/001"
let pendingSave = record.recovered(outputExists: true)
precondition(pendingSave.state == "completed" && pendingSave.photoSaveInFlight)
precondition(pendingSave.error != nil && pendingSave.pendingAssetIdentifier == record.pendingAssetIdentifier)
record.state = "completed"
record.photoSaveInFlight = false
record.pendingAssetIdentifier = nil
record.savedAssetIdentifier = "saved-asset/L0/001"
precondition(record.recovered(outputExists: true).savedAssetIdentifier == record.savedAssetIdentifier)
print("PASS interrupted recovery, rename-before-metadata recovery and Photos idempotency fence")

let cancelled = IOSComparisonExportCancellation()
precondition(cancelled.cancel(.user))
precondition(cancelled.reason == .user)
expectFailure { try cancelled.check() }
var publishedAfterCancel = false
expectFailure { try cancelled.commit { publishedAfterCancel = true } }
precondition(!publishedAfterCancel)
let published = IOSComparisonExportCancellation()
try published.commit {}
precondition(!published.cancel(.user) && published.reason == nil)
let expired = IOSComparisonExportCancellation()
precondition(expired.cancel(.backgroundExpired))
precondition(expired.reason == .backgroundExpired)
print("PASS cancellation/publication gate, late cancellation and background expiration")

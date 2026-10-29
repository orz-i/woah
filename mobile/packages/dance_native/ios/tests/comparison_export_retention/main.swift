import Foundation

func ensure(_ ok: Bool, _ message: String) { precondition(ok, message) }
ensure(IOSComparisonExportFiles.needsReplacementConsent(state: "completed", savedIdentifier: nil,
  outputExists: true, confirmed: false), "Unsaved completed export needs consent")
ensure(!IOSComparisonExportFiles.needsReplacementConsent(state: "completed", savedIdentifier: nil,
  outputExists: true, confirmed: true), "Explicit consent allows replacement")
ensure(!IOSComparisonExportFiles.needsReplacementConsent(state: "completed", savedIdentifier: "ph://asset",
  outputExists: true, confirmed: false), "Photos-saved exports are replaceable")
ensure(!IOSComparisonExportFiles.needsReplacementConsent(state: "interrupted", savedIdentifier: nil,
  outputExists: true, confirmed: false), "Retryable exports are not an unsaved completion")
ensure(!IOSComparisonExportFiles.needsReplacementConsent(state: "completed", savedIdentifier: nil,
  outputExists: false, confirmed: false), "Missing movie cannot block forever")

let directory = FileManager.default.temporaryDirectory.appendingPathComponent("woah-export-retention-\(UUID().uuidString)")
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: directory) }
func mp4(_ id: String, old: Bool) throws -> URL {
  let file = directory.appendingPathComponent("\(id).mp4")
  try Data([1, 2, 3]).write(to: file)
  if old { try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 100)],
    ofItemAtPath: file.path) }
  return file
}
let now = Date(timeIntervalSince1970: 2_000_000)
let latest = try mp4(UUID().uuidString, old: true)
let previous = try mp4(UUID().uuidString, old: false)
let agedLegacy = try mp4(UUID().uuidString, old: true)
let youngLegacy = try mp4(UUID().uuidString, old: false)
let foreign = directory.appendingPathComponent("external_video.mp4")
try Data([3]).write(to: foreign)
let symlink = directory.appendingPathComponent("\(UUID().uuidString).mp4")
try FileManager.default.createSymbolicLink(at: symlink, withDestinationURL: foreign)
let removed = try IOSComparisonExportFiles.prune(directory: directory,
  keeping: latest.deletingPathExtension().lastPathComponent,
  replacing: previous.deletingPathExtension().lastPathComponent,
  now: now, legacyAge: 1000)
ensure(removed == 2, "Replaced predecessor and aged orphan must be reclaimed")
for url in [latest, youngLegacy, foreign, symlink] {
  ensure(FileManager.default.fileExists(atPath: url.path), "Current, fresh or unmanaged file lost: \(url.lastPathComponent)")
}
for url in [previous, agedLegacy] {
  ensure(!FileManager.default.fileExists(atPath: url.path), "Orphan remained: \(url.lastPathComponent)")
}
print("PASS iOS unsaved-export consent + safe private MP4 retention and legacy cleanup")

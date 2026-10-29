import Foundation

// Standalone Foundation integrity gate; only newly generated UUID test files
// are removed. Existing media/projects in Application Support are untouched.
func rejects(_ body: () throws -> Void) {
  do { try body(); fatalError("Expected rejection") } catch { }
}
func require(_ condition: Bool, _ message: String = "Integrity check failed") {
  precondition(condition, message)
}
func json(_ object: [String: Any]) throws -> String {
  String(data: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), encoding: .utf8)!
}
func object(_ json: String) -> [String: Any]? {
  guard let data = json.data(using: .utf8) else { return nil }
  return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
}

let media = try IOSComparisonFiles.newMediaURL(extension: "mov")
let missingMedia = try IOSComparisonFiles.newMediaURL(extension: "mp4")
let symlink = try IOSComparisonFiles.newMediaURL(extension: "mov")
let external = FileManager.default.temporaryDirectory.appendingPathComponent("woah-external-\(UUID().uuidString).mov")
let projectID = "codex_test_\(UUID().uuidString)"
let missingID = "codex_test_\(UUID().uuidString)"
let brokenID = "codex_test_\(UUID().uuidString)"
let support = try FileManager.default.url(for: .applicationSupportDirectory,
  in: .userDomainMask, appropriateFor: nil, create: true)
let projects = support.appendingPathComponent("WoahComparisonProjects", isDirectory: true)
defer {
  for url in [media, missingMedia, symlink, external,
    projects.appendingPathComponent("\(projectID).json"),
    projects.appendingPathComponent("\(missingID).json"),
    projects.appendingPathComponent("\(brokenID).json")] {
    try? FileManager.default.removeItem(at: url)
  }
}
try Data([1, 2, 3]).write(to: media)
let stable = "woah-comparison-media://\(media.lastPathComponent)"
require(try IOSComparisonFiles.resolve(stable) == media)
precondition(IOSComparisonFiles.persistedURI(media.absoluteString) == stable)
precondition(IOSComparisonFiles.restoredURI(stable) == media.absoluteString)
let container = UUID().uuidString
let old = URL(fileURLWithPath: "/var/mobile/Containers/Data/Application/\(container)/Library/Application Support/WoahComparisonMedia/\(media.lastPathComponent)")
require(try IOSComparisonFiles.resolve(old.absoluteString) == media)
precondition(IOSComparisonFiles.persistedURI(old.absoluteString) == stable)
let simulator = URL(fileURLWithPath: "/Users/test/Library/Developer/CoreSimulator/Devices/\(UUID().uuidString)/data/Containers/Data/Application/\(container)/Library/Application Support/WoahComparisonMedia/\(media.lastPathComponent)")
require(try IOSComparisonFiles.resolve(simulator.absoluteString) == media)
let other = URL(fileURLWithPath: "/tmp/\(media.lastPathComponent)")
require(try IOSComparisonFiles.resolve(other.absoluteString) == other)
precondition(IOSComparisonFiles.persistedURI(other.absoluteString) == other.absoluteString)
let spoof = URL(fileURLWithPath: "/tmp/Containers/Data/Application/\(container)/Library/Application Support/WoahComparisonMedia/\(media.lastPathComponent)")
require(try IOSComparisonFiles.resolve(spoof.absoluteString) == spoof)
for invalid in ["https://example.com/video.mov", "video.mov", "file://server/tmp/video.mov",
  "woah-comparison-media://../../video.mov", "woah-comparison-media://invalid.mov", stable + "?x=1", stable + "/extra"] {
  rejects { _ = try IOSComparisonFiles.resolve(invalid) }
}
try Data([4]).write(to: external)
try FileManager.default.createSymbolicLink(at: symlink, withDestinationURL: external)
rejects { _ = try IOSComparisonFiles.resolve("woah-comparison-media://\(symlink.lastPathComponent)") }

func project(_ id: String, _ uri: String) -> [String: Any] {
  ["schemaVersion": 1, "id": id, "title": "Integrity test", "positionUs": 0,
    "offsetUs": -3_000_000, "timeScale": 1.001, "speed": 0.75,
    "teacher": ["uri": uri, "name": "Test", "durationUs": 10_000_000, "hasAudio": true],
    "markers": [["id": "test-marker", "sourceUri": uri, "startUs": 1, "endUs": 2]]]
}
let original = project(projectID, media.absoluteString)
try IOSComparisonProjectStore.save(json(original))
let projectFile = projects.appendingPathComponent("\(projectID).json")
let storedData = try Data(contentsOf: projectFile)
let stored = try JSONSerialization.jsonObject(with: storedData) as! [String: Any]
precondition((stored["teacher"] as! [String: Any])["uri"] as! String == stable)
precondition((stored["markers"] as! [[String: Any]])[0]["sourceUri"] as! String == stable)
let brokenRaw = "{damaged comparison test \(brokenID)"
try Data(brokenRaw.utf8).write(to: projects.appendingPathComponent("\(brokenID).json"))
let loaded = try IOSComparisonProjectStore.load()
precondition(loaded.contains(brokenRaw), "Damaged JSON must reach unreadableCount unchanged")
let restored = loaded.compactMap(object).first { $0["id"] as? String == projectID }!
precondition((restored["teacher"] as! [String: Any])["uri"] as! String == media.absoluteString)
precondition((restored["markers"] as! [[String: Any]])[0]["sourceUri"] as! String == media.absoluteString)
var invalid = original
invalid["speed"] = 2
rejects { try IOSComparisonProjectStore.save(json(invalid)) }
require(try Data(contentsOf: projectFile) == storedData, "Invalid save changed existing project")
invalid = original; invalid["id"] = "../escape"
rejects { try IOSComparisonProjectStore.save(json(invalid)) }
invalid = original; invalid["schemaVersion"] = true
rejects { try IOSComparisonProjectStore.save(json(invalid)) }

let missingStable = "woah-comparison-media://\(missingMedia.lastPathComponent)"
try IOSComparisonProjectStore.save(json(project(missingID, missingMedia.absoluteString)))
let missing = try IOSComparisonProjectStore.load().compactMap(object).first { $0["id"] as? String == missingID }!
precondition((missing["teacher"] as! [String: Any])["uri"] as! String == missingStable)
try Data([5]).write(to: missingMedia)
let recovered = try IOSComparisonProjectStore.load().compactMap(object).first { $0["id"] as? String == missingID }!
precondition((recovered["teacher"] as! [String: Any])["uri"] as! String == missingMedia.absoluteString)
print("Comparison media path / atomic project / corrupt entry / marker identity integrity gate passed (macOS)")


// Age-gated reclamation is based on ALL persisted project sources and markers,
// plus the latest frozen export. An unreadable project must block deletion.
let gcRoot = FileManager.default.temporaryDirectory.appendingPathComponent("woah-media-gc-\(UUID().uuidString)")
let gcMedia = gcRoot.appendingPathComponent("media", isDirectory: true)
let gcProjects = gcRoot.appendingPathComponent("projects", isDirectory: true)
let gcExport = gcRoot.appendingPathComponent("exports/latest.json")
try FileManager.default.createDirectory(at: gcMedia, withIntermediateDirectories: true)
try FileManager.default.createDirectory(at: gcProjects, withIntermediateDirectories: true)
try FileManager.default.createDirectory(at: gcExport.deletingLastPathComponent(), withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: gcRoot) }
func gcFile(_ ext: String = "mov", old: Bool = true) throws -> URL {
  let url = gcMedia.appendingPathComponent("\(UUID().uuidString.lowercased()).\(ext)")
  try Data([1, 2, 3]).write(to: url)
  if old { try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 100)],
    ofItemAtPath: url.path) }
  return url
}
func gcURI(_ url: URL) -> String { "woah-comparison-media://\(url.lastPathComponent)" }
let referenced = try gcFile()
let referencedMarker = try gcFile()
let exported = try gcFile()
let orphan = try gcFile()
let recent = try gcFile(old: false)
let foreign = gcMedia.appendingPathComponent("not-managed.mp4")
try Data([9]).write(to: foreign)
let symlinkGC = gcMedia.appendingPathComponent("\(UUID().uuidString.lowercased()).mov")
try FileManager.default.createSymbolicLink(at: symlinkGC, withDestinationURL: external)
var gcProject = project("gc-project", gcURI(referenced))
gcProject["markers"] = [["id": "gc-marker", "sourceUri": gcURI(referencedMarker), "startUs": 1, "endUs": 2]]
try Data(try json(gcProject).utf8).write(to: gcProjects.appendingPathComponent("project.json"))
let frozen = ["spec": ["teacherURI": gcURI(exported), "learnerURI": gcURI(referenced)]]
try Data(try json(frozen).utf8).write(to: gcExport)
let gcNow = Date(timeIntervalSince1970: 200_000)
let removed1 = try IOSComparisonProjectStore.pruneStaleImports(now: gcNow, minimumAge: 100,
  projects: gcProjects, exports: gcExport, media: gcMedia)
require(removed1 == 1, "Only an unreferenced, old import should be reclaimed")
for url in [referenced, referencedMarker, exported, recent, foreign, symlinkGC] {
  require(FileManager.default.fileExists(atPath: url.path), "Protected or young file was removed: \(url.lastPathComponent)")
}
try Data("{corrupt".utf8).write(to: gcProjects.appendingPathComponent("corrupt.json"))
let blocked = try IOSComparisonProjectStore.pruneStaleImports(now: gcNow, minimumAge: 100,
  projects: gcProjects, exports: gcExport, media: gcMedia)
require(blocked == 0, "Corrupt project must disable pruning")
try FileManager.default.removeItem(at: gcProjects.appendingPathComponent("corrupt.json"))
try FileManager.default.removeItem(at: gcExport)
let removed2 = try IOSComparisonProjectStore.pruneStaleImports(now: gcNow, minimumAge: 100,
  projects: gcProjects, exports: gcExport, media: gcMedia)
require(removed2 == 1 && !FileManager.default.fileExists(atPath: exported.path),
  "Old export source should become reclaimable only after its reference disappears")
print("Comparison imported media GC honors references, young imports, exports and unreadable projects")

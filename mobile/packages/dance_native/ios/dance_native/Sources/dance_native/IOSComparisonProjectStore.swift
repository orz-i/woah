import CoreFoundation
import Foundation

/// One atomic JSON file per project. A damaged entry never hides its siblings.
enum IOSComparisonProjectStore {
  private static let lock = NSLock()

  static func load() throws -> [String] {
    lock.lock()
    defer { lock.unlock() }
    let directory = try projectDirectory(create: false)
    guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
    let urls = try FileManager.default.contentsOfDirectory(at: directory,
      includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey], options: [.skipsHiddenFiles])
      .filter { $0.pathExtension == "json" }
      .sorted {
        let left = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
        let right = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
        return left == right ? $0.lastPathComponent < $1.lastPathComponent : left > right
      }
    return urls.map { url in
      guard let data = try? Data(contentsOf: url) else {
        // Invalid JSON intentionally reaches Dart's unreadableCount path.
        return "Unreadable comparison project: \(url.lastPathComponent)"
      }
      let raw = String(decoding: data, as: UTF8.self)
      guard var project = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
            number(project["schemaVersion"])?.doubleValue == 1 else { return raw }
      rewriteSources(&project, transform: IOSComparisonFiles.restoredURI)
      guard let restored = try? JSONSerialization.data(withJSONObject: project, options: [.sortedKeys]),
            let json = String(data: restored, encoding: .utf8) else { return raw }
      return json
    }
  }

  /// Keep every project's source/marker and the most recent frozen export source.
  /// A damaged or unreadable record blocks all reclamation, rather than risking
  /// a reference that we could not inspect. This is best-effort maintenance.
  static func pruneStaleImports(now: Date = Date(), minimumAge: TimeInterval = 24 * 60 * 60,
    projects overrideProjects: URL? = nil, exports overrideExports: URL? = nil,
    media overrideMedia: URL? = nil) throws -> Int {
    lock.lock()
    defer { lock.unlock() }
    let projects = try overrideProjects ?? projectDirectory(create: false)
    var references = Set<String>()
    if FileManager.default.fileExists(atPath: projects.path) {
      let entries = try FileManager.default.contentsOfDirectory(at: projects,
        includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles])
      for entry in entries where entry.pathExtension == "json" {
        let values = try entry.resourceValues(forKeys: [.isRegularFileKey])
        guard values.isRegularFile == true,
              let data = try? Data(contentsOf: entry),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              number(object["schemaVersion"])?.intValue == 1 else { return 0 }
        for key in ["teacher", "learner"] {
          if object[key] is NSNull || object[key] == nil { continue }
          guard let source = object[key] as? [String: Any],
                let uri = source["uri"] as? String else { return 0 }
          references.insert(uri)
        }
        if let markers = object["markers"] {
          guard let list = markers as? [[String: Any]] else { return 0 }
          for marker in list {
            guard let uri = marker["sourceUri"] as? String else { return 0 }
            references.insert(uri)
          }
        }
      }
    }
    let metadata = overrideExports ?? FileManager.default.urls(for: .applicationSupportDirectory,
      in: .userDomainMask)[0].appendingPathComponent("WoahComparisonExports/latest.json")
    if FileManager.default.fileExists(atPath: metadata.path) {
      guard let data = try? Data(contentsOf: metadata),
            let record = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
            let spec = record["spec"] as? [String: Any],
            let reference = spec["teacherURI"] as? String,
            let target = spec["learnerURI"] as? String else { return 0 }
      references.insert(reference)
      references.insert(target)
    }
    return try IOSComparisonFiles.pruneUnreferenced(references, now: now,
      minimumAge: minimumAge, directory: overrideMedia)
  }

  static func save(_ projectJson: String) throws {
    guard let data = projectJson.data(using: .utf8),
          var project = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          number(project["schemaVersion"])?.doubleValue == 1,
          let id = project["id"] as? String,
          id.range(of: "^[A-Za-z0-9_-]{1,80}$", options: .regularExpression) != nil,
          let position = number(project["positionUs"])?.doubleValue, position.isFinite, position >= 0,
          let offset = number(project["offsetUs"])?.doubleValue, offset.isFinite,
          (-172_800_000_000...172_800_000_000).contains(offset),
          let speed = number(project["speed"])?.doubleValue, [0.5, 0.75, 1].contains(speed) else {
      throw failure("Invalid comparison project state.")
    }
    let scale: Double
    if let value = project["timeScale"], !(value is NSNull) {
      guard let parsed = number(value)?.doubleValue else { throw failure("Invalid music time scale.") }
      scale = parsed
    } else { scale = 1 }
    guard scale.isFinite, (0.5...2).contains(scale) else { throw failure("Invalid music time scale.") }
    rewriteSources(&project, transform: IOSComparisonFiles.persistedURI)
    let persisted = try JSONSerialization.data(withJSONObject: project, options: [.sortedKeys])
    lock.lock()
    defer { lock.unlock() }
    let directory = try projectDirectory(create: true)
    try persisted.write(to: directory.appendingPathComponent("\(id).json"), options: [.atomic])
  }

  private static func rewriteSources(_ project: inout [String: Any], transform: (String) -> String) {
    for key in ["teacher", "learner"] {
      if var source = project[key] as? [String: Any], let uri = source["uri"] as? String {
        source["uri"] = transform(uri)
        project[key] = source
      }
    }
    // Markers use source URI as identity and must migrate with both source slots.
    if var markers = project["markers"] as? [[String: Any]] {
      for index in markers.indices {
        if let uri = markers[index]["sourceUri"] as? String { markers[index]["sourceUri"] = transform(uri) }
      }
      project["markers"] = markers
    }
  }

  private static func projectDirectory(create: Bool) throws -> URL {
    let support = try FileManager.default.url(for: .applicationSupportDirectory,
      in: .userDomainMask, appropriateFor: nil, create: create)
    let directory = support.appendingPathComponent("WoahComparisonProjects", isDirectory: true)
    if create { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
    return directory
  }

  private static func number(_ value: Any?) -> NSNumber? {
    guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() else { return nil }
    return value
  }

  private static func failure(_ message: String) -> NSError {
    NSError(domain: "WoahComparisonProjectStore", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
}

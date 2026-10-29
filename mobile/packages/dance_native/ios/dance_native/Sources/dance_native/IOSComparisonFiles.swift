import Foundation

/// Only UUID files inside the comparison media namespace may be relocated when
/// iOS changes the application's container path after an update or restore.
enum IOSComparisonFiles {
  private static let mediaScheme = "woah-comparison-media"
  private static let mediaFolder = "WoahComparisonMedia"

  static func mediaDirectory() throws -> URL {
    let support = try FileManager.default.url(for: .applicationSupportDirectory,
      in: .userDomainMask, appropriateFor: nil, create: true)
    let directory = support.appendingPathComponent(mediaFolder, isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return directory.standardizedFileURL
  }

  static func newMediaURL(`extension` fileExtension: String) throws -> URL {
    let suffix = fileExtension.lowercased()
    guard suffix.range(of: "^[a-z0-9]{1,10}$", options: .regularExpression) != nil else {
      throw failure("Invalid comparison media extension.")
    }
    let directory = try mediaDirectory()
    var url: URL
    repeat { url = directory.appendingPathComponent("\(UUID().uuidString.lowercased()).\(suffix)") }
    while FileManager.default.fileExists(atPath: url.path)
    return url
  }

  static func resolve(_ uri: String) throws -> URL {
    if let components = URLComponents(string: uri), components.scheme?.lowercased() == mediaScheme {
      guard components.user == nil, components.password == nil, components.port == nil,
            components.query == nil, components.fragment == nil, components.path.isEmpty,
            let filename = components.host, validFilename(filename) else {
        throw failure("Invalid comparison media reference.")
      }
      return try ownedURL(filename)
    }
    let local = try localURL(uri)
    if let filename = try ownedFilename(local) { return try ownedURL(filename) }
    return local
  }

  /// Save stable identities for owned files; external or unavailable references
  /// remain opaque so a project can still be opened and manually relinked.
  static func persistedURI(_ uri: String) -> String {
    guard let url = try? resolve(uri), let filename = try? ownedFilename(url) else { return uri }
    return "\(mediaScheme)://\(filename)"
  }

  static func restoredURI(_ uri: String) -> String {
    guard let url = try? resolve(uri), FileManager.default.isReadableFile(atPath: url.path) else { return uri }
    return url.absoluteString
  }

  private static func localURL(_ uri: String) throws -> URL {
    if uri.hasPrefix("/") { return URL(fileURLWithPath: uri).standardizedFileURL }
    guard let components = URLComponents(string: uri), components.scheme?.lowercased() == "file",
          components.user == nil, components.password == nil, components.port == nil,
          components.query == nil, components.fragment == nil,
          components.host == nil || components.host == "" || components.host?.lowercased() == "localhost",
          let url = components.url, url.isFileURL, url.path.hasPrefix("/") else {
      throw failure("Comparison sources must be absolute local file URLs.")
    }
    return url.standardizedFileURL
  }

  /// Reclaim only aged, unreferenced imported copies; UUID-only files and a
  /// fixed owned directory prevent deleting unrelated files or symlink targets.
  /// Recent copies are leased while the picker/editor completes its async save.
  static func pruneUnreferenced(_ referencedURIs: Set<String>, now: Date = Date(),
    minimumAge: TimeInterval = 24 * 60 * 60, directory explicitDirectory: URL? = nil) throws -> Int {
    let directory = try (explicitDirectory ?? mediaDirectory()).standardizedFileURL
    let keep = Set(referencedURIs.compactMap { uri -> String? in
      if let components = URLComponents(string: uri),
         components.scheme?.lowercased() == mediaScheme,
         components.path.isEmpty, components.query == nil, components.fragment == nil,
         let filename = components.host, validFilename(filename) { return filename.lowercased() }
      guard let url = try? localURL(uri) else { return nil }
      return (try? ownedFilename(url))?.lowercased()
    })
    let keys: Set<URLResourceKey> = [.isRegularFileKey, .isSymbolicLinkKey, .contentModificationDateKey]
    let candidates = try FileManager.default.contentsOfDirectory(at: directory,
      includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles])
    var removed = 0
    for candidate in candidates {
      guard validFilename(candidate.lastPathComponent),
            !keep.contains(candidate.lastPathComponent.lowercased()),
            candidate.deletingLastPathComponent().standardizedFileURL == directory else { continue }
      let attributes = try candidate.resourceValues(forKeys: keys)
      guard attributes.isRegularFile == true, attributes.isSymbolicLink != true,
            let modified = attributes.contentModificationDate,
            modified <= now.addingTimeInterval(-minimumAge) else { continue }
      try FileManager.default.removeItem(at: candidate)
      removed += 1
    }
    return removed
  }

  private static func validFilename(_ filename: String) -> Bool {
    filename.range(of: "^[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}\\.[A-Za-z0-9]{1,10}$",
      options: .regularExpression) != nil
  }

  private static func ownedFilename(_ url: URL) throws -> String? {
    let file = url.standardizedFileURL
    let filename = file.lastPathComponent
    guard validFilename(filename) else { return nil }
    let directory = try mediaDirectory()
    if file.deletingLastPathComponent().path == directory.path { return filename }
    // Device and simulator containers share this exact suffix. A matching
    // basename under Downloads, tmp, or another arbitrary directory is external.
    let parts = file.pathComponents
    guard parts.count >= 8 else { return nil }
    let tail = Array(parts.suffix(8))
    guard tail[0] == "Containers", tail[1] == "Data", tail[2] == "Application",
          UUID(uuidString: tail[3]) != nil, tail[4] == "Library",
          tail[5] == "Application Support", tail[6] == mediaFolder else { return nil }
    let prefix = Array(parts.dropLast(8))
    let device = prefix == ["/", "var", "mobile"] || prefix == ["/", "private", "var", "mobile"]
    let simulatorTail = Array(prefix.suffix(6))
    let simulator = simulatorTail.count == 6 && simulatorTail[0] == "Library"
      && simulatorTail[1] == "Developer" && simulatorTail[2] == "CoreSimulator"
      && simulatorTail[3] == "Devices" && UUID(uuidString: simulatorTail[4]) != nil
      && simulatorTail[5] == "data"
    guard device || simulator else { return nil }
    return filename
  }

  private static func ownedURL(_ filename: String) throws -> URL {
    let directory = try mediaDirectory()
    let url = directory.appendingPathComponent(filename).standardizedFileURL
    // Never follow an unexpected symlink out of the owned directory.
    if FileManager.default.fileExists(atPath: url.path),
       url.resolvingSymlinksInPath().deletingLastPathComponent().path != directory.resolvingSymlinksInPath().path {
      throw failure("Comparison media reference escapes its media directory.")
    }
    return url
  }

  private static func failure(_ message: String) -> NSError {
    NSError(domain: "WoahComparisonFiles", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
}

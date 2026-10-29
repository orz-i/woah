import Foundation

/// Only local copies in WoahComparisonExports are managed here. External Photos
/// / MediaStore entries are never touched by comparison retention maintenance.
enum IOSComparisonExportFiles {
  static func needsReplacementConsent(state: String, savedIdentifier: String?,
    outputExists: Bool, confirmed: Bool) -> Bool {
    state == "completed" && savedIdentifier == nil && outputExists && !confirmed
  }

  /// Retain the current job, retire an explicitly replaced predecessor, and
  /// migrate legacy unindexed MP4s only after a conservative seven-day grace.
  @discardableResult
  static func prune(directory: URL, keeping id: String?, replacing oldId: String? = nil,
    now: Date = Date(), legacyAge: TimeInterval = 7 * 24 * 60 * 60) throws -> Int {
    let keys: Set<URLResourceKey> = [.isRegularFileKey, .isSymbolicLinkKey, .contentModificationDateKey]
    let entries = try FileManager.default.contentsOfDirectory(at: directory,
      includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles])
    var removed = 0
    for url in entries {
      let name = url.lastPathComponent
      let partial = name.hasSuffix(".partial.mp4")
      let suffix = partial ? ".partial.mp4" : ".mp4"
      guard name.hasSuffix(suffix) else { continue }
      let candidateId = String(name.dropLast(suffix.count))
      guard UUID(uuidString: candidateId) != nil,
            candidateId != id else { continue }
      let attrs = try url.resourceValues(forKeys: keys)
      guard attrs.isRegularFile == true, attrs.isSymbolicLink != true,
            let changed = attrs.contentModificationDate else { continue }
      let explicitlyReplaced = !partial && candidateId == oldId
      guard explicitlyReplaced || changed <= now.addingTimeInterval(-legacyAge) else { continue }
      try FileManager.default.removeItem(at: url)
      removed += 1
    }
    return removed
  }
}

import Foundation

struct IOSComparisonExportError: LocalizedError {
  let code: String
  let message: String
  var errorDescription: String? { message }

  static func invalid(_ message: String) -> Self {
    Self(code: "COMPARISON_EXPORT_INVALID", message: message)
  }
}

/// A persisted snapshot. Practice speed, loop state and subsequent project edits
/// cannot change a running export or its retry.
struct IOSComparisonExportSpec: Codable, Equatable {
  struct Picture: Codable, Equatable {
    var zoom: Double = 1
    var panX: Double = 0
    var panY: Double = 0

    func validate() throws {
      guard zoom.isFinite, (1...4).contains(zoom), panX.isFinite,
            panY.isFinite, (-1...1).contains(panX), (-1...1).contains(panY) else {
        throw IOSComparisonExportError.invalid("画面缩放或位置无效")
      }
    }
  }

  let projectId: String
  let teacherURI: String
  let learnerURI: String
  let offsetUs: Int64
  let timeScale: Double
  let startUs: Int64
  let endUs: Int64
  let width: Int
  let height: Int
  let gap: Int
  let sideBySide: Bool
  let mirrorTeacher: Bool
  let mirrorLearner: Bool
  let teacherPicture: Picture
  let learnerPicture: Picture

  var durationUs: Int64 { endUs - startUs }

  func validate() throws {
    guard Self.validIdentifier(projectId), !teacherURI.isEmpty, !learnerURI.isEmpty,
          teacherURI.utf8.count <= 16_384, learnerURI.utf8.count <= 16_384,
          (-172_800_000_000...172_800_000_000).contains(offsetUs),
          timeScale.isFinite, (0.5...2).contains(timeScale),
          startUs >= 0, endUs > startUs, endUs <= 86_400_000_000,
          (16...1920).contains(width), (16...1920).contains(height),
          width % 2 == 0, height % 2 == 0, (1...64).contains(gap),
          gap < min(width, height) else {
      throw IOSComparisonExportError.invalid("对比导出参数无效")
    }
    try teacherPicture.validate()
    try learnerPicture.validate()
  }

  static func validIdentifier(_ value: String) -> Bool {
    !value.isEmpty && value.utf8.count <= 80 && value.utf8.allSatisfy {
      (48...57).contains($0) || (65...90).contains($0) ||
        (97...122).contains($0) || $0 == 45 || $0 == 95
    }
  }

  static func fromProject(_ json: String, startUs: Int64, endUs: Int64,
    width: Int, height: Int, gap: Int, sideBySide: Bool) throws -> Self {
    guard json.utf8.count <= 2_000_000, let data = json.data(using: .utf8) else {
      throw IOSComparisonExportError.invalid("项目文件无效")
    }
    struct Source: Decodable { let uri: String }
    struct Project: Decodable {
      let schemaVersion: Int
      let id: String
      let teacher: Source
      let learner: Source
      let offsetUs: Int64
      let timeScale: Double?
      let mirrorTeacher: Bool?
      let mirrorLearner: Bool?
      let teacherPicture: Picture?
      let learnerPicture: Picture?
    }
    let project: Project
    do { project = try JSONDecoder().decode(Project.self, from: data) }
    catch { throw IOSComparisonExportError.invalid("项目文件无效") }
    guard project.schemaVersion == 1 else {
      throw IOSComparisonExportError.invalid("项目版本不受支持")
    }
    let spec = Self(projectId: project.id, teacherURI: project.teacher.uri,
      learnerURI: project.learner.uri, offsetUs: project.offsetUs,
      timeScale: project.timeScale ?? 1, startUs: startUs, endUs: endUs,
      width: width, height: height, gap: gap, sideBySide: sideBySide,
      mirrorTeacher: project.mirrorTeacher ?? false, mirrorLearner: project.mirrorLearner ?? false,
      teacherPicture: project.teacherPicture ?? Picture(),
      learnerPicture: project.learnerPicture ?? Picture())
    try spec.validate()
    return spec
  }
}

struct IOSComparisonExportRecord: Codable {
  let id: String
  let spec: IOSComparisonExportSpec
  var state = "queued"
  var stage = "准备导出"
  var progress: Double = 0
  var savedAssetIdentifier: String?
  var pendingAssetIdentifier: String?
  var photoSaveInFlight = false
  var error: String?

  var active: Bool { ["queued", "encoding", "cancelling", "publishing"].contains(state) }

  func validate() throws {
    guard IOSComparisonExportSpec.validIdentifier(id),
          ["queued", "encoding", "cancelling", "publishing", "completed", "failed",
           "cancelled", "interrupted"].contains(state),
          progress.isFinite, (0...1).contains(progress) else {
      throw IOSComparisonExportError.invalid("导出记录无效")
    }
    try spec.validate()
  }

  /// A same-directory rename is the local file's commit point. A crash between
  /// that rename and the metadata update must recover the finished movie.
  func recovered(outputExists: Bool) -> Self {
    var result = self
    if active {
      result.state = outputExists ? "completed" : "interrupted"
      result.stage = outputExists ? "导出完成" : "导出已中断"
      result.progress = outputExists ? 1 : progress
      result.error = outputExists ? nil : "上次导出已中断，可以重试"
    } else if state == "completed", !outputExists {
      result.state = "interrupted"
      result.stage = "导出文件不可用"
      result.error = "导出文件已丢失，可以重试"
      result.savedAssetIdentifier = nil
    }
    if result.photoSaveInFlight, result.savedAssetIdentifier == nil {
      // Add-only Photos access cannot inspect an interrupted transaction. Keep
      // its placeholder as an idempotency fence instead of creating a duplicate.
      result.error = "上次相册保存结果待确认；本地视频仍可播放"
    }
    return result
  }
}

/// Cancellation and publication share a gate, so a late cancel cannot remove a
/// successfully published movie or allow an early cancel to publish one.
final class IOSComparisonExportCancellation {
  enum Reason: Equatable { case user, backgroundExpired }
  private let lock = NSLock()
  private var reasonValue: Reason?
  private var committed = false

  var reason: Reason? {
    lock.lock(); defer { lock.unlock() }
    return reasonValue
  }

  @discardableResult
  func cancel(_ reason: Reason) -> Bool {
    lock.lock(); defer { lock.unlock() }
    guard !committed else { return false }
    if reasonValue == nil { reasonValue = reason }
    return true
  }

  func check() throws {
    if reason != nil { throw IOSComparisonExportError(code: "COMPARISON_EXPORT_CANCELLED", message: "导出已取消") }
  }

  func commit(_ body: () throws -> Void) throws {
    lock.lock(); defer { lock.unlock() }
    guard reasonValue == nil else {
      throw IOSComparisonExportError(code: "COMPARISON_EXPORT_CANCELLED", message: "导出已取消")
    }
    try body()
    committed = true
  }
}

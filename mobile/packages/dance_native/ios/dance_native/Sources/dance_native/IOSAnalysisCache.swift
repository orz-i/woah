import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

struct IOSCachedBBox: Codable {
  let left: Double
  let top: Double
  let right: Double
  let bottom: Double
}

struct IOSCachedPerson: Codable {
  let id: Int
  let bbox: IOSCachedBBox
  let confidence: Double
}

struct IOSAnalysisMetadata: Codable {
  let schemaVersion: Int
  let sourceUri: String
  let persons: [IOSCachedPerson]
}

final class IOSAnalysisCache {
  private let fileManager: FileManager
  private let rootDirectory: URL

  init(fileManager: FileManager = .default) {
    self.fileManager = fileManager
    let cacheRoot = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    rootDirectory = cacheRoot.appendingPathComponent("analysis", isDirectory: true)
  }

  func createAnalysisDirectory(cacheId: String) throws -> URL {
    let directory = try analysisDirectory(cacheId: cacheId)
    try fileManager.createDirectory(
      at: directory,
      withIntermediateDirectories: true,
      attributes: nil
    )
    return directory
  }

  func saveVideoUri(cacheId: String, videoUri: String) throws {
    let directory = try createAnalysisDirectory(cacheId: cacheId)
    try videoUri.write(
      to: directory.appendingPathComponent("source_uri.txt"),
      atomically: true,
      encoding: .utf8
    )
  }

  func loadVideoUri(cacheId: String) throws -> String? {
    let file = try analysisDirectory(cacheId: cacheId)
      .appendingPathComponent("source_uri.txt")
    guard fileManager.fileExists(atPath: file.path) else { return nil }
    return try String(contentsOf: file, encoding: .utf8)
      .trimmingCharacters(in: .whitespacesAndNewlines)
  }

  func saveMetadata(cacheId: String, metadata: IOSAnalysisMetadata) throws {
    let directory = try createAnalysisDirectory(cacheId: cacheId)
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    let data = try encoder.encode(metadata)
    try data.write(
      to: directory.appendingPathComponent("analysis.json"),
      options: .atomic
    )
  }

  func loadMetadata(cacheId: String) throws -> IOSAnalysisMetadata? {
    let file = try analysisDirectory(cacheId: cacheId)
      .appendingPathComponent("analysis.json")
    guard fileManager.fileExists(atPath: file.path) else { return nil }
    return try JSONDecoder().decode(
      IOSAnalysisMetadata.self,
      from: Data(contentsOf: file)
    )
  }

  func savePersonThumbnail(
    cacheId: String,
    personId: Int,
    frame: CGImage,
    x1: Float32,
    y1: Float32,
    x2: Float32,
    y2: Float32
  ) throws -> String {
    let frameWidth = frame.width
    let frameHeight = frame.height
    guard frameWidth > 1, frameHeight > 1 else { return "" }

    let bboxWidth = max(0, x2 - x1)
    let bboxHeight = max(0, y2 - y1)
    let cropX1 = clamp(
      Int(x1 - bboxWidth * 0.1),
      lower: 0,
      upper: max(0, frameWidth - 2)
    )
    let cropY1 = clamp(
      Int(y1 - bboxHeight * 0.1),
      lower: 0,
      upper: max(0, frameHeight - 2)
    )
    let cropX2 = clamp(
      Int(x2 + bboxWidth * 0.1),
      lower: cropX1 + 1,
      upper: frameWidth
    )
    let cropY2 = clamp(
      Int(y2 + bboxHeight * 0.1),
      lower: cropY1 + 1,
      upper: frameHeight
    )
    let cropWidth = max(1, cropX2 - cropX1)
    let cropHeight = max(1, cropY2 - cropY1)

    let thumbnailWidth = 160
    let thumbnailHeight = 240
    let bytesPerRow = thumbnailWidth * 4
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    guard let context = CGContext(
      data: nil,
      width: thumbnailWidth,
      height: thumbnailHeight,
      bitsPerComponent: 8,
      bytesPerRow: bytesPerRow,
      space: colorSpace,
      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else {
      throw cacheError("Could not allocate person thumbnail context.")
    }

    // Match the top-left/downward visual coordinate convention used by the
    // YOLO preprocessor. Android intentionally scales every expanded crop to
    // 160x240, so keep the same fixed thumbnail geometry here.
    context.translateBy(x: 0, y: CGFloat(thumbnailHeight))
    context.scaleBy(x: 1, y: -1)
    context.interpolationQuality = .high
    let scaleX = CGFloat(thumbnailWidth) / CGFloat(cropWidth)
    let scaleY = CGFloat(thumbnailHeight) / CGFloat(cropHeight)
    context.draw(
      frame,
      in: CGRect(
        x: -CGFloat(cropX1) * scaleX,
        y: -CGFloat(cropY1) * scaleY,
        width: CGFloat(frameWidth) * scaleX,
        height: CGFloat(frameHeight) * scaleY
      )
    )
    guard let thumbnail = context.makeImage() else {
      throw cacheError("Could not render person thumbnail.")
    }

    let directory = try createAnalysisDirectory(cacheId: cacheId)
    let file = directory.appendingPathComponent("person_\(personId).jpg")
    guard let destination = CGImageDestinationCreateWithURL(
      file as CFURL,
      UTType.jpeg.identifier as CFString,
      1,
      nil
    ) else {
      throw cacheError("Could not create JPEG thumbnail destination.")
    }
    let options = [
      kCGImageDestinationLossyCompressionQuality: 0.85,
    ] as CFDictionary
    CGImageDestinationAddImage(destination, thumbnail, options)
    guard CGImageDestinationFinalize(destination) else {
      throw cacheError("Could not finalize JPEG person thumbnail.")
    }
    return file.path
  }

  func clearAnalysisCache(cacheId: String) throws {
    let directory = try analysisDirectory(cacheId: cacheId)
    guard fileManager.fileExists(atPath: directory.path) else { return }
    try fileManager.removeItem(at: directory)
  }

  private func analysisDirectory(cacheId: String) throws -> URL {
    guard !cacheId.isEmpty,
          cacheId != ".",
          cacheId != "..",
          !cacheId.contains("/"),
          !cacheId.contains("\\") else {
      throw PigeonError(
        code: "INVALID_CACHE_ID",
        message: "Invalid iOS analysis cache id.",
        details: cacheId
      )
    }
    return rootDirectory.appendingPathComponent(cacheId, isDirectory: true)
  }

  private func clamp(_ value: Int, lower: Int, upper: Int) -> Int {
    if upper < lower { return lower }
    return max(lower, min(upper, value))
  }

  private func cacheError(_ message: String) -> PigeonError {
    PigeonError(code: "ANALYSIS_CACHE_FAILED", message: message, details: nil)
  }
}

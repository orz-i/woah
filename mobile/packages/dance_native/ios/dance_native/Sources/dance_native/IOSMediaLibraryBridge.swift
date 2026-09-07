import AVFoundation
import AVKit
import Foundation
import Photos
import UIKit

final class IOSMediaLibraryBridge {
  private static let thumbnailMaxDimension = 240.0

  func saveVideoToGallery(filePath: String) async throws -> String {
    let sourceURL = try localFileURL(from: filePath)
    let status = await photoAddAuthorizationStatus()
    guard status == .authorized || status == .limited else {
      throw PigeonError(
        code: "PHOTO_LIBRARY_PERMISSION_DENIED",
        message: "Photo Library add-only permission was not granted.",
        details: nil
      )
    }

    try await withCheckedThrowingContinuation {
      (continuation: CheckedContinuation<Void, Error>) in
      PHPhotoLibrary.shared().performChanges({
        PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: sourceURL)
      }, completionHandler: { success, error in
        if success {
          continuation.resume(returning: ())
        } else {
          continuation.resume(throwing: PigeonError(
            code: "SAVE_VIDEO_FAILED",
            message: error?.localizedDescription ?? "Failed to save video to Photos.",
            details: nil
          ))
        }
      })
    }

    // Add-only Photos access intentionally avoids requesting broad read access.
    // Return the app-owned source URI so sharing/opening can work without
    // reading the newly-created PHAsset back from the user's photo library.
    return sourceURL.absoluteString
  }

  func createTrimThumbnails(
    videoUri: String,
    timestampsMs: [Int64]
  ) async throws -> [String] {
    let sourceURL = IOSVideoProbe.assetURL(from: videoUri)
    let securityScoped = sourceURL.isFileURL && sourceURL.startAccessingSecurityScopedResource()
    defer {
      if securityScoped {
        sourceURL.stopAccessingSecurityScopedResource()
      }
    }

    let asset = AVURLAsset(url: sourceURL)
    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.maximumSize = CGSize(
      width: Self.thumbnailMaxDimension,
      height: Self.thumbnailMaxDimension
    )
    generator.requestedTimeToleranceBefore = CMTime(value: 1, timescale: 15)
    generator.requestedTimeToleranceAfter = CMTime(value: 1, timescale: 15)

    let fileManager = FileManager.default
    let cacheRoot = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? fileManager.temporaryDirectory
    let directory = cacheRoot.appendingPathComponent("trim_thumbnails", isDirectory: true)
    try fileManager.createDirectory(
      at: directory,
      withIntermediateDirectories: true,
      attributes: nil
    )

    let token = UUID().uuidString
    var paths: [String] = []
    paths.reserveCapacity(timestampsMs.count)

    for (index, timestampMs) in timestampsMs.enumerated() {
      do {
        let time = CMTime(value: max(0, timestampMs), timescale: 1000)
        let generated = try await generator.image(at: time)
        let image = UIImage(cgImage: generated.image)
        guard let jpeg = image.jpegData(compressionQuality: 0.72) else {
          continue
        }
        let outputURL = directory.appendingPathComponent(
          "trim_\(token)_\(index).jpg",
          isDirectory: false
        )
        try jpeg.write(to: outputURL, options: .atomic)
        paths.append(outputURL.path)
      } catch {
        // Match the Android thumbnail bridge: a single unavailable timestamp
        // should not fail the entire timeline request.
        continue
      }
    }
    return paths
  }

  func shareVideo(publicUri: String) async throws {
    let sourceURL = try localFileURL(from: publicUri)
    try await MainActor.run {
      guard let presenter = Self.topViewController() else {
        throw PigeonError(
          code: "PRESENTATION_UNAVAILABLE",
          message: "No active view controller is available for sharing.",
          details: nil
        )
      }
      let controller = UIActivityViewController(
        activityItems: [sourceURL],
        applicationActivities: nil
      )
      if let popover = controller.popoverPresentationController {
        popover.sourceView = presenter.view
        popover.sourceRect = CGRect(
          x: presenter.view.bounds.midX,
          y: presenter.view.bounds.midY,
          width: 1,
          height: 1
        )
        popover.permittedArrowDirections = []
      }
      presenter.present(controller, animated: true)
    }
  }

  func openVideo(publicUri: String) async throws {
    let sourceURL = try localFileURL(from: publicUri)
    try await MainActor.run {
      guard let presenter = Self.topViewController() else {
        throw PigeonError(
          code: "PRESENTATION_UNAVAILABLE",
          message: "No active view controller is available for video playback.",
          details: nil
        )
      }
      let controller = AVPlayerViewController()
      controller.player = AVPlayer(url: sourceURL)
      presenter.present(controller, animated: true) {
        controller.player?.play()
      }
    }
  }

  private func photoAddAuthorizationStatus() async -> PHAuthorizationStatus {
    let current = PHPhotoLibrary.authorizationStatus(for: .addOnly)
    if current != .notDetermined {
      return current
    }
    return await withCheckedContinuation { continuation in
      PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
        continuation.resume(returning: status)
      }
    }
  }

  private func localFileURL(from rawValue: String) throws -> URL {
    let url: URL
    if rawValue.hasPrefix("file://"), let parsed = URL(string: rawValue) {
      url = parsed
    } else {
      url = URL(fileURLWithPath: rawValue)
    }
    guard url.isFileURL, FileManager.default.fileExists(atPath: url.path) else {
      throw PigeonError(
        code: "VIDEO_FILE_NOT_FOUND",
        message: "Video file does not exist: \(rawValue)",
        details: nil
      )
    }
    return url
  }

  @MainActor
  private static func topViewController() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
    let foregroundScenes = scenes.filter {
      $0.activationState == .foregroundActive || $0.activationState == .foregroundInactive
    }
    let candidateScenes = foregroundScenes.isEmpty ? scenes : foregroundScenes
    let windows = candidateScenes.flatMap { $0.windows }
    let window = windows.first(where: { $0.isKeyWindow }) ?? windows.first
    return topViewController(from: window?.rootViewController)
  }

  @MainActor
  private static func topViewController(from base: UIViewController?) -> UIViewController? {
    if let presented = base?.presentedViewController {
      return topViewController(from: presented)
    }
    if let navigation = base as? UINavigationController {
      return topViewController(from: navigation.visibleViewController)
    }
    if let tab = base as? UITabBarController,
       let selected = tab.selectedViewController {
      return topViewController(from: selected)
    }
    return base
  }
}

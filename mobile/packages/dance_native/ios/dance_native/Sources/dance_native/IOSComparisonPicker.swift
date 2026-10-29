import AVFoundation
import CoreMedia
import Foundation
import PhotosUI
import UIKit
import UniformTypeIdentifiers

@MainActor
final class IOSComparisonPicker: NSObject, PHPickerViewControllerDelegate, UIAdaptivePresentationControllerDelegate {
  private var continuation: CheckedContinuation<ComparisonSourceDto?, Error>?
  private var requestID: UUID?
  private var picker: PHPickerViewController?
  private var progress: Progress?
  private var metadataTask: Task<Void, Never>?
  private var importedURL: URL?

  func choose(from presenter: UIViewController) async throws -> ComparisonSourceDto? {
    guard continuation == nil, presenter.presentedViewController == nil,
          presenter.viewIfLoaded?.window != nil, !presenter.isBeingDismissed else {
      throw failure("无法在当前界面打开视频选择器。")
    }
    let id = UUID()
    requestID = id
    return try await withTaskCancellationHandler(operation: {
      try await withCheckedThrowingContinuation { continuation in
        self.continuation = continuation
        if Task.isCancelled {
          finish(.success(nil), id: id)
          return
        }
        // The out-of-process picker only exposes the selected video, without
        // requesting broad PHPhotoLibrary read authorization.
        var configuration = PHPickerConfiguration()
        configuration.filter = .videos
        configuration.selectionLimit = 1
        configuration.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = self
        self.picker = picker
        presenter.present(picker, animated: true)
        picker.presentationController?.delegate = self
      }
    }, onCancel: { [weak self] in
      Task { @MainActor in
        guard let self, self.requestID == id else { return }
        self.cancel()
      }
    })
  }

  func cancel() {
    guard let id = requestID else { return }
    finish(.success(nil), id: id)
  }

  func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
    guard let picker, presentationController.presentedViewController === picker,
          let id = requestID else { return }
    finish(.success(nil), id: id)
  }

  func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
    guard self.picker === picker, let id = requestID else { return }
    picker.dismiss(animated: true)
    self.picker = nil
    guard let provider = results.first?.itemProvider else {
      finish(.success(nil), id: id)
      return
    }
    let type = provider.registeredTypeIdentifiers.first {
      UTType($0)?.conforms(to: .movie) == true
    } ?? UTType.movie.identifier
    guard provider.hasItemConformingToTypeIdentifier(type) else {
      finish(.failure(failure("所选项目不是可读取的视频。")), id: id)
      return
    }
    let suggestedName = provider.suggestedName
    progress = provider.loadFileRepresentation(forTypeIdentifier: type) { [weak self] temporary, error in
      // NSItemProvider owns this temporary URL. Copy synchronously BEFORE its
      // completion handler returns; dispatching the copy to MainActor is too late.
      let copied: Result<URL, Error>
      if let error { copied = .failure(error) }
      else if let temporary {
        var destination: URL?
        do {
          let suffix = temporary.pathExtension.isEmpty
            ? (UTType(type)?.preferredFilenameExtension ?? "mov") : temporary.pathExtension
          let url = try IOSComparisonFiles.newMediaURL(extension: suffix)
          destination = url
          try FileManager.default.copyItem(at: temporary, to: url)
          // Providers may preserve the source's years-old modification time.
          // Reset the managed copy's age so the 24h orphan grace starts at import.
          try FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
          copied = .success(url)
        } catch {
          if let destination { try? FileManager.default.removeItem(at: destination) }
          copied = .failure(error)
        }
      } else {
        copied = .failure(NSError(domain: "WoahComparisonPicker", code: 1,
          userInfo: [NSLocalizedDescriptionKey: "视频提供方没有返回文件。 "]))
      }
      Task { @MainActor in
        guard let self, self.requestID == id else {
          if case .success(let url) = copied { try? FileManager.default.removeItem(at: url) }
          return
        }
        self.progress = nil
        switch copied {
        case .failure(let error): self.finish(.failure(error), id: id)
        case .success(let url):
          self.importedURL = url
          self.metadataTask = Task { [weak self] in
            guard let self else { return }
            do {
              let asset = AVURLAsset(url: url)
              guard let video = try await asset.loadTracks(withMediaType: .video).first else {
                throw self.failure("所选文件没有视频轨道。")
              }
              let assetDuration = try await asset.load(.duration)
              let videoRange = try await video.load(.timeRange)
              let end = CMTimeMinimum(assetDuration, CMTimeRangeGetEnd(videoRange))
              let seconds = CMTimeGetSeconds(end)
              guard assetDuration.isNumeric, videoRange.isValid, videoRange.duration.isNumeric,
                    CMTimeCompare(videoRange.duration, .zero) > 0,
                    end.isNumeric, CMTimeCompare(end, videoRange.start) > 0,
                    seconds.isFinite, seconds > 0, seconds <= 86_400 else {
                throw self.failure("所选视频没有有效时长，或超过24小时。")
              }
              let audio = try await asset.loadTracks(withMediaType: .audio)
              guard self.requestID == id, !Task.isCancelled else { return }
              let name = suggestedName.flatMap { $0.isEmpty ? nil : $0 } ?? "舞蹈视频.\(url.pathExtension)"
              let source = ComparisonSourceDto(uri: url.absoluteString, name: name,
                durationUs: Int64(floor(seconds * 1_000_000)), hasAudio: !audio.isEmpty)
              self.finish(.success(source), id: id, keepImported: true)
            } catch {
              guard self.requestID == id else { return }
              self.finish(.failure(error), id: id)
            }
          }
        }
      }
    }
  }

  private func finish(_ result: Result<ComparisonSourceDto?, Error>, id: UUID, keepImported: Bool = false) {
    guard requestID == id, let continuation else { return }
    requestID = nil
    self.continuation = nil
    progress?.cancel()
    progress = nil
    metadataTask?.cancel()
    metadataTask = nil
    picker?.dismiss(animated: true)
    picker = nil
    if !keepImported, let importedURL { try? FileManager.default.removeItem(at: importedURL) }
    importedURL = nil
    continuation.resume(with: result)
  }

  private func failure(_ message: String) -> NSError {
    NSError(domain: "WoahComparisonPicker", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
}

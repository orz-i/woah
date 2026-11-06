#if DEBUG && targetEnvironment(simulator)
import Foundation
import simd

/// Read-only source-to-presentation geometry, explicitly opted into by QA.
/// No pixel data, biometric input or production identity state is persisted.
final class IOSPixelAuditCropRecorder {
  let outputURL: URL
  private let handle: FileHandle
  private var frameIndex = 0

  init?(sourceName: String, outputName: String, outputWidth: Int, outputHeight: Int) {
    guard ProcessInfo.processInfo.environment["WOAH_PIXEL_CROP_AUDIT"] == "1" else { return nil }
    guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
    let url = dir.appendingPathComponent("qa_pixel_crops_\(UUID().uuidString).jsonl")
    guard FileManager.default.createFile(atPath: url.path, contents: nil),
          let file = try? FileHandle(forWritingTo: url) else { return nil }
    outputURL = url
    handle = file
    append([
      "kind": "header", "schema": "woah-ios-pixel-crop-v1",
      "source_basename": sourceName, "output_basename": outputName, "output_width": outputWidth,
      "output_height": outputHeight,
    ])
  }

  deinit { handle.closeFile() }

  private func append(_ row: [String: Any]) {
    guard JSONSerialization.isValidJSONObject(row),
          let data = try? JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]) else {
      return
    }
    handle.write(data)
    handle.write(Data([10]))
  }

  func record(timestampUs: Int64, crop: SIMD4<Float>,
              faceRegions: [Int: IOSFacePrivacyEllipse] = [:],
              persons: [IOSPreviewPerson] = [], unmaskedPersonId: Int? = nil) {
    var row: [String: Any] = [
      "kind": "crop", "frame_index": frameIndex, "t_us": timestampUs,
      "source_crop_normalized": [Double(crop.x), Double(crop.y), Double(crop.z), Double(crop.w)],
      "face_regions": faceRegions.keys.sorted().compactMap { id -> [String: Any]? in
        guard let face = faceRegions[id] else { return nil }
        return ["id": id, "center": [face.centerX, face.centerY],
                "radii": [face.radiusX, face.radiusY], "source": face.source.rawValue]
      },
      "observations": persons.map { person -> [String: Any] in
        let d = person.detection
        return ["id": person.id, "bbox": [d.x1, d.y1, d.x2, d.y2],
                "predicted": person.conservativePrivacyFallback]
      },
    ]
    if let unmaskedPersonId { row["unmasked_person_id"] = unmaskedPersonId }
    append(row)
    frameIndex += 1
  }
}
#endif

#if DEBUG && targetEnvironment(simulator)
import Foundation

/// Explicitly opt-in, Simulator-only export evidence recorder.
/// Captures canonical source-coordinate YOLO observations and privacy tracker
/// decisions; it never changes matching, fallback, or failure behavior.
/// Full 160x160 mask bytes are retained only in local Documents JSONL for an
/// exact offline replay, not exposed through production APIs or console logs.
final class IOSPrivacyIdentityFrameRecorder {
  let outputURL: URL
  private let file: FileHandle
  private(set) var recordedFrames = 0
  private(set) var recordingErrors = 0

  init?(sourceURL: URL, metadata: IOSAnalysisMetadata?, fullBodyIds: Set<Int>,
        faceOnlyIds: Set<Int>, followId: Int?, frameWidth: Int, frameHeight: Int, pipeline: String = "export") {
    guard ProcessInfo.processInfo.environment["WOAH_PRIVACY_TRACE"] == "1" else { return nil }
    guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
    let url = docs.appendingPathComponent("qa_privacy_identity_\(UUID().uuidString).jsonl")
    guard FileManager.default.createFile(atPath: url.path, contents: nil),
          let handle = try? FileHandle(forWritingTo: url) else { return nil }
    file = handle
    outputURL = url
    emit([
      "kind": "header", "schema": "woah-ios-identity-raw-v1", "pipeline": pipeline,
      "source_basename": sourceURL.lastPathComponent,
      "source_width": frameWidth, "source_height": frameHeight,
      "selected_full_body_ids": fullBodyIds.sorted(),
      "selected_face_only_ids": faceOnlyIds.sorted(),
      "follow_root_id": followId as Any? ?? NSNull(),
      "analysis_roots": (metadata?.persons ?? []).map { person -> [String: Any] in
        ["id": person.id, "confidence": person.confidence,
         "bbox_normalized": [person.bbox.left, person.bbox.top, person.bbox.right, person.bbox.bottom]]
      }
    ])
  }

  deinit { file.closeFile() }

  private func emit(_ value: [String: Any]) {
    guard JSONSerialization.isValidJSONObject(value),
          let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) else {
      recordingErrors += 1
      return
    }
    file.write(data)
    file.write(Data([0x0a]))
  }

  func recordInput(_ inference: IOSYoloInferenceResult, timestampUs: Int64) {
    let preprocess = inference.preprocess
    emit([
      "kind": "frame", "t_us": timestampUs,
      "frame_index": recordedFrames,
      "backend": inference.runtime.effectiveBackend.rawValue,
      "preprocess": ["scale": Double(preprocess.scale),
                     "pad_left": Double(preprocess.padLeft),
                     "pad_top": Double(preprocess.padTop),
                     "source_width": preprocess.sourceWidth,
                     "source_height": preprocess.sourceHeight,
                     "input_size": preprocess.inputSize],
      "detections": inference.detections.map { d -> [String: Any] in
        ["bbox": [Double(d.x1), Double(d.y1), Double(d.x2), Double(d.y2)],
         "confidence": Double(d.confidence),
         "mask_base64": Data(d.mask).base64EncodedString()]
      }
    ])
    recordedFrames += 1
  }

  func recordSharedSnapshot(_ snapshot: IOSSharedTrackingSnapshot, timestampUs: Int64, action: UInt32,
      assigned: [Int], protected: Set<Int>, body: Set<Int>, face: Set<Int>) {
    emit(["kind":"shared_snapshot", "t_us":timestampUs,"action":action,"assigned_ids":assigned,
      "protected_ids":protected.sorted(),"body_ids":body.sorted(),"face_ids":face.sorted(),
      "tracks":snapshot.regions.map {r -> [String:Any] in
        ["id":r.id,"state":r.state,"observed":r.observed,"missed":r.missedFrames,
         "bbox":[r.bounds.x,r.bounds.y,r.bounds.z,r.bounds.w],"anchor":[r.anchor.x,r.anchor.y,r.anchor.z,r.anchor.w]]
      }])
  }

  func recordTracking(tracker: IOSTemporalIdentityTracker, timestampUs: Int64,
                      classEvidence: [IOSFreshPrivacyClassEvidence]) {
    emit([
      "kind": "tracking", "t_us": timestampUs,
      "tracks": tracker.paritySnapshots().map { t -> [String: Any] in
        ["id": t.id, "state": t.state.rawValue, "missed": t.missedFrames,
         "observed": t.observedThisFrame, "protected": t.identityProtected,
         "selected": t.privacySelected,
         "bbox_predicted": [Double(t.predictedX1),Double(t.predictedY1),
                            Double(t.predictedX2),Double(t.predictedY2)]]
      },
      "classes": classEvidence.map { e -> [String: Any] in
        ["detection_index": e.detectionIndex,
         "selection_class": e.selectionClass.rawValue,
         "conservative_unknown": e.conservativeUnknown]
      }
    ])
  }

  func recordFailure(timestampUs: Int64, error: Error, tracker: IOSTemporalIdentityTracker) {
    let code = (error as? PigeonError)?.code ?? "EXPORT_ERROR"
    emit(["kind":"failure", "t_us":timestampUs, "error_code":code,
          "tracks":tracker.paritySnapshots().map { ["id":$0.id,"missed":$0.missedFrames,
                                                     "state":$0.state.rawValue] }])
  }
}
#endif

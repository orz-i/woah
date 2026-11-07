import AVFoundation
import CoreGraphics
import Foundation

/// Native decoding/inference transport for FrameKernel's anchored replay plan.
final class IOSPreviewIdentityReplay {
  private let lock = NSRecursiveLock()
  private var key: String?
  private var cacheId: String?
  private var lastUs: Int64?
  private var session: IOSSharedTrackingSession?
  private var persons: [IOSPreviewPerson] = []
#if DEBUG && targetEnvironment(simulator)
  private var recorder: IOSPrivacyIdentityFrameRecorder?
#endif

  func clear(cacheId: String? = nil) {
    lock.lock(); defer { lock.unlock() }
    if cacheId == nil || self.cacheId == cacheId {
      session = nil; key = nil; self.cacheId = nil; lastUs = nil; persons = []
#if DEBUG && targetEnvironment(simulator)
      recorder = nil
#endif
    }
  }
  func resolve(cacheId: String, key: String, metadata: IOSAnalysisMetadata?, scope: UInt32,
      hero: Int?, body: Set<Int>, face: Set<Int>, timestampUs: Int64, stepUs: Int64,
      image: CGImage, inference: IOSYoloInferenceResult, asset: AVAsset, runner: IOSYoloRunner) throws -> [IOSPreviewPerson] {
    lock.lock(); defer { lock.unlock() }
    let plan = try IOSSharedTrackingSession.replayPlan(scope: scope, hero: hero != nil,
      namedTargets: !body.isEmpty || !face.isEmpty, anchorUs: metadata?.anchorTimestampUs,
      lastUs: self.key == key ? lastUs : nil, targetUs: timestampUs, stepUs: stepUs)
    precondition(plan.required)
    do {
      let required = body.union(face).union(hero.map { [$0] } ?? [])
      if self.key != key || plan.reset || session == nil {
        clear(); self.key = key; self.cacheId = cacheId
        let created = try IOSSharedTrackingSession()
        let credible = face.isEmpty ? Set<Int>() : Set((metadata?.persons ?? []).filter { $0.confidence >= 0.60 }.map(\.id))
        try created.configure(protected: required.union(credible), body: body, face: face.subtracting(body),
          dormancy: !body.isEmpty && !face.isEmpty)
        session = created
#if DEBUG && targetEnvironment(simulator)
        if let source=(asset as? AVURLAsset)?.url {
          recorder=IOSPrivacyIdentityFrameRecorder(sourceURL:source,metadata:metadata,fullBodyIds:body,
            faceOnlyIds:face,followId:hero,frameWidth:image.width,frameHeight:image.height,pipeline:"preview")
        }
#endif
      }
      guard plan.count > 0, let session else { return persons }
      let generator = AVAssetImageGenerator(asset: asset)
      generator.appliesPreferredTrackTransform = true
      generator.requestedTimeToleranceBefore = .zero
      generator.requestedTimeToleranceAfter = .zero
      for index in 0..<plan.count {
        try Task.checkCancellation()
        let at = plan.time(index)
        let result: (IOSYoloInferenceResult, Int, Int) = try autoreleasepool {
          if at == timestampUs { return (inference, image.width, image.height) }
          let frame = try generator.copyCGImage(at: CMTime(value: at, timescale: 1_000_000), actualTime: nil)
          return (try runner.run(image: frame, preferredBackend: .xnnpack), frame.width, frame.height)
        }
        let current = result.0
        let observations = current.detections.map { detection in
          IOSSharedTrackingObservation(bounds: SIMD4(detection.x1, detection.y1, detection.x2, detection.y2),
            confidence: detection.confidence, footY: detection.y2,
            mask: detection.mask.isEmpty ? nil : detection.mask,
            maskWidth: detection.mask.isEmpty ? 0 : IOSYoloPostprocessor.protoSize,
            maskHeight: detection.mask.isEmpty ? 0 : IOSYoloPostprocessor.protoSize,
            transform: IOSFrameKernel.transform(current.preprocess))
        }
        let assigned = lastUs == nil ? try IOSPreviewIdentityMatcher.assign(detections: current.detections,
          metadata: metadata, frameWidth: result.1, frameHeight: result.2).map(\.id) : []
#if DEBUG && targetEnvironment(simulator)
        recorder?.recordInput(current,timestampUs:at)
#endif
        let snapshot = try session.step(action: lastUs == nil ? 0 : 1, timestampUs: at,
          observations: observations, assignedIds: assigned)
#if DEBUG && targetEnvironment(simulator)
        let credible=face.isEmpty ? Set<Int>():Set((metadata?.persons ?? []).filter{$0.confidence>=0.60}.map(\.id))
        recorder?.recordSharedSnapshot(snapshot,timestampUs:at,action:lastUs == nil ? 0:1,assigned:assigned,
          protected:required.union(credible),body:body,face:face.subtracting(body))
#endif
        if lastUs == nil {
          guard required.isSubset(of: Set(snapshot.regions.map(\.id))) else {
            throw PigeonError(code: "PREVIEW_PRIVACY_UNRESOLVED",
              message: "Selected identity is missing at its observed analysis anchor.", details: nil)
          }
          try session.reserveNextIdentity(Int64((metadata?.persons.map(\.id).max() ?? -1) + 1))
        }
        persons = snapshot.regions.filter { $0.observed || required.contains($0.id) }.map { region in
          let detection = IOSYoloDetection(x1: region.bounds.x, y1: region.bounds.y,
            x2: region.bounds.z, y2: region.bounds.w, confidence: region.confidence,
            mask: region.mask ?? [UInt8](repeating: 0, count: IOSYoloPostprocessor.protoSize * IOSYoloPostprocessor.protoSize))
          return IOSPreviewPerson(id: region.id, detection: detection, conservativePrivacyFallback: !region.observed)
        }
        lastUs = at
      }
      return persons
    } catch { clear(); throw error }
  }
}

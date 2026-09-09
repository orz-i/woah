import CoreGraphics
import Foundation

private final class IOSPhase5SequenceFaceLocator: IOSFaceLocating {
  private let batches: [[IOSFaceCandidate]]
  private var index = 0

  init(batches: [[IOSFaceCandidate]]) {
    self.batches = batches
  }

  func locateFaces(in image: CGImage) throws -> [IOSFaceCandidate] {
    guard !batches.isEmpty else { return [] }
    let current = batches[min(index, batches.count - 1)]
    index += 1
    return current
  }
}

enum IOSFacePrivacyPhase5Smoke {
  static func run() throws -> [String: Any] {
    let width = 64
    let height = 64
    let source = try makeSolidImage(width: width, height: height, rgba: (0, 0, 255, 255))
    let visionSource = try makeSolidImage(width: 256, height: 256, rgba: (0, 0, 255, 255))
    let preprocess = IOSYoloPreprocessResult(
      input: [],
      scale: Float32(IOSYoloPreprocessor.inputSize) / Float32(width),
      padLeft: 0,
      padTop: 0,
      sourceWidth: width,
      sourceHeight: height,
      inputSize: IOSYoloPreprocessor.inputSize
    )
    let visionRuntimeFaces: [IOSFaceCandidate]
    do {
      visionRuntimeFaces = try IOSVisionFaceLocator().locateFaces(in: visionSource)
    } catch {
      throw smokeFailure("Vision FACE_ONLY runtime probe failed: \(error)")
    }
    let personDetection = makeDetection(x1: 8, y1: 4, x2: 56, y2: 60)
    let person = IOSPreviewPerson(id: 0, detection: personDetection)
    let trustedFace = IOSFaceCandidate(
      x1: 27,
      y1: 8,
      x2: 37,
      y2: 20,
      confidence: 0.99
    )

    let resolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[trustedFace], []])
    )
    let detected = resolver.resolve(
      image: source,
      persons: [person],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 0
    )
    guard let detectedRegion = detected[0], detectedRegion.source == .detectedFace else {
      throw smokeFailure("A clear face candidate was not accepted for the YOLO-owned selected identity.")
    }
    try assertNear(detectedRegion.centerX, 32, tolerance: 0.01, label: "detected centerX")
    try assertNear(detectedRegion.centerY, 13.52, tolerance: 0.05, label: "detected centerY")
    try assertNear(detectedRegion.radiusX, 6.6, tolerance: 0.05, label: "detected radiusX")
    try assertNear(detectedRegion.radiusY, 8.88, tolerance: 0.05, label: "detected radiusY")

    let missed = resolver.resolve(
      image: source,
      persons: [person],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 33_333
    )
    guard let missedRegion = missed[0], missedRegion.source == .predictedFace else {
      throw smokeFailure("A short detector miss did not use the trusted FACE_ONLY prediction lease.")
    }
    guard missedRegion.radiusX > 0, missedRegion.radiusY > 0 else {
      throw smokeFailure("Detector-miss prediction produced an empty privacy region.")
    }

    let movedPerson = IOSPreviewPerson(
      id: 0,
      detection: makeDetection(x1: 12, y1: 4, x2: 60, y2: 60)
    )
    let translatedPrediction = resolver.resolve(
      image: source,
      persons: [movedPerson],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 66_666
    )
    guard let translatedRegion = translatedPrediction[0], translatedRegion.source == .predictedFace else {
      throw smokeFailure("Trusted FACE_ONLY prediction did not survive a short translated detector miss.")
    }
    try assertNear(
      translatedRegion.centerX,
      36,
      tolerance: 0.05,
      label: "translated predicted centerX"
    )

    let expired = resolver.resolve(
      image: source,
      persons: [movedPerson],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 200_000
    )
    guard let expiredRegion = expired[0], expiredRegion.source == .yoloHeadFallback else {
      throw smokeFailure("Stale FACE_ONLY trusted geometry remained renderable beyond the 150ms lease.")
    }
    try assertNear(
      expiredRegion.centerX,
      36,
      tolerance: 0.05,
      label: "expired generic fallback centerX"
    )

    let maskGuidedResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[trustedFace], [], []])
    )
    _ = maskGuidedResolver.resolve(
      image: source,
      persons: [person],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 0
    )
    let headShiftMask = makeProtoMask(
      sourceRects: [
        (35, 8, 43, 20),
        (20, 20, 48, 60),
      ],
      preprocess: preprocess
    )
    let maskGuidedPerson = IOSPreviewPerson(
      id: 0,
      detection: makeDetection(
        x1: 8,
        y1: 4,
        x2: 56,
        y2: 60,
        mask: headShiftMask
      )
    )
    let maskGuided = maskGuidedResolver.resolve(
      image: source,
      persons: [maskGuidedPerson],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 200_000
    )
    guard let maskGuidedRegion = maskGuided[0],
          maskGuidedRegion.source == .yoloHeadFallback,
          maskGuidedRegion.centerX > 34 else {
      throw smokeFailure("Expired trusted face did not move toward current head-like YOLO mask support.")
    }
    let maskSeedExpired = maskGuidedResolver.resolve(
      image: source,
      persons: [maskGuidedPerson],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 900_000
    )
    guard let maskSeedExpiredRegion = maskSeedExpired[0],
          maskSeedExpiredRegion.source == .yoloHeadFallback else {
      throw smokeFailure("Expired FACE_ONLY mask seed did not return to generic YOLO head fallback.")
    }
    try assertNear(
      maskSeedExpiredRegion.centerX,
      32,
      tolerance: 0.05,
      label: "800ms mask-seed expiry centerX"
    )

    let unsupportedMaskResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[trustedFace], []])
    )
    _ = unsupportedMaskResolver.resolve(
      image: source,
      persons: [person],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 0
    )
    let emptyMaskPerson = IOSPreviewPerson(
      id: 0,
      detection: makeDetection(
        x1: 12,
        y1: 4,
        x2: 60,
        y2: 60,
        mask: [UInt8](
          repeating: 0,
          count: IOSYoloPostprocessor.protoSize * IOSYoloPostprocessor.protoSize
        )
      )
    )
    let unsupportedMask = unsupportedMaskResolver.resolve(
      image: source,
      persons: [emptyMaskPerson],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 200_000
    )
    guard let unsupportedMaskRegion = unsupportedMask[0],
          unsupportedMaskRegion.source == .yoloHeadFallback else {
      throw smokeFailure("Missing current head-like mask support removed FACE_ONLY fallback privacy.")
    }
    try assertNear(
      unsupportedMaskRegion.centerX,
      36,
      tolerance: 0.05,
      label: "unsupported-mask generic fallback centerX"
    )

    let ambiguousResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[
        IOSFaceCandidate(x1: 20, y1: 8, x2: 30, y2: 20, confidence: 0.99),
        IOSFaceCandidate(x1: 34, y1: 8, x2: 44, y2: 20, confidence: 0.99),
      ]])
    )
    let ambiguous = ambiguousResolver.resolve(
      image: source,
      persons: [person],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 0
    )
    guard ambiguous[0]?.source == .yoloHeadFallback else {
      throw smokeFailure("Near-tie face candidates must defer localization and use the privacy fallback.")
    }

    let neighborResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[trustedFace]])
    )
    let selectedOverlap = IOSPreviewPerson(
      id: 0,
      detection: makeDetection(x1: 4, y1: 4, x2: 44, y2: 60)
    )
    let unselectedOverlap = IOSPreviewPerson(
      id: 1,
      detection: makeDetection(x1: 20, y1: 4, x2: 60, y2: 60)
    )
    let neighborCompetition = neighborResolver.resolve(
      image: source,
      persons: [selectedOverlap, unselectedOverlap],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 0
    )
    guard neighborCompetition[0]?.source == .yoloHeadFallback else {
      throw smokeFailure("An unselected observed neighbor must participate in face ownership ambiguity.")
    }

    let predictedResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[trustedFace]])
    )
    let predictedPerson = IOSPreviewPerson(
      id: 0,
      detection: personDetection,
      conservativePrivacyFallback: true
    )
    let predicted = predictedResolver.resolve(
      image: source,
      persons: [predictedPerson],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 0
    )
    guard predicted[0]?.source == .yoloHeadFallback else {
      throw smokeFailure("An unobserved predicted body must not consume fresh face evidence as identity evidence.")
    }

    let cachedPredictedResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[trustedFace], []])
    )
    _ = cachedPredictedResolver.resolve(
      image: source,
      persons: [person],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 0
    )
    let cachedPredictedBody = IOSPreviewPerson(
      id: 0,
      detection: makeDetection(x1: 12, y1: 4, x2: 60, y2: 60),
      conservativePrivacyFallback: true
    )
    let cachedPredicted = cachedPredictedResolver.resolve(
      image: source,
      persons: [cachedPredictedBody],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 33_333
    )
    guard let cachedPredictedRegion = cachedPredicted[0],
          cachedPredictedRegion.source == .predictedFace else {
      throw smokeFailure("A brief YOLO observation gap did not preserve the trusted FACE_ONLY prediction lease.")
    }
    try assertNear(
      cachedPredictedRegion.centerX,
      36,
      tolerance: 0.05,
      label: "predicted-body face centerX"
    )

    let motionBase = makeDetection(x1: 100, y1: 100, x2: 300, y2: 700)
    let coherentMotion = IOSPersonBboxMotionEstimator.estimate(
      previous: motionBase,
      current: makeDetection(x1: 140, y1: 135, x2: 340, y2: 735)
    )
    try assertNear(coherentMotion.dx, 40, tolerance: 0.01, label: "coherent body dx")
    try assertNear(coherentMotion.dy, 35, tolerance: 0.01, label: "coherent body dy")
    let topEdgeJitter = IOSPersonBboxMotionEstimator.estimate(
      previous: motionBase,
      current: makeDetection(x1: 100, y1: 200, x2: 300, y2: 700)
    )
    try assertNear(topEdgeJitter.dy, 0, tolerance: 0.01, label: "top-edge jitter dy")

    let classMetadata = IOSAnalysisMetadata(
      schemaVersion: 1,
      sourceUri: "woah-phase5://face-class-selected",
      persons: [
        IOSCachedPerson(
          id: 0,
          bbox: IOSCachedBBox(left: 4.0 / 64.0, top: 4.0 / 64.0, right: 24.0 / 64.0, bottom: 60.0 / 64.0),
          confidence: 0.99
        ),
        IOSCachedPerson(
          id: 1,
          bbox: IOSCachedBBox(left: 40.0 / 64.0, top: 4.0 / 64.0, right: 60.0 / 64.0, bottom: 60.0 / 64.0),
          confidence: 0.99
        ),
      ]
    )
    let selectedClassTracker = IOSTemporalIdentityTracker(
      metadata: classMetadata,
      fullBodyIds: [],
      faceOnlyIds: [0, 1],
      frameWidth: width,
      frameHeight: height
    )
    _ = try selectedClassTracker.update(
      detections: [
        makeDetection(x1: 4, y1: 4, x2: 24, y2: 60),
        makeDetection(x1: 40, y1: 4, x2: 60, y2: 60),
      ],
      preprocess: preprocess,
      timestampUs: 0
    )
    // Two fresh detections remain positionally between the two protected
    // identities. Their masks keep both owners plausible, producing a balanced
    // 2-detection / 2-owner ambiguity group without committing either identity.
    let freshAmbiguousDetections = [
      makeDetection(x1: 27, y1: 4, x2: 33, y2: 60),
      makeDetection(x1: 31, y1: 4, x2: 37, y2: 60),
    ]
    let selectedClassPersons = try selectedClassTracker.update(
      detections: freshAmbiguousDetections,
      preprocess: preprocess,
      timestampUs: 33_333
    )
    let selectedClassEvidence = selectedClassTracker.facePrivacyClassEvidence()
    guard selectedClassEvidence.count == 2,
          selectedClassEvidence[0].detectionIndex == 0,
          selectedClassEvidence[1].detectionIndex == 1,
          selectedClassEvidence.allSatisfy({ $0.residualTrackIds == Set([0, 1]) }) else {
      throw smokeFailure("Balanced selected-only ambiguity did not produce complete FACE_ONLY class evidence.")
    }
    let selectedClassSnapshotIds = Set(selectedClassTracker.paritySnapshots().map(\.id))
    guard selectedClassSnapshotIds == Set([0, 1]) else {
      throw smokeFailure("FACE_ONLY class-evidence reservation created a real synthetic identity.")
    }
    let classResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[]])
    )
    let classRegions = classResolver.resolve(
      image: source,
      persons: selectedClassPersons,
      faceOnlyIds: [0, 1],
      preprocess: preprocess,
      freshPrivacyClassEvidence: selectedClassEvidence,
      timestampUs: 33_333
    )
    guard let syntheticClassRegion = classRegions[-1_000_000],
          let secondSyntheticClassRegion = classRegions[-1_000_001],
          syntheticClassRegion.source == .yoloHeadFallback,
          secondSyntheticClassRegion.source == .yoloHeadFallback else {
      throw smokeFailure("Selected-only ambiguous evidence did not create a synthetic FACE_ONLY region.")
    }
    try assertNear(
      syntheticClassRegion.centerX,
      30,
      tolerance: 0.05,
      label: "synthetic class fallback centerX"
    )
    try assertNear(
      secondSyntheticClassRegion.centerX,
      34,
      tolerance: 0.05,
      label: "second synthetic class fallback centerX"
    )

    let mixedClassTracker = IOSTemporalIdentityTracker(
      metadata: classMetadata,
      fullBodyIds: [],
      faceOnlyIds: [0],
      frameWidth: width,
      frameHeight: height
    )
    _ = try mixedClassTracker.update(
      detections: [
        makeDetection(x1: 4, y1: 4, x2: 24, y2: 60),
        makeDetection(x1: 40, y1: 4, x2: 60, y2: 60),
      ],
      preprocess: preprocess,
      timestampUs: 0
    )
    _ = try mixedClassTracker.update(
      detections: freshAmbiguousDetections,
      preprocess: preprocess,
      timestampUs: 33_333
    )
    guard mixedClassTracker.facePrivacyClassEvidence().isEmpty else {
      throw smokeFailure("Ambiguous detection with an unselected possible owner received FACE_ONLY class fallback.")
    }

    let uniqueClassResolver = IOSFacePrivacyTemporalResolver(
      locator: IOSPhase5SequenceFaceLocator(batches: [[trustedFace], []])
    )
    _ = uniqueClassResolver.resolve(
      image: source,
      persons: [person],
      faceOnlyIds: [0],
      preprocess: preprocess,
      timestampUs: 0
    )
    let uniqueClassEvidence = IOSFreshFacePrivacyClassEvidence(
      detectionIndex: 5,
      detection: makeDetection(
        x1: 40,
        y1: 4,
        x2: 63,
        y2: 60,
        mask: [UInt8](
          repeating: 0,
          count: IOSYoloPostprocessor.protoSize * IOSYoloPostprocessor.protoSize
        )
      ),
      residualTrackIds: [0]
    )
    let uniqueClassRegions = uniqueClassResolver.resolve(
      image: source,
      persons: [predictedPerson],
      faceOnlyIds: [0],
      preprocess: preprocess,
      freshPrivacyClassEvidence: [uniqueClassEvidence],
      timestampUs: 200_000
    )
    guard let uniqueSyntheticRegion = uniqueClassRegions[-1_000_005] else {
      throw smokeFailure("Unique selected owner evidence did not create synthetic FACE_ONLY privacy.")
    }
    try assertNear(uniqueSyntheticRegion.centerX, 51.5, tolerance: 0.05, label: "unique class fresh centerX")
    try assertNear(uniqueSyntheticRegion.radiusX, 8.184, tolerance: 0.05, label: "unique class trusted radiusX")
    try assertNear(uniqueSyntheticRegion.radiusY, 11.0112, tolerance: 0.08, label: "unique class trusted radiusY")

    let renderer = try IOSMetalPreviewRenderer()
    let effects = EffectConfigDto(
      fillMode: "solid",
      fillColorArgb: Int64(0xFFFF0000),
      borderColorArgb: 0,
      opacity: 1.0,
      borderWidth: 0,
      blurStrength: 1.0,
      faceStickerEnabled: false,
      stickerAssetId: nil,
      stickerScale: 1.0,
      skinWhiten: 0,
      legStretchEnabled: false,
      legStretch: 0,
      legZoneTop: 0,
      legZoneBottom: 1
    )
    let renderRegion = IOSFacePrivacyEllipse(
      centerX: 32,
      centerY: 32,
      radiusX: 10,
      radiusY: 12,
      source: .detectedFace
    )
    let rendered = try renderer.render(
      source: source,
      persons: [person],
      preprocess: preprocess,
      fullBodyIds: [],
      faceOnlyIds: [0],
      effects: effects,
      faceRegions: [0: renderRegion]
    )
    let centerPixel = try pixel(rendered, x: 32, y: 32)
    guard centerPixel.r >= 240, centerPixel.g <= 20, centerPixel.b <= 20 else {
      throw smokeFailure("FACE_ONLY ellipse center was not covered by the opaque privacy layer.")
    }
    let cornerPixel = try pixel(rendered, x: 23, y: 21)
    guard cornerPixel.b >= 230, cornerPixel.r <= 30 else {
      throw smokeFailure("FACE_ONLY privacy regressed to a rectangular mask instead of ellipse geometry.")
    }
    let syntheticRendered = try renderer.render(
      source: source,
      persons: [],
      preprocess: preprocess,
      fullBodyIds: [],
      faceOnlyIds: [],
      effects: effects,
      faceRegions: [-1_000_000: syntheticClassRegion]
    )
    let syntheticPixel = try pixel(
      syntheticRendered,
      x: Int(syntheticClassRegion.centerX.rounded()),
      y: Int(syntheticClassRegion.centerY.rounded())
    )
    let syntheticMirroredPixel = try pixel(
      syntheticRendered,
      x: Int(syntheticClassRegion.centerX.rounded()),
      y: max(
        0,
        min(
          syntheticRendered.height - 1,
          syntheticRendered.height - 1 - Int(syntheticClassRegion.centerY.rounded())
        )
      )
    )
    let syntheticCovered =
      (syntheticPixel.r >= 240 && syntheticPixel.g <= 20 && syntheticPixel.b <= 20)
        || (syntheticMirroredPixel.r >= 240
          && syntheticMirroredPixel.g <= 20
          && syntheticMirroredPixel.b <= 20)
    guard syntheticCovered else {
      throw smokeFailure("Metal did not render synthetic negative-ID FACE_ONLY privacy evidence.")
    }

    return [
      "status": "pass",
      "detected_source": detectedRegion.source.rawValue,
      "miss_source": missedRegion.source.rawValue,
      "translated_prediction_source": translatedRegion.source.rawValue,
      "expired_prediction_source": expiredRegion.source.rawValue,
      "mask_guided_center_x": maskGuidedRegion.centerX,
      "mask_seed_expired_center_x": maskSeedExpiredRegion.centerX,
      "unsupported_mask_center_x": unsupportedMaskRegion.centerX,
      "ambiguous_source": ambiguous[0]?.source.rawValue ?? "missing",
      "neighbor_competition_source": neighborCompetition[0]?.source.rawValue ?? "missing",
      "predicted_body_source": predicted[0]?.source.rawValue ?? "missing",
      "cached_predicted_body_source": cachedPredictedRegion.source.rawValue,
      "coherent_body_motion": [coherentMotion.dx, coherentMotion.dy],
      "top_edge_jitter_motion": [topEdgeJitter.dx, topEdgeJitter.dy],
      "class_evidence_residual_ids": selectedClassEvidence[0].residualTrackIds.sorted(),
      "class_evidence_synthetic_id": -1_000_000,
      "class_evidence_center_x": syntheticClassRegion.centerX,
      "mixed_class_evidence_count": mixedClassTracker.facePrivacyClassEvidence().count,
      "unique_class_radius": [uniqueSyntheticRegion.radiusX, uniqueSyntheticRegion.radiusY],
      "vision_runtime_face_count": visionRuntimeFaces.count,
      "center_pixel": [centerPixel.r, centerPixel.g, centerPixel.b, centerPixel.a],
      "corner_pixel": [cornerPixel.r, cornerPixel.g, cornerPixel.b, cornerPixel.a],
      "synthetic_pixel": [syntheticPixel.r, syntheticPixel.g, syntheticPixel.b, syntheticPixel.a],
      "synthetic_mirrored_pixel": [
        syntheticMirroredPixel.r,
        syntheticMirroredPixel.g,
        syntheticMirroredPixel.b,
        syntheticMirroredPixel.a,
      ],
    ]
  }

  private static func makeDetection(
    x1: Float32,
    y1: Float32,
    x2: Float32,
    y2: Float32,
    mask: [UInt8]? = nil
  ) -> IOSYoloDetection {
    IOSYoloDetection(
      x1: x1,
      y1: y1,
      x2: x2,
      y2: y2,
      confidence: 0.99,
      mask: mask ?? [UInt8](
        repeating: 255,
        count: IOSYoloPostprocessor.protoSize * IOSYoloPostprocessor.protoSize
      )
    )
  }

  private static func makeProtoMask(
    sourceRects: [(Float32, Float32, Float32, Float32)],
    preprocess: IOSYoloPreprocessResult
  ) -> [UInt8] {
    let proto = IOSYoloPostprocessor.protoSize
    var mask = [UInt8](repeating: 0, count: proto * proto)
    for rect in sourceRects {
      let modelX1 = rect.0 * preprocess.scale + preprocess.padLeft
      let modelY1 = rect.1 * preprocess.scale + preprocess.padTop
      let modelX2 = rect.2 * preprocess.scale + preprocess.padLeft
      let modelY2 = rect.3 * preprocess.scale + preprocess.padTop
      let factor = Float32(proto) / Float32(preprocess.inputSize)
      let x1 = max(0, min(proto - 1, Int(floor(Double(modelX1 * factor)))))
      let y1 = max(0, min(proto - 1, Int(floor(Double(modelY1 * factor)))))
      let x2 = max(x1 + 1, min(proto, Int(ceil(Double(modelX2 * factor)))))
      let y2 = max(y1 + 1, min(proto, Int(ceil(Double(modelY2 * factor)))))
      for y in y1..<y2 {
        for x in x1..<x2 {
          mask[y * proto + x] = 255
        }
      }
    }
    return mask
  }

  private static func assertNear(
    _ actual: Float32,
    _ expected: Float32,
    tolerance: Float32,
    label: String
  ) throws {
    guard abs(actual - expected) <= tolerance else {
      throw smokeFailure("\(label) expected=\(expected) actual=\(actual)")
    }
  }

  private static func makeSolidImage(
    width: Int,
    height: Int,
    rgba: (UInt8, UInt8, UInt8, UInt8)
  ) throws -> CGImage {
    var bytes = [UInt8](repeating: 0, count: width * height * 4)
    for index in 0..<(width * height) {
      let offset = index * 4
      bytes[offset] = rgba.0
      bytes[offset + 1] = rgba.1
      bytes[offset + 2] = rgba.2
      bytes[offset + 3] = rgba.3
    }
    let data = Data(bytes) as CFData
    guard let provider = CGDataProvider(data: data),
          let image = CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(
              rawValue: CGBitmapInfo.byteOrder32Big.rawValue
                | CGImageAlphaInfo.premultipliedLast.rawValue
            ),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
          ) else {
      throw smokeFailure("Could not create the Phase 5C FACE_ONLY smoke source image.")
    }
    return image
  }

  private static func pixel(
    _ image: CGImage,
    x: Int,
    y: Int
  ) throws -> (r: Int, g: Int, b: Int, a: Int) {
    let width = image.width
    let height = image.height
    guard x >= 0, x < width, y >= 0, y < height else {
      throw smokeFailure("Requested FACE_ONLY smoke readback pixel is outside the image.")
    }
    var bytes = [UInt8](repeating: 0, count: width * height * 4)
    let rendered = bytes.withUnsafeMutableBytes { raw -> Bool in
      guard let base = raw.baseAddress,
            let context = CGContext(
              data: base,
              width: width,
              height: height,
              bitsPerComponent: 8,
              bytesPerRow: width * 4,
              space: CGColorSpaceCreateDeviceRGB(),
              bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue
                | CGImageAlphaInfo.premultipliedLast.rawValue
            ) else {
        return false
      }
      context.interpolationQuality = .none
      context.translateBy(x: 0, y: CGFloat(height))
      context.scaleBy(x: 1, y: -1)
      context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
      return true
    }
    guard rendered else {
      throw smokeFailure("Could not read back the Phase 5C FACE_ONLY Metal output.")
    }
    let offset = (y * width + x) * 4
    return (
      Int(bytes[offset]),
      Int(bytes[offset + 1]),
      Int(bytes[offset + 2]),
      Int(bytes[offset + 3])
    )
  }

  private static func smokeFailure(_ message: String) -> PigeonError {
    PigeonError(
      code: "IOS_PHASE5_FACE_PRIVACY_SMOKE_FAILED",
      message: message,
      details: nil
    )
  }
}

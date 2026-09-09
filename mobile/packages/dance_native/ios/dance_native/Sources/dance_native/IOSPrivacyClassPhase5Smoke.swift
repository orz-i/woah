import CoreGraphics
import Foundation

enum IOSPrivacyClassPhase5Smoke {
  static func run() throws -> [String: Any] {
    let preprocess = makePreprocess(width: 640, height: 640)

    let rootTracker = IOSPrivacyClassTemporalTracker()
    let rootFrame = [
      makeDetection(x1: 100, y1: 100, x2: 260, y2: 300, preprocess: preprocess),
      makeDetection(x1: 380, y1: 100, x2: 540, y2: 300, preprocess: preprocess),
    ]
    let seeded = rootTracker.update(
      detections: rootFrame,
      preprocess: preprocess,
      hardClassByDetectionIndex: [0: .selected, 1: .unselected],
      timestampUs: 0
    )
    try requireClass(seeded, index: 0, selectionClass: .selected, unknown: false)
    try requireClass(seeded, index: 1, selectionClass: .unselected, unknown: false)

    let shiftedFrame = [
      makeDetection(x1: 130, y1: 100, x2: 290, y2: 300, preprocess: preprocess),
      makeDetection(x1: 350, y1: 100, x2: 510, y2: 300, preprocess: preprocess),
    ]
    let poisonedRuntimeLabels = rootTracker.update(
      detections: shiftedFrame,
      preprocess: preprocess,
      // Runtime identity-derived labels must never overwrite immutable roots.
      hardClassByDetectionIndex: [0: .unselected, 1: .selected],
      timestampUs: 16_667
    )
    try requireClass(poisonedRuntimeLabels, index: 0, selectionClass: .selected, unknown: false)
    try requireClass(poisonedRuntimeLabels, index: 1, selectionClass: .unselected, unknown: false)

    let crossingFrames = [
      [
        makeDetection(x1: 160, y1: 100, x2: 320, y2: 300, preprocess: preprocess),
        makeDetection(x1: 320, y1: 100, x2: 480, y2: 300, preprocess: preprocess),
      ],
      [
        makeDetection(x1: 220, y1: 100, x2: 380, y2: 300, preprocess: preprocess),
        makeDetection(x1: 260, y1: 100, x2: 420, y2: 300, preprocess: preprocess),
      ],
      [
        makeDetection(x1: 280, y1: 100, x2: 440, y2: 300, preprocess: preprocess),
        makeDetection(x1: 200, y1: 100, x2: 360, y2: 300, preprocess: preprocess),
      ],
    ]
    var crossingTimestamp: Int64 = 33_334
    for frame in crossingFrames {
      let evidence = rootTracker.update(
        detections: frame,
        preprocess: preprocess,
        hardClassByDetectionIndex: [:],
        timestampUs: crossingTimestamp
      )
      try requireClass(evidence, index: 0, selectionClass: .selected, unknown: false)
      try requireClass(evidence, index: 1, selectionClass: .unselected, unknown: false)
      crossingTimestamp += 16_667
    }

    let unknownTracker = IOSPrivacyClassTemporalTracker(minClassMargin: 0.20)
    _ = unknownTracker.update(
      detections: [
        makeDetection(x1: 100, y1: 100, x2: 260, y2: 300, preprocess: preprocess),
        makeDetection(x1: 300, y1: 100, x2: 460, y2: 300, preprocess: preprocess),
      ],
      preprocess: preprocess,
      hardClassByDetectionIndex: [0: .selected, 1: .unselected],
      timestampUs: 0
    )
    let merged = unknownTracker.update(
      detections: [
        makeDetection(x1: 200, y1: 100, x2: 360, y2: 300, preprocess: preprocess),
      ],
      preprocess: preprocess,
      hardClassByDetectionIndex: [:],
      timestampUs: 16_667
    )
    try requireClass(merged, index: 0, selectionClass: .selected, unknown: true)

    let entrantTracker = IOSPrivacyClassTemporalTracker()
    _ = entrantTracker.update(
      detections: [
        makeDetection(x1: 100, y1: 100, x2: 260, y2: 300, preprocess: preprocess),
      ],
      preprocess: preprocess,
      hardClassByDetectionIndex: [0: .selected],
      timestampUs: 0
    )
    let entrant = entrantTracker.update(
      detections: [
        makeDetection(x1: 500, y1: 20, x2: 620, y2: 180, preprocess: preprocess),
      ],
      preprocess: preprocess,
      hardClassByDetectionIndex: [:],
      timestampUs: 16_667
    )
    try requireClass(entrant, index: 0, selectionClass: .selected, unknown: true)

    let occlusionTracker = IOSPrivacyClassTemporalTracker()
    _ = occlusionTracker.update(
      detections: rootFrame,
      preprocess: preprocess,
      hardClassByDetectionIndex: [0: .selected, 1: .unselected],
      timestampUs: 0
    )
    _ = occlusionTracker.update(
      detections: shiftedFrame,
      preprocess: preprocess,
      hardClassByDetectionIndex: [:],
      timestampUs: 16_667
    )
    _ = occlusionTracker.update(
      detections: [
        makeDetection(x1: 160, y1: 100, x2: 320, y2: 300, preprocess: preprocess),
      ],
      preprocess: preprocess,
      hardClassByDetectionIndex: [:],
      timestampUs: 33_334
    )
    let returned = occlusionTracker.update(
      detections: [
        makeDetection(x1: 190, y1: 100, x2: 350, y2: 300, preprocess: preprocess),
        makeDetection(x1: 290, y1: 100, x2: 450, y2: 300, preprocess: preprocess),
      ],
      preprocess: preprocess,
      hardClassByDetectionIndex: [:],
      timestampUs: 50_001
    )
    try requireClass(returned, index: 0, selectionClass: .selected, unknown: false)
    try requireClass(returned, index: 1, selectionClass: .unselected, unknown: false)

    let cachedTracker = IOSPrivacyClassTemporalTracker(
      reuseFrameSimilarityCache: true,
      countSimilarityEvaluations: true
    )
    let uncachedTracker = IOSPrivacyClassTemporalTracker(
      reuseFrameSimilarityCache: false,
      countSimilarityEvaluations: true
    )
    let cacheFrames = [
      rootFrame,
      [
        makeDetection(x1: 115, y1: 100, x2: 275, y2: 300, preprocess: preprocess),
        makeDetection(x1: 365, y1: 100, x2: 525, y2: 300, preprocess: preprocess),
      ],
      shiftedFrame,
      [
        makeDetection(x1: 145, y1: 100, x2: 305, y2: 300, preprocess: preprocess),
        makeDetection(x1: 335, y1: 100, x2: 495, y2: 300, preprocess: preprocess),
      ],
    ]
    var cachedEvaluations = 0
    var uncachedEvaluations = 0
    for (index, frame) in cacheFrames.enumerated() {
      let hard: [Int: IOSPrivacySelectionClass] = index == 0
        ? [0: .selected, 1: .unselected]
        : [:]
      let cachedResult = cachedTracker.update(
        detections: frame,
        preprocess: preprocess,
        hardClassByDetectionIndex: hard,
        timestampUs: Int64(index) * 16_667
      )
      let uncachedResult = uncachedTracker.update(
        detections: frame,
        preprocess: preprocess,
        hardClassByDetectionIndex: hard,
        timestampUs: Int64(index) * 16_667
      )
      guard cachedResult.map({ [$0.selectionClass.rawValue, String($0.detectionIndex), String($0.conservativeUnknown)] })
        == uncachedResult.map({ [$0.selectionClass.rawValue, String($0.detectionIndex), String($0.conservativeUnknown)] }) else {
        throw failure("Frame similarity cache changed privacy-class decisions.")
      }
      cachedEvaluations += cachedTracker.lastSimilarityEvaluationCount
      uncachedEvaluations += uncachedTracker.lastSimilarityEvaluationCount
    }
    guard cachedEvaluations == 12, uncachedEvaluations == 18 else {
      throw failure(
        "Privacy-class similarity cache evaluation counts drifted: cached=\(cachedEvaluations), uncached=\(uncachedEvaluations)"
      )
    }

    let rendererReport = try verifyRendererBoundary()
    return [
      "status": "pass",
      "immutable_roots": true,
      "crossing_frames": crossingFrames.count,
      "merged_unknown_fail_closed": merged[0].conservativeUnknown,
      "far_entrant_unknown_fail_closed": entrant[0].conservativeUnknown,
      "one_frame_occlusion_return": returned[1].selectionClass.rawValue,
      "cached_similarity_evaluations": cachedEvaluations,
      "uncached_similarity_evaluations": uncachedEvaluations,
      "renderer": rendererReport,
    ]
  }

  private static func verifyRendererBoundary() throws -> [String: Any] {
    let width = 64
    let height = 64
    let preprocess = makePreprocess(width: width, height: height)
    let selectedDetection = makeDetection(
      x1: 6,
      y1: 8,
      x2: 26,
      y2: 56,
      preprocess: preprocess
    )
    let unselectedDetection = makeDetection(
      x1: 38,
      y1: 8,
      x2: 58,
      y2: 56,
      preprocess: preprocess
    )
    let source = try sourceImage(width: width, height: height)
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
    let evidence = [
      IOSFreshPrivacyClassEvidence(
        selectionClass: .selected,
        detectionIndex: 0,
        detection: selectedDetection,
        conservativeUnknown: false
      ),
      IOSFreshPrivacyClassEvidence(
        selectionClass: .unselected,
        detectionIndex: 1,
        detection: unselectedDetection,
        conservativeUnknown: false
      ),
    ]
    let freshPrimary = try renderer.render(
      source: source,
      persons: [IOSPreviewPerson(id: 0, detection: unselectedDetection)],
      preprocess: preprocess,
      fullBodyIds: [0],
      faceOnlyIds: [],
      effects: effects,
      freshFullBodyPrivacyEvidence: evidence,
      preferFreshFullBodyClassPrimary: true
    )
    let selectedPixel = try pixel(freshPrimary, x: 16, y: 32)
    let unselectedPixel = try pixel(freshPrimary, x: 48, y: 32)
    guard isPrivacyRed(selectedPixel), !isPrivacyRed(unselectedPixel) else {
      throw failure("FULL_BODY-only fresh privacy-class primary rendered the wrong class.")
    }

    // Defense in depth: even if a caller mistakenly supplies fresh evidence,
    // any FACE_ONLY membership disables the non-identity FULL_BODY primary.
    let mixed = try renderer.render(
      source: source,
      persons: [IOSPreviewPerson(id: 0, detection: unselectedDetection)],
      preprocess: preprocess,
      fullBodyIds: [0],
      faceOnlyIds: [99],
      effects: effects,
      freshFullBodyPrivacyEvidence: evidence,
      preferFreshFullBodyClassPrimary: true
    )
    let mixedFreshPixel = try pixel(mixed, x: 16, y: 32)
    let mixedTrackedPixel = try pixel(mixed, x: 48, y: 32)
    guard !isPrivacyRed(mixedFreshPixel), isPrivacyRed(mixedTrackedPixel) else {
      throw failure("Mixed/FACE_ONLY composition consumed non-identity FULL_BODY class evidence.")
    }
    return [
      "full_body_selected_pixel": [selectedPixel.r, selectedPixel.g, selectedPixel.b, selectedPixel.a],
      "full_body_unselected_pixel": [unselectedPixel.r, unselectedPixel.g, unselectedPixel.b, unselectedPixel.a],
      "mixed_fresh_pixel": [mixedFreshPixel.r, mixedFreshPixel.g, mixedFreshPixel.b, mixedFreshPixel.a],
      "mixed_tracked_pixel": [mixedTrackedPixel.r, mixedTrackedPixel.g, mixedTrackedPixel.b, mixedTrackedPixel.a],
    ]
  }

  private static func requireClass(
    _ evidence: [IOSFreshPrivacyClassEvidence],
    index: Int,
    selectionClass: IOSPrivacySelectionClass,
    unknown: Bool
  ) throws {
    guard let item = evidence.first(where: { $0.detectionIndex == index }),
          item.selectionClass == selectionClass,
          item.conservativeUnknown == unknown else {
      throw failure(
        "privacy class mismatch index=\(index) expected=\(selectionClass.rawValue) unknown=\(unknown)"
      )
    }
  }

  private static func makePreprocess(width: Int, height: Int) -> IOSYoloPreprocessResult {
    let scale = min(640.0 / Double(max(1, width)), 640.0 / Double(max(1, height)))
    let scaledWidth = Double(width) * scale
    let scaledHeight = Double(height) * scale
    return IOSYoloPreprocessResult(
      input: [],
      scale: Float32(scale),
      padLeft: Float32((640.0 - scaledWidth) * 0.5),
      padTop: Float32((640.0 - scaledHeight) * 0.5),
      sourceWidth: width,
      sourceHeight: height,
      inputSize: 640
    )
  }

  private static func makeDetection(
    x1: Float32,
    y1: Float32,
    x2: Float32,
    y2: Float32,
    preprocess: IOSYoloPreprocessResult
  ) -> IOSYoloDetection {
    let proto = IOSYoloPostprocessor.protoSize
    var mask = [UInt8](repeating: 0, count: proto * proto)
    let px1 = clampProto(sourceToProtoX(x1, preprocess: preprocess), proto: proto)
    let py1 = clampProto(sourceToProtoY(y1, preprocess: preprocess), proto: proto)
    let px2 = clampProto(sourceToProtoX(x2, preprocess: preprocess), proto: proto)
    let py2 = clampProto(sourceToProtoY(y2, preprocess: preprocess), proto: proto)
    if px2 >= px1, py2 >= py1 {
      for y in py1...py2 {
        for x in px1...px2 { mask[y * proto + x] = 255 }
      }
    }
    return IOSYoloDetection(
      x1: x1,
      y1: y1,
      x2: x2,
      y2: y2,
      confidence: 0.95,
      mask: mask
    )
  }

  private static func sourceToProtoX(
    _ sourceX: Float32,
    preprocess: IOSYoloPreprocessResult
  ) -> Float32 {
    (sourceX * preprocess.scale + preprocess.padLeft)
      / Float32(preprocess.inputSize) * Float32(IOSYoloPostprocessor.protoSize)
  }

  private static func sourceToProtoY(
    _ sourceY: Float32,
    preprocess: IOSYoloPreprocessResult
  ) -> Float32 {
    (sourceY * preprocess.scale + preprocess.padTop)
      / Float32(preprocess.inputSize) * Float32(IOSYoloPostprocessor.protoSize)
  }

  private static func clampProto(_ value: Float32, proto: Int) -> Int {
    min(proto - 1, max(0, Int(value.rounded())))
  }

  private static func sourceImage(width: Int, height: Int) throws -> CGImage {
    var bytes = [UInt8](repeating: 0, count: width * height * 4)
    for index in 0..<(width * height) {
      let offset = index * 4
      bytes[offset] = 0
      bytes[offset + 1] = 0
      bytes[offset + 2] = 255
      bytes[offset + 3] = 255
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
      throw failure("Could not create the Phase 5I privacy-class smoke image.")
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
      context.translateBy(x: 0, y: CGFloat(height))
      context.scaleBy(x: 1, y: -1)
      context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
      return true
    }
    guard rendered, x >= 0, x < width, y >= 0, y < height else {
      throw failure("Could not read Phase 5I Metal output pixel.")
    }
    let offset = (y * width + x) * 4
    return (
      Int(bytes[offset]),
      Int(bytes[offset + 1]),
      Int(bytes[offset + 2]),
      Int(bytes[offset + 3])
    )
  }

  private static func isPrivacyRed(_ value: (r: Int, g: Int, b: Int, a: Int)) -> Bool {
    value.r >= 240 && value.g <= 20 && value.b <= 20 && value.a >= 240
  }

  private static func failure(_ message: String) -> PigeonError {
    PigeonError(
      code: "IOS_PHASE5_PRIVACY_CLASS_SMOKE_FAILED",
      message: message,
      details: nil
    )
  }
}

import Foundation

private struct IOSPhase5GoldenSuite: Decodable {
  let schemaVersion: Int
  let frameIntervalUs: Int64
  let description: String
  let cases: [IOSPhase5GoldenCase]
}

private struct IOSPhase5GoldenCase: Decodable {
  let name: String
  let androidReferences: [IOSPhase5AndroidReference]
  let frameWidth: Int
  let frameHeight: Int
  let fullBodyIds: [Int]
  let faceOnlyIds: [Int]
  let analysisPersons: [IOSPhase5AnalysisPerson]
  let steps: [IOSPhase5GoldenStep]
}

private struct IOSPhase5AndroidReference: Decodable {
  let path: String
  let testName: String
}

private struct IOSPhase5AnalysisPerson: Decodable {
  let id: Int
  let bbox: [Double]
  let confidence: Double
}

private struct IOSPhase5GoldenStep: Decodable {
  let repeatCount: Int
  let detections: [IOSPhase5GoldenDetection]
  let expect: [IOSPhase5TrackExpectation]?
  let expectedErrorCode: String?

  private enum CodingKeys: String, CodingKey {
    case repeatCount = "repeat"
    case detections
    case expect
    case expectedErrorCode
  }
}

private struct IOSPhase5GoldenDetection: Decodable {
  let bbox: [Double]
  let confidence: Double
  let maskRect: [Double]
}

private struct IOSPhase5TrackExpectation: Decodable {
  let id: Int
  let state: String
  let missedFrames: Int
  let observed: Bool
  let identityProtected: Bool
  let privacySelected: Bool
  let outputPresent: Bool
  let fallback: Bool
}

enum IOSGoldenTracePhase5Smoke {
  private static let maskSize = IOSYoloPostprocessor.protoSize

  static func run() throws -> [String: Any] {
    guard let traceURL = IOSGoldenTraceResources.phase5TrackingURL() else {
      throw PigeonError(
        code: "IOS_PHASE5_GOLDEN_TRACE_MISSING",
        message: "The Phase 5 tracking Golden Trace resource was not bundled.",
        details: nil
      )
    }

    let data = try Data(contentsOf: traceURL)
    let suite: IOSPhase5GoldenSuite
    do {
      suite = try JSONDecoder().decode(IOSPhase5GoldenSuite.self, from: data)
    } catch {
      throw PigeonError(
        code: "IOS_PHASE5_GOLDEN_TRACE_INVALID",
        message: "Unable to decode the Phase 5 tracking Golden Trace.",
        details: String(describing: error)
      )
    }

    guard suite.schemaVersion == 1 else {
      throw PigeonError(
        code: "IOS_PHASE5_GOLDEN_TRACE_SCHEMA_UNSUPPORTED",
        message: "Unsupported Phase 5 Golden Trace schema.",
        details: suite.schemaVersion
      )
    }
    guard suite.frameIntervalUs > 0, !suite.cases.isEmpty else {
      throw PigeonError(
        code: "IOS_PHASE5_GOLDEN_TRACE_INVALID",
        message: "Phase 5 Golden Trace requires a positive frame interval and at least one case.",
        details: nil
      )
    }

    var caseReports: [[String: Any]] = []
    var totalFrames = 0
    var failClosedCases = 0
    for traceCase in suite.cases {
      let report = try runCase(traceCase, frameIntervalUs: suite.frameIntervalUs)
      totalFrames += report.frameCount
      if report.expectedFailureObserved { failClosedCases += 1 }
      caseReports.append([
        "name": traceCase.name,
        "frames": report.frameCount,
        "androidReferenceCount": traceCase.androidReferences.count,
        "expectedFailureObserved": report.expectedFailureObserved,
      ])
    }

    return [
      "schemaVersion": suite.schemaVersion,
      "caseCount": suite.cases.count,
      "totalFrames": totalFrames,
      "failClosedCases": failClosedCases,
      "resource": traceURL.lastPathComponent,
      "cases": caseReports,
    ]
  }

  private struct CaseRunReport {
    let frameCount: Int
    let expectedFailureObserved: Bool
  }

  private static func runCase(
    _ traceCase: IOSPhase5GoldenCase,
    frameIntervalUs: Int64
  ) throws -> CaseRunReport {
    guard traceCase.frameWidth > 0, traceCase.frameHeight > 0 else {
      throw failure(traceCase.name, frame: 0, "invalid frame dimensions")
    }
    guard !traceCase.steps.isEmpty else {
      throw failure(traceCase.name, frame: 0, "trace case contains no steps")
    }

    let metadata = try makeMetadata(traceCase)
    let tracker = IOSTemporalIdentityTracker(
      metadata: metadata,
      fullBodyIds: Set(traceCase.fullBodyIds),
      faceOnlyIds: Set(traceCase.faceOnlyIds),
      frameWidth: traceCase.frameWidth,
      frameHeight: traceCase.frameHeight
    )
    let preprocess = makePreprocess(
      frameWidth: traceCase.frameWidth,
      frameHeight: traceCase.frameHeight
    )

    var frameIndex = 0
    var expectedFailureObserved = false
    for (stepIndex, step) in traceCase.steps.enumerated() {
      guard step.repeatCount > 0 else {
        throw failure(traceCase.name, frame: frameIndex, "step repeat must be positive")
      }
      if step.expectedErrorCode != nil && stepIndex != traceCase.steps.count - 1 {
        throw failure(traceCase.name, frame: frameIndex, "expected error step must terminate the case")
      }

      for repetition in 0..<step.repeatCount {
        let isFinalRepetition = repetition == step.repeatCount - 1
        let detections = try step.detections.map {
          try makeDetection($0, caseName: traceCase.name, frame: frameIndex)
        }
        let timestampUs = Int64(frameIndex) * frameIntervalUs

        do {
          let output = try tracker.update(
            detections: detections,
            preprocess: preprocess,
            timestampUs: timestampUs
          )
          if isFinalRepetition, let expectedErrorCode = step.expectedErrorCode {
            throw failure(
              traceCase.name,
              frame: frameIndex,
              "expected \(expectedErrorCode) but update succeeded"
            )
          }
          if isFinalRepetition, let expectations = step.expect {
            try validate(
              tracker: tracker,
              output: output,
              expectations: expectations,
              caseName: traceCase.name,
              frame: frameIndex
            )
          }
        } catch let error as PigeonError {
          guard isFinalRepetition,
                let expectedErrorCode = step.expectedErrorCode,
                error.code == expectedErrorCode else {
            throw error
          }
          expectedFailureObserved = true
        }
        frameIndex += 1
      }
    }

    return CaseRunReport(
      frameCount: frameIndex,
      expectedFailureObserved: expectedFailureObserved
    )
  }

  private static func makeMetadata(
    _ traceCase: IOSPhase5GoldenCase
  ) throws -> IOSAnalysisMetadata {
    let persons = try traceCase.analysisPersons.map { person -> IOSCachedPerson in
      let bbox = try checkedRect(
        person.bbox,
        caseName: traceCase.name,
        frame: 0,
        label: "analysis bbox"
      )
      return IOSCachedPerson(
        id: person.id,
        bbox: IOSCachedBBox(
          left: bbox.0 / Double(traceCase.frameWidth),
          top: bbox.1 / Double(traceCase.frameHeight),
          right: bbox.2 / Double(traceCase.frameWidth),
          bottom: bbox.3 / Double(traceCase.frameHeight)
        ),
        confidence: person.confidence
      )
    }
    return IOSAnalysisMetadata(
      schemaVersion: 1,
      sourceUri: "woah-phase5://\(traceCase.name)",
      persons: persons
    )
  }

  private static func makePreprocess(
    frameWidth: Int,
    frameHeight: Int
  ) -> IOSYoloPreprocessResult {
    let inputSize = IOSYoloPreprocessor.inputSize
    let scale = min(
      Float32(inputSize) / Float32(frameWidth),
      Float32(inputSize) / Float32(frameHeight)
    )
    let resizedWidth = Float32(frameWidth) * scale
    let resizedHeight = Float32(frameHeight) * scale
    return IOSYoloPreprocessResult(
      input: [],
      scale: scale,
      padLeft: (Float32(inputSize) - resizedWidth) * 0.5,
      padTop: (Float32(inputSize) - resizedHeight) * 0.5,
      sourceWidth: frameWidth,
      sourceHeight: frameHeight,
      inputSize: inputSize
    )
  }

  private static func makeDetection(
    _ detection: IOSPhase5GoldenDetection,
    caseName: String,
    frame: Int
  ) throws -> IOSYoloDetection {
    let bbox = try checkedRect(
      detection.bbox,
      caseName: caseName,
      frame: frame,
      label: "detection bbox"
    )
    let maskRect = try checkedUnitRect(
      detection.maskRect,
      caseName: caseName,
      frame: frame
    )
    return IOSYoloDetection(
      x1: Float32(bbox.0),
      y1: Float32(bbox.1),
      x2: Float32(bbox.2),
      y2: Float32(bbox.3),
      confidence: Float32(detection.confidence),
      mask: makeMask(maskRect)
    )
  }

  private static func makeMask(
    _ rect: (Double, Double, Double, Double)
  ) -> [UInt8] {
    var mask = [UInt8](repeating: 0, count: maskSize * maskSize)
    let x1 = max(0, min(maskSize - 1, Int(floor(rect.0 * Double(maskSize)))))
    let y1 = max(0, min(maskSize - 1, Int(floor(rect.1 * Double(maskSize)))))
    let x2 = max(x1 + 1, min(maskSize, Int(ceil(rect.2 * Double(maskSize)))))
    let y2 = max(y1 + 1, min(maskSize, Int(ceil(rect.3 * Double(maskSize)))))
    for y in y1..<y2 {
      let row = y * maskSize
      for x in x1..<x2 {
        mask[row + x] = 255
      }
    }
    return mask
  }

  private static func validate(
    tracker: IOSTemporalIdentityTracker,
    output: [IOSPreviewPerson],
    expectations: [IOSPhase5TrackExpectation],
    caseName: String,
    frame: Int
  ) throws {
    let snapshots = tracker.paritySnapshots()
    let expectedIds = Set(expectations.map(\.id))
    let actualIds = Set(snapshots.map(\.id))
    guard actualIds == expectedIds else {
      throw failure(
        caseName,
        frame: frame,
        "track IDs expected=\(expectedIds.sorted()) actual=\(actualIds.sorted())"
      )
    }

    let snapshotsById = Dictionary(uniqueKeysWithValues: snapshots.map { ($0.id, $0) })
    let outputById = Dictionary(uniqueKeysWithValues: output.map { ($0.id, $0) })
    for expectation in expectations {
      guard let snapshot = snapshotsById[expectation.id] else {
        throw failure(caseName, frame: frame, "missing track \(expectation.id)")
      }
      guard snapshot.state.rawValue == expectation.state else {
        throw failure(
          caseName,
          frame: frame,
          "track \(expectation.id) state expected=\(expectation.state) actual=\(snapshot.state.rawValue)"
        )
      }
      guard snapshot.missedFrames == expectation.missedFrames else {
        throw failure(
          caseName,
          frame: frame,
          "track \(expectation.id) missedFrames expected=\(expectation.missedFrames) actual=\(snapshot.missedFrames)"
        )
      }
      guard snapshot.observedThisFrame == expectation.observed else {
        throw failure(caseName, frame: frame, "track \(expectation.id) observed flag drifted")
      }
      guard snapshot.identityProtected == expectation.identityProtected else {
        throw failure(caseName, frame: frame, "track \(expectation.id) identity protection drifted")
      }
      guard snapshot.privacySelected == expectation.privacySelected else {
        throw failure(caseName, frame: frame, "track \(expectation.id) privacy selection drifted")
      }

      let emitted = outputById[expectation.id]
      guard (emitted != nil) == expectation.outputPresent else {
        throw failure(caseName, frame: frame, "track \(expectation.id) output presence drifted")
      }
      let fallback = emitted?.conservativePrivacyFallback ?? false
      guard fallback == expectation.fallback else {
        throw failure(caseName, frame: frame, "track \(expectation.id) fallback flag drifted")
      }
    }
  }

  private static func checkedRect(
    _ values: [Double],
    caseName: String,
    frame: Int,
    label: String
  ) throws -> (Double, Double, Double, Double) {
    guard values.count == 4,
          values.allSatisfy({ $0.isFinite }),
          values[0] < values[2],
          values[1] < values[3] else {
      throw failure(caseName, frame: frame, "invalid \(label): \(values)")
    }
    return (values[0], values[1], values[2], values[3])
  }

  private static func checkedUnitRect(
    _ values: [Double],
    caseName: String,
    frame: Int
  ) throws -> (Double, Double, Double, Double) {
    let rect = try checkedRect(
      values,
      caseName: caseName,
      frame: frame,
      label: "maskRect"
    )
    guard rect.0 >= 0, rect.1 >= 0, rect.2 <= 1, rect.3 <= 1 else {
      throw failure(caseName, frame: frame, "maskRect must stay within 0...1")
    }
    return rect
  }

  private static func failure(
    _ caseName: String,
    frame: Int,
    _ message: String
  ) -> PigeonError {
    PigeonError(
      code: "IOS_PHASE5_GOLDEN_TRACE_MISMATCH",
      message: "Phase 5 Golden Trace mismatch in \(caseName) at frame \(frame): \(message)",
      details: ["case": caseName, "frame": frame]
    )
  }
}

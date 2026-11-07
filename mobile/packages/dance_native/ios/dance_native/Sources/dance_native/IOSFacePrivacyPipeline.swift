import CoreGraphics
import Foundation
@_implementationOnly import FrameKernelC

enum IOSFacePrivacyRegionSource: String {
  case detectedFace = "DETECTED_FACE"
  case predictedFace = "PREDICTED_FACE"
  case yoloHeadFallback = "YOLO_HEAD_FALLBACK"
}

enum IOSBodyMaskFaceHeadEstimator {
  static func estimate(
    mask: [UInt8], personDetection: IOSYoloDetection,
    seedCenterX: Float32, seedCenterY: Float32, seedRadiusX: Float32, seedRadiusY: Float32,
    preprocess: IOSYoloPreprocessResult
  ) -> (x: Float32, y: Float32)? {
    guard let center = IOSFrameKernelFaceGeometry.estimate(mask, person: personDetection,
      seed: SIMD4(seedCenterX, seedCenterY, seedRadiusX, seedRadiusY), preprocess: preprocess) else { return nil }
    return (x: center.x, y: center.y)
  }

}

enum IOSPersonBboxMotionEstimator {
  static func estimate(previous: IOSYoloDetection, current: IOSYoloDetection) -> (dx: Float32, dy: Float32) {
    let out=IOSFrameKernel.anchorTranslation(previous:SIMD4<Float>(previous.x1,previous.y1,previous.x2,previous.y2),
      current:SIMD4<Float>(current.x1,current.y1,current.x2,current.y2))
    return (dx:out.x,dy:out.y)
  }
}

struct IOSFacePrivacyEllipse {
  let centerX: Float32
  let centerY: Float32
  let radiusX: Float32
  let radiusY: Float32
  let source: IOSFacePrivacyRegionSource
}

/// Render-only temporal continuity for anonymous FACE_ONLY privacy-class
/// fallbacks. The owner ID is only a private state key; this class never writes
/// back to IOSTemporalIdentityTracker or the normal per-ID face cache.
final class IOSFacePrivacyClassFallbackContinuity {
  private var states: [Int: IOSFrameKernel.RegionState] = [:]
  func reset() { states.removeAll() }
  func retain(ownerIds: Set<Int>) { states = states.filter { ownerIds.contains($0.key) } }
  func stabilize(ownerId: Int, rawRegion: IOSFacePrivacyEllipse, personDetection: IOSYoloDetection,
      timestampUs: Int64, bodyMaskGuided: Bool) throws -> IOSFacePrivacyEllipse {
    let state=states[ownerId] ?? IOSFrameKernel.RegionState()
    let output=try rawRegion.sharedStep(state:state,person:personDetection,timestampUs:timestampUs,
      observed:true,trustedCenter:false,translationOnly:!bodyMaskGuided)
    states[ownerId]=state
    return output
  }
}

private extension IOSFacePrivacyEllipse {
  func sharedStep(state: IOSFrameKernel.RegionState, person: IOSYoloDetection, timestampUs: Int64,
      observed: Bool, trustedCenter: Bool, translationOnly: Bool=false) throws -> IOSFacePrivacyEllipse {
    let kind:UInt32
    switch source {case .detectedFace:kind=0;case .predictedFace:kind=1;case .yoloHeadFallback:kind=2}
    let out=try state.step(region:SIMD4<Float>(centerX,centerY,radiusX,radiusY),
      anchor:SIMD4<Float>(person.x1,person.y1,person.x2,person.y2),timestampUs:timestampUs,kind:kind,
      observed:observed,trustedCenter:trustedCenter,translationOnly:translationOnly,coverCurrent:kind==0)
    return IOSFacePrivacyEllipse(centerX:out.x,centerY:out.y,radiusX:out.z,radiusY:out.w,source:source)
  }
}

struct IOSFaceCandidate {
  let x1: Float32
  let y1: Float32
  let x2: Float32
  let y2: Float32
  let confidence: Float32
  var yaw: Float32 = 0
  var observationView: Int = 0
  var landmarks: [SIMD2<Float32>] = []
}

protocol IOSFaceLocating {
  func locateFaces(in image: CGImage) throws -> [IOSFaceCandidate]
  func locateFaceDetails(in image: CGImage, regions: [CGRect]) throws -> [IOSFaceCandidate]
}
extension IOSFaceLocating {
  func locateFaceDetails(in image: CGImage, regions: [CGRect]) throws -> [IOSFaceCandidate] { [] }
}

enum IOSFacePrivacyGeometry {
  static func faceOwnedByCurrentMask(_ face: IOSFaceCandidate, person: IOSYoloDetection,
                                    preprocess: IOSYoloPreprocessResult) -> Bool {
    IOSFrameKernelFaceGeometry.owns(face,person:person,preprocess:preprocess)
  }

  static func currentMaskSupport(_ region: IOSFacePrivacyEllipse, person: IOSYoloDetection,
                                 preprocess: IOSYoloPreprocessResult) -> (distinctPixels: Int, samples: Int) {
    IOSFrameKernelFaceGeometry.support(SIMD4(region.centerX,region.centerY,region.radiusX,region.radiusY),
      person:person,preprocess:preprocess) ?? (0,0)
  }

  static func upperBodyFaceSearchRegion(_ detection: IOSYoloDetection) -> CGRect {
    IOSFrameKernelFaceGeometry.upperBody(detection) ?? .zero
  }

  static func mergeFaceObservations(_ primary: [IOSFaceCandidate], detail: [IOSFaceCandidate],
                                    ownerOf: ((IOSFaceCandidate) -> Int?)? = nil) -> [IOSFaceCandidate] {
    let input=primary+detail
    let owners=ownerOf.map {owner in input.map {owner($0)}}
    // Retaining all observations on invalid input preserves fail-closed coverage.
    return IOSFrameKernelFaceGeometry.merge(input,primary:primary.count,owners:owners) ?? input
  }

  // All detector and current-body regions are computed in FrameKernel.
  static func detectedEllipse(_ face: IOSFaceCandidate) -> IOSFacePrivacyEllipse? {
    guard let region=IOSFrameKernelFaceGeometry.detected(face) else {return nil}
    return IOSFacePrivacyEllipse(centerX:region.x,centerY:region.y,radiusX:region.z,radiusY:region.w,source:.detectedFace)
  }

  static func fallbackGeometry(_ detection:IOSYoloDetection,preprocess:IOSYoloPreprocessResult,
      currentMask:Bool)->(region:IOSFacePrivacyEllipse,bodyMaskGuided:Bool)? {
    if let result=IOSFrameKernelFaceGeometry.currentHead(detection,preprocess:preprocess,currentMask:currentMask) {
      let r=result.region
      return (IOSFacePrivacyEllipse(centerX:r.x,centerY:r.y,radiusX:r.z,radiusY:r.w,source:.yoloHeadFallback),result.maskGuided)
    }
    // Invalid transport must not erase the existing coarse protection seed.
    guard let r=IOSFrameKernelFaceGeometry.fallback(detection) else{return nil}
    return (IOSFacePrivacyEllipse(centerX:r.x,centerY:r.y,radiusX:r.z,radiusY:r.w,source:.yoloHeadFallback),false)
  }

}

/// Privacy-first FACE_ONLY resolver for Phase 5C.
///
/// - Person identity stays owned by YOLO + IOSTemporalIdentityTracker.
/// - A face candidate is accepted only when its geometry is a clear one-to-one
///   match to an observed YOLO person.
/// - Locator failure, ambiguity, or an unobserved predicted person always falls
///   back to the current YOLO-owned head geometry instead of removing privacy.
/// - The temporal stabilizer mirrors Android's trusted-size and residual-motion
///   bounds so detector/fallback transitions do not create large sticker jumps.
final class IOSFacePrivacyTemporalResolver {
  private(set) var localizationFailed = false
  private struct TrustedFaceGeometry {
    let centerX: Float32
    let centerY: Float32
    let radiusX: Float32
    let radiusY: Float32
    let trustedPersonDetection: IOSYoloDetection
    let lastTrustedTimestampUs: Int64

    func project(personDetection: IOSYoloDetection, ageUs: Int64) -> IOSFacePrivacyEllipse? {
      guard let region = IOSFrameKernelFaceGeometry.project(personDetection,
        previous: trustedPersonDetection, seed: SIMD4(centerX, centerY, radiusX, radiusY), ageUs: ageUs) else { return nil }
      return IOSFacePrivacyEllipse(centerX: region.x, centerY: region.y,
        radiusX: region.z, radiusY: region.w, source: .predictedFace)
    }

    func occlusionPadding(personDetection: IOSYoloDetection, preprocess: IOSYoloPreprocessResult,
                          timestampUs: Int64) -> IOSFacePrivacyEllipse? {
      guard timestampUs >= lastTrustedTimestampUs,
        let region=IOSFrameKernelFaceGeometry.padding(personDetection,
          seed:SIMD4(centerX,centerY,radiusX,radiusY),age:timestampUs-lastTrustedTimestampUs,
          lease:IOSFacePrivacyTemporalResolver.trustedMaskSeedMaxAgeUs,preprocess:preprocess) else {return nil}
      return IOSFacePrivacyEllipse(centerX:region.x,centerY:region.y,radiusX:region.z,radiusY:region.w,source:.predictedFace)
    }

    func maskGuidedFallback(
      personDetection: IOSYoloDetection, preprocess: IOSYoloPreprocessResult
    ) -> IOSFacePrivacyEllipse? {
      guard let region = IOSFrameKernelFaceGeometry.trusted(personDetection.mask,
        person: personDetection, previous: trustedPersonDetection,
        seed: SIMD4(centerX, centerY, radiusX, radiusY),
        expansion: IOSFacePrivacyTemporalResolver.expiredFaceMaskFallbackExpansion,
        preprocess: preprocess) else { return nil }
      return IOSFacePrivacyEllipse(centerX: region.x, centerY: region.y,
        radiusX: region.z, radiusY: region.w, source: .yoloHeadFallback)
    }

  }

  private struct State {
    let motion: IOSFrameKernel.RegionState
    let trustedFace: TrustedFaceGeometry?
  }

  private let locator: IOSFaceLocating
  private let lock = NSLock()
  private var stateByTrackId: [Int: State] = [:]
  // Spatial privacy padding for one uniformly selected current person. This
  // never supplies an identity, owns a hero exemption, or renews itself.
  private let uniformRegionContinuation = IOSFrameKernel.RegionContinuation()
  private var singleSceneFace: TrustedFaceGeometry?
  private let classFallbackContinuity = IOSFacePrivacyClassFallbackContinuity()

  init(locator: IOSFaceLocating = IOSGridFaceLocator()) {
    self.locator = locator
  }

  func reset() {
    lock.lock()
    stateByTrackId.removeAll()
    singleSceneFace = nil
    uniformRegionContinuation.reset()
    classFallbackContinuity.reset()
    lock.unlock()
  }

  func resolve(
    image: CGImage,
    persons: [IOSPreviewPerson],
    faceOnlyIds: Set<Int>,
    preprocess: IOSYoloPreprocessResult,
    freshPrivacyClassEvidence: [IOSFreshFacePrivacyClassEvidence] = [],
    protectUnassignedFaces: Bool = false,
    unmaskedPersonId: Int? = nil,
    coveredHeadFallbackIds: Set<Int> = [],
    timestampUs: Int64
  ) throws -> [Int: IOSFacePrivacyEllipse] {
    localizationFailed = false
    let coverUnownedFaces = protectUnassignedFaces && persons.allSatisfy {
      faceOnlyIds.contains($0.id) || $0.id == unmaskedPersonId
    }
    guard !faceOnlyIds.isEmpty || coverUnownedFaces else {
      reset()
      return [:]
    }

    let selectedPersons = persons.filter { faceOnlyIds.contains($0.id) }
    guard !selectedPersons.isEmpty || !freshPrivacyClassEvidence.isEmpty || coverUnownedFaces else {
      lock.lock()
      stateByTrackId = stateByTrackId.filter { faceOnlyIds.contains($0.key) }
      lock.unlock()
      return [:]
    }

    // Detector failure only reduces localization precision. It is never allowed
    // to remove privacy or to create a new identity root.
    var faces: [IOSFaceCandidate] = []
    if !selectedPersons.isEmpty || coverUnownedFaces {
      do { faces = try locator.locateFaces(in: image) }
      catch { localizationFailed = true }
    }
    var assignments = associate(
      faces: faces,
      // Every observed YOLO person participates in ownership competition even
      // when it is not privacy-selected. Otherwise an unselected neighbor's
      // face could be incorrectly consumed as the selected target's face.
      persons: persons.filter { !$0.conservativePrivacyFallback },
      unmaskedPersonId: coverUnownedFaces ? unmaskedPersonId : nil,
      preprocess: preprocess
    )

    if coverUnownedFaces {
      let currentPeople = persons.filter { !$0.conservativePrivacyFallback }
      if !currentPeople.isEmpty {
        // A foreground body ROI can contain a smaller occluded person's face.
        // Search the hero ROI too, even when the hero already has a face: one
        // body observation is not evidence that no other visible face exists.
        var detail: [IOSFaceCandidate] = []
        var searchRegions = currentPeople.compactMap { IOSFrameKernel.faceHeadPlan($0.detection,
          width: preprocess.sourceWidth, height: preprocess.sourceHeight)?.rect }
        if faces.isEmpty, currentPeople.count == 1 {
          searchRegions.append(IOSFacePrivacyGeometry.upperBodyFaceSearchRegion(currentPeople[0].detection))
        }
        do {
          detail = try locator.locateFaceDetails(in: image, regions: searchRegions)
        } catch { localizationFailed = true }
        IOSFaceObservationRecorder.record(primary:faces,detail:detail,persons:currentPeople,
          hero:unmaskedPersonId,preprocess:preprocess,timestampUs:timestampUs)
        faces = IOSFacePrivacyGeometry.mergeFaceObservations(faces, detail: detail,
          ownerOf: { self.uniqueCurrentOwner(face: $0, persons: currentPeople,
            unmaskedPersonId: unmaskedPersonId, preprocess: preprocess) })
        assignments = associate(faces: faces, persons: currentPeople,
          unmaskedPersonId: unmaskedPersonId, preprocess: preprocess)
      }
    }

    lock.lock()
    defer { lock.unlock() }
    stateByTrackId = stateByTrackId.filter { faceOnlyIds.contains($0.key) }
    let singleScene = coverUnownedFaces && unmaskedPersonId == nil && persons.count == 1
      && selectedPersons.count == 1 && !persons[0].conservativePrivacyFallback
    if !singleScene { singleSceneFace = nil }

    var regions: [Int: IOSFacePrivacyEllipse] = [:]
    for person in selectedPersons {
      let raw: IOSFacePrivacyEllipse?
      if !person.conservativePrivacyFallback,
         let faceIndex = assignments[person.id],
         faces.indices.contains(faceIndex) {
        raw = IOSFacePrivacyGeometry.detectedEllipse(faces[faceIndex])
          ?? IOSFacePrivacyGeometry.fallbackGeometry(person.detection,preprocess:preprocess,
            currentMask:!person.conservativePrivacyFallback)?.region
        if singleScene, let raw, raw.source == .detectedFace {
          singleSceneFace = TrustedFaceGeometry(centerX: raw.centerX, centerY: raw.centerY,
            radiusX: raw.radiusX, radiusY: raw.radiusY, trustedPersonDetection: person.detection,
            lastTrustedTimestampUs: timestampUs)
        }
      } else if singleScene, let cached = singleSceneFace,
                let padding = cached.occlusionPadding(personDetection: person.detection,
                  preprocess: preprocess, timestampUs: timestampUs) {
        // History may add privacy, never remove the current body's head
        // fallback: a newly visible/moving face must not inherit an exemption.
        regions[Self.syntheticClassFallbackBase - 200_000] = padding
        raw = IOSFacePrivacyGeometry.fallbackGeometry(person.detection,preprocess:preprocess,
            currentMask:!person.conservativePrivacyFallback)?.region
      } else if let trusted = stateByTrackId[person.id]?.trustedFace,
                timestampUs >= trusted.lastTrustedTimestampUs,
                timestampUs - trusted.lastTrustedTimestampUs <= Self.maxPredictedFaceAgeUs,
                let projected = trusted.project(
                  personDetection: person.detection,
                  ageUs: timestampUs - trusted.lastTrustedTimestampUs
                ) {
        raw = projected
      } else if !person.conservativePrivacyFallback,
                let trusted = stateByTrackId[person.id]?.trustedFace,
                timestampUs >= trusted.lastTrustedTimestampUs,
                timestampUs - trusted.lastTrustedTimestampUs <= Self.trustedMaskSeedMaxAgeUs,
                let maskGuided = trusted.maskGuidedFallback(
                  personDetection: person.detection,
                  preprocess: preprocess
                ) {
        // Beyond the 150ms direct face lease, stale face geometry may only act
        // as a local seed. Current YOLO segmentation must provide the rendered
        // center; without current head-like mask support we fall through to the
        // generic current-body head fallback instead of drawing the stale face.
        raw = maskGuided
      } else {
        raw = IOSFacePrivacyGeometry.fallbackGeometry(person.detection,preprocess:preprocess,
            currentMask:!person.conservativePrivacyFallback)?.region
      }
      guard let raw else { continue }
      if raw.source == .yoloHeadFallback && coveredHeadFallbackIds.contains(person.id) { continue }
      var region = try stabilize(
        trackId: person.id,
        rawRegion: raw,
        personDetection: person.detection,
        timestampUs: timestampUs,
        personObservedThisFrame: !person.conservativePrivacyFallback
      )
      if coverUnownedFaces && unmaskedPersonId == nil {
        let kind: UInt32 = region.source == .detectedFace ? 0 : (region.source == .predictedFace ? 1 : 2)
        let d = person.detection
        let continued = try uniformRegionContinuation.step(
          raw: SIMD4(region.centerX, region.centerY, region.radiusX, region.radiusY),
          anchor: SIMD4(d.x1, d.y1, d.x2, d.y2), timestampUs: timestampUs, kind: kind)
        region = IOSFacePrivacyEllipse(centerX: continued.x, centerY: continued.y,
          radiusX: continued.z, radiusY: continued.w, source: region.source)
      } else {
        uniformRegionContinuation.reset()
      }
      if region.source == .detectedFace && !person.conservativePrivacyFallback {
        guard let covered=IOSFrameKernelFaceGeometry.coverCurrent(person.detection,
          region:SIMD4(region.centerX,region.centerY,region.radiusX,region.radiusY),preprocess:preprocess) else {
          throw NSError(domain:"FrameKernel",code:-1,
            userInfo:[NSLocalizedDescriptionKey:"Invalid current face coverage input"])
        }
        region=IOSFacePrivacyEllipse(centerX:covered.x,centerY:covered.y,
          radiusX:covered.z,radiusY:covered.w,source:region.source)
      }
      regions[person.id] = region
    }

    let activeUniqueClassOwners = Set(freshPrivacyClassEvidence.compactMap { evidence -> Int? in
      guard evidence.residualTrackIds.count == 1,
            let ownerId = evidence.residualTrackIds.first,
            faceOnlyIds.contains(ownerId) else {
        return nil
      }
      return ownerId
    })
    classFallbackContinuity.retain(ownerIds: activeUniqueClassOwners)

    var seenDetectionIndices = Set<Int>()
    for evidence in freshPrivacyClassEvidence.sorted(by: {
      $0.detectionIndex < $1.detectionIndex
    }) {
      guard seenDetectionIndices.insert(evidence.detectionIndex).inserted,
            !evidence.residualTrackIds.isEmpty,
            evidence.residualTrackIds.allSatisfy({ faceOnlyIds.contains($0) }),
            let fallback = IOSFacePrivacyGeometry.fallbackGeometry(evidence.detection,
              preprocess:preprocess,currentMask:true) else {
        continue
      }

      var region = fallback.region
      var bodyMaskGuided = fallback.bodyMaskGuided
      let uniqueOwnerId = evidence.residualTrackIds.count == 1
        ? evidence.residualTrackIds.first
        : nil
      if let ownerId = uniqueOwnerId,
         let trusted = stateByTrackId[ownerId]?.trustedFace {
        if let maskGuided = trusted.maskGuidedFallback(
          personDetection: evidence.detection,
          preprocess: preprocess
        ) {
          region = maskGuided
          bodyMaskGuided = true
        } else {
          // Exact identity is intentionally unresolved, but the privacy class
          // sidecar has reduced the owner set to one selected slot. Borrow only
          // the already trusted face size; the rendered center remains derived
          // from this fresh detection's current YOLO body geometry.
          region = IOSFacePrivacyEllipse(
            centerX: region.centerX,
            centerY: region.centerY,
            radiusX: max(1, trusted.radiusX * Self.classFallbackTrustedSizeExpansion),
            radiusY: max(1, trusted.radiusY * Self.classFallbackTrustedSizeExpansion),
            source: .yoloHeadFallback
          )
        }
      }

      if let ownerId = uniqueOwnerId {
        region = try classFallbackContinuity.stabilize(
          ownerId: ownerId,
          rawRegion: region,
          personDetection: evidence.detection,
          timestampUs: timestampUs,
          bodyMaskGuided: bodyMaskGuided
        )
      }

      // If a normal selected placement already covers this fresh head location,
      // avoid drawing a duplicate synthetic sticker. This test deliberately uses
      // the bounding box of the conservative ellipse, matching Android's source
      // placement rectangle containment check.
      let alreadyCovered = regions.values.contains { existing in
        region.centerX >= existing.centerX - existing.radiusX
          && region.centerX <= existing.centerX + existing.radiusX
          && region.centerY >= existing.centerY - existing.radiusY
          && region.centerY <= existing.centerY + existing.radiusY
      }
      guard !alreadyCovered else { continue }
      regions[Self.syntheticClassFallbackBase - evidence.detectionIndex] = region
    }
    if coverUnownedFaces {
      let exemptFaceIndex = unmaskedPersonId.flatMap { assignments[$0] }
      let coveredFaceIndices = Set(selectedPersons.compactMap { assignments[$0.id] })
      for (index, face) in faces.enumerated()
        where index != exemptFaceIndex && !coveredFaceIndices.contains(index) {
        // A visible face with no certain person owner remains protected. No
        // identity is assigned and no guessed hero bbox can exempt this face.
        if let region = IOSFacePrivacyGeometry.detectedEllipse(face) {
          regions[Self.syntheticClassFallbackBase - 100_000 - index] = region
        }
      }
    }
    return regions
  }

  private func uniqueCurrentOwner(face: IOSFaceCandidate, persons: [IOSPreviewPerson],
                                  unmaskedPersonId: Int?, preprocess: IOSYoloPreprocessResult) -> Int? {
    let scores=persons.map {person -> Float in
      if person.id != unmaskedPersonId {
        guard let plan=IOSFrameKernel.faceHeadPlan(person.detection,width:preprocess.sourceWidth,height:preprocess.sourceHeight),
          IOSFrameKernel.selectFace([face],plan:plan) != nil else {return -1}
      }
      return associationScore(face:face,person:person.detection,preprocess:preprocess,
        requireMaskSupport:person.id==unmaskedPersonId) ?? -1
    }
    guard let index=IOSFrameKernelFaceGeometry.uniqueOwner(scores),persons.indices.contains(index) else {return nil}
    return persons[index].id
  }

  private func associate(faces: [IOSFaceCandidate], persons: [IOSPreviewPerson],
    unmaskedPersonId: Int? = nil, preprocess: IOSYoloPreprocessResult? = nil) -> [Int:Int] {
    guard !faces.isEmpty,!persons.isEmpty else {return [:]}
    var scores=[Float](repeating:-1,count:persons.count*faces.count)
    var preferred=[Int32](repeating:-1,count:persons.count)
    for (personIndex,person) in persons.enumerated() {
      var options:[Int]=[]
      for faceIndex in faces.indices {
        if let score=associationScore(face:faces[faceIndex],person:person.detection,
          preprocess:preprocess,requireMaskSupport:person.id==unmaskedPersonId) {
          scores[personIndex*faces.count+faceIndex]=score;options.append(faceIndex)
        }
      }
      if person.id != unmaskedPersonId,let preprocess,
        let plan=IOSFrameKernel.faceHeadPlan(person.detection,width:preprocess.sourceWidth,height:preprocess.sourceHeight),
        let chosen=IOSFrameKernel.selectFace(options.map {faces[$0]},plan:plan) {
        preferred[personIndex]=Int32(options[chosen])
      }
    }
    let hero=unmaskedPersonId.flatMap {id in persons.firstIndex {$0.id==id}}
    guard let choices=IOSFrameKernelFaceGeometry.resolveOwners(scores,people:persons.count,
      faces:faces.count,preferred:preferred,hero:hero) else {return [:]}
    var accepted:[Int:Int]=[:]
    for (index,choice) in choices.enumerated() where choice>=0 && faces.indices.contains(Int(choice)) {
      accepted[persons[index].id]=Int(choice)
    }
    return accepted
  }

  private func associationScore(face: IOSFaceCandidate, person: IOSYoloDetection,
    preprocess: IOSYoloPreprocessResult? = nil, requireMaskSupport: Bool = false) -> Float32? {
    if let preprocess, !person.mask.isEmpty {
      guard IOSFrameKernelFaceGeometry.currentOwned(face,person:person,preprocess:preprocess) else {return nil}
    } else if requireMaskSupport { return nil }
    return IOSFrameKernelFaceGeometry.score(face,person:person)
  }

  private func stabilize(trackId: Int, rawRegion: IOSFacePrivacyEllipse,
      personDetection: IOSYoloDetection, timestampUs: Int64,
      personObservedThisFrame: Bool) throws -> IOSFacePrivacyEllipse {
    let previous=stateByTrackId[trackId]
    let motion=previous?.motion ?? IOSFrameKernel.RegionState()
    let output=try rawRegion.sharedStep(state:motion,person:personDetection,timestampUs:timestampUs,
      observed:personObservedThisFrame,trustedCenter:rawRegion.source == .detectedFace)
    let trustedFace: TrustedFaceGeometry?
    if rawRegion.source == .detectedFace {
      let reference=motion.referenceRadii
      trustedFace=TrustedFaceGeometry(centerX:output.centerX,centerY:output.centerY,
        radiusX:reference?.x ?? output.radiusX,radiusY:reference?.y ?? output.radiusY,
        trustedPersonDetection:personDetection,lastTrustedTimestampUs:timestampUs)
    } else {trustedFace=previous?.trustedFace}
    stateByTrackId[trackId]=State(motion:motion,trustedFace:trustedFace)
    return output
  }

  private static let maxPredictedFaceAgeUs: Int64 = 150_000
  private static let trustedMaskSeedMaxAgeUs: Int64 = 800_000
  private static let expiredFaceMaskFallbackExpansion: Float32 = 1.10
  private static let syntheticClassFallbackBase = -1_000_000
  private static let classFallbackTrustedSizeExpansion: Float32 = 1.24
}

extension IOSFrameKernel {
  struct FaceHeadPlan {
    let rect: CGRect
    let anchor: SIMD2<Float>
  }
  private static func faceRecord(_ face: IOSFaceCandidate) -> [Float] {
    var row: [Float] = [face.x1,face.y1,face.x2,face.y2,face.confidence,Float(min(6,face.landmarks.count))]
    for i in 0..<6 {
      row.append(i<face.landmarks.count ? face.landmarks[i].x : 0)
      row.append(i<face.landmarks.count ? face.landmarks[i].y : 0)
    }
    return row
  }
  static func faceHeadPlan(_ person: IOSYoloDetection, width: Int, height: Int) -> FaceHeadPlan? {
    guard width>1,height>1,width<=65535,height<=65535 else {return nil}
    var out=[Float](repeating:0,count:6)
    let status=out.withUnsafeMutableBufferPointer { p in
      frame_kernel_face_head_plan(FrameKernelRect(left:person.x1,top:person.y1,right:person.x2,bottom:person.y2),
        UInt32(width),UInt32(height),p.baseAddress)
    }
    guard status==0 else {return nil}
    return FaceHeadPlan(rect:CGRect(x:CGFloat(out[0]),y:CGFloat(out[1]),width:CGFloat(out[2]-out[0]),height:CGFloat(out[3]-out[1])),
      anchor:SIMD2<Float>(out[4],out[5]))
  }
  static func selectFace(_ faces: [IOSFaceCandidate], plan: FaceHeadPlan) -> Int? {
    let rows=faces.flatMap { faceRecord($0) }
    let r=plan.rect
    var distance: Float=0
    let index=rows.withUnsafeBufferPointer { p in
      frame_kernel_face_select(p.baseAddress,faces.count,
        FrameKernelRect(left:Float(r.minX),top:Float(r.minY),right:Float(r.maxX),bottom:Float(r.maxY)),
        plan.anchor.x,plan.anchor.y,0.22,0.04,&distance)
    }
    return index>=0 ? Int(index) : nil
  }

}

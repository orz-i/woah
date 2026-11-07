import Foundation
import CoreGraphics

func test(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() { fatalError("TRACKER_TEST_FAIL: " + message) }
}
let w=640, h=640
let preprocess=IOSYoloPreprocessResult(input:[],scale:1,padLeft:0,padTop:0,sourceWidth:w,sourceHeight:h,inputSize:640)
func mask(_ x1:Int=20, _ x2:Int=140) -> [UInt8] {
  var data=[UInt8](repeating:0,count:160*160)
  for y in 20..<145 {
    for x in x1..<x2 {data[y*160+x]=255}
  }
  return data
}
func det(_ x:Float32, _ mask:[UInt8], confidence:Float32=0.95) -> IOSYoloDetection {
  IOSYoloDetection(x1:x,y1:100,x2:x+60,y2:300,confidence:confidence,mask:mask)
}
let shared=mask()
func rootMetadata()->IOSAnalysisMetadata {
  IOSAnalysisMetadata(schemaVersion:1,sourceUri:"synthetic://test",persons:[
     IOSCachedPerson(id:0,bbox:IOSCachedBBox(left:100.0/640,top:100.0/640,right:160.0/640,bottom:300.0/640),confidence:0.95),
     IOSCachedPerson(id:1,bbox:IOSCachedBBox(left:160.0/640,top:100.0/640,right:220.0/640,bottom:300.0/640),confidence:0.95),
  ])
}
func makeTracker(_ full:Set<Int>, _ face:Set<Int>) -> IOSTemporalIdentityTracker {
  IOSTemporalIdentityTracker(metadata:rootMetadata(),fullBodyIds:full,faceOnlyIds:face,
    frameWidth:640,frameHeight:640)
}
func snapshot(_ tracker: IOSTemporalIdentityTracker) -> String {
  tracker.paritySnapshots().map{"\($0.id):\($0.state.rawValue):missed=\($0.missedFrames)"}.joined(separator:",")
}

// FULL_BODY-only: if two protected roots have one temporally ambiguous fresh
// detection, do NOT let that unresolved sample become a brand-new track.
let full=makeTracker([0,1],[])
_ = try full.update(detections:[det(100,shared),det(160,shared)],preprocess:preprocess,timestampUs:0)
test(Set(full.paritySnapshots().map(\.id))==[0,1],"initial full roots")
let amb=try full.update(detections:[det(130,shared)],preprocess:preprocess,timestampUs:40000)
let IDs=Set(full.paritySnapshots().map(\.id))
print("FULL_BODY_AMBIGUOUS_IDS=\(IDs.sorted()) STATE=\(snapshot(full))")
test(IDs==[0,1],"full body ambiguous detection spawned a duplicate track")
test(Set(amb.map(\.id))==[0,1] && amb.allSatisfy{$0.detection.mask.contains(255)},"both selected roots need nonempty privacy coverage")
for person in amb {test(person.conservativePrivacyFallback == !(full.paritySnapshots().first{$0.id==person.id}!.observedThisFrame),"fallback flag must agree with the canonical observation") }
let recovered=try full.update(detections:[det(100,shared),det(160,shared)],preprocess:preprocess,timestampUs:80000)
test(Set(full.paritySnapshots().map(\.id))==[0,1],"post-ambiguity ID churn")
test(Set(recovered.map(\.id))==[0,1] && recovered.allSatisfy{$0.detection.mask.contains(255)},"named roots lost their privacy coverage during separate observations")
print("FULL_BODY_REACQUIRE=PASS")

// Shared canonical FACE_ONLY ownership never transfers IDs from class evidence.
let face=makeTracker([],[0])
_ = try face.update(detections:[det(100,shared),det(160,shared)],preprocess:preprocess,timestampUs:0)
_ = try face.update(detections:[det(130,shared)],preprocess:preprocess,timestampUs:40000)
let faceIDs=Set(face.paritySnapshots().map(\.id))
print("FACE_ONLY_IDS=\(faceIDs.sorted()) STATE=\(snapshot(face))")
test(faceIDs==[0,1],"canonical face-only overlap introduced a duplicate identity")

// A non-confusable entrant on the far side must still be materialized as a
// fresh track; the fix cannot silently ignore all unmatched detections.
let entrants=makeTracker([0,1],[])
_ = try entrants.update(detections:[det(100,shared),det(160,shared)],preprocess:preprocess,timestampUs:0)
_ = try entrants.update(detections:[det(100,shared),det(160,shared),det(400,mask(2,45))],preprocess:preprocess,timestampUs:40000)
print("REAL_NEW_ENTRANT_IDS=\(Set(entrants.paritySnapshots().map(\.id)).sorted())")
test(Set(entrants.paritySnapshots().map(\.id))==[0,1,2],"real novel entrant was suppressed")

// Last-resort fail-closed is unchanged: no detections for 91 consecutive frames
// MUST still fail, with no falsely published output.
let lost=makeTracker([0],[])
_ = try lost.update(detections:[det(100,shared),det(160,shared)],preprocess:preprocess,timestampUs:0)
var failed=false
for frame in 1...91 {
  do {
    _ = try lost.update(detections:[],preprocess:preprocess,timestampUs:Int64(frame)*40000)
  } catch let err as PigeonError {
    test(frame==91 && err.code=="EXPORT_PRIVACY_UNRESOLVED", "wrong fail-closed frame or code")
    failed=true
  }
  if failed {break}
}
test(failed,"91-frame disappearance was silently allowed")
print("FAIL_CLOSED_91_EMPTY_FRAMES=PASS")

// Non-empty detections are still not proof that a protected identity was
// observed. The observed person may be the UNSELECTED hero fully occluding
// the protected person. The identity must NOT be silently rebound to that
// unselected detection, nor may its 90-frame fail-closed deadline be waived.
let heroOnly = makeTracker([0], [])
let protectedMask = mask(10, 48)
let heroMask = mask(105, 150)
_ = try heroOnly.update(
  detections: [det(100, protectedMask), det(160, heroMask)],
  preprocess: preprocess, timestampUs: 0
)
var occlusionFailed = false
for frame in 1...91 {
  do {
    let observed = try heroOnly.update(
      detections: [det(160, heroMask)], preprocess: preprocess,
      timestampUs: Int64(frame) * 40_000
    )
    if frame == 90 {
      let roots=heroOnly.paritySnapshots()
      test(roots.first(where: { $0.id==0 })?.observationAge == 90,
           "selected root deadline was reset by hero observation")
      test(observed.contains(where: { $0.id==0 && $0.conservativePrivacyFallback }),
           "lost selected root fallback was silently omitted")
      test(roots.first(where: { $0.id==1 })?.observedThisFrame == true,
           "unselected hero should remain observed")
    }
  } catch let error as PigeonError {
    test(frame==91 && error.code=="EXPORT_PRIVACY_UNRESOLVED",
         "wrong fail-closed deadline while hero remains visible")
    occlusionFailed=true
  }
  if occlusionFailed { break }
}
test(occlusionFailed,"visible hero accidentally allowed permanently lost protected root")
print("FAIL_CLOSED_91_WITH_VISIBLE_UNSELECTED_HERO=PASS")

// FACE_ONLY Android-equivalent group-follow mode also must *not* silently
// bind an invisible protected dancer's face to the visible unmasked hero.
// This is separate from the FULL_BODY-only failure above: the previous iOS
// 22-case fixture accidentally tested FULL_BODY under the Android FACE_ONLY
// long-follow case name.
let faceLost = makeTracker([], [0])
_ = try faceLost.update(
  detections: [det(100, protectedMask), det(160, heroMask)],
  preprocess: preprocess, timestampUs: 0
)
var faceFailed = false
for frame in 1...91 {
  do {
    let observed = try faceLost.update(
      detections: [det(160, heroMask)],preprocess: preprocess,
      timestampUs: Int64(frame) * 40_000
    )
    if frame == 90 {
      let snapshots = faceLost.paritySnapshots()
      test(snapshots.first(where: { $0.id == 0 })?.observationAge == 90,
           "FACE_ONLY protected target missing deadline was reset by visible hero")
      test(observed.contains(where: { $0.id == 0 && $0.conservativePrivacyFallback }),
           "FACE_ONLY missing protected root fallback was not returned")
      test(snapshots.first(where: { $0.id == 1 })?.observedThisFrame == true,
           "FACE_ONLY unselected hero stopped being observed")
    }
  } catch let error as PigeonError {
    test(frame == 91 && error.code == "EXPORT_PRIVACY_UNRESOLVED",
         "FACE_ONLY visible hero incorrectly revived protected target")
    faceFailed = true
  }
  if faceFailed { break }
}
test(faceFailed, "FACE_ONLY permanently missing root was improperly allowed")
print("FACE_ONLY_FAIL_CLOSED_91_WITH_VISIBLE_HERO=PASS")
// A moving selected root can disappear near the source-frame edge. Its bounded
// predicted fallback may become completely off-frame long before the 90-frame
// unresolved deadline. Without an independent offscreen-exit proof, silently
// dropping that fallback would encode frames with no protected coverage.
// Both FACE_ONLY and FULL_BODY must fail closed on the first such frame.
for faceMode in [false, true] {
  let nearEdgeRoot=IOSAnalysisMetadata(
    schemaVersion:1,sourceUri:"synthetic://offscreen-privacy-unknown",
    persons:[IOSCachedPerson(id:0,bbox:IOSCachedBBox(
      left:570.0/640,top:100.0/640,right:620.0/640,bottom:300.0/640
    ),confidence:0.99)]
  )
  let edge=IOSTemporalIdentityTracker(
    metadata:nearEdgeRoot,
    fullBodyIds:faceMode ? [] : [0],
    faceOnlyIds:faceMode ? [0] : [],
    frameWidth:w,frameHeight:h
  )
  let edgeMask=mask(12,45)
  let visibleHero=det(200,mask(90,120))
  let first=IOSYoloDetection(
    x1:570,y1:100,x2:620,y2:300,confidence:0.99,mask:edgeMask
  )
  let outbound=IOSYoloDetection(
    x1:590,y1:100,x2:640,y2:300,confidence:0.99,mask:edgeMask
  )
  _ = try edge.update(detections:[first,visibleHero],preprocess:preprocess,timestampUs:0)
  let reobserved = try edge.update(
    detections:[outbound,visibleHero],preprocess:preprocess,timestampUs:40_000
  )
  test(reobserved.contains(where: { $0.id==0 && !$0.conservativePrivacyFallback }),
       "edge target did not establish observed outward motion")
  test(reobserved.contains(where: { $0.id==1 && !$0.conservativePrivacyFallback }),
       "unselected hero did not retain its distinct observed identity")
  var earlyFailClosedAt: Int? = nil
  for missed in 1...90 {
    do {
      let persons = try edge.update(
        detections:[visibleHero],preprocess:preprocess,
        timestampUs:Int64(missed+1)*40_000
      )
      test(persons.contains(where: { $0.id==1 && !$0.conservativePrivacyFallback }),
           "visible hero was incorrectly assigned or removed")
      test(persons.contains(where: { $0.id==0 && $0.conservativePrivacyFallback }),
           "unobserved protected root silently disappeared before fail-closed, mode=\(faceMode), missed=\(missed)")
    } catch let error as PigeonError {
      test(error.code=="EXPORT_PRIVACY_UNRESOLVED" && missed<91,
           "wrong privacy guard on offscreen fallback")
      earlyFailClosedAt=missed
      break
    }
  }
  test(earlyFailClosedAt != nil,
       "selected offscreen fallback was allowed to be omitted for 90 frames")
  print("OFFSCREEN_PROTECTED_FALLBACK_FAIL_CLOSED=PASS mode=\(faceMode ? "FACE_ONLY" : "FULL_BODY") missed=\(earlyFailClosedAt!)")
}

test(IOSPrivacySelectionScope.coversAllOtherFaces(metadata:rootMetadata(),fullBodyIds:[],faceOnlyIds:[0],followTargetId:1),"all-other scope rejected")
test(!IOSPrivacySelectionScope.coversAllOtherFaces(metadata:rootMetadata(),fullBodyIds:[],faceOnlyIds:[0],followTargetId:nil),"no-follow scope was widened")
test(!IOSPrivacySelectionScope.coversAllOtherFaces(metadata:rootMetadata(),fullBodyIds:[0],faceOnlyIds:[],followTargetId:1),"body scope was widened")
test(!IOSPrivacySelectionScope.coversAllOtherFaces(metadata:rootMetadata(),fullBodyIds:[],faceOnlyIds:[0,1],followTargetId:1),"hero-selected scope was widened")
let sceneTracker=IOSTemporalIdentityTracker(metadata:rootMetadata(),fullBodyIds:[],faceOnlyIds:[0],
  frameWidth:640,frameHeight:640,followTargetId:1,usesCurrentSceneFaceCoverage:true)
_ = try sceneTracker.update(detections:[det(100,protectedMask),det(160,heroMask)],preprocess:preprocess,timestampUs:0)
for frame in 1...100 {
  _ = try sceneTracker.update(detections:[det(160,heroMask)],preprocess:preprocess,timestampUs:Int64(frame)*40000)
}
let lostSceneRoot=sceneTracker.paritySnapshots().first{$0.id==0}!
test(!lostSceneRoot.observedThisFrame && lostSceneRoot.observationAge==100 && lostSceneRoot.state == .lost,
     "scene coverage fabricated protected identity recovery")
test(sceneTracker.paritySnapshots().first{$0.id==1}?.observedThisFrame==true,"scene hero identity changed")
let entrantScene=try sceneTracker.update(detections:[det(160,heroMask),det(400,mask(2,45))],preprocess:preprocess,timestampUs:4040000)
test(entrantScene.contains{$0.id==2},"new entrant was renamed to the lost selected root")
let current=try IOSPrivacySelectionScope.currentPersons(detections:[det(160,heroMask),det(400,mask(2,45))],
  heroId:1,observedHeroBounds:sceneTracker.observedBounds(for:1),frameIndex:101)
test(current[0].id==1 && current[1].id<0,"current scene exemption or anonymous entrant")
let missingHero=try IOSPrivacySelectionScope.currentPersons(detections:[det(400,mask(2,45))],
  heroId:1,observedHeroBounds:nil,frameIndex:102)
test(missingHero.allSatisfy{$0.id<0},"missing hero gained a proxy exemption")
let duplicateHero=try IOSPrivacySelectionScope.currentPersons(detections:[det(160,heroMask),det(160,heroMask)],
  heroId:1,observedHeroBounds:SIMD4<Float>(160,100,220,300),frameIndex:103)
test(duplicateHero.allSatisfy{$0.id<0},"ambiguous hero observation granted an exemption")
print("ALL_OTHER_FACES_NO_ID_REBIND_OR_PROXY_EXEMPTION=PASS")

final class TestFaceLocator: IOSFaceLocating {
  let faces:[IOSFaceCandidate]
  init(_ faces:[IOSFaceCandidate]) {self.faces=faces}
  func locateFaces(in image:CGImage) throws -> [IOSFaceCandidate] {faces}
}
let colorSpace=CGColorSpaceCreateDeviceRGB()
let context=CGContext(data:nil,width:64,height:64,bitsPerComponent:8,bytesPerRow:256,space:colorSpace,
  bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue)!
let image=context.makeImage()!
let smallPrep=IOSYoloPreprocessResult(input:[],scale:10,padLeft:0,padTop:0,sourceWidth:64,sourceHeight:64,inputSize:640)
func box(_ x1:Float32,_ y1:Float32,_ x2:Float32,_ y2:Float32)->IOSYoloDetection {
  IOSYoloDetection(x1:x1,y1:y1,x2:x2,y2:y2,confidence:0.99,mask:shared)
}
func faceBox(_ x1:Float32,_ x2:Float32)->IOSFaceCandidate {
  IOSFaceCandidate(x1:x1,y1:8,x2:x2,y2:20,confidence:0.99)
}
let left=IOSPreviewPerson(id:0,detection:box(8,4,28,60))
let heroPerson=IOSPreviewPerson(id:1,detection:box(44,4,62,60))
let faceRegions=try IOSFacePrivacyTemporalResolver(locator:TestFaceLocator([faceBox(14,22),faceBox(32,40),faceBox(50,58)]))
  .resolve(image:image,persons:[left,heroPerson],faceOnlyIds:[0],preprocess:smallPrep,
           protectUnassignedFaces:true,unmaskedPersonId:1,timestampUs:0)
test(faceRegions.values.contains{abs($0.centerX-36)<0.1 && $0.source == .detectedFace},"unowned visible face was exposed")
test(!faceRegions.values.contains{abs($0.centerX-54)<0.1},"clear current hero face was masked")
let overlapping=IOSPreviewPerson(id:0,detection:box(20,4,44,60))
let overlappingHero=IOSPreviewPerson(id:1,detection:overlapping.detection)
let ambiguousRegions=try IOSFacePrivacyTemporalResolver(locator:TestFaceLocator([faceBox(27,37)]))
  .resolve(image:image,persons:[overlapping,overlappingHero],faceOnlyIds:[0],preprocess:smallPrep,
           protectUnassignedFaces:true,unmaskedPersonId:1,timestampUs:0)
test(ambiguousRegions.values.contains{$0.source == .detectedFace && abs($0.centerX-32)<0.1},
     "ambiguous hero/selected face was exempted")
let trustedWins=try IOSFacePrivacyTemporalResolver(locator:TestFaceLocator([faceBox(14,22)]))
  .resolve(image:image,persons:[left],faceOnlyIds:[0],preprocess:smallPrep,
           coveredHeadFallbackIds:[0],timestampUs:0)
test(trustedWins[0]?.source == .detectedFace,"occlusion hint suppressed a real detected face")
let hiddenGuess=try IOSFacePrivacyTemporalResolver(locator:TestFaceLocator([]))
  .resolve(image:image,persons:[left],faceOnlyIds:[0],preprocess:smallPrep,
           coveredHeadFallbackIds:[0],timestampUs:0)
test(hiddenGuess[0]==nil,"fully covered guessed head remained visible")
test(IOSPrivacySelectionScope.compositorFaceIds(selected:[0],coveredHeadFallbackIds:[0],explicitFaceRegionIds:[]).isEmpty,"renderer recreated suppressed head fallback")
test(IOSPrivacySelectionScope.compositorFaceIds(selected:[0],coveredHeadFallbackIds:[0],explicitFaceRegionIds:[0])==[0],"current detected face lost to occlusion hint")
print("UNOWNED_AMBIGUOUS_FACES_PROTECTED_AND_REAL_FACE_WINS=PASS")


let lowConfidenceMetadata=IOSAnalysisMetadata(schemaVersion:1,sourceUri:"synthetic://low-confidence",persons:
  rootMetadata().persons.map { IOSCachedPerson(id:$0.id,bbox:$0.bbox,confidence:$0.id==1 ? 0.30 : $0.confidence) })
let lowHero=IOSTemporalIdentityTracker(metadata:lowConfidenceMetadata,fullBodyIds:[],faceOnlyIds:[0],
  frameWidth:640,frameHeight:640,followTargetId:1,usesCurrentSceneFaceCoverage:true)
_ = try lowHero.update(detections:[det(100,protectedMask),det(160,heroMask)],preprocess:preprocess,timestampUs:0)
test(lowHero.paritySnapshots().first{$0.id==1}?.identityProtected == true,"low-confidence hero exemption used weak association")
final class TestDetailFaceLocator: IOSFaceLocating {
  func locateFaces(in image:CGImage) throws -> [IOSFaceCandidate] {[]}
  func locateFaceDetails(in image:CGImage,regions:[CGRect]) throws -> [IOSFaceCandidate] {
    test(!regions.isEmpty,"missing current body ROI")
    return [faceBox(14,22)]
  }
}
let detailFaces=try IOSFacePrivacyTemporalResolver(locator:TestDetailFaceLocator())
  .resolve(image:image,persons:[left],faceOnlyIds:[0],preprocess:smallPrep,
           protectUnassignedFaces:true,timestampUs:0)
test(detailFaces[0]?.source == .detectedFace,"small face ROI did not refine the current head")
var duplicateFace=faceBox(14.2,21.8);duplicateFace.observationView=1
var distinctFace=faceBox(32,40);distinctFace.observationView=1
let mergedFaces=IOSFacePrivacyGeometry.mergeFaceObservations([faceBox(14,22)],detail:[duplicateFace,distinctFace])
test(mergedFaces.count==2 && mergedFaces[0].x1==14,"face ROI duplicate displaced stable scene observation")
let sameViewFaces=IOSFacePrivacyGeometry.mergeFaceObservations([],detail:[faceBox(14,22),faceBox(15,23)],ownerOf:{_ in 1})
test(sameViewFaces.count==2,"two faces from one observation were collapsed into a hero exemption")
var otherOwnerFace=faceBox(15,23);otherOwnerFace.observationView=2
let differentOwners=IOSFacePrivacyGeometry.mergeFaceObservations([faceBox(14,22)],detail:[otherOwnerFace],ownerOf:{$0.observationView==0 ? 1:2})
test(differentOwners.count==2,"neighbor faces were merged across different owners")
var profileLeft=faceBox(14,22);profileLeft.yaw = -Float32.pi/4
var profileRight=profileLeft;profileRight.yaw = Float32.pi/4
let leftRegion=IOSFacePrivacyGeometry.detectedEllipse(profileLeft)!
let rightRegion=IOSFacePrivacyGeometry.detectedEllipse(profileRight)!
test(leftRegion.centerX>18 && rightRegion.centerX<18 && leftRegion.centerY>13.52,"profile face did not include the hidden cheek/chin side")
test(leftRegion.radiusX>6.6 && leftRegion.radiusY>8.88,"profile face retained a frontal-feature-sized mask")
print("SMALL_FACE_DETAIL_AND_STRICT_HERO_EXEMPTION=PASS")


var heroInstanceMask=shared
for y in 15..<58 {for x in 65..<95 {heroInstanceMask[y*160+x]=0}}
let heroWithFaceHole=IOSPreviewPerson(id:1,detection:IOSYoloDetection(x1:8,y1:4,x2:62,y2:60,
  confidence:0.99,mask:heroInstanceMask))
let faceInsideHeroBox=try IOSFacePrivacyTemporalResolver(locator:TestFaceLocator([faceBox(27,37),faceBox(50,58)]))
  .resolve(image:image,persons:[heroWithFaceHole],faceOnlyIds:[],preprocess:smallPrep,
           protectUnassignedFaces:true,unmaskedPersonId:1,timestampUs:0)
test(faceInsideHeroBox.values.contains{abs($0.centerX-32)<0.1},
     "an unknown face inside the hero bbox inherited the hero exemption")
test(!faceInsideHeroBox.values.contains{abs($0.centerX-54)<0.1},
     "the current mask-supported hero face was unnecessarily protected")
final class TestFailingFaceLocator: IOSFaceLocating {
  let failGlobal:Bool
  init(_ failGlobal:Bool) {self.failGlobal=failGlobal}
  func locateFaces(in image:CGImage) throws -> [IOSFaceCandidate] {
    if failGlobal {throw NSError(domain:"QA_FACE_MODEL",code:1)}
    return []
  }
  func locateFaceDetails(in image:CGImage,regions:[CGRect]) throws -> [IOSFaceCandidate] {
    if !failGlobal {throw NSError(domain:"QA_FACE_MODEL",code:2)}
    return []
  }
}
for global in [true,false] {
  let failedResolver=IOSFacePrivacyTemporalResolver(locator:TestFailingFaceLocator(global))
  _=try failedResolver.resolve(image:image,persons:[left],faceOnlyIds:[0],preprocess:smallPrep,
                           protectUnassignedFaces:true,timestampUs:0)
  test(failedResolver.localizationFailed,"current-scene localization failure was silently accepted")
}
print("HERO_FACE_REQUIRES_CURRENT_INSTANCE_PIXELS_AND_LOCALIZATION_SUCCESS=PASS")

final class MovingFaceLocator: IOSFaceLocating {
  var faces=[faceBox(14,22)]
  func locateFaces(in image:CGImage) throws -> [IOSFaceCandidate] {faces}
}
let movingLocator=MovingFaceLocator()
let movingResolver=IOSFacePrivacyTemporalResolver(locator:movingLocator)
// The current body moves with the detected face; both inputs satisfy the shared head gate.
let widePerson=IOSPreviewPerson(id:0,detection:box(4,4,32,60))
let movedPerson=IOSPreviewPerson(id:0,detection:box(24,4,62,60))
_=try movingResolver.resolve(image:image,persons:[widePerson],faceOnlyIds:[0],preprocess:smallPrep,timestampUs:0)
movingLocator.faces=[faceBox(42,58)]
let moved=try movingResolver.resolve(image:image,persons:[movedPerson],faceOnlyIds:[0],preprocess:smallPrep,timestampUs:40_000)[0]!
let currentFace=IOSFacePrivacyGeometry.detectedEllipse(movingLocator.faces[0])!
test(abs(moved.centerX-currentFace.centerX)<0.001 && abs(moved.centerY-currentFace.centerY)<0.001,
     "current detected face was delayed by the old fallback position")
test(moved.radiusX>=currentFace.radiusX && moved.radiusY>=currentFace.radiusY,
     "smoothing shrank current detected face coverage")
let raisedArmBody=box(125.8871,403.623,1461.6781,3440.3723)
let recoveryROI=IOSFacePrivacyGeometry.upperBodyFaceSearchRegion(raisedArmBody)
test(recoveryROI.contains(CGPoint(x:1213,y:1177)),"raised-arm recovery ROI omitted the actual face")
test(recoveryROI.width<IOSFacePrivacyGeometry.faceSearchRegion(raisedArmBody).width,
     "raised-arm recovery did not restore spatial detail")
print("CURRENT_DETECTED_FACE_NO_LAG_AND_RAISED_ARM_DETAIL=PASS")

let paddingLocator=MovingFaceLocator()
let paddingResolver=IOSFacePrivacyTemporalResolver(locator:paddingLocator)
let firstPrivacyPerson=IOSPreviewPerson(id:-2_000_000,detection:left.detection)
_=try paddingResolver.resolve(image:image,persons:[firstPrivacyPerson],faceOnlyIds:[firstPrivacyPerson.id],preprocess:smallPrep,
  protectUnassignedFaces:true,timestampUs:0)
paddingLocator.faces=[]
let armPerson=IOSPreviewPerson(id:-2_001_000,detection:box(2,0,62,60))
let paddedRegions=try paddingResolver.resolve(image:image,persons:[armPerson],faceOnlyIds:[armPerson.id],preprocess:smallPrep,
  protectUnassignedFaces:true,timestampUs:400_000)
let padded=paddedRegions.values.first{$0.source == .predictedFace}!
test(padded.source == .predictedFace && abs(padded.centerX-18)<0.001,"raised bbox dragged the prior observed face onto an arm")
let currentHead=IOSFacePrivacyGeometry.fallbackEllipse(armPerson.detection)!
let paddingDidNotRemoveCurrentHead=paddedRegions.values.contains { region in
  pow((currentHead.centerX-region.centerX)/region.radiusX,2)
    + pow((currentHead.centerY-region.centerY)/region.radiusY,2)<=1
}
test(paddingDidNotRemoveCurrentHead,"spatial padding removed current head coverage instead of adding protection")
let expiredPadding=try paddingResolver.resolve(image:image,persons:[armPerson],faceOnlyIds:[armPerson.id],preprocess:smallPrep,
  protectUnassignedFaces:true,timestampUs:800_001)
test(!expiredPadding.values.contains{$0.source == .predictedFace},"spatial padding renewed its own face lease")
paddingLocator.faces=[faceBox(14,22)]
_=try paddingResolver.resolve(image:image,persons:[firstPrivacyPerson],faceOnlyIds:[firstPrivacyPerson.id],preprocess:smallPrep,
  protectUnassignedFaces:true,timestampUs:1_000_000)
paddingLocator.faces=[]
let unsupportedArm=IOSPreviewPerson(id:-2_002_000,detection:IOSYoloDetection(x1:2,y1:0,x2:62,y2:60,confidence:0.99,
  mask:[UInt8](repeating:0,count:160*160)))
let rejectedPadding=try paddingResolver.resolve(image:image,persons:[unsupportedArm],faceOnlyIds:[unsupportedArm.id],preprocess:smallPrep,
  protectUnassignedFaces:true,timestampUs:1_040_000)
test(!rejectedPadding.values.contains{$0.source == .predictedFace},"padding survived without current instance support")
_=try paddingResolver.resolve(image:image,persons:[armPerson,heroPerson],faceOnlyIds:[armPerson.id,heroPerson.id],preprocess:smallPrep,
  protectUnassignedFaces:true,timestampUs:1_080_000)
let resetPadding=try paddingResolver.resolve(image:image,persons:[armPerson],faceOnlyIds:[armPerson.id],preprocess:smallPrep,
  protectUnassignedFaces:true,timestampUs:1_120_000)
test(!resetPadding.values.contains{$0.source == .predictedFace},"single-person padding crossed a multi-person scope boundary")
print("SINGLE_SCENE_OCCLUSION_PADDING_CURRENT_MASK_EXPIRY_AND_SCOPE=PASS")

// Bound to the actual source observations at 22.16/22.24 seconds in raised-arm footage.
let realArmBody=box(152.49,430.80,1942.18,3455.41)
let realArmPlan=IOSFrameKernel.faceHeadPlan(realArmBody,width:2160,height:4096)!
let waistFalseFace=IOSFaceCandidate(x1:900.16,y1:1814.05,x2:1100.16,y2:2014.05,confidence:0.99)
test(IOSFrameKernel.selectFace([waistFalseFace],plan:realArmPlan)==nil,"waist false positive replaced current head fallback")
let validArmFace=IOSFaceCandidate(x1:1162.10,y1:1077.52,x2:1362.10,y2:1277.52,confidence:0.55)
test(IOSFrameKernel.selectFace([waistFalseFace,validArmFace],plan:realArmPlan)==1,"valid head lost to confident waist detection")
let realHandPlan=IOSFrameKernel.faceHeadPlan(box(172.46,637.08,2103.70,3438.19),width:2160,height:4096)!
let handFalseFace=IOSFaceCandidate(x1:1399.36,y1:661.19,x2:1599.36,y2:861.19,confidence:0.99)
test(IOSFrameKernel.selectFace([handFalseFace],plan:realHandPlan)==nil,"raised hand replaced current head fallback")
print("SHARED_HEAD_OWNERSHIP_RAISED_ARM_WAIST_AND_HAND=PASS")

print("IOS_TRACKER_HOST_REGRESSION=PASS")

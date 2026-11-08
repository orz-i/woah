import Foundation
import CoreGraphics
import CryptoKit
import Darwin
setbuf(stdout,nil)
struct Reader {
  let bytes:[UInt8];var at=0
  mutating func u()->UInt32 {let p=at;at+=4;return UInt32(bytes[p])<<24|UInt32(bytes[p+1])<<16|UInt32(bytes[p+2])<<8|UInt32(bytes[p+3])}
  mutating func f()->Float {Float(bitPattern:u())}
  mutating func q()->Int64 {let high=UInt64(u()),low=UInt64(u());return Int64(bitPattern:(high<<32)|low)}
  mutating func flag()->Bool {let b=bytes[at];at+=1;return b != 0}
  mutating func box()->SIMD4<Float> {SIMD4(f(),f(),f(),f())}
  mutating func region(_ kind:UInt32)->[UInt32]? {
    if !flag(){return nil}
    let value=(0..<4).map{_ in u()};precondition(u()==kind);return value
  }
}
func bits(_ value:SIMD4<Float>?)->[UInt32]? {value.map{[$0.x.bitPattern,$0.y.bitPattern,$0.z.bitPattern,$0.w.bitPattern]}}
func person(_ box:SIMD4<Float>,_ mask:[UInt8]=[])->IOSYoloDetection {
  IOSYoloDetection(x1:box.x,y1:box.y,x2:box.z,y2:box.w,confidence:0.9,mask:mask)
}
func observationSamples(_ face:IOSFaceCandidate,index:Int)->[IOSFaceCandidate] {
  let w=face.x2-face.x1,h=face.y2-face.y1
  let offsets:[(Float,Float,Float,Int)]=[(0,0,1,0),(3*w,0,1,0),(w*0.01,h*0.01,1.1,index%4),(0,0,1,0),(3*w,w*0.01,1.05,2)]
  return offsets.enumerated().map { i,v in
    IOSFaceCandidate(x1:face.x1+v.0,y1:face.y1+v.1,x2:face.x1+v.0+w*v.2,y2:face.y1+v.1+h*v.2,confidence:Float(i+1)/10,observationView:v.3)
  }
}
func observationOwner(_ face:IOSFaceCandidate)->Int? {face.observationView==3 ? nil:(face.confidence<0.25 ? 10:20)}
func observationIndices(_ output:[IOSFaceCandidate],in input:[IOSFaceCandidate])->[Int] {
  output.map { f in input.firstIndex { $0.confidence.bitPattern==f.confidence.bitPattern }! }
}
let data=try Data(contentsOf:URL(fileURLWithPath:"crates/frame-kernel/testdata/face-region-native-v1.bin"))
let sha=SHA256.hash(data:data).map{String(format:"%02x",$0)}.joined()
precondition(sha=="e0e75f439552d6e7599c73f1020dd1d1d7f5323db0db26a4628bf10035b96298")
var r=Reader(bytes:Array(data));precondition(r.u()==0x464B4731);precondition(r.u()==512)
for index in 0..<512 {
  let width=Int(r.u()),height=Int(r.u()),body=r.box(),view=r.box(),size=Float(r.u()),bounds=r.box(),confidence=r.f()
  var points:[SIMD2<Float>]=[];for _ in 0..<r.u(){points.append(SIMD2(r.f(),r.f()))}
  let face=IOSFaceCandidate(x1:bounds.x,y1:bounds.y,x2:bounds.z,y2:bounds.w,confidence:confidence,landmarks:points)
  let source=CGRect(x:CGFloat(view.x),y:CGFloat(view.y),width:CGFloat(view.z)-CGFloat(view.x),height:CGFloat(view.w)-CGFloat(view.y))
  precondition(bits(IOSFrameKernelFaceGeometry.detected(face,view:source,outputSize:size))==r.region(0),"detected case\(index)")
  precondition(bits(IOSFrameKernelFaceGeometry.fallback(person(body)))==r.region(2),"fallback case\(index)")
  let scale=r.f(),left=r.f(),top=r.f(),seed=r.box()
  let pre=IOSYoloPreprocessResult(input:[],scale:scale,padLeft:left,padTop:top,sourceWidth:width,sourceHeight:height,inputSize:640)
  var pixels:[UInt8]=[]
  for _ in 0..<r.u(){let count=Int(r.u()),value=r.bytes[r.at];r.at+=1;pixels.append(contentsOf:repeatElement(value,count:count))}
  precondition(pixels.count==25600)
  let expected=r.flag() ? [r.u(),r.u()]:nil
  let current=person(body,pixels)
  let region=IOSFacePrivacyEllipse(centerX:seed.x,centerY:seed.y,radiusX:seed.z,radiusY:seed.w,source:.detectedFace)
  let oldSupport=CanonicalObservationGeometry.currentMaskSupport(region,person:current,preprocess:pre)
  let productionSupport=IOSFacePrivacyGeometry.currentMaskSupport(region,person:current,preprocess:pre)
  precondition(oldSupport==productionSupport,"native support baseline case\(index)")
  guard let sharedSupport=IOSFrameKernelFaceGeometry.support(seed,person:current,preprocess:pre) else {fatalError("missing shared support case\(index)")}
  precondition(sharedSupport==oldSupport,"shared support case\(index)")
  precondition(IOSFrameKernelFaceGeometry.upperBody(current)==CanonicalObservationGeometry.upperBodyFaceSearchRegion(current),"shared search case\(index)")
  let global=IOSFaceCandidate(x1:view.x+face.x1/size*(view.z-view.x),y1:view.y+face.y1/size*(view.w-view.y),
    x2:view.x+face.x2/size*(view.z-view.x),y2:view.y+face.y2/size*(view.w-view.y),confidence:face.confidence)
  precondition(IOSFrameKernelFaceGeometry.score(global,person:current)?.bitPattern==CanonicalObservationGeometry.associationScore(face:global,person:current)?.bitPattern,"shared score case\(index)")
  precondition(IOSFrameKernelFaceGeometry.owns(global,person:current,preprocess:pre)==CanonicalFaceOwnershipEvidence.owned(global,person:current,preprocess:pre),"original ownership window case\(index)")

  let age=Int64(index%13)*80_000
  var expectedPadding:SIMD4<Float>?
  if age<=800_000,seed.x>=current.x1,seed.x<=current.x2,seed.y>=current.y1,seed.y<=current.y2 {
    let progress=Float(age)/800_000
    let padded=IOSFacePrivacyEllipse(centerX:seed.x,centerY:seed.y,radiusX:seed.z*(1+progress*0.8),radiusY:seed.w*(1+progress*0.8),source:.predictedFace)
    let support=CanonicalObservationGeometry.currentMaskSupport(padded,person:current,preprocess:pre)
    if support.distinctPixels>=4&&support.samples>=4 {expectedPadding=SIMD4(padded.centerX,padded.centerY,padded.radiusX,padded.radiusY)}
  }
  precondition(bits(IOSFrameKernelFaceGeometry.padding(current,seed:seed,age:age,lease:800_000,preprocess:pre))==bits(expectedPadding),"shared padding case\(index)")

  precondition(CanonicalObservationGeometry.upperBodyFaceSearchRegion(current)==IOSFacePrivacyGeometry.upperBodyFaceSearchRegion(current),"native search baseline case\(index)")
  let samples=observationSamples(face,index:index)
  let owner:((IOSFaceCandidate)->Int?)?=index%2==0 ? observationOwner:nil
  let oldMerge=CanonicalObservationGeometry.mergeFaceObservations(Array(samples.prefix(2)),detail:Array(samples.dropFirst(2)),ownerOf:owner)
  let productionMerge=IOSFacePrivacyGeometry.mergeFaceObservations(Array(samples.prefix(2)),detail:Array(samples.dropFirst(2)),ownerOf:owner)
  precondition(observationIndices(oldMerge,in:samples)==observationIndices(productionMerge,in:samples),"native merge baseline case\(index)")
  let merged=IOSFrameKernelFaceGeometry.merge(samples,primary:2,owners:owner.map {fn in samples.map {fn($0)}})
  precondition(merged != nil && observationIndices(merged!,in:samples)==observationIndices(oldMerge,in:samples),"shared merge case\(index)")


  let head=IOSFrameKernelFaceGeometry.estimate(pixels,person:current,seed:seed,preprocess:pre)
  precondition(head.map{[$0.x.bitPattern,$0.y.bitPattern]}==expected,"head case\(index)")
  guard let complete=IOSFrameKernelFaceGeometry.currentHead(current,preprocess:pre,seed:seed,currentMask:true) else {fatalError("missing complete head case\(index)")}
  precondition(complete.maskGuided==(expected != nil),"complete evidence case\(index)")
  if let expected {
    precondition([complete.region.x.bitPattern,complete.region.y.bitPattern]==expected,"complete head case\(index)")
  }else {precondition(bits(complete.region)==bits(seed),"complete conservative seed case\(index)")}
  precondition(complete.region.z.bitPattern==seed.z.bitPattern&&complete.region.w.bitPattern==seed.w.bitPattern)

  precondition(bits(IOSFrameKernelFaceGeometry.trusted(pixels,person:current,previous:current,seed:seed,expansion:1.10,preprocess:pre))==r.region(2),"trusted case\(index)")
}
// Original Swift projection arithmetic retained only in this conformance test.
let ages:[Int64]=[-1,0,1,75_000,149_999,150_000,800_000,Int64.max]
let scales:[Float]=[0.12,0.50,0.88,1,1.12,1.5,3,10]
for i in 0..<512 {
  let left=Float(i%17)*31.25,top=Float(i%13)*12.5
  let old=person(SIMD4(left,top,left+80+Float(i%109),top+240+Float(i%337)))
  let dx=Float(i%9-4)*4.25,dy=Float(i%7-3)*5.25,scale=scales[i%8]
  let current=person(SIMD4(old.x1+dx,old.y1+dy,old.x2+dx+(old.x2-old.x1)*(scale-1),old.y2+dy+(old.y2-old.y1)*(scale-1)))
  let seed=SIMD4((old.x1+old.x2)*0.5,old.y1+(old.y2-old.y1)*0.14,8+Float(i%33),12+Float(i%55))
  let age=ages[i%8]
  let wr=max(0.1,(current.x2-current.x1)/max(1,old.x2-old.x1))
  let hr=max(0.1,(current.y2-current.y1)/max(1,old.y2-old.y1))
  let bounded=min(Float(1.12),max(Float(0.88),sqrt(wr*hr)))
  let expansion=1+Float(min(Int64(150_000),max(Int64(0),age)))/150_000*Float(0.10)
  let delta=IOSPersonBboxMotionEstimator.estimate(previous:old,current:current)
  let expected=SIMD4(seed.x+delta.dx,seed.y+delta.dy,max(1,seed.z*bounded*expansion),max(1,seed.w*bounded*expansion))
  precondition(bits(IOSFrameKernelFaceGeometry.project(current,previous:old,seed:seed,ageUs:age))==bits(expected),"projection case\(i)")
}
print("TRUSTED_REGION_PROJECTION_NATIVE=PASS cases=512")
precondition(r.at==r.bytes.count)
precondition(IOSFrameKernelFaceGeometry.uniqueOwner([0.9,0.89])==nil)
precondition(IOSFrameKernelFaceGeometry.uniqueOwner([0.9,0.1])==0)
precondition(IOSFrameKernelFaceGeometry.resolveOwners([0.9,0.89,0.2,0.8],people:2,faces:2,preferred:[0,1],hero:0)==[-1,-1])
precondition(IOSFrameKernelFaceGeometry.resolveOwners([0.9,0.1,0.2,0.8],people:2,faces:2,preferred:[0,1],hero:0)==[0,1])
print("SHARED_OBSERVATION_GEOMETRY_NATIVE=PASS cases=512")
print("OBSERVATION_GEOMETRY_NATIVE_BASELINE=PASS cases=512")
print("SHARED_FACE_GEOMETRY_NATIVE_CORPUS=PASS cases=512 sha256=\(sha)")

// Actual first full4K top-edge failure, frozen before the production caller switch.
let edgeData=try Data(contentsOf:URL(fileURLWithPath:"crates/frame-kernel/testdata/face-edge-ios-185-v1.bin"))
precondition(SHA256.hash(data:edgeData).map{String(format:"%02x",$0)}.joined()=="15158a59608780bc1a8eb0660ff60da19a3d3b1cc7bb7995b4d309cf3e45e2b7")
var e=Reader(bytes:Array(edgeData));precondition(e.u()==0x464b4531)
let ew=Int(e.u()),eh=Int(e.u()),input=Int(e.u()),proto=Int(e.u())
let es=e.f(),el=e.f(),et=e.f();precondition(e.q()==7_400_000&&e.q()==(-2_185_002))
let body=e.box(),count=Int(e.u());var edgePixels:[UInt8]=[]
for _ in 0..<e.u(){let n=Int(e.u()),v=e.bytes[e.at];e.at+=1;edgePixels.append(contentsOf:repeatElement(v,count:n))}
precondition(count==25600&&edgePixels.count==count&&proto==160)
let partial=e.box();precondition(e.at==e.bytes.count)
let edgePerson=person(body,edgePixels)
let edgePre=IOSYoloPreprocessResult(input:[],scale:es,padLeft:el,padTop:et,sourceWidth:ew,sourceHeight:eh,inputSize:input)
func partialFaceCoverage(_ region:SIMD4<Float>)->Float {
  var covered=0
  for y in 0..<31 {for x in 0..<31 {
    let sx=partial.x+(partial.z-partial.x)*(Float(x)/30)
    let sy=partial.y+(partial.w-partial.y)*(Float(y)/30)
    let dx=(sx-region.x)/region.z,dy=(sy-region.y)/region.w
    if dx*dx+dy*dy<=1 {covered+=1}
  }}
  return Float(covered)/961
}
let oldBox=IOSFrameKernelFaceGeometry.fallback(edgePerson)!
precondition(partialFaceCoverage(oldBox)==0)
let edge=IOSFrameKernelFaceGeometry.currentHead(edgePerson,preprocess:edgePre,currentMask:true)!
precondition(edge.maskGuided&&partialFaceCoverage(edge.region)==1)
let noCurrent=IOSFrameKernelFaceGeometry.currentHead(edgePerson,preprocess:edgePre,currentMask:false)!
precondition(!noCurrent.maskGuided&&bits(noCurrent.region)==bits(oldBox))
let malformed=IOSFrameKernelFaceGeometry.currentHead(person(body,[1,2,3]),preprocess:edgePre,currentMask:true)
precondition(malformed==nil)
print("CURRENT_HEAD_NATIVE=PASS frozen=512 actual_frame=185 partial_face_full_bbox=\(partialFaceCoverage(edge.region))")

// The actual production resolver must use the combined operation, not only its
// standalone bridge. Replay every recorded person/mask and face observation.
let sceneBytes=try Data(contentsOf:URL(fileURLWithPath:"crates/frame-kernel/testdata/face-edge-scene-ios-185-v1.json"))
precondition(SHA256.hash(data:sceneBytes).map{String(format:"%02x",$0)}.joined()=="45d2071b31c55da0cbecdbeddf1268f058dc04318db49976bebc463b5ed4c235")
let scene=try JSONSerialization.jsonObject(with:sceneBytes) as! [String:Any]
func sceneFace(_ row:[String:Any])->IOSFaceCandidate {
  let b=(row["bbox"] as! [NSNumber]).map{$0.floatValue}
  let points=(row["landmarks"] as! [[NSNumber]]).map{SIMD2($0[0].floatValue,$0[1].floatValue)}
  return IOSFaceCandidate(x1:b[0],y1:b[1],x2:b[2],y2:b[3],confidence:(row["confidence"] as! NSNumber).floatValue,
    yaw:(row["yaw"] as! NSNumber).floatValue,observationView:(row["view"] as! NSNumber).intValue,landmarks:points)
}
final class CapturedEdgeLocator:IOSFaceLocating {
  let primary:[IOSFaceCandidate],detail:[IOSFaceCandidate]
  init(_ input:[String:Any]) {primary=(input["primary"] as! [[String:Any]]).map(sceneFace);detail=(input["detail"] as! [[String:Any]]).map(sceneFace)}
  func locateFaces(in image:CGImage)throws->[IOSFaceCandidate] {primary}
  func locateFaceDetails(in image:CGImage,regions:[CGRect])throws->[IOSFaceCandidate] {detail}
}
let currentPeople=(scene["persons"] as! [[String:Any]]).map {row->IOSPreviewPerson in
  let b=(row["bbox"] as! [NSNumber]).map{$0.floatValue}
  let mask=Array(Data(base64Encoded:row["mask_base64"] as! String)!)
  // Confidence is not consumed by this resolver and was not part of its capture.
  return IOSPreviewPerson(id:(row["id"] as! NSNumber).intValue,
    detection:IOSYoloDetection(x1:b[0],y1:b[1],x2:b[2],y2:b[3],confidence:0.9,mask:mask))
}
let blank=CGContext(data:nil,width:1,height:1,bitsPerComponent:8,bytesPerRow:4,
  space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.noneSkipLast.rawValue)!.makeImage()!
let production=IOSFacePrivacyTemporalResolver(locator:CapturedEdgeLocator(scene))
let regions=try production.resolve(image:blank,persons:currentPeople,faceOnlyIds:Set(currentPeople.map{ $0.id }),
  preprocess:edgePre,protectUnassignedFaces:true,timestampUs:7_400_000)
guard let actual=regions[-2_185_002] else {fatalError("actual current protected person lost its privacy region")}
let actualCoverage=partialFaceCoverage(SIMD4(actual.centerX,actual.centerY,actual.radiusX,actual.radiusY))
precondition(actualCoverage==1,"real-frame185 production fallback leaves visible feature core=\(actualCoverage)")
print("CURRENT_HEAD_PRODUCTION_SCENE=PASS people=5 detail=6 partial_face_full_bbox=\(actualCoverage)")

// Synthetic rectangles only: an overlapping neighbor observation cannot replace
// the selected person's own head fallback. Original Android captures stay local.
let ownershipPre=IOSYoloPreprocessResult(input:[],scale:1,padLeft:0,padTop:0,sourceWidth:640,sourceHeight:640,inputSize:640)
func rectangleMask(_ left:Int,_ right:Int)->[UInt8] {
  var pixels=[UInt8](repeating:0,count:25600)
  for y in 4..<80 {for x in left..<right {pixels[y*160+x]=255}}
  return pixels
}
let ownershipFace=IOSFaceCandidate(x1:320,y1:120,x2:420,y2:220,confidence:0.9)
let ownershipOwn=person(SIMD4(0,0,640,640),rectangleMask(4,70))
let ownershipNeighbor=person(SIMD4(240,0,600,640),rectangleMask(76,110))
precondition(!IOSFrameKernelFaceGeometry.currentOwned(ownershipFace,person:ownershipOwn,preprocess:ownershipPre))
precondition(IOSFrameKernelFaceGeometry.currentOwned(ownershipFace,person:ownershipNeighbor,preprocess:ownershipPre))
final class SyntheticOwnershipLocator:IOSFaceLocating {
  func locateFaces(in image:CGImage)throws->[IOSFaceCandidate] {[ownershipFace]}
}
let ownershipResolver=IOSFacePrivacyTemporalResolver(locator:SyntheticOwnershipLocator())
let ownershipRegions=try ownershipResolver.resolve(image:blank,
  persons:[IOSPreviewPerson(id:0,detection:ownershipOwn),IOSPreviewPerson(id:1,detection:ownershipNeighbor)],
  faceOnlyIds:[0,1],preprocess:ownershipPre,timestampUs:0)
precondition(ownershipRegions[0]?.source == .yoloHeadFallback)
precondition(ownershipRegions[1]?.source == .detectedFace)
print("CURRENT_FACE_OWNERSHIP_PRODUCTION_SYNTHETIC=PASS overlapping_people=2")

// Current-mask rendering coverage is separate from detector/identity evidence.
// Deliberately synthetic rectangles: no original Android inputs leave that host.
var profileMask=[UInt8](repeating:0,count:25600)
for y in 35..<65 {for x in 50..<100 {profileMask[y*160+x]=255}}
let profilePerson=person(SIMD4(80,60,560,620),profileMask)
let profileSeed=SIMD4<Float>(280,200,60,70)
let profileCovered=IOSFrameKernelFaceGeometry.coverCurrent(profilePerson,region:profileSeed,preprocess:ownershipPre)!
func profileContains(_ e:SIMD4<Float>,_ x:Float,_ y:Float)->Bool {
  let dx=(x-e.x)/e.z;let dy=(y-e.y)/e.w;return dx*dx+dy*dy<=1.00001
}
precondition(!profileContains(profileSeed,400,200))
precondition(profileContains(profileCovered,400,200))
precondition(profileContains(profileCovered,220,200)&&profileContains(profileCovered,280,270))
final class SyntheticProfileLocator:IOSFaceLocating {
  func locateFaces(in image:CGImage)throws->[IOSFaceCandidate] {
    [IOSFaceCandidate(x1:232,y1:152,x2:328,y2:248,confidence:0.4)]
  }
}
let profileResolver=IOSFacePrivacyTemporalResolver(locator:SyntheticProfileLocator())
let profileRegions=try profileResolver.resolve(image:blank,persons:[IOSPreviewPerson(id:0,detection:profilePerson)],
  faceOnlyIds:[0],preprocess:ownershipPre,timestampUs:0)
let profileOutput=profileRegions[0]!
precondition(profileOutput.source == .detectedFace)
precondition(profileContains(SIMD4(profileOutput.centerX,profileOutput.centerY,profileOutput.radiusX,profileOutput.radiusY),400,200))
print("CURRENT_FACE_COVERAGE_PRODUCTION_SYNTHETIC=PASS current_profile_and_original_extent")
let sparseCurrent=person(SIMD4(80,60,560,620),[UInt8](repeating:0,count:25600))
let chinMargin=IOSFrameKernelFaceGeometry.coverCurrent(sparseCurrent,region:profileSeed,preprocess:ownershipPre)!
precondition(chinMargin == SIMD4<Float>(280,200,64,74))
precondition(!profileContains(profileSeed,280,273) && profileContains(chinMargin,280,273))
print("CURRENT_FACE_SAMPLING_MARGIN_NATIVE=PASS no_coherent_head_run_chin_covered")

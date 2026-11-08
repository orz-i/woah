import Foundation
import FrameKernelC

private struct FrozenReader {
 let bytes:[UInt8]; var position=0
 mutating func u()->UInt32 {let p=position;position+=4;return UInt32(bytes[p])<<24|UInt32(bytes[p+1])<<16|UInt32(bytes[p+2])<<8|UInt32(bytes[p+3])}
 mutating func i()->Int {Int(Int32(bitPattern:u()))}
 mutating func id()->Int64 {let hi=u(),lo=u();return Int64(bitPattern:UInt64(hi)<<32|UInt64(lo))}
 mutating func f()->Float {Float(bitPattern:u())}
 mutating func flag()->Bool {let p=position;position+=1;return bytes[p] != 0}
 mutating func rect()->SIMD4<Float> {SIMD4(f(),f(),f(),f())}
 mutating func ids()->Set<Int> {var values=Set<Int>();for _ in 0..<i(){values.insert(i())};return values}
 mutating func mask()->[UInt8]? {let n=i();if n<0{return nil};let p=position;position+=n;return Array(bytes[p..<position])}
 mutating func inputs()->[IOSSharedTrackingObservation] {
  var values:[IOSSharedTrackingObservation]=[]
  for _ in 0..<i(){let box=rect(),confidence=f(),foot=f(),seed=i();let pixels=(0..<256).map{UInt8(($0*17+$0/16*29+seed*37)&255)}
   values.append(IOSSharedTrackingObservation(bounds:box,confidence:confidence,footY:foot,mask:pixels,maskWidth:16,maskHeight:16,transform:FrameKernelTransform(scale:640.0/1920.0,pad_left:0,pad_top:140,source_width:1920,source_height:1080,input_size:640,proto_size:16)))
  };return values
 }
 mutating func classes(_ actual:[IOSSharedTrackingClassEvidence],_ context:String){let count=i();precondition(count==actual.count,context+" class count");for e in actual {precondition(e.selection==u()&&e.detectionIndex==i()&&e.residualIds==ids()&&e.conservativeUnknown==flag(),context+" class evidence")}}
}
@main struct TrackingSessionConformance {
 static func main() throws {
  var r=FrozenReader(bytes:Array(try Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))));precondition(r.u()==0x464B5531)
  var steps=0
  for _ in 0..<r.i(){let mode=r.i();var config=frame_kernel_tracking_defaults();config.max_missed=UInt32(3+mode%3);config.grace=3;config.scene_compensation=mode>=6 ? 1:0
   let s=try IOSSharedTrackingSession(options:config);let protected=r.ids(),body=r.ids(),face=r.ids();try s.configure(protected:protected,body:body,face:face,dormancy:mode%2==0)
   let initial=r.inputs();let n=r.i();var assigned:[Int]=[];for _ in 0..<n{assigned.append(r.i())};_ = try s.step(action:0,timestampUs:0,observations:initial,assignedIds:assigned)
   for frame in 0..<r.i(){let command=r.i(),at=r.id(),observations=r.inputs();let action:UInt32=command==1 ? 2:command==2 ? 3:1
    let snapshot=try s.step(action:action,timestampUs:at,observations:observations);let context="mode \(mode) frame \(frame)";precondition(snapshot.regions.count==r.i(),context+" track count")
    for t in snapshot.regions {precondition(t.id==r.i()&&t.state==r.u()&&t.missedFrames==r.i()&&t.observationAge==r.i()&&t.age==r.i()&&t.observed==r.flag(),context+" track metadata");let box=r.rect();precondition([t.bounds.x.bitPattern,t.bounds.y.bitPattern,t.bounds.z.bitPattern,t.bounds.w.bitPattern]==[box.x.bitPattern,box.y.bitPattern,box.z.bitPattern,box.w.bitPattern],context+" Float32 box");precondition(t.confidence.bitPattern==r.f().bitPattern&&t.footY.bitPattern==r.f().bitPattern&&t.occluders==r.ids()&&t.mask==r.mask(),context+" region evidence")}
    r.classes(snapshot.bodyEvidence,context);r.classes(snapshot.faceEvidence,context);precondition(snapshot.suppressed==r.ids(),context+" suppressed")
    var hard:[Int:UInt32]=[:];for _ in 0..<r.i(){let index=r.i();hard[index]=r.u()};precondition(snapshot.hardClasses==hard,context+" hard classes")
    let count=r.i();var motion:[Int:(Int,UInt32,UInt32,UInt32,Int64)]=[:]
    for _ in 0..<count {let id=r.i(),index=r.i(),score=r.u(),bi=r.u(),mi=r.u(),at=r.id();motion[id]=(index,score,bi,mi,at)}
    precondition(snapshot.motionEvidence.count==count,context+" motion count")
    for e in snapshot.motionEvidence {guard let v=motion[e.id]else{fatalError(context+" motion ID")};precondition(e.detectionIndex==v.0&&e.score.bitPattern==v.1&&e.boundsIoU.bitPattern==v.2&&e.maskIoU.bitPattern==v.3&&e.timestampUs==v.4,context+" motion evidence")};steps+=1
   }
  };precondition(r.position==r.bytes.count);print("TRACKING_SESSION_SWIFT_PARITY=PASS steps=\(steps)")
 }
}

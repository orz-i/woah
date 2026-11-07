import Foundation
import CoreGraphics
import CryptoKit
struct Reader {
  let bytes:[UInt8];var at=0
  mutating func u()->UInt32 {let p=at;at+=4;return UInt32(bytes[p])<<24|UInt32(bytes[p+1])<<16|UInt32(bytes[p+2])<<8|UInt32(bytes[p+3])}
  mutating func f()->Float {Float(bitPattern:u())}
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
  let head=IOSFrameKernelFaceGeometry.estimate(pixels,person:current,seed:seed,preprocess:pre)
  precondition(head.map{[$0.x.bitPattern,$0.y.bitPattern]}==expected,"head case\(index)")
  precondition(bits(IOSFrameKernelFaceGeometry.trusted(pixels,person:current,previous:current,seed:seed,expansion:1.10,preprocess:pre))==r.region(2),"trusted case\(index)")
}
precondition(r.at==r.bytes.count)
print("SHARED_FACE_GEOMETRY_NATIVE_CORPUS=PASS cases=512 sha256=\(sha)")

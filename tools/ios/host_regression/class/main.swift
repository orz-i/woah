import Foundation
import CryptoKit
import FrameKernelC

struct Reader {
  let bytes:[UInt8];var at=0
  mutating func int()->Int {let p=at;at+=4;return Int(Int32(bitPattern:UInt32(bytes[p])<<24|UInt32(bytes[p+1])<<16|UInt32(bytes[p+2])<<8|UInt32(bytes[p+3])))}
  mutating func float()->Float {Float(bitPattern:UInt32(truncatingIfNeeded:int()))}
  mutating func flag()->Bool {let v=bytes[at];at+=1;return v != 0}
  mutating func long()->Int64 {let hi=UInt64(UInt32(truncatingIfNeeded:int())),lo=UInt64(UInt32(truncatingIfNeeded:int()));return Int64(bitPattern:hi<<32|lo)}
  mutating func rect()->SIMD4<Float> {SIMD4(float(),float(),float(),float())}
  mutating func mask()->(Int,Int,[UInt8]?) {
    let w=int();if w<0{return (0,0,nil)}
    let h=int(),p=at;at+=w*h;return(w,h,Array(bytes[p..<at]))
  }
}
let path="crates/frame-kernel/testdata/privacy-class-native-v1.bin"
let data=try Data(contentsOf:URL(fileURLWithPath:path))
let sha=SHA256.hash(data:data).map{String(format:"%02x",$0)}.joined()
precondition(sha=="d17fad6313559b4c6e3a6363ccc228901188399d1fac82bf7874ecc44eba9d92")
var r=Reader(bytes:Array(data));precondition(r.int()==0x464B4331);precondition(r.int()==16)
var steps=0
for _ in 0..<16 {
  let cache=r.flag();_=r.int();let frames=r.int()
  let session=IOSSharedClassSession(reuseSimilarity:cache,reuseWarp:cache,countEvaluations:true)
  for _ in 0..<frames {
    _=r.long()
    var obs:[IOSSharedTrackingObservation]=[]
    for _ in 0..<r.int(){
      let box=r.rect(),confidence=r.float();let(w,h,mask)=r.mask()
      let t=FrameKernelTransform(scale:1,pad_left:0,pad_top:0,source_width:640,source_height:640,input_size:640,proto_size:UInt32(max(1,w)))
      obs.append(IOSSharedTrackingObservation(bounds:box,confidence:confidence,footY:box.w,mask:mask,maskWidth:w,maskHeight:h,transform:t))
    }
    var hard:[(Int,UInt32)]=[]
    for _ in 0..<r.int(){let index=r.int(),selection=r.int();hard.append((index,UInt32(selection)))}
    let result=try session.update(obs,roots:hard)
    precondition(result.count==r.int())
    for e in result {precondition(e.detectionIndex==r.int());precondition(e.selection==UInt32(r.int()));precondition(e.conservativeUnknown==r.flag())}
    _=r.flag();precondition(session.evaluations==r.int())
    for _ in 0..<r.int(){_=r.int();_=r.rect();_=r.mask();_=r.float();_=r.float();_=r.int();_=r.float()}
    steps+=1
  }
}
precondition(r.at==r.bytes.count && steps==1024)
print("SHARED_PRIVACY_CLASS_NATIVE_CORPUS=PASS steps=1024 sha256=\(sha)")

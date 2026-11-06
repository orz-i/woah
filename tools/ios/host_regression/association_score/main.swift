import Foundation
import CryptoKit
func check(_ value: Bool, _ message: String) { if !value {fatalError(message)} }
var data=Data()
func put(_ value: UInt32) {var bits=value.littleEndian;withUnsafeBytes(of:&bits) {data.append(contentsOf:$0)} }
var state:UInt64=0x13579BDF
func next(_ maximum: Int) -> Int {
  state=(state*1664525+1013904223)&0xffffffff
  return Int((state>>8)%UInt64(maximum))
}
let weights=IOSFrameKernel.associationWeights
check(weights==[0.35,0.25,0.25,0.15,16],"default weights")
for _ in 0..<10000 {
  let p:[Float]=[Float(next(8000))/4,Float(next(5000))/4,10+Float(next(1200))/4,20+Float(next(2000))/4]
  var v=[Float](repeating:0,count:18)
  v[0]=p[0];v[1]=p[1];v[2]=p[0]+p[2];v[3]=p[1]+p[3]
  v[4]=p[0]+Float(next(800)-400)/4;v[5]=p[1]+Float(next(400)-200)/4
  v[6]=v[4]+p[2];v[7]=v[5]+p[3]
  v[8]=p[0]+Float(next(1600)-800)/4;v[9]=p[1]+Float(next(1200)-600)/4
  v[10]=v[8]+p[2]*Float(next(150)+50)/100;v[11]=v[9]+p[3]*Float(next(150)+50)/100
  v[12]=Float(next(65536))/65535;v[13]=Float(next(6400))/100
  v[14]=Float(next(2400)-1200)/4;v[15]=Float(next(2400)-1200)/4
  v[16]=Float(next(800)-400)/4;v[17]=Float(next(400)-200)/4
  let flags=UInt32(next(32)),age=Int64(next(800001))
  let score=try IOSFrameKernel.associationScore(v,historyAgeUs:age,flags:flags,weights:weights)
  put(score.bitPattern)
}
for flags in UInt32(0)...7 {
  for owners in UInt32(0)...3 {
    for box:Float in [0,0.499,0.5,0.9] {
      for mask:Float in [0,0.449,0.45,0.9] {
        let expected=flags&3 == 3 && owners>0 && (owners>=2 || (flags&4 == 0 && box>=0.5 && mask>=0.45))
        let result=try IOSFrameKernel.reservationRelease(strict:flags&1 != 0,durableNonBody:flags&2 != 0,
          owners:owners,currentOwnerSupport:flags&4 != 0,bboxIoU:box,maskIoU:mask)
        check(result==expected,"reservation release")
        put(result ? 1:0)
      }
    }
  }
}
let hash=SHA256.hash(data:data).map {String(format:"%02x",$0)}.joined()
check(hash=="9970b76bf03ed4aeb7f0fcc3df51b8b2a41c1dea8f5cfa0f744c255bfe4fa55d","association score corpus: \(hash)")
print("IDENTITY_SCORE_VECTOR_SHA256=\(hash)")
print("IDENTITY_SCORE_SWIFT_ADAPTER=PASS")

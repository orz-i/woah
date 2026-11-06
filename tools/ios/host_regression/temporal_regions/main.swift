import Foundation
import CryptoKit
func check(_ value: Bool, _ message: String) { if !value {fatalError(message)} }
var data=Data()
func put(_ value: Float) {var bits=value.bitPattern.littleEndian;withUnsafeBytes(of:&bits) {data.append(contentsOf:$0)} }
var random:UInt64=0x1d2e3f4
func next(_ maximum: Int) -> Int {
  random=(random*1664525+1013904223)&0xffffffff
  return Int((random>>8)%UInt64(maximum))
}
for sequence in 0..<32 {
  let state=IOSFrameKernel.RegionState()
  var left=Float(next(8000))/4,top=Float(next(4000))/4
  let width:Float=100+Float(next(1000))/4,height:Float=300+Float(next(2000))/4
  var previous=SIMD4<Float>(left,top,left+width,top+height)
  var pts:Int64=0
  for index in 0..<64 {
    left+=Float(next(80)-40)/4;top+=Float(next(80)-40)/4
    let anchor=SIMD4<Float>(left,top,left+width+Float(next(200)-100)/4,top+height+Float(next(200)-100)/4)
    let raw=SIMD4<Float>(left+Float(next(400)-200)/4,top+Float(next(400)-200)/4,
      10+Float(next(200))/4,10+Float(next(200))/4)
    let kind=UInt32(next(3)),observed=next(2)==0,trusted=next(5)==0
    let delta:Int64=index>0 && index%31==0 ? -1000:[0,8333,16667,33333,40000,100000,250000,500000][next(8)]
    pts+=delta
    let output=try state.step(region:raw,anchor:anchor,timestampUs:pts,kind:kind,
      observed:observed,trustedCenter:trusted,canonical:sequence%2==0)
    for value in [output.x,output.y,output.z,output.w] {put(value)}
    let translation=IOSFrameKernel.anchorTranslation(previous:previous,current:anchor)
    put(translation.x);put(translation.y);previous=anchor
  }
}
let hash=SHA256.hash(data:data).map {String(format:"%02x",$0)}.joined()
check(hash=="905b408fc4b329704fcb6541e6370aadd77b3f0bc9acc5d3db6a0b2cf219f5a8","temporal corpus: \(hash)")
print("TEMPORAL_REGION_VECTOR_SHA256=\(hash)")
print("TEMPORAL_REGION_SWIFT_ADAPTER=PASS")

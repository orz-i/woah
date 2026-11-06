import Foundation
import CryptoKit
func check(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
var data=Data()
for scenario in 0...1 {
  var filter=try IOSFrameKernel.BoxFilter(bounds:SIMD4<Float>(10,20,90,180),timestampUs:1)
  var pts:Int64=1
  for i in 0..<240 {
    pts += [Int64(0),16667,33333,40000,100000,500000][i%6]
    if i==140 {pts=0}
    let x:Float=scenario==0 ? 10+Float(i%120)*3:500-Float(i%120)*2
    let box=SIMD4<Float>(x,20+Float(i%17),x+80+Float(i%5)/4,180+Float(i%17)+Float(i%7)/2)
    if i==200 {filter=try IOSFrameKernel.BoxFilter(bounds:box,timestampUs:pts)}
    _=try filter.predict(timestampUs:pts)
    let gate=try filter.gating(bounds:box)
    if !(60...89).contains(i) {try filter.observe(bounds:box,timestampUs:pts)}
    if i%29==0 {try filter.dampen(0.7)}
    let bounds=try filter.bounds()
    for value in [bounds.x,bounds.y,bounds.z,bounds.w,gate]+filter.snapshotFloats() {
      var bits=value.bitPattern.littleEndian
      withUnsafeBytes(of:&bits) {data.append(contentsOf:$0)}
    }
  }
}
let hash=SHA256.hash(data:data).map {String(format:"%02x",$0)}.joined()
check(hash=="e6d1cb740e6c0cf00ae01d954401505b1c8f8351eb771e1398bcf3969e1923fd","box filter corpus: \(hash)")
print("BOX_FILTER_VECTOR_SHA256=\(hash)")
print("BOX_FILTER_SWIFT_ADAPTER=PASS")

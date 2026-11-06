import Foundation
import CryptoKit
func check(_ value: Bool, _ message: String) { if !value {fatalError(message)} }
let values=[-3,2,9,44]
let roots=Set(values.prefix(3))
func ids(_ mask: Int) -> Set<Int> {Set(values.enumerated().filter {mask & (1 << $0.offset) != 0}.map(\.element))}
var data=Data()
func put(_ value: Int32) {var value=value.littleEndian;withUnsafeBytes(of:&value) {data.append(contentsOf:$0)} }
func putId(_ value: Int) {var value=Int64(value).littleEndian;withUnsafeBytes(of:&value) {data.append(contentsOf:$0)} }
for body in 0..<16 {for face in 0..<16 {for hero in [nil]+values.map({Optional($0)}) {
  let result=IOSFrameKernel.sceneScope(roots:roots,body:ids(body),face:ids(face),hero:hero)
  let expected:Int
  if let hero {expected=roots.contains(hero) && ids(body).isEmpty && ids(face)==roots.subtracting([hero]) ? 4:0}
  else if ids(face).isEmpty && ids(body).isSuperset(of:roots) {expected=1}
  else if ids(body).isEmpty && ids(face).isSuperset(of:roots) {expected=2}
  else {expected=0}
  check(result==expected,"scope differs");put(Int32(result))
} } }
for selected in 0..<16 {for covered in 0..<16 {for explicit in 0..<16 {
  let actual=IOSFrameKernel.compositorTargets(selected:ids(selected),covered:ids(covered),explicit:ids(explicit)).sorted()
  let expected=ids(selected).subtracting(ids(covered).subtracting(ids(explicit))).sorted()
  check(actual==expected,"fallback precedence differs");put(Int32(actual.count));actual.forEach(putId)
} } }
let hero=SIMD4<Float>(1,2,3,4)
let unique=try IOSFrameKernel.currentHeroIndex(boxes:[SIMD4<Float>(9,8,10,12),hero],observed:hero)
check(unique==1,"unique observed hero rejected")
check(try IOSFrameKernel.currentHeroIndex(boxes:[hero,hero],observed:hero)==nil,"duplicate geometry granted exemption")
check(try IOSFrameKernel.currentHeroIndex(boxes:[hero],observed:nil)==nil,"absent observation granted exemption")
let digest=SHA256.hash(data:data).map {String(format:"%02x",$0)}.joined()
check(digest=="1b5ca65615939625878106d6f85088fab8cdef5b5ed45dbf4f10a58df33f2117","selection corpus: \(digest)")
print("SELECTION_VECTOR_SHA256=\(digest)")
print("SELECTION_SWIFT_ADAPTER=PASS")

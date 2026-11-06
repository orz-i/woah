import Foundation
import CoreGraphics
func check(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
func prep(_ w: Int, _ h: Int) -> IOSYoloPreprocessResult {
  let s=min(Float32(640)/Float32(w),Float32(640)/Float32(h))
  return IOSYoloPreprocessResult(input:[],scale:s,padLeft:Float32(640-Int(Float32(w)*s))/2,
    padTop:Float32(640-Int(Float32(h)*s))/2,sourceWidth:w,sourceHeight:h,inputSize:640)
}
let proto=(0..<25600).map { UInt8(($0 * 37 + $0/160) % 256) }
func reference(_ p: IOSYoloPreprocessResult, _ width: Int, _ height: Int, _ crop: SIMD4<Float>) -> [UInt8] {
  var output=[UInt8](repeating:0,count:width*height)
  for y in 0..<height {
    let sy=(Float32(y)+0.5)*Float32(p.sourceHeight)/Float32(height)*(crop.w-crop.y)+crop.y*Float32(p.sourceHeight)
    let py=Int(floor(Double((sy*p.scale+p.padTop)/Float32(p.inputSize)*160)))
    guard py>=0 && py<160 else {continue}
    for x in 0..<width {
      let sx=(Float32(x)+0.5)*Float32(p.sourceWidth)/Float32(width)*(crop.z-crop.x)+crop.x*Float32(p.sourceWidth)
      let px=Int(floor(Double((sx*p.scale+p.padLeft)/Float32(p.inputSize)*160)))
      if px>=0 && px<160 {output[y*width+x]=proto[py*160+px]}
    }
  }
  return output
}
for (w,h,ow,oh,crop) in [
  (3840,2160,3840,2160,SIMD4<Float>(0,0,1,1)),
  (2160,4096,2160,4096,SIMD4<Float>(0,0,1,1)),
  (1920,1080,720,1280,SIMD4<Float>(0.30,0.1,0.65,0.95)),
  (1080,2048,540,1024,SIMD4<Float>(0.01,0.04,0.98,0.93))
] {
  let p=prep(w,h)
  let actual=IOSPrivacyMaskProjector.project(mask:proto,preprocess:p,sourceWidth:w,sourceHeight:h,width:ow,height:oh,crop:crop)
  check(actual == reference(p,ow,oh,crop),"projector pixel mismatch \(w)x\(h)")
  print("MASK_PROJECTION_\(w)x\(h)=PASS")
}
let views=IOSHighResolutionPrivacyCoverage.views(width:3840,height:2160)
check(views.count==4 && views[3]==CGRect(x:1536,y:864,width:2304,height:1296),"tile geometry")
func det(_ x: Float32,_ y: Float32,_ w: Float32,_ h: Float32, _ mask:[UInt8]=[UInt8](repeating:255,count:25600)) -> IOSYoloDetection {
  IOSYoloDetection(x1:x,y1:y,x2:x+w,y2:y+h,confidence:0.9,mask:mask)
}
check(!IOSHighResolutionPrivacyCoverage.isComplete(det(0,40,40,50),view:views[3],width:3840,height:2160),"partial tile left edge accepted")
let moved=IOSHighResolutionPrivacyCoverage.toScene(det(100,100,200,600),view:views[3],local:prep(2304,1296),scene:prep(3840,2160))
check(moved.x1==1636 && moved.y1==964 && moved.x2==1836 && moved.y2==1564,"scene bbox mapping")
let p=prep(3840,2160)
for y in 0..<160 {
  for x in 0..<160 {
    let sx=((Float32(x)+0.5)*4-p.padLeft)/p.scale, sy=((Float32(y)+0.5)*4-p.padTop)/p.scale
    let covered=sx>=1536 && sx<3840 && sy>=864 && sy<2160
    check((moved.mask[y*160+x]>0)==covered,"global mask crop orientation")
  }
}
let primary=det(100,100,200,600)
let merged=IOSHighResolutionPrivacyCoverage.merge([primary],extra:[det(105,110,200,600),det(1200,100,200,600)])
check(merged.count==2 && merged[0].x1==100,"primary deduplication")
let persons=[IOSPreviewPerson(id:1,detection:det(200,200,60,200)),
             IOSPreviewPerson(id:2,detection:det(100,100,400,500))]
check(IOSHighResolutionPrivacyCoverage.coveredHeadFallbackIds(persons:persons,preprocess:prep(640,640))==[1],"occluded head hint")
check(IOSHighResolutionPrivacyCoverage.coveredHeadFallbackIds(persons:[persons[0]],preprocess:prep(640,640)).isEmpty,"unoccluded head suppressed")
print("TILE_REPROJECTION_DEDUP_OCCLUSION=PASS")

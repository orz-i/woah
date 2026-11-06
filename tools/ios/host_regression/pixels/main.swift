import Foundation
import CryptoKit
import CoreGraphics
@_implementationOnly import FrameKernelC
func check(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
let yBytes=(0..<51).map {UInt8(($0*17+3)&255)}
let uBytes=(0..<31).map {UInt8(($0*11+17)&255)}
let vBytes=(0..<31).map {UInt8(($0*23+71)&255)}
var digest=Data()
for cropped in [false,true] { for rotation in [0,90,180,270] {
  let l=cropped ? 1:0, t=cropped ? 1:0, r=cropped ? 6:7, b=5
  let swap=rotation==90 || rotation==270
  let dw=swap ? b-t:r-l, dh=swap ? r-l:b-t
  for matrix in 0...2 { for full in 0...1 { for flip in 0...1 {
    var output=[UInt8](repeating:0,count:1024)
    let spec=FrameKernelYuvSpec(width:7,height:5,crop_left:UInt32(l),crop_top:UInt32(t),crop_right:UInt32(r),crop_bottom:5,
      display_width:UInt32(dw),display_height:UInt32(dh),rotation:UInt32(rotation),output_width:16,output_height:16,
      flip_rows:UInt32(flip),matrix:UInt32(matrix),full_range:UInt32(full),fill:114)
    let status=yBytes.withUnsafeBufferPointer { y in uBytes.withUnsafeBufferPointer { u in vBytes.withUnsafeBufferPointer { v in
      output.withUnsafeMutableBufferPointer { out in
        frame_kernel_yuv420_rgba(FrameKernelPlane(data:y.baseAddress,len:y.count,row_stride:11,pixel_stride:1),
          FrameKernelPlane(data:u.baseAddress,len:u.count,row_stride:12,pixel_stride:2),
          FrameKernelPlane(data:v.baseAddress,len:v.count,row_stride:12,pixel_stride:2),spec,out.baseAddress,out.count)
      }
    } } }
    check(status==0,"YUV contract rejected");digest.append(contentsOf:output)
  } } }
} }
let hash=SHA256.hash(data:digest).map {String(format:"%02x",$0)}.joined()
check(hash=="3fb3f8996f0cf401e8b9d707d51798c90774c5880d143dc3d558ea48c1783d51","pixel corpus: \(hash)")
let rgba=(0..<1024).map {UInt8(($0*37+11)&255)}
for fy in [false,true] { for fx in [false,true] { for interleaved in [false,true] {
  for (scale,bias):(Float,Float) in [(1/255,0),(1/127.5,-1)] {
    let output=try IOSFrameKernel.normalizePixels(rgba,width:16,height:16,flipRows:fy,flipColumns:fx,
      interleaved:interleaved,scale:scale,bias:bias)
    for y in 0..<16 { for x in 0..<16 { for c in 0..<3 {
      let src=((fy ? 15-y:y)*16+(fx ? 15-x:x))*4+c
      let expected=Float(rgba[src])*scale+bias
      let index=interleaved ? (y*16+x)*3+c:c*256+y*16+x
      check(output[index].bitPattern==expected.bitPattern,"tensor normalization differs")
    } } }
  }
} } }
// Exercise the real CGImage transport adapter, not just a duplicated DTO stub.
let pixels:[UInt8]=[255,0,0,255,0,255,0,255,0,0,255,255,255,255,255,255]
let provider=CGDataProvider(data:Data(pixels) as CFData)!
let image=CGImage(width:2,height:2,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:8,
  space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.premultipliedLast.rawValue),
  provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent)!
let prepared=try IOSYoloPreprocessor.process(image:image)
check(prepared.input.count==3*640*640 && prepared.scale==320,"CGImage adapter geometry")
check(prepared.input[0]==1 && prepared.input[2*640*640+0]==0,"CGImage orientation")
print("PIXEL_VECTOR_SHA256=\(hash)")
print("PIXEL_NATIVE_ADAPTER_PARITY=PASS")

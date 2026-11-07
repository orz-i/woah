import CoreGraphics
import Foundation
import CryptoKit
@_implementationOnly import FrameKernelC
#if canImport(TensorFlowLite)
import TensorFlowLite
#endif

/// Native model execution and pixel transport only. Face policy is in FrameKernel.
final class IOSGridFaceLocator: IOSFaceLocating {
  private let lock = NSRecursiveLock()
#if canImport(TensorFlowLite)
  private var interpreter: Interpreter?
#endif
  func locateFaceDetails(in image: CGImage, regions: [CGRect]) throws -> [IOSFaceCandidate] {
    let bounds = CGRect(x:0,y:0,width:image.width,height:image.height)
    return try regions.enumerated().flatMap { index,region -> [IOSFaceCandidate] in
      let rect=region.integral.intersection(bounds)
      guard rect.width>=32,rect.height>=32,let crop=image.cropping(to:rect) else {return []}
      return try locateFaces(in:crop).map {face in
        IOSFaceCandidate(x1:face.x1+Float(rect.minX),y1:face.y1+Float(rect.minY),
          x2:face.x2+Float(rect.minX),y2:face.y2+Float(rect.minY),confidence:face.confidence,
          yaw:face.yaw,observationView:index+1,
          landmarks:face.landmarks.map { $0 + SIMD2<Float>(Float(rect.minX),Float(rect.minY)) })
      }
    }
  }
  func locateFaces(in image: CGImage) throws -> [IOSFaceCandidate] {
    lock.lock();defer {lock.unlock()}
#if canImport(TensorFlowLite)
    if interpreter == nil {
      guard let url=IOSModelResources.gridFaceModelURL() else {throw failure("FACE_MODEL_MISSING")}
      let digest=SHA256.hash(data:try Data(contentsOf:url)).map{String(format:"%02x",$0)}.joined()
      guard digest=="3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b" else {throw failure("FACE_MODEL_HASH_CONTRACT")}
      var options=Interpreter.Options();options.threadCount=2;options.isXNNPackEnabled=true
      let model=try Interpreter(modelPath:url.path,options:options)
      try model.allocateTensors()
      guard try model.input(at:0).shape.dimensions == [1,192,192,3],
            try model.output(at:0).shape.dimensions == [1,2304,16],
            try model.output(at:1).shape.dimensions == [1,2304,1] else {throw failure("FACE_MODEL_CONTRACT")}
      interpreter=model
    }
    guard let model=interpreter else {throw failure("FACE_MODEL_MISSING")}
    let w=image.width,h=image.height
    guard w>1,h>1,w<=65535,h<=65535,w*h<=16_777_216 else {throw failure("FACE_FRAME_INVALID")}
    var rgba=[UInt8](repeating:0,count:w*h*4)
    let bitmapInfo=CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
    let valid=rgba.withUnsafeMutableBytes {raw -> Bool in
      guard let context=CGContext(data:raw.baseAddress,width:w,height:h,bitsPerComponent:8,
        bytesPerRow:w*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:bitmapInfo) else {return false}
      context.draw(image,in:CGRect(x:0,y:0,width:w,height:h));return true
    }
    guard valid else {throw failure("FACE_FRAME_INVALID")}
    var input=[Float](repeating:0,count:192*192*3)
    let status=rgba.withUnsafeBufferPointer {p in input.withUnsafeMutableBufferPointer {out in
      frame_kernel_grid_face_input(p.baseAddress,p.count,UInt32(w),UInt32(h),UInt32(w*4),out.baseAddress,out.count)
    }}
    guard status==0 else {throw failure("FACE_PREPROCESS_FAILED")}
    let data=input.withUnsafeBytes{Data($0)}
    try model.copy(data,toInputAt:0);try model.invoke()
    func floats(_ data:Data) throws -> [Float] {
      guard data.count % 4 == 0 else {throw failure("FACE_TENSOR_INVALID")}
      var result=[Float](repeating:0,count:data.count/4)
      result.withUnsafeMutableBytes {destination in data.copyBytes(to:destination)}
      return result
    }
    let boxes=try floats(model.output(at:0).data),scores=try floats(model.output(at:1).data)
    var output=[Float](repeating:0,count:2304*17)
    let count=boxes.withUnsafeBufferPointer {b in scores.withUnsafeBufferPointer {s in
      output.withUnsafeMutableBufferPointer {out in
        frame_kernel_grid_face_decode(b.baseAddress,b.count,s.baseAddress,s.count,UInt32(w),UInt32(h),0.35,0.3,out.baseAddress,out.count)
      }
    }}
    guard count>=0 else {throw failure("FACE_POSTPROCESS_FAILED")}
    return (0..<Int(count)).map {index in
      let i=index*17
      return IOSFaceCandidate(x1:output[i],y1:output[i+1],x2:output[i+2],y2:output[i+3],confidence:output[i+4],
        landmarks:(0..<6).map { SIMD2<Float>(output[i+5+$0*2],output[i+6+$0*2]) })
    }
#else
    throw failure("FACE_RUNTIME_UNAVAILABLE")
#endif
  }
  private func failure(_ code:String) -> PigeonError {
    PigeonError(code:code,message:"Shared face inference did not complete.",details:nil)
  }
}

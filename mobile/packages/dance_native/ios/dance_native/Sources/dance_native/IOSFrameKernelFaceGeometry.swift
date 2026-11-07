import CoreGraphics
import Foundation
@_implementationOnly import FrameKernelC

/// Native data transport for the shared face geometry verified against 512 frozen cases.
/// Every new region API returns 0 on success; nonzero outputs are never consumed.
enum IOSFrameKernelFaceGeometry {
  private static func rect(_ person:IOSYoloDetection)->FrameKernelRect {
    FrameKernelRect(left:person.x1,top:person.y1,right:person.x2,bottom:person.y2)
  }
  private static func ellipse(_ seed:SIMD4<Float>)->FrameKernelEllipse {
    FrameKernelEllipse(center_x:seed.x,center_y:seed.y,radius_x:seed.z,radius_y:seed.w)
  }
  private static func values(_ region:FrameKernelEllipse)->SIMD4<Float> {
    SIMD4(region.center_x,region.center_y,region.radius_x,region.radius_y)
  }
  private static func record(_ face:IOSFaceCandidate)->[Float] {
    var row:[Float]=[face.x1,face.y1,face.x2,face.y2,face.confidence,Float(min(6,face.landmarks.count))]
    for index in 0..<6 {
      row.append(index<face.landmarks.count ? face.landmarks[index].x:0)
      row.append(index<face.landmarks.count ? face.landmarks[index].y:0)
    }
    return row
  }
  static func detected(_ face:IOSFaceCandidate,view:CGRect?=nil,outputSize:Float=1)->SIMD4<Float>? {
    let row=record(face);var output=FrameKernelEllipse()
    let status=row.withUnsafeBufferPointer {p -> Int32 in
      if let view {
        var source=FrameKernelRect(left:Float(view.minX),top:Float(view.minY),right:Float(view.maxX),bottom:Float(view.maxY))
        return frame_kernel_detected_face_region(p.baseAddress,&source,outputSize,face.yaw,&output)
      }
      return frame_kernel_detected_face_region(p.baseAddress,nil,outputSize,face.yaw,&output)
    }
    guard status==0 else{return nil}
    return values(output)
  }
  static func project(_ person:IOSYoloDetection,previous:IOSYoloDetection,seed:SIMD4<Float>,ageUs:Int64)->SIMD4<Float>? {
    var output=FrameKernelEllipse()
    let status=frame_kernel_project_face_region(rect(person),rect(previous),ellipse(seed),ageUs,&output)
    guard status==0 else{return nil}
    return values(output)
  }
  static func fallback(_ person:IOSYoloDetection)->SIMD4<Float>? {
    var output=FrameKernelEllipse()
    let status=frame_kernel_head_fallback(rect(person),&output)
    guard status==0 else{return nil}
    return values(output)
  }
  private static func spec(_ person:IOSYoloDetection,_ preprocess:IOSYoloPreprocessResult)->FrameKernelHeadMaskSpec? {
    guard preprocess.sourceWidth>0,preprocess.sourceHeight>0,preprocess.inputSize>0,
      preprocess.sourceWidth<=65535,preprocess.sourceHeight<=65535,preprocess.inputSize<=65535 else{return nil}
    let t=FrameKernelTransform(scale:preprocess.scale,pad_left:preprocess.padLeft,pad_top:preprocess.padTop,
      source_width:UInt32(preprocess.sourceWidth),source_height:UInt32(preprocess.sourceHeight),
      input_size:UInt32(preprocess.inputSize),proto_size:UInt32(IOSYoloPostprocessor.protoSize))
    return FrameKernelHeadMaskSpec(width:UInt32(IOSYoloPostprocessor.protoSize),height:UInt32(IOSYoloPostprocessor.protoSize),transform:t,person:rect(person))
  }
  static func estimate(_ mask:[UInt8],person:IOSYoloDetection,seed:SIMD4<Float>,preprocess:IOSYoloPreprocessResult)->SIMD2<Float>? {
    guard mask.count==IOSYoloPostprocessor.protoSize*IOSYoloPostprocessor.protoSize,let metadata=spec(person,preprocess) else{return nil}
    var output=[Float](repeating:0,count:2)
    let status=mask.withUnsafeBufferPointer {p in output.withUnsafeMutableBufferPointer {out in
      frame_kernel_mask_head(p.baseAddress,p.count,metadata,ellipse(seed),out.baseAddress)
    }}
    guard status==0 else{return nil}
    return SIMD2(output[0],output[1])
  }
  static func trusted(_ mask:[UInt8],person:IOSYoloDetection,previous:IOSYoloDetection,seed:SIMD4<Float>,expansion:Float,preprocess:IOSYoloPreprocessResult)->SIMD4<Float>? {
    guard mask.count==IOSYoloPostprocessor.protoSize*IOSYoloPostprocessor.protoSize,let metadata=spec(person,preprocess) else{return nil}
    var output=FrameKernelEllipse()
    let status=mask.withUnsafeBufferPointer {p in
      frame_kernel_trusted_mask_region(p.baseAddress,p.count,metadata,rect(previous),ellipse(seed),expansion,&output)
    }
    guard status==0 else{return nil}
    return values(output)
  }
}

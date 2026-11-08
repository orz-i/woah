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
  static func owns(_ face:IOSFaceCandidate,person:IOSYoloDetection,preprocess:IOSYoloPreprocessResult)->Bool {
    guard person.mask.count==IOSYoloPostprocessor.protoSize*IOSYoloPostprocessor.protoSize,
      let metadata=spec(person,preprocess) else{return false}
    let row=record(face);var output:UInt32=0
    let status=person.mask.withUnsafeBufferPointer {p in row.withUnsafeBufferPointer {f in
      frame_kernel_face_mask_owned(p.baseAddress,p.count,metadata,f.baseAddress,face.yaw,&output)
    }}
    return status==0&&output==1
  }
  static func currentOwned(_ face:IOSFaceCandidate,person:IOSYoloDetection,preprocess:IOSYoloPreprocessResult)->Bool {
    guard person.mask.count==IOSYoloPostprocessor.protoSize*IOSYoloPostprocessor.protoSize,
      let metadata=spec(person,preprocess) else{return false}
    let row=record(face);var output:UInt32=0
    let status=person.mask.withUnsafeBufferPointer {p in row.withUnsafeBufferPointer {f in
      frame_kernel_current_face_owned(p.baseAddress,p.count,metadata,f.baseAddress,nil,1,face.yaw,&output)
    }}
    return status==0&&output==1
  }
  static func support(_ region:SIMD4<Float>,person:IOSYoloDetection,preprocess:IOSYoloPreprocessResult)->(Int,Int)? {
    guard person.mask.count==IOSYoloPostprocessor.protoSize*IOSYoloPostprocessor.protoSize,
      let metadata=spec(person,preprocess) else{return nil}
    var output=[UInt32](repeating:0,count:2)
    let status=person.mask.withUnsafeBufferPointer {p in output.withUnsafeMutableBufferPointer {out in
      frame_kernel_face_mask_support(p.baseAddress,p.count,metadata,ellipse(region),out.baseAddress)
    }}
    guard status==0 else{return nil}
    return (Int(output[0]),Int(output[1]))
  }
  static func score(_ face:IOSFaceCandidate,person:IOSYoloDetection)->Float? {
    let row=record(face);var output:Float=0
    let status=row.withUnsafeBufferPointer {p in frame_kernel_face_observation_score(p.baseAddress,rect(person),&output)}
    guard status==0 else{return nil}
    return output
  }
  static func upperBody(_ person:IOSYoloDetection)->CGRect? {
    var output=[Float](repeating:0,count:4)
    let status=output.withUnsafeMutableBufferPointer {p in frame_kernel_face_upper_body_view(rect(person),p.baseAddress)}
    guard status==0 else{return nil}
    return CGRect(x:Double(output[0]),y:Double(output[1]),width:Double(output[2]),height:Double(output[3]))
  }
  static func merge(_ input:[IOSFaceCandidate],primary:Int,owners:[Int?]?)->[IOSFaceCandidate]? {
    guard input.count<=4096,primary>=0,primary<=input.count,owners==nil||owners?.count==input.count,
      input.allSatisfy({$0.observationView>=0&&$0.observationView<=Int(UInt32.max)}) else{return nil}
    let rows=input.enumerated().map {i,f in FrameKernelFaceView(
      bounds:FrameKernelRect(left:f.x1,top:f.y1,right:f.x2,bottom:f.y2),view:UInt32(f.observationView),
      known_owner:owners?[i]==nil ? 0:1,owner:Int64(owners?[i] ?? 0))}
    var output=[UInt32](repeating:0,count:input.count);var count=0
    let status=rows.withUnsafeBufferPointer {p in output.withUnsafeMutableBufferPointer {out in
      frame_kernel_face_merge_views(p.baseAddress,p.count,primary,owners==nil ? 0:1,out.baseAddress,out.count,&count)
    }}
    guard status==0,count>=0,count<=output.count else{return nil}
    return output.prefix(count).map {input[Int($0)]}
  }
  static func uniqueOwner(_ scores:[Float],margin:Float=0.08)->Int? {
    var output:UInt32=0
    let status=scores.withUnsafeBufferPointer {p in frame_kernel_face_unique_owner(p.baseAddress,p.count,margin,&output)}
    guard status==0 else{return nil}
    return Int(output)
  }
  static func resolveOwners(_ scores:[Float],people:Int,faces:Int,preferred:[Int32],hero:Int?,margin:Float=0.08)->[Int32]? {
    guard people>=0,people<=4096,faces>=0,faces<=4096,preferred.count==people,scores.count==people*faces else{return nil}
    var output=[Int32](repeating:-1,count:people)
    let status=scores.withUnsafeBufferPointer {p in preferred.withUnsafeBufferPointer {choices in output.withUnsafeMutableBufferPointer {out in
      frame_kernel_face_resolve_owners(p.baseAddress,p.count,people,faces,choices.baseAddress,Int64(hero ?? -1),margin,out.baseAddress,out.count)
    }}}
    guard status==0 else{return nil}
    return output
  }
  static func padding(_ person:IOSYoloDetection,seed:SIMD4<Float>,age:Int64,lease:Int64,preprocess:IOSYoloPreprocessResult)->SIMD4<Float>? {
    guard person.mask.count==IOSYoloPostprocessor.protoSize*IOSYoloPostprocessor.protoSize,
      let metadata=spec(person,preprocess) else{return nil}
    var output=FrameKernelEllipse()
    let status=person.mask.withUnsafeBufferPointer {p in
      frame_kernel_face_occlusion_padding(p.baseAddress,p.count,metadata,ellipse(seed),age,lease,&output)
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
  static func currentHead(_ person:IOSYoloDetection,preprocess:IOSYoloPreprocessResult,
      seed:SIMD4<Float>?=nil,currentMask:Bool)->(region:SIMD4<Float>,maskGuided:Bool)? {
    guard let metadata=spec(person,preprocess) else{return nil}
    var output=FrameKernelCurrentHeadRegion()
    func invoke(_ pixels:UnsafePointer<UInt8>?,_ count:Int)->Int32 {
      if let seed {
        var input=ellipse(seed)
        return frame_kernel_current_head_region(pixels,count,metadata,&input,&output)
      }
      return frame_kernel_current_head_region(pixels,count,metadata,nil,&output)
    }
    let status=currentMask ? person.mask.withUnsafeBufferPointer {p in invoke(p.baseAddress,p.count)}:invoke(nil,0)
    guard status==0 else{return nil}
    return (values(output.region),output.current_mask_guided==1)
  }
  static func coverCurrent(_ person:IOSYoloDetection,region:SIMD4<Float>,
      preprocess:IOSYoloPreprocessResult)->SIMD4<Float>? {
    guard let metadata=spec(person,preprocess),person.mask.isEmpty ||
      person.mask.count==IOSYoloPostprocessor.protoSize*IOSYoloPostprocessor.protoSize else{return nil}
    var output=FrameKernelEllipse()
    let status=person.mask.withUnsafeBufferPointer {p in
      frame_kernel_current_face_region(p.baseAddress,p.count,metadata,ellipse(region),&output)
    }
    guard status==0 else{return nil}
    return values(output)
  }
  static func coverStandalone(region:SIMD4<Float>,preprocess:IOSYoloPreprocessResult)->SIMD4<Float>? {
    guard let metadata=transform(preprocess) else{return nil}
    var output=FrameKernelEllipse()
    let status=frame_kernel_standalone_face_region(ellipse(region),metadata,&output)
    return status==0 ? values(output) : nil
  }
  private static func transform(_ preprocess:IOSYoloPreprocessResult)->FrameKernelTransform? {
    guard preprocess.sourceWidth>0,preprocess.sourceHeight>0,preprocess.inputSize>0,
      preprocess.sourceWidth<=65535,preprocess.sourceHeight<=65535,preprocess.inputSize<=65535 else{return nil}
    return FrameKernelTransform(scale:preprocess.scale,pad_left:preprocess.padLeft,pad_top:preprocess.padTop,
      source_width:UInt32(preprocess.sourceWidth),source_height:UInt32(preprocess.sourceHeight),
      input_size:UInt32(preprocess.inputSize),proto_size:UInt32(IOSYoloPostprocessor.protoSize))
  }
  private static func spec(_ person:IOSYoloDetection,_ preprocess:IOSYoloPreprocessResult)->FrameKernelHeadMaskSpec? {
    guard let t=transform(preprocess) else{return nil}
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

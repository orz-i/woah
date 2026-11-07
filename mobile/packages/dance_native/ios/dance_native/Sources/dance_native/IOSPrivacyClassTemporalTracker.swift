import Foundation
@_implementationOnly import FrameKernelC

enum IOSPrivacySelectionClass: String {
  case selected = "SELECTED"
  case unselected = "UNSELECTED"
}

struct IOSFreshPrivacyClassEvidence {
  let selectionClass: IOSPrivacySelectionClass
  let detectionIndex: Int
  let detection: IOSYoloDetection
  let conservativeUnknown: Bool
}

/// Borrowed value buffers and owned session lifetime; no independent class policy.
final class IOSSharedClassSession {
  private var id:UInt64=0
  private let options:FrameKernelClassOptions
  private let lock=NSLock()
  private(set) var evaluations=0
  init(minimum:Float=0.42,singleMinimum:Float=0.65,margin:Float=0.12,maximumMisses:Int=4,
       reuseSimilarity:Bool=true,reuseWarp:Bool=true,countEvaluations:Bool=false) {
    precondition(maximumMisses>=0 && maximumMisses<=1_000_000)
    options=FrameKernelClassOptions(minimum:minimum,single_minimum:singleMinimum,margin:margin,maximum_misses:UInt32(maximumMisses),
      flags:(reuseSimilarity ? 1:0) | (reuseWarp ? 2:0) | (countEvaluations ? 4:0))
  }
  deinit {if id != 0 {_ = frame_kernel_class_destroy(id)}}
  func reset(){
    lock.lock();defer{lock.unlock()}
    if id != 0 {precondition(frame_kernel_class_reset(id)==0)}
    evaluations=0
  }
  func update(_ input:[IOSSharedTrackingObservation], roots:[(Int,UInt32)]) throws -> [IOSSharedTrackingClassEvidence] {
    lock.lock();defer{lock.unlock()}
    guard input.count<=4096,roots.count<=4096 else{throw Self.failure()}
    if id==0 {id=frame_kernel_class_create(options)}
    guard id != 0 else{throw Self.failure()}
    var pool:[UInt8]=[],obs:[FrameKernelTrackingObservation]=[]
    for d in input {
      guard d.maskWidth>=0,d.maskHeight>=0,d.maskWidth<=65535,d.maskHeight<=65535 else{throw Self.failure()}
      let offset=d.mask==nil ? UInt64.max:UInt64(pool.count)
      if let mask=d.mask {
        guard d.maskWidth>0,d.maskHeight>0,d.maskWidth*d.maskHeight==mask.count,
              pool.count<=256*1024*1024-mask.count else{throw Self.failure()}
        pool.append(contentsOf:mask)
      }
      obs.append(FrameKernelTrackingObservation(bounds:FrameKernelRect(left:d.bounds.x,top:d.bounds.y,right:d.bounds.z,bottom:d.bounds.w),
        confidence:d.confidence,foot_y:d.footY,mask_offset:offset,mask_width:UInt32(d.maskWidth),mask_height:UInt32(d.maskHeight),transform:d.transform))
    }
    var hard:[FrameKernelClassRoot]=[]
    for (index,selection) in roots {
      guard index>=0,index<=Int(UInt32.max),selection<=1 else{throw Self.failure()}
      hard.append(FrameKernelClassRoot(index:UInt32(index),selection:selection))
    }
    var output=[FrameKernelClassDecision](repeating:FrameKernelClassDecision(),count:input.count)
    var countEvaluations:UInt32=0
    let count=obs.withUnsafeBufferPointer {o in pool.withUnsafeBufferPointer {p in hard.withUnsafeBufferPointer {r in
      output.withUnsafeMutableBufferPointer {v in
        frame_kernel_class_update(id,o.baseAddress,o.count,p.baseAddress,p.count,r.baseAddress,r.count,v.baseAddress,v.count,&countEvaluations)
      }
    }}}
    guard count>=0 else{throw Self.failure()}
    evaluations=Int(countEvaluations)
    return output.prefix(Int(count)).map{IOSSharedTrackingClassEvidence(selection:$0.selection,detectionIndex:Int($0.index),
      residualIds:[],conservativeUnknown:$0.unknown != 0)}
  }
  private static func failure()->NSError {
    NSError(domain:"FrameKernelClassTransport",code:1,userInfo:[NSLocalizedDescriptionKey:"Invalid shared privacy-class observations or session."])
  }
}

/// Product DTO transport only. Unknown protection and immutable roots run in Rust.
final class IOSPrivacyClassTemporalTracker {
  private let session:IOSSharedClassSession
  var lastSimilarityEvaluationCount:Int {session.evaluations}
  init(minClassScore:Float32=0.42,minSingleClassScore:Float32=0.65,minClassMargin:Float32=0.12,
      maxPrototypeMisses:Int=4,reuseFrameSimilarityCache:Bool=true,countSimilarityEvaluations:Bool=false) {
    session=IOSSharedClassSession(minimum:minClassScore,singleMinimum:minSingleClassScore,margin:minClassMargin,
      maximumMisses:maxPrototypeMisses,reuseSimilarity:reuseFrameSimilarityCache,countEvaluations:countSimilarityEvaluations)
  }
  func reset(){session.reset()}
  func update(detections:[IOSYoloDetection],preprocess:IOSYoloPreprocessResult,
      hardClassByDetectionIndex:[Int:IOSPrivacySelectionClass],timestampUs:Int64) throws -> [IOSFreshPrivacyClassEvidence] {
    _=timestampUs
    guard preprocess.sourceWidth>0,preprocess.sourceHeight>0,preprocess.inputSize>0,
          preprocess.sourceWidth<=65535,preprocess.sourceHeight<=65535,preprocess.inputSize<=65535 else {
      throw NSError(domain:"FrameKernelClassTransport",code:2)
    }
    let transform=FrameKernelTransform(scale:preprocess.scale,pad_left:preprocess.padLeft,pad_top:preprocess.padTop,
      source_width:UInt32(preprocess.sourceWidth),source_height:UInt32(preprocess.sourceHeight),
      input_size:UInt32(preprocess.inputSize),proto_size:UInt32(IOSYoloPostprocessor.protoSize))
    let obs=detections.map{d in IOSSharedTrackingObservation(bounds:SIMD4(d.x1,d.y1,d.x2,d.y2),confidence:d.confidence,footY:d.y2,
      mask:d.mask.isEmpty ? nil:d.mask,maskWidth:d.mask.isEmpty ? 0:IOSYoloPostprocessor.protoSize,
      maskHeight:d.mask.isEmpty ? 0:IOSYoloPostprocessor.protoSize,transform:transform)}
    let roots=hardClassByDetectionIndex.keys.sorted().map{($0,hardClassByDetectionIndex[$0] == .selected ? UInt32(0):UInt32(1))}
    return try session.update(obs,roots:roots).map{e in
      IOSFreshPrivacyClassEvidence(selectionClass:e.selection==0 ? .selected:.unselected,detectionIndex:e.detectionIndex,
        detection:detections[e.detectionIndex],conservativeUnknown:e.conservativeUnknown)
    }
  }
}

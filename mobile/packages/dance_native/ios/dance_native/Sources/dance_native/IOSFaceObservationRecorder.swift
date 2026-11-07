import Foundation

/// Opt-in simulator-only evidence for one source PTS. Never affects face policy.
enum IOSFaceObservationRecorder {
  static func record(primary:[IOSFaceCandidate],detail:[IOSFaceCandidate],persons:[IOSPreviewPerson],
    hero:Int?,preprocess:IOSYoloPreprocessResult,timestampUs:Int64) {
    #if DEBUG && targetEnvironment(simulator)
    let environment=ProcessInfo.processInfo.environment
    guard environment["WOAH_PRIVACY_TRACE"]=="1",
      let requested=Int64(environment["WOAH_PRIVACY_TRACE_FACE_US"] ?? ""),requested==timestampUs,
      let directory=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask).first else{return}
    func face(_ f:IOSFaceCandidate)->[String:Any] {
      ["bbox":[Double(f.x1),Double(f.y1),Double(f.x2),Double(f.y2)],"confidence":Double(f.confidence),
       "yaw":Double(f.yaw),"view":f.observationView,"landmarks":f.landmarks.map{[Double($0.x),Double($0.y)]}]
    }
    let rows:[[String:Any]]=persons.map {p in
      ["id":p.id,"bbox":[Double(p.detection.x1),Double(p.detection.y1),Double(p.detection.x2),Double(p.detection.y2)],
       "mask_base64":Data(p.detection.mask).base64EncodedString()]
    }
    let value:[String:Any]=["schema":"frame-kernel-face-observation-capture-v1","timestamp_us":timestampUs,
      "hero":hero.map{$0 as Any} ?? NSNull(),"source_width":preprocess.sourceWidth,"source_height":preprocess.sourceHeight,
      "scale":Double(preprocess.scale),"pad_left":Double(preprocess.padLeft),"pad_top":Double(preprocess.padTop),
      "input_size":preprocess.inputSize,"proto_size":IOSYoloPostprocessor.protoSize,"persons":rows,
      "primary":primary.map(face),"detail":detail.map(face)]
    guard let data=try? JSONSerialization.data(withJSONObject:value,options:[.sortedKeys]) else{return}
    let path=directory.appendingPathComponent("qa_face_observation_\(timestampUs)_\(UUID().uuidString).json")
    try? data.write(to:path,options:.atomic)
    #endif
  }
}

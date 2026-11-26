import AVFoundation
import CoreImage
import ImageIO
import UniformTypeIdentifiers
import Foundation
@_implementationOnly import FrameKernelC

struct IOSComparisonCutoutSeed: Sendable, Equatable {
  let ptsUs: Int64
  let x: Float
  let y: Float
}

enum IOSComparisonCutoutAnalysisError: Error {
  case invalidSelection, unsupportedRange, invalidFrame, invalidTracking, cancelled
}

/// Owns only the experimental comparison analysis. It does not mutate the
/// privacy pipeline or its inference threshold.
final class IOSComparisonCutoutAnalysis {
  static let maximumFrames = 3600
  private static let maximumPrefixBytes:UInt64 = 512 * 1024 * 1024
  private let runner = IOSYoloRunner()
  private let context = CIContext(options: [.cacheIntermediates: false])

  private final class Track {
    let id = frame_kernel_tracking_create(frame_kernel_tracking_defaults())
    deinit { if id != 0 { _ = frame_kernel_tracking_destroy(id) } }
    func step(_ observation: Observation, seed: Int = -1) throws -> Int {
      guard id != 0 else { throw IOSComparisonCutoutAnalysisError.invalidTracking }
      let result = observation.rows.withUnsafeBufferPointer { rows in observation.masks.withUnsafeBufferPointer { masks in
        frame_kernel_cutout_track_step(id,observation.pts,Int32(seed),rows.baseAddress,rows.count,
          masks.baseAddress,masks.count,observation.transform)
      } }
      guard result >= -1 else { throw IOSComparisonCutoutAnalysisError.invalidTracking }
      return Int(result)
    }
  }

  struct Observation: @unchecked Sendable {
    let pts: Int64
    let stamp: IOSComparisonSourceStamp
    let rows: [FrameKernelInstanceDetection]
    let masks: [UInt8]
    let transform: FrameKernelTransform
    let area = 160*160

    init(pts: Int64, stamp: IOSComparisonSourceStamp, result: IOSYoloInferenceResult) throws {
      self.pts = pts; self.stamp = stamp
      let p = result.preprocess
      guard result.detections.count <= 256, result.detections.allSatisfy({ $0.mask.count == 160*160 }) else {
        throw IOSComparisonCutoutAnalysisError.invalidFrame
      }
      rows = result.detections.map { d in
        FrameKernelInstanceDetection(bounds: FrameKernelRect(left: d.x1,top: d.y1,right: d.x2,bottom: d.y2),
          confidence: d.confidence)
      }
      masks = result.detections.flatMap(\.mask)
      transform = FrameKernelTransform(scale: p.scale,pad_left: p.padLeft,pad_top: p.padTop,
        source_width: UInt32(p.sourceWidth),source_height: UInt32(p.sourceHeight),input_size: UInt32(p.inputSize),proto_size: 160)
    }

    private init(pts:Int64,stamp:IOSComparisonSourceStamp,rows:[FrameKernelInstanceDetection],masks:[UInt8],transform:FrameKernelTransform){
      self.pts=pts;self.stamp=stamp;self.rows=rows;self.masks=masks;self.transform=transform
    }
    func encode()->Data {
      var data=IOSCutoutBytes()
      data.put(pts);data.put(UInt32(stamp.width));data.put(UInt32(stamp.height));data.putBytes(stamp.samples)
      data.putFloat(transform.scale);data.putFloat(transform.pad_left);data.putFloat(transform.pad_top)
      data.put(transform.source_width);data.put(transform.source_height);data.put(transform.input_size);data.put(transform.proto_size)
      data.put(UInt32(rows.count))
      for row in rows {
        data.putFloat(row.bounds.left);data.putFloat(row.bounds.top);data.putFloat(row.bounds.right)
        data.putFloat(row.bounds.bottom);data.putFloat(row.confidence)
      }
      data.putBytes(masks);return data.data
    }
    static func decode(_ bytes:Data)throws->Observation {
      var data=IOSCutoutBytes(bytes)
      let pts=try data.get(Int64.self),w=Int(try data.get(UInt32.self)),h=Int(try data.get(UInt32.self))
      let stamp=IOSComparisonSourceStamp(width:w,height:h,samples:try data.getBytes(3072))
      let transform=FrameKernelTransform(scale:try data.getFloat(),pad_left:try data.getFloat(),pad_top:try data.getFloat(),
        source_width:try data.get(UInt32.self),source_height:try data.get(UInt32.self),
        input_size:try data.get(UInt32.self),proto_size:try data.get(UInt32.self))
      let count=Int(try data.get(UInt32.self))
      guard pts>=0,w>0,h>0,count<=256,transform.proto_size==160, data.remaining==count*(20+160*160) else {
        throw IOSComparisonCutoutAnalysisError.invalidFrame
      }
      let rows=try (0..<count).map{_ in
        FrameKernelInstanceDetection(bounds:FrameKernelRect(left:try data.getFloat(),top:try data.getFloat(),
          right:try data.getFloat(),bottom:try data.getFloat()),confidence:try data.getFloat())
      }
      return Observation(pts:pts,stamp:stamp,rows:rows,masks:try data.getBytes(count*160*160),transform:transform)
    }

    func select(_ seed: IOSComparisonCutoutSeed) -> Int {
      masks.withUnsafeBufferPointer { Int(frame_kernel_cutout_select($0.baseAddress,$0.count,transform,seed.x,seed.y)) }
    }
    func thumbnail(index: Int,input: [Float32]) throws -> (Data,Int,Int) {
      let n = Int(transform.input_size), count = n*n
      guard rows.indices.contains(index), (1...1024).contains(n), input.count == count*3 else {
        throw IOSComparisonCutoutAnalysisError.invalidFrame
      }
      // The normalized planar tensor is exactly the RGB data used by YOLO.
      var rgba = [UInt8](repeating: 255,count: count*4)
      for i in 0..<count {
        for c in 0..<3 {
          let v = input[c*count+i]
          guard v.isFinite else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
          rgba[i*4+c] = UInt8((max(0,min(1,v))*255).rounded())
        }
      }
      var out = [UInt8](repeating: 0,count: rgba.count)
      let crop = masks.withUnsafeBufferPointer { mask in rgba.withUnsafeBufferPointer { rgb in
        out.withUnsafeMutableBufferPointer { result in
          frame_kernel_cutout_thumbnail(mask.baseAddress?.advanced(by: index*area),area,transform,
            rgb.baseAddress,rgb.count,0,result.baseAddress,result.count)
        }
      } }
      let w=Int(crop.width),h=Int(crop.height)
      guard w>0,h>0,let provider=CGDataProvider(data: Data(out) as CFData),
            let full=CGImage(width:n,height:n,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:n*4,
              space:CGColorSpaceCreateDeviceRGB(),
              bitmapInfo:CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
              provider:provider,decode:nil,shouldInterpolate:true,intent:.defaultIntent),
            let image=full.cropping(to:CGRect(x:Int(crop.x),y:Int(crop.top),width:w,height:h)) else {
        throw IOSComparisonCutoutAnalysisError.invalidFrame
      }
      let data=NSMutableData()
      guard let destination=CGImageDestinationCreateWithData(data,UTType.png.identifier as CFString,1,nil) else {
        throw IOSComparisonCutoutAnalysisError.invalidFrame
      }
      CGImageDestinationAddImage(destination,image,nil)
      guard CGImageDestinationFinalize(destination) else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
      return (data as Data,w,h)
    }

    func alpha(index: Int,width: Int,height: Int) throws -> [UInt8] {
      guard rows.indices.contains(index) else { throw IOSComparisonCutoutAnalysisError.invalidTracking }
      var output = [UInt8](repeating: 0,count: width*height)
      let result = masks.withUnsafeBufferPointer { input in output.withUnsafeMutableBufferPointer { out in
        frame_kernel_cutout_alpha(input.baseAddress?.advanced(by: index*area),area,transform,
          UInt32(width),UInt32(height),out.baseAddress,out.count)
      } }
      guard result == 0 else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
      return output
    }
  }

  struct Inspection: Sendable {
    let seed: IOSComparisonCutoutSeed
    let observation: Observation
    let index: Int
    let png: Data
    let width: Int
    let height: Int
  }

  private func infer(pixel: CVPixelBuffer,orientation: CGAffineTransform,pts: Int64,
      confidence: Float) throws -> (Observation,[Float32]) {
    guard let stamp = IOSComparisonSourceStamp(pixel) else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
    var image = IOSComparisonCompositor.oriented(pixel,transform: orientation)
    let extent = image.extent
    let scale = min(1,768 / max(extent.width,extent.height))
    let w = max(2,Int((extent.width*scale).rounded())), h = max(2,Int((extent.height*scale).rounded()))
    image = image.transformed(by: CGAffineTransform(scaleX: CGFloat(w)/extent.width,y: CGFloat(h)/extent.height))
    guard let cg = context.createCGImage(image,from: CGRect(x: 0,y: 0,width: w,height: h)) else {
      throw IOSComparisonCutoutAnalysisError.invalidFrame
    }
    let result = try runner.run(image: cg,preferredBackend: .xnnpack,confidenceThreshold: confidence)
    return (try Observation(pts:pts,stamp:stamp,result:result),result.preprocess.input)
  }

  func inspect(url: URL,seed: IOSComparisonCutoutSeed,isCancelled: () -> Bool) throws -> Inspection {
    guard seed.ptsUs >= 0,seed.x.isFinite,seed.y.isFinite,
          (0..<1).contains(seed.x),(0..<1).contains(seed.y) else { throw IOSComparisonCutoutAnalysisError.invalidSelection }
    let scoped=url.isFileURL && url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    let asset=AVURLAsset(url:url,options:[AVURLAssetPreferPreciseDurationAndTimingKey:true])
    guard let video=asset.tracks(withMediaType:.video).first else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
    let reader=try AVAssetReader(asset:asset)
    defer { reader.cancelReading() }
    let output=AVAssetReaderTrackOutput(track:video,outputSettings:[
      kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32BGRA,
      kCVPixelBufferIOSurfacePropertiesKey as String:[:]])
    output.alwaysCopiesSampleData=false
    let begin=CMTimeMaximum(video.timeRange.start,CMTime(value:max(0,seed.ptsUs-1),timescale:1_000_000))
    reader.timeRange=CMTimeRange(start:begin,end:CMTimeRangeGetEnd(video.timeRange))
    guard reader.canAdd(output) else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
    reader.add(output)
    guard reader.startReading() else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
    while !isCancelled() {
      guard let sample=output.copyNextSampleBuffer() else { break }
      guard let pts=IOSComparisonTimeCore.map(CMSampleBufferGetOutputPresentationTimeStamp(sample)) else { continue }
      if pts<seed.ptsUs { continue }
      guard pts==seed.ptsUs,let pixel=CMSampleBufferGetImageBuffer(sample) else { break }
      let (observation,input)=try infer(pixel:pixel,orientation:video.preferredTransform,pts:pts,confidence:0.25)
      let index=observation.select(seed)
      guard index>=0 else { throw IOSComparisonCutoutAnalysisError.invalidSelection }
      let (png,w,h)=try observation.thumbnail(index:index,input:input)
      if isCancelled() { throw CancellationError() }
      return Inspection(seed:seed,observation:observation,index:index,png:png,width:w,height:h)
    }
    if isCancelled() { throw CancellationError() }
    throw IOSComparisonCutoutAnalysisError.invalidFrame
  }

  static func window(_ sorted: [Int64],start: Int64,end: Int64,anchor: Int64) throws -> [Int64] {
    guard start >= 0, end >= start, anchor >= 0,
          let first = IOSComparisonTimeCore.frameIndex(sorted,time: start),
          let last = IOSComparisonTimeCore.frameIndex(sorted,time: end),
          last >= first, last-first+1 <= maximumFrames else { throw IOSComparisonCutoutAnalysisError.unsupportedRange }
    let result = Array(sorted[first...last])
    guard result.contains(anchor) else { throw IOSComparisonCutoutAnalysisError.invalidSelection }
    return result
  }

  func analyze(url: URL,index: IOSComparisonFrameIndex,start: Int64,end: Int64,
    seed: IOSComparisonCutoutSeed,reference: Bool,mirror: Bool,inspection: Inspection? = nil,isCancelled: () -> Bool) throws -> IOSComparisonCutoutTrackCache {
    func check() throws { if isCancelled() { throw CancellationError() } }
    try check()
    guard seed.x.isFinite, seed.y.isFinite, (0..<1).contains(seed.x), (0..<1).contains(seed.y) else {
      throw IOSComparisonCutoutAnalysisError.invalidSelection
    }
    let fingerprint=try IOSComparisonCutoutFingerprint.read(url,current:{!isCancelled()})
    let times = try Self.window(index.presentationTimesUs,start: start,end: end,anchor: seed.ptsUs)
    let scoped = url.isFileURL && url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    let asset = AVURLAsset(url: url,options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
    guard let video = asset.tracks(withMediaType: .video).first else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
    let orientation = video.preferredTransform
    let reader = try AVAssetReader(asset: asset)
    defer { reader.cancelReading() }
    let output = AVAssetReaderTrackOutput(track: video,outputSettings: [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
      kCVPixelBufferIOSurfacePropertiesKey as String: [:]])
    output.alwaysCopiesSampleData = false
    // Keep original decode dependencies and include any rational first sample.
    let begin = CMTimeMaximum(video.timeRange.start,CMTime(value: max(0,times[0]-1),timescale: 1_000_000))
    reader.timeRange = CMTimeRange(start: begin,end: CMTimeRangeGetEnd(video.timeRange))
    guard reader.canAdd(output) else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
    reader.add(output)
    guard reader.startReading() else { throw IOSComparisonCutoutAnalysisError.invalidFrame }

    let forward = Track(), reverse = Track()
    let spool=try IOSComparisonCutoutDisk(budget:Self.maximumPrefixBytes)
    let entries=try IOSComparisonCutoutDiskTrack(times:times)
    var prefix:[IOSComparisonCutoutDisk.Record]=[]
    var placement: IOSComparisonCutoutPlacement?
    var cursor = 0

    func add(_ observation: Observation,_ selected: Int) throws {
      try check()
      var layer: IOSComparisonCutoutLayer?
      if selected >= 0, let placement {
        let sw = Double(observation.transform.source_width), sh = Double(observation.transform.source_height)
        let scale = 256 / max(sw,sh)
        let w = max(1,Int((sw*scale).rounded())), h = max(1,Int((sh*scale).rounded()))
        let alpha = try observation.alpha(index: selected,width: w,height: h)
        if alpha.contains(where: { $0 != 0 }) {
          layer = IOSComparisonCutoutLayer(sourcePtsUs: observation.pts,width: w,height: h,alpha: alpha,placement: placement)
        }
      }
      guard let placement else {throw IOSComparisonCutoutAnalysisError.invalidTracking}
      try entries.add(observation.pts,stamp:observation.stamp,layer:layer,placement:placement)
    }
    while cursor < times.count {
      try check()
      let found = try autoreleasepool { () throws -> Bool in
        guard let sample = output.copyNextSampleBuffer() else { return false }
        guard let pts = IOSComparisonTimeCore.map(CMSampleBufferGetOutputPresentationTimeStamp(sample)) else { return true }
        if pts < times[cursor] { return true }
        guard pts == times[cursor], let pixel = CMSampleBufferGetImageBuffer(sample),
              let stamp = IOSComparisonSourceStamp(pixel) else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
        let inspected=inspection.flatMap { $0.seed==seed && pts==seed.ptsUs ? $0 : nil }
        let observation: Observation
        if let inspected {
          guard inspected.observation.stamp.agrees(with: stamp) else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
          observation=inspected.observation
        } else {
          observation=try infer(pixel:pixel,orientation:orientation,pts:pts,confidence:pts==seed.ptsUs ? 0.25 : 0.20).0
        }
        cursor += 1
        if pts < seed.ptsUs {
          prefix.append(try spool.append(observation.encode()))
        } else if pts == seed.ptsUs {
          let selected = observation.select(seed)
          guard selected >= 0, inspected == nil || inspected?.index == selected else { throw IOSComparisonCutoutAnalysisError.invalidSelection }
          let box = observation.rows[selected].bounds
          let sw = Float(observation.transform.source_width), sh = Float(observation.transform.source_height)
          let anchor = SIMD4(box.left/sw,box.top/sh,box.right/sw,box.bottom/sh)
          guard IOSComparisonTimeCore.cutoutQuad(width: 960,height: 540,aspect: Double(sw/sh),anchor: anchor,
            x: reference ? 0.27 : 0.73,y: 0.88,subjectHeight: 0.70,mirror: mirror) != nil else {
            throw IOSComparisonCutoutAnalysisError.invalidSelection
          }
          placement = IOSComparisonCutoutPlacement(anchor: anchor,x: reference ? 0.27 : 0.73,y: 0.88,
            subjectHeight: 0.70,mirror: mirror,opacity: 1)
          let f = try forward.step(observation,seed: selected), r = try reverse.step(observation,seed: selected)
          guard f == selected,r == selected else { throw IOSComparisonCutoutAnalysisError.invalidTracking }
          try add(observation,selected)
          for record in prefix.reversed() {
            try check()
            let prior=try Observation.decode(spool.read(record))
            try add(prior,reverse.step(prior))
          }
          prefix.removeAll();spool.close()
        } else {
          try add(observation,forward.step(observation))
        }
        return true
      }
      guard found else { break }
    }
    try check()
    guard cursor==times.count else {throw IOSComparisonCutoutAnalysisError.invalidFrame}
    try entries.seal(anchor:seed.ptsUs)
    guard try IOSComparisonCutoutFingerprint.read(url,current:{!isCancelled()})==fingerprint else {
      throw IOSComparisonCutoutAnalysisError.invalidFrame
    }
    return IOSComparisonCutoutTrackCache(disk:entries,fingerprint:fingerprint)
  }
}

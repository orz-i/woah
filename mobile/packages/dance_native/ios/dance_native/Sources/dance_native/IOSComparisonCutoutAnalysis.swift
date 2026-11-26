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
    let id = frame_kernel_dance_cutout_create()
    deinit { if id != 0 { _ = frame_kernel_dance_cutout_destroy(id) } }
    func step(_ observation: Observation, seed: Int = -1) throws -> Int {
      guard id != 0 else { throw IOSComparisonCutoutAnalysisError.invalidTracking }
      let result = observation.rows.withUnsafeBufferPointer { rows in observation.masks.withUnsafeBufferPointer { masks in
        observation.appearance.withUnsafeBufferPointer { appearance in
          frame_kernel_dance_cutout_step(id,observation.pts,Int32(seed),rows.baseAddress,rows.count,
            masks.baseAddress,masks.count,observation.transform,appearance.baseAddress,appearance.count)
        }
      } }
      guard result >= -1 else { throw IOSComparisonCutoutAnalysisError.invalidTracking }
      return Int(result)
    }
    func stepOrDetail(_ o:Observation,scheduled:Bool)throws->(Int,FrameKernelViewport){
      var roi=FrameKernelViewport()
      let status=o.rows.withUnsafeBufferPointer { rows in o.masks.withUnsafeBufferPointer { masks in
        o.appearance.withUnsafeBufferPointer { f in
          if scheduled {
            return frame_kernel_dance_step_or_detail_scheduled(id,o.pts,rows.baseAddress,rows.count,masks.baseAddress,masks.count,
              o.transform,f.baseAddress,f.count,&roi)
          }
          return frame_kernel_dance_step_or_detail(id,o.pts,rows.baseAddress,rows.count,masks.baseAddress,masks.count,
            o.transform,f.baseAddress,f.count,&roi)
        }
      } }
      guard status>=(-1) || status == -3 || status == -4 else{throw IOSComparisonCutoutAnalysisError.invalidTracking}
      return (Int(status),roi)
    }
    func finishDetail(_ o:Observation,fullCount:Int)throws->Int{
      let status=o.rows.withUnsafeBufferPointer { rows in o.masks.withUnsafeBufferPointer { masks in
        o.appearance.withUnsafeBufferPointer { f in
          frame_kernel_dance_finish_detail(id,o.pts,fullCount,rows.baseAddress,rows.count,
            masks.baseAddress,masks.count,o.transform,f.baseAddress,f.count)
        }
      } }
      guard status>=(-1) else{throw IOSComparisonCutoutAnalysisError.invalidTracking}
      return Int(status)
    }
  }

  struct Observation: @unchecked Sendable {
    let pts: Int64
    let stamp: IOSComparisonSourceStamp
    let rows: [FrameKernelInstanceDetection]
    let masks: [UInt8]
    let transform: FrameKernelTransform
    let appearance: [Float]
    static let appearanceBins=Int(FRAME_KERNEL_DANCE_APPEARANCE_BINS)
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
      let count=rows.count,rawMasks=masks,mapping=transform
      var descriptor=[Float](repeating:0,count:count*Self.appearanceBins)
      let status=p.input.withUnsafeBufferPointer { rgb in rawMasks.withUnsafeBufferPointer { masks in
        descriptor.withUnsafeMutableBufferPointer { out in
          frame_kernel_dance_appearance_planar(rgb.baseAddress,rgb.count,masks.baseAddress,masks.count,
            count,mapping,out.baseAddress,out.count)
        }
      } }
      guard status==0 else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
      appearance=descriptor
    }

    private init(pts:Int64,stamp:IOSComparisonSourceStamp,rows:[FrameKernelInstanceDetection],masks:[UInt8],transform:FrameKernelTransform,appearance:[Float]){
      self.pts=pts;self.stamp=stamp;self.rows=rows;self.masks=masks;self.transform=transform;self.appearance=appearance
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
      data.putBytes(masks)
      for value in appearance{data.putFloat(value)}
      return data.data
    }
    static func decode(_ bytes:Data)throws->Observation {
      var data=IOSCutoutBytes(bytes)
      let pts=try data.get(Int64.self),w=Int(try data.get(UInt32.self)),h=Int(try data.get(UInt32.self))
      let stamp=IOSComparisonSourceStamp(width:w,height:h,samples:try data.getBytes(3072))
      let transform=FrameKernelTransform(scale:try data.getFloat(),pad_left:try data.getFloat(),pad_top:try data.getFloat(),
        source_width:try data.get(UInt32.self),source_height:try data.get(UInt32.self),
        input_size:try data.get(UInt32.self),proto_size:try data.get(UInt32.self))
      let count=Int(try data.get(UInt32.self))
      guard pts>=0,w>0,h>0,count<=256,transform.proto_size==160, data.remaining==count*(20+160*160+Self.appearanceBins*4) else {
        throw IOSComparisonCutoutAnalysisError.invalidFrame
      }
      let rows=try (0..<count).map{_ in
        FrameKernelInstanceDetection(bounds:FrameKernelRect(left:try data.getFloat(),top:try data.getFloat(),
          right:try data.getFloat(),bottom:try data.getFloat()),confidence:try data.getFloat())
      }
      let masks=try data.getBytes(count*160*160)
      let appearance=try (0..<count*Self.appearanceBins).map{_ in try data.getFloat()}
      return Observation(pts:pts,stamp:stamp,rows:rows,masks:masks,transform:transform,appearance:appearance)
    }

    func select(_ seed: IOSComparisonCutoutSeed) -> Int {
      masks.withUnsafeBufferPointer { Int(frame_kernel_cutout_select($0.baseAddress,$0.count,transform,seed.x,seed.y)) }
    }
    func withDetail(_ local:Self,roi:FrameKernelViewport)throws->Self{
      guard pts==local.pts,stamp.agrees(with:local.stamp) else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
      var allRows=rows,allMasks=masks,allAppearance=appearance
      for i in local.rows.indices{
        if allRows.count>=256{break}
        var output=[UInt8](repeating:0,count:area)
        var mapped=FrameKernelInstanceDetection()
        let status=local.masks.withUnsafeBufferPointer{raw in allRows.withUnsafeBufferPointer{full in
          allMasks.withUnsafeBufferPointer{fullMasks in output.withUnsafeMutableBufferPointer{out in
            frame_kernel_dance_detail_remap(local.rows[i],raw.baseAddress?.advanced(by:i*area),area,
              local.transform,transform,roi,full.baseAddress,full.count,fullMasks.baseAddress,fullMasks.count,
              out.baseAddress,out.count,&mapped)
          } }
        } }
        guard status>=0 else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
        if status==0{continue}
        allRows.append(mapped);allMasks.append(contentsOf:output)
        allAppearance.append(contentsOf:local.appearance[i*Self.appearanceBins..<(i+1)*Self.appearanceBins])
      }
      return Self(pts:pts,stamp:stamp,rows:allRows,masks:allMasks,transform:transform,appearance:allAppearance)
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

  /// Both direction handles live until the lane finishes. The initial
  /// immutable preview and the final disk cache contain the same alpha bytes.
  final class Work: @unchecked Sendable {
    let preview:IOSComparisonCutoutTrackCache
    let total:Int
    private let disk:IOSComparisonCutoutDiskTrack
    private let completeWork:() throws -> IOSComparisonCutoutTrackCache
    private var finished=false
    init(preview:IOSComparisonCutoutTrackCache,total:Int,disk:IOSComparisonCutoutDiskTrack,
         completeWork:@escaping () throws -> IOSComparisonCutoutTrackCache){
      self.preview=preview;self.total=total;self.disk=disk;self.completeWork=completeWork
    }
    var analyzed:Int{disk.analyzedCount}
    var visible:Int{disk.visibleCount}
    func finish()throws->IOSComparisonCutoutTrackCache {
      guard !finished else {throw IOSComparisonCutoutAnalysisError.invalidTracking}
      let result=try completeWork();finished=true;return result
    }
  }

  func begin(url: URL,index: IOSComparisonFrameIndex,start: Int64,end: Int64,
    firstPreview:Int64,lastPreview:Int64,seed: IOSComparisonCutoutSeed,reference: Bool,mirror: Bool,
    inspection: Inspection? = nil,fixedGrid:(Int,Int)?=nil,detailEnabled:Bool=true,detailScheduled:Bool=true,isCancelled: @escaping () -> Bool) throws -> Work {
    func check() throws { if isCancelled() { throw CancellationError() } }
    try check()
    guard seed.x.isFinite, seed.y.isFinite, (0..<1).contains(seed.x), (0..<1).contains(seed.y) else {
      throw IOSComparisonCutoutAnalysisError.invalidSelection
    }
    let fingerprint=try IOSComparisonCutoutFingerprint.read(url,current:{!isCancelled()})
    let times=try Self.window(index.presentationTimesUs,start:start,end:end,anchor:seed.ptsUs)
    let nearby=try Self.window(times,start:firstPreview,end:lastPreview,anchor:seed.ptsUs)
    guard nearby.count<=256,let first=times.firstIndex(of:nearby[0]),
          let last=times.firstIndex(of:nearby[nearby.count-1]),let anchor=times.firstIndex(of:seed.ptsUs) else {
      throw IOSComparisonCutoutAnalysisError.unsupportedRange
    }
    let asset=AVURLAsset(url:url,options:[AVURLAssetPreferPreciseDurationAndTimingKey:true])
    guard let video=asset.tracks(withMediaType:.video).first else {throw IOSComparisonCutoutAnalysisError.invalidFrame}
    let orientation=video.preferredTransform
    let forward=Track(),reverse=Track()
    let entries=try IOSComparisonCutoutDiskTrack(times:times)
    var small:[Int64:IOSComparisonCachedCutout]=[:]
    var placement:IOSComparisonCutoutPlacement?
    var dimensions=fixedGrid
    func add(_ o:Observation,_ selected:Int,_ initial:Bool) throws {
      try check()
      guard let placement else {throw IOSComparisonCutoutAnalysisError.invalidTracking}
      var layer:IOSComparisonCutoutLayer?
      if selected>=0 {
        if dimensions==nil {
          let grid=frame_kernel_cutout_grid(o.transform.source_width,o.transform.source_height,UInt32(times.count),UInt32(nearby.count))
          dimensions=(Int(grid.width),Int(grid.height))
        }
        guard let (w,h)=dimensions,(1...384).contains(w),(1...384).contains(h),
          (w*h+3200)*times.count<=256*1024*1024,(w*h+3200)*nearby.count<=18*1024*1024 else{throw IOSComparisonCutoutAnalysisError.unsupportedRange}
        let alpha=try o.alpha(index:selected,width:w,height:h)
        if alpha.contains(where:{$0 != 0}){
          layer=IOSComparisonCutoutLayer(sourcePtsUs:o.pts,width:w,height:h,alpha:alpha,placement:placement)
        }
      }
      try entries.add(o.pts,stamp:o.stamp,layer:layer,placement:placement)
      if initial{small[o.pts]=IOSComparisonCachedCutout(stamp:o.stamp,layer:layer)}
    }
    func decode(_ from:Int,_ to:Int,_ consume:(Observation,CVPixelBuffer)throws->Void)throws{
      if from>=to{return}
      let scoped=url.isFileURL && url.startAccessingSecurityScopedResource()
      defer{if scoped{url.stopAccessingSecurityScopedResource()}}
      let reader=try AVAssetReader(asset:asset)
      defer{reader.cancelReading()}
      let output=AVAssetReaderTrackOutput(track:video,outputSettings:[
        kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32BGRA,
        kCVPixelBufferIOSurfacePropertiesKey as String:[:]])
      output.alwaysCopiesSampleData=false
      let begin=CMTimeMaximum(video.timeRange.start,CMTime(value:max(0,times[from]-1),timescale:1_000_000))
      reader.timeRange=CMTimeRange(start:begin,end:CMTimeRangeGetEnd(video.timeRange))
      guard reader.canAdd(output) else {throw IOSComparisonCutoutAnalysisError.invalidFrame}
      reader.add(output)
      guard reader.startReading() else {throw IOSComparisonCutoutAnalysisError.invalidFrame}
      var cursor=from
      while cursor<to{
        try check()
        let found=try autoreleasepool{()->Bool in
          guard let sample=output.copyNextSampleBuffer() else{return false}
          guard let pts=IOSComparisonTimeCore.map(CMSampleBufferGetOutputPresentationTimeStamp(sample)) else{return true}
          if pts<times[cursor]{return true}
          guard pts==times[cursor],let pixel=CMSampleBufferGetImageBuffer(sample),
                let stamp=IOSComparisonSourceStamp(pixel) else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
          let inspected=inspection.flatMap{$0.seed==seed && pts==seed.ptsUs ? $0:nil}
          let observation:Observation
          if let inspected{
            guard inspected.observation.stamp.agrees(with:stamp) else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
            observation=inspected.observation
          }else{observation=try self.infer(pixel:pixel,orientation:orientation,pts:pts,confidence:pts==seed.ptsUs ? 0.25:0.20).0}
          cursor+=1
          try consume(observation,pixel)
          return true
        }
        if !found{break}
      }
      guard cursor==to else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
    }
    let detailBudget=min(160,max(1,(times.count+3)/4))
    var detailAttempts=0,detailAccepted=0,detailFailures=0,detailSkipped=0
    var detailInline=0,detailReaderOpens=0,detailCacheHits=0,detailReadSamples=0
    var detailDecodeMs=0.0,detailInferenceMs=0.0
    func localObservation(_ original:Observation,_ roi:FrameKernelViewport,_ pixel:CVPixelBuffer)throws->Observation{
        try check()
        guard let stamp=IOSComparisonSourceStamp(pixel),original.stamp.agrees(with:stamp) else{
          throw IOSComparisonCutoutAnalysisError.invalidFrame
        }
        let before=ProcessInfo.processInfo.systemUptime
        let image=IOSComparisonCompositor.oriented(pixel,transform:orientation)
        let extent=image.extent,sw=CGFloat(original.transform.source_width),sh=CGFloat(original.transform.source_height)
        let w=CGFloat(roi.width),h=CGFloat(roi.height)
        guard w>0,h>0,roi.x>=0,roi.top>=0,CGFloat(roi.x)+w<=sw,CGFloat(roi.top)+h<=sh else{
          throw IOSComparisonCutoutAnalysisError.invalidFrame
        }
        // Source pixels, never the already downscaled inference tensor.
        let region=CGRect(x:CGFloat(roi.x)*extent.width/sw,
          y:(sh-CGFloat(roi.top)-h)*extent.height/sh,width:w*extent.width/sw,height:h*extent.height/sh)
        let cropped=image.cropped(to:region)
          .transformed(by:CGAffineTransform(translationX:-region.minX,y:-region.minY))
          .transformed(by:CGAffineTransform(scaleX:w/region.width,y:h/region.height))
        guard let cg=self.context.createCGImage(cropped,from:CGRect(x:0,y:0,width:w,height:h)) else{
          throw IOSComparisonCutoutAnalysisError.invalidFrame
        }
        detailDecodeMs+=(ProcessInfo.processInfo.systemUptime-before)*1000
        try check()
        let inferStart=ProcessInfo.processInfo.systemUptime
        let result=try self.runner.run(image:cg,preferredBackend:.xnnpack,confidenceThreshold:0.25)
        detailInferenceMs+=(ProcessInfo.processInfo.systemUptime-inferStart)*1000
        return try Observation(pts:original.pts,stamp:stamp,result:result)
    }
    // Reverse inference observations are already spooled without source RGB.
    // Read only on demand, in small ascending blocks, retain at most four
    // original frames (24 MiB). No pixel buffer leaves this reverse span.
    func loadReverse(_ lower:Int,_ wanted:Int)throws->[Int64:CVPixelBuffer]{
      try check()
      let readStart=ProcessInfo.processInfo.systemUptime
      defer{detailDecodeMs+=(ProcessInfo.processInfo.systemUptime-readStart)*1000}
      let scoped=url.isFileURL && url.startAccessingSecurityScopedResource()
      defer{if scoped{url.stopAccessingSecurityScopedResource()}}
      let reader=try AVAssetReader(asset:asset)
      defer{reader.cancelReading()}
      detailReaderOpens+=1
      let output=AVAssetReaderTrackOutput(track:video,outputSettings:[
        kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32BGRA,
        kCVPixelBufferIOSurfacePropertiesKey as String:[:]])
      output.alwaysCopiesSampleData=false
      let first=max(lower,wanted-3)
      let begin=CMTimeMaximum(video.timeRange.start,CMTime(value:max(0,times[first]-1),timescale:1_000_000))
      reader.timeRange=CMTimeRange(start:begin,end:CMTimeRangeGetEnd(video.timeRange))
      guard reader.canAdd(output) else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
      reader.add(output)
      guard reader.startReading() else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
      let budget=24*1024*1024
      var cache:[Int64:CVPixelBuffer]=[:],order:[Int64]=[],bytes=0
      func size(_ pixel:CVPixelBuffer)->Int{CVPixelBufferGetBytesPerRow(pixel)*CVPixelBufferGetHeight(pixel)}
      var cursor=first
      while cursor<=wanted{
        try check()
        let ok=try autoreleasepool{()->Bool in
          guard let sample=output.copyNextSampleBuffer() else{return false}
          guard let pts=IOSComparisonTimeCore.map(CMSampleBufferGetOutputPresentationTimeStamp(sample)) else{return true}
          if pts<times[cursor]{return true}
          guard pts==times[cursor],let pixel=CMSampleBufferGetImageBuffer(sample) else{
            throw IOSComparisonCutoutAnalysisError.invalidFrame
          }
          detailReadSamples+=1
          let n=size(pixel)
          guard n>0 else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
          while !order.isEmpty && bytes+n>budget{
            let key=order.removeFirst()
            if let old=cache.removeValue(forKey:key){bytes-=size(old)}
          }
          // An unusually large frame is used only for its requested PTS,
          // never held alongside any other frame.
          if n<=budget || cursor==wanted{
            cache[pts]=pixel;order.append(pts);bytes+=n
          }
          cursor+=1
          return true
        }
        if !ok{break}
      }
      guard cursor==wanted+1,cache[times[wanted]] != nil else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
      return cache
    }
    func addTracked(_ o:Observation,_ track:Track,_ initial:Bool,
                    source:()throws->CVPixelBuffer)throws{
      try check()
      if !detailEnabled || detailAttempts>=detailBudget{
        try add(o,track.step(o),initial);return
      }
      let (selected,roi)=try track.stepOrDetail(o,scheduled:detailScheduled)
      if selected == -4{detailSkipped+=1;try add(o,-1,initial);return}
      if selected != -3{try add(o,selected,initial);return}
      detailAttempts+=1
      var merged=o
      do{
        let local=try localObservation(o,roi,source())
        merged=try o.withDetail(local,roi:roi)
      }catch is CancellationError{throw CancellationError()}
      catch{detailFailures+=1}
      try check()
      let index=try track.finishDetail(merged,fullCount:o.rows.count)
      if index>=o.rows.count{detailAccepted+=1}
      try add(merged,index,initial)
    }
    func backwards(_ from:Int,_ to:Int,_ initial:Bool)throws{
      if from>=to{return}
      let spool=try IOSComparisonCutoutDisk(budget:Self.maximumPrefixBytes)
      defer{spool.close()}
      var records:[IOSComparisonCutoutDisk.Record]=[]
      try decode(from,to){o,_ in records.append(try spool.append(o.encode()))}
      var cache:[Int64:CVPixelBuffer]=[:]
      for (i,record) in records.enumerated().reversed(){
        try check()
        let prior=try Observation.decode(spool.read(record))
        try addTracked(prior,reverse,initial){
          if let pixel=cache[prior.pts]{detailCacheHits+=1;return pixel}
          cache.removeAll()
          cache=try loadReverse(from,from+i)
          guard let pixel=cache[prior.pts] else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
          return pixel
        }
        // Future reverse requests can never use this (or a later) sample.
        cache.removeValue(forKey:prior.pts)
      }
    }
    try decode(anchor,anchor+1){o,_ in
      let selected=o.select(seed)
      guard selected>=0,inspection?.seed != seed || inspection?.index==selected else{
        throw IOSComparisonCutoutAnalysisError.invalidSelection
      }
      let box=o.rows[selected].bounds
      let sw=Float(o.transform.source_width),sh=Float(o.transform.source_height)
      let fixed=SIMD4(box.left/sw,box.top/sh,box.right/sw,box.bottom/sh)
      guard IOSComparisonTimeCore.cutoutQuad(width:960,height:540,aspect:Double(sw/sh),anchor:fixed,
        x:reference ? 0.27:0.73,y:0.88,subjectHeight:0.70,mirror:mirror) != nil else{
        throw IOSComparisonCutoutAnalysisError.invalidSelection
      }
      placement=IOSComparisonCutoutPlacement(anchor:fixed,x:reference ? 0.27:0.73,y:0.88,subjectHeight:0.70,mirror:mirror,opacity:1)
      guard try forward.step(o,seed:selected)==selected,try reverse.step(o,seed:selected)==selected else{
        throw IOSComparisonCutoutAnalysisError.invalidTracking
      }
      try add(o,selected,true)
    }
    try backwards(first,anchor,true)
    try decode(anchor+1,last+1){o,pixel in
      try addTracked(o,forward,true){detailInline+=1;return pixel}
    }
    guard small.count==nearby.count,small[seed.ptsUs]?.layer != nil else{throw IOSComparisonCutoutAnalysisError.invalidFrame}
    let preview=IOSComparisonCutoutTrackCache(entries:small,times:nearby)
    small.removeAll()
    return Work(preview:preview,total:times.count,disk:entries){
      try decode(last+1,times.count){o,pixel in
        try addTracked(o,forward,false){detailInline+=1;return pixel}
      }
      try backwards(0,first,false)
      try entries.seal(anchor:seed.ptsUs)
      #if DEBUG
      NSLog("WoahCutoutDetail role=%@ frames=%d attempts=%d accepted=%d failures=%d skipped=%d inline=%d readerOpens=%d cacheHits=%d readSamples=%d decodeMs=%.1f inferenceMs=%.1f",reference ? "reference":"target",times.count,detailAttempts,detailAccepted,detailFailures,detailSkipped,detailInline,detailReaderOpens,detailCacheHits,detailReadSamples,detailDecodeMs,detailInferenceMs)
      #endif

      guard try IOSComparisonCutoutFingerprint.read(url,current:{!isCancelled()})==fingerprint else{
        throw IOSComparisonCutoutAnalysisError.invalidFrame
      }
      return IOSComparisonCutoutTrackCache(disk:entries,fingerprint:fingerprint)
    }
  }
}

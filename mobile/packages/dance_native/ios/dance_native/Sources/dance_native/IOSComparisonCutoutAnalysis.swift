import AVFoundation
import CoreImage
import Foundation
@_implementationOnly import FrameKernelC

struct IOSComparisonCutoutSeed: Sendable {
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
  static let maximumFrames = 300
  private static let maximumPrefixBytes = 32 * 1024 * 1024
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

  private struct Observation {
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

    func select(_ seed: IOSComparisonCutoutSeed) -> Int {
      masks.withUnsafeBufferPointer { Int(frame_kernel_cutout_select($0.baseAddress,$0.count,transform,seed.x,seed.y)) }
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
    seed: IOSComparisonCutoutSeed,reference: Bool,mirror: Bool,isCancelled: () -> Bool) throws -> IOSComparisonCutoutTrackCache {
    func check() throws { if isCancelled() { throw CancellationError() } }
    try check()
    guard seed.x.isFinite, seed.y.isFinite, (0..<1).contains(seed.x), (0..<1).contains(seed.y) else {
      throw IOSComparisonCutoutAnalysisError.invalidSelection
    }
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
    var prefix: [Observation] = [], prefixBytes = 0
    var entries: [Int64: IOSComparisonCachedCutout] = [:]
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
      entries[observation.pts] = IOSComparisonCachedCutout(stamp: observation.stamp,layer: layer)
    }
    while cursor < times.count {
      try check()
      let found = try autoreleasepool { () throws -> Bool in
        guard let sample = output.copyNextSampleBuffer() else { return false }
        guard let pts = IOSComparisonTimeCore.map(CMSampleBufferGetOutputPresentationTimeStamp(sample)) else { return true }
        if pts < times[cursor] { return true }
        guard pts == times[cursor], let pixel = CMSampleBufferGetImageBuffer(sample),
              let stamp = IOSComparisonSourceStamp(pixel) else { throw IOSComparisonCutoutAnalysisError.invalidFrame }
        var image = IOSComparisonCompositor.oriented(pixel,transform: orientation)
        let extent = image.extent
        let scale = min(1,768 / max(extent.width,extent.height))
        let w = max(2,Int((extent.width*scale).rounded())), h = max(2,Int((extent.height*scale).rounded()))
        image = image.transformed(by: CGAffineTransform(scaleX: CGFloat(w)/extent.width,y: CGFloat(h)/extent.height))
        guard let cg = context.createCGImage(image,from: CGRect(x: 0,y: 0,width: w,height: h)) else {
          throw IOSComparisonCutoutAnalysisError.invalidFrame
        }
        let result = try runner.run(image: cg,preferredBackend: .xnnpack,confidenceThreshold: pts == seed.ptsUs ? 0.25 : 0.20)
        let observation = try Observation(pts: pts,stamp: stamp,result: result)
        cursor += 1
        if pts < seed.ptsUs {
          prefixBytes += observation.masks.count
          guard prefixBytes <= Self.maximumPrefixBytes else { throw IOSComparisonCutoutAnalysisError.unsupportedRange }
          prefix.append(observation)
        } else if pts == seed.ptsUs {
          let selected = observation.select(seed)
          guard selected >= 0 else { throw IOSComparisonCutoutAnalysisError.invalidSelection }
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
          for prior in prefix.reversed() { try add(prior,reverse.step(prior)) }
          prefix.removeAll()
        } else {
          try add(observation,forward.step(observation))
        }
        return true
      }
      guard found else { break }
    }
    try check()
    guard cursor == times.count,entries.count == times.count,entries[seed.ptsUs]?.layer != nil else {
      throw IOSComparisonCutoutAnalysisError.invalidFrame
    }
    return IOSComparisonCutoutTrackCache(entries: entries,times: times)
  }
}

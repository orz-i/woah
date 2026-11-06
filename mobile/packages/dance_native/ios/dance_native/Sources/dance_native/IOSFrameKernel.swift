import CoreGraphics
import Foundation
@_implementationOnly import FrameKernelC

/// Platform transport only. No independent geometry or protection policy here.
enum IOSFrameKernel {
  private static let checkedABI: Void = {
    precondition(frame_kernel_abi_version() == 1, "Unsupported FrameKernel ABI")
  }()

  final class BoxFilter {
    private var state = FrameKernelBoxFilter()
    init(bounds: SIMD4<Float>, timestampUs: Int64) throws {
      _ = IOSFrameKernel.checkedABI
      _ = try step(0,bounds:bounds,pts:timestampUs)
    }
    func predict(timestampUs: Int64) throws -> SIMD4<Float> { try step(1,pts:timestampUs).bounds }
    func observe(bounds: SIMD4<Float>, timestampUs: Int64) throws { _ = try step(2,bounds:bounds,pts:timestampUs) }
    func dampen(_ factor: Float) throws { _ = try step(3,factor:factor) }
    func gating(bounds: SIMD4<Float>) throws -> Float { try step(4,bounds:bounds).distance }
    func bounds() throws -> SIMD4<Float> { try step(5).bounds }
    func snapshotFloats() -> [Float] {
      withUnsafeBytes(of:state) { Array($0.bindMemory(to:Float.self).prefix(72)) }
    }
    private func step(_ operation: UInt32, bounds: SIMD4<Float> = .zero,
        pts: Int64 = -1, factor: Float = 1) throws -> (bounds: SIMD4<Float>, distance: Float) {
      var out = FrameKernelRect();var distance:Float=0
      let input = FrameKernelRect(left:bounds.x,top:bounds.y,right:bounds.z,bottom:bounds.w)
      guard frame_kernel_box_filter_step(&state,operation,input,pts,factor,&out,&distance) == 0 else {
        throw PigeonError(code:"TRACKING_MOTION_INVALID",message:"FrameKernel rejected the motion state.",details:nil)
      }
      return (SIMD4<Float>(out.left,out.top,out.right,out.bottom),distance)
    }
  }

  static func letterbox(width: Int, height: Int, outputWidth: Int, outputHeight: Int) throws -> [Float] {
    _ = checkedABI
    guard [width,height,outputWidth,outputHeight].allSatisfy({ (1...65535).contains($0) }) else { throw pixelError() }
    var plan = FrameKernelLetterbox()
    guard frame_kernel_letterbox(UInt32(width),UInt32(height),UInt32(outputWidth),UInt32(outputHeight),&plan) == 0 else { throw pixelError() }
    return [plan.scale,plan.width,plan.height,plan.left,plan.top]
  }

  static func normalizePixels(_ input: [UInt8], width: Int, height: Int, flipRows: Bool = false,
      flipColumns: Bool = false, interleaved: Bool = false, scale: Float = 1 / 255, bias: Float = 0) throws -> [Float] {
    _ = checkedABI
    guard (1...65535).contains(width), (1...65535).contains(height), width*height <= 16_777_216 else { throw pixelError() }
    var output = [Float](repeating:0,count:width*height*3)
    let spec = FrameKernelPixelSpec(width:UInt32(width),height:UInt32(height),row_stride:UInt32(width*4),
      channels:4,red:0,green:1,blue:2,flip_rows:flipRows ? 1:0,flip_columns:flipColumns ? 1:0,
      interleaved:interleaved ? 1:0,scale:scale,bias:bias)
    let result = input.withUnsafeBufferPointer { pixels in output.withUnsafeMutableBufferPointer { out in
      frame_kernel_normalize_pixels(pixels.baseAddress,pixels.count,spec,out.baseAddress,out.count)
    } }
    guard result == 0 else { throw pixelError() }
    return output
  }

  static func resizeRgba(_ input: [UInt8], width: Int, height: Int, outputWidth: Int,
      outputHeight: Int, fill: UInt8 = 114) throws -> (pixels: [UInt8], geometry: [Float]) {
    _ = checkedABI
    guard [width,height,outputWidth,outputHeight].allSatisfy({ (1...65535).contains($0) }),
      outputWidth*outputHeight <= 16_777_216 else { throw pixelError() }
    var output = [UInt8](repeating:0,count:outputWidth*outputHeight*4)
    var plan = FrameKernelLetterbox()
    let result = input.withUnsafeBufferPointer { pixels in output.withUnsafeMutableBufferPointer { out in
      frame_kernel_resize_rgba(pixels.baseAddress,pixels.count,UInt32(width),UInt32(height),UInt32(width*4),
        out.baseAddress,out.count,UInt32(outputWidth),UInt32(outputHeight),fill,&plan)
    } }
    guard result == 0 else { throw pixelError() }
    return (output,[plan.scale,plan.width,plan.height,plan.left,plan.top])
  }

  private static func pixelError() -> PigeonError {
    PigeonError(code:"INFERENCE_PREPROCESS_FAILED",message:"FrameKernel rejected the pixel input contract.",details:nil)
  }

  static func matchRects(rows: [SIMD4<Float>], columns: [SIMD4<Float>], maxCost: Float) throws -> [(row: Int, column: Int)] {
    _ = checkedABI
    let rows = rows.map { FrameKernelRect(left: $0.x, top: $0.y, right: $0.z, bottom: $0.w) }
    let columns = columns.map { FrameKernelRect(left: $0.x, top: $0.y, right: $0.z, bottom: $0.w) }
    var pairs = [FrameKernelMatchPair](repeating: FrameKernelMatchPair(), count: min(rows.count, columns.count))
    let count = rows.withUnsafeBufferPointer { r in
      columns.withUnsafeBufferPointer { c in
        pairs.withUnsafeMutableBufferPointer { out in
          frame_kernel_match_rects(r.baseAddress, UInt32(r.count), c.baseAddress, UInt32(c.count),
            maxCost, out.baseAddress, out.count)
        }
      }
    }
    guard count >= 0 else {
      throw PigeonError(code: "IDENTITY_ASSOCIATION_INVALID", message: "FrameKernel rejected identity association inputs.", details: nil)
    }
    return pairs.prefix(Int(count)).map { (Int($0.row), Int($0.column)) }
  }

  final class Follower {
    private var state = FrameKernelFollowState()
    init() { _ = IOSFrameKernel.checkedABI; reset() }
    func reset() { precondition(frame_kernel_follow_reset(&state) == 0) }
    func motionState() -> [Float] {
      [state.camera_x, state.camera_y, state.target_x, state.target_y, state.velocity_x, state.velocity_y]
    }
    func crop(target: SIMD4<Float>?, ptsUs: Int64, sourceAspect: Float, outputAspect: Float,
              zoom: Float, smooth: Float) -> SIMD4<Float> {
      let target = target.map { FrameKernelRect(left: $0.x, top: $0.y, right: $0.z, bottom: $0.w) }
      var output = FrameKernelRect()
      precondition(frame_kernel_follow_step(&state, target ?? FrameKernelRect(), target == nil ? 0 : 1,
        ptsUs, sourceAspect, outputAspect, zoom, smooth, &output) == 0, "Invalid shared follow camera input")
      return SIMD4<Float>(output.left, output.top, output.right, output.bottom)
    }
  }

  static func fitEvenRatio(width: Int, height: Int, maxHeight: Int, ratioWidth: Int, ratioHeight: Int) -> (width: Int, height: Int)? {
    _ = checkedABI
    guard width > 0, height > 0, maxHeight > 0, ratioWidth > 0, ratioHeight > 0,
      width <= Int(UInt32.max), height <= Int(UInt32.max), ratioWidth <= Int(UInt32.max), ratioHeight <= Int(UInt32.max) else { return nil }
    var size = [UInt32](repeating: 0, count: 2)
    guard frame_kernel_fit_even_ratio(UInt32(width), UInt32(height), UInt32(clamping: maxHeight),
      UInt32(ratioWidth), UInt32(ratioHeight), &size) == 0 else { return nil }
    return (Int(size[0]), Int(size[1]))
  }

  /// Buffer ownership and DTO transport only. The generic core receives the
  /// model layout from the consumer; it contains no YOLO/project constants.
  final class InstanceDecoder {
    private var capacity = 32
    private var values = [FrameKernelInstanceDetection](repeating: FrameKernelInstanceDetection(), count: 32)
    private var masks = [UInt8](repeating: 0, count: 32 * 160 * 160)

    func decode(head: [Float32], proto: [Float32], preprocess: IOSYoloPreprocessResult,
                headTransposed: Bool, protoNhwc: Bool, confidence: Float32,
                bboxIoU: Float32, maskIoU: Float32) throws -> [IOSYoloDetection] {
      _ = IOSFrameKernel.checkedABI
      guard (1...65535).contains(preprocess.sourceWidth), (1...65535).contains(preprocess.sourceHeight),
            (1...65535).contains(preprocess.inputSize) else { throw contractError() }
      let spec = FrameKernelInstanceSpec(anchors: UInt32(IOSYoloPostprocessor.anchorCount),
        attributes: UInt32(IOSYoloPostprocessor.attributeCount), score_index: 4, coefficient_index: 84,
        channels: UInt32(IOSYoloPostprocessor.protoChannels), head_transposed: headTransposed ? 1 : 0,
        proto_nhwc: protoNhwc ? 1 : 0, coordinate_units: 2)
      let thresholds = FrameKernelInstanceThresholds(confidence: confidence, bbox_iou: bboxIoU, mask_iou: maskIoU)
      let transform = IOSFrameKernel.transform(preprocess)
      let pixels = IOSYoloPostprocessor.protoSize * IOSYoloPostprocessor.protoSize
      var count: Int32 = 0
      while true {
        count = head.withUnsafeBufferPointer { h in
          proto.withUnsafeBufferPointer { p in
            values.withUnsafeMutableBufferPointer { v in
              masks.withUnsafeMutableBufferPointer { m in
                frame_kernel_decode_instances(h.baseAddress, h.count, p.baseAddress, p.count, spec, transform,
                  thresholds, v.baseAddress, v.count, m.baseAddress, m.count, nil)
              }
            }
          }
        }
        if count >= 0 { break }
        if count == -1 { throw contractError() }
        let required = -Int(count) - 1
        guard required > capacity, required <= IOSYoloPostprocessor.anchorCount else { throw contractError() }
        capacity = required
        values = [FrameKernelInstanceDetection](repeating: FrameKernelInstanceDetection(), count: capacity)
        masks = [UInt8](repeating: 0, count: capacity * pixels)
      }
      return (0..<Int(count)).map { i in
        let value = values[i]
        // Return independently owned masks before reusing the workspace.
        return IOSYoloDetection(x1: value.bounds.left, y1: value.bounds.top, x2: value.bounds.right,
          y2: value.bounds.bottom, confidence: value.confidence, mask: Array(masks[i*pixels..<(i+1)*pixels]))
      }
    }

    private func contractError() -> PigeonError {
      PigeonError(code: "YOLO_TENSOR_CONTRACT_MISMATCH", message: "FrameKernel rejected the instance tensor contract.", details: nil)
    }
  }

  static func needsFaceProtection(body: Bool, face: Bool) -> Bool {
    _ = checkedABI
    return (frame_kernel_protection_layers(body ? 1 : 0, face ? 1 : 0, 0) & FRAME_KERNEL_LAYER_FACE) != 0
  }

  static func faceProtectionIds(body: Set<Int>, face: Set<Int>) -> Set<Int> {
    _ = checkedABI
    let bodies = body.map { Int64($0) }, faces = face.map { Int64($0) }
    var result = [Int64](repeating: 0, count: bodies.count + faces.count)
    let count = bodies.withUnsafeBufferPointer { b in
      faces.withUnsafeBufferPointer { f in
        result.withUnsafeMutableBufferPointer { out in
          frame_kernel_face_targets(b.baseAddress, b.count, f.baseAddress, f.count, out.baseAddress, out.count)
        }
      }
    }
    precondition(count >= 0, "Invalid shared face protection plan")
    return Set(result.prefix(Int(count)).map { Int($0) })
  }

  static func rect(_ r: CGRect) -> FrameKernelRect {
    FrameKernelRect(left: Float32(r.minX), top: Float32(r.minY), right: Float32(r.maxX), bottom: Float32(r.maxY))
  }

  static func transform(_ p: IOSYoloPreprocessResult) -> FrameKernelTransform {
    FrameKernelTransform(scale: p.scale, pad_left: p.padLeft, pad_top: p.padTop,
      source_width: UInt32(p.sourceWidth), source_height: UInt32(p.sourceHeight),
      input_size: UInt32(p.inputSize), proto_size: UInt32(IOSYoloPostprocessor.protoSize))
  }

  static func views(width: Int, height: Int) -> [CGRect] {
    _ = checkedABI
    var result = [FrameKernelRect](repeating: FrameKernelRect(), count: 4)
    precondition(frame_kernel_detail_views(UInt32(width), UInt32(height), &result) == 0)
    return result.map { CGRect(x: CGFloat($0.left), y: CGFloat($0.top),
      width: CGFloat($0.right-$0.left), height: CGFloat($0.bottom-$0.top)) }
  }

  static func isComplete(_ d: IOSYoloDetection, view: CGRect, width: Int, height: Int) -> Bool {
    _ = checkedABI
    return frame_kernel_detail_complete(FrameKernelRect(left: d.x1, top: d.y1, right: d.x2, bottom: d.y2),
      rect(view), UInt32(width), UInt32(height)) != 0
  }

  static func iou(_ a: IOSYoloDetection, _ b: IOSYoloDetection) -> Float32 {
    _ = checkedABI
    return frame_kernel_iou(FrameKernelRect(left: a.x1, top: a.y1, right: a.x2, bottom: a.y2),
      FrameKernelRect(left: b.x1, top: b.y1, right: b.x2, bottom: b.y2))
  }

  static func reproject(mask: [UInt8], view: CGRect,
                        local: IOSYoloPreprocessResult, scene: IOSYoloPreprocessResult) -> [UInt8] {
    _ = checkedABI
    let size = IOSYoloPostprocessor.protoSize
    guard mask.count == size * size else { return [UInt8](repeating: 0, count: size * size) }
    var result = [UInt8](repeating: 0, count: size * size)
    let status = mask.withUnsafeBufferPointer { input in
      result.withUnsafeMutableBufferPointer { output in
        frame_kernel_reproject_mask(input.baseAddress, input.count, transform(local), transform(scene),
          rect(view), output.baseAddress, output.count)
      }
    }
    precondition(status == 0, "Invalid shared mask reprojection")
    return result
  }

  static func project(mask: [UInt8], preprocess: IOSYoloPreprocessResult,
                      sourceWidth: Int, sourceHeight: Int, width: Int, height: Int,
                      crop: SIMD4<Float>) -> [UInt8] {
    _ = checkedABI
    let size = IOSYoloPostprocessor.protoSize
    guard width > 0, height > 0, mask.count == size * size else { return [] }
    var result = [UInt8](repeating: 0, count: width * height)
    let status = mask.withUnsafeBufferPointer { input in
      result.withUnsafeMutableBufferPointer { output in
        frame_kernel_project_mask(input.baseAddress, input.count, transform(preprocess),
          UInt32(sourceWidth), UInt32(sourceHeight), UInt32(width), UInt32(height),
          FrameKernelRect(left: crop.x, top: crop.y, right: crop.z, bottom: crop.w), output.baseAddress, output.count)
      }
    }
    precondition(status == 0, "Invalid shared mask projection")
    return result
  }
}

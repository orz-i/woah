import CoreGraphics
import Foundation
@_implementationOnly import FrameKernelC

/// Platform transport only. No independent geometry or protection policy here.
enum IOSFrameKernel {
  private static let checkedABI: Void = {
    precondition(frame_kernel_abi_version() == 1, "Unsupported FrameKernel ABI")
  }()

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

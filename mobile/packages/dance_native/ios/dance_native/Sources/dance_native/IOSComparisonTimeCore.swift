import CoreMedia
import Foundation
@_implementationOnly import FrameKernelC

/// Transport only; the source-time rules are shared with JNI and Dart.
enum IOSComparisonTimeCore {
  static func map(_ value: Int64, scale: Double, offset: Int64) -> Int64 {
    frame_kernel_time_value(0, value, 1_000_000, scale, offset)
  }

  static func map(_ time: CMTime, scale: Double = 1, offset: Int64 = 0) -> Int64? {
    guard time.isNumeric, time.timescale > 0 else { return nil }
    let value = frame_kernel_time_value(0, time.value, UInt32(time.timescale), scale, offset)
    return value == Int64.min ? nil : value
  }

  static func overlap(rs: Int64, re: Int64, ts: Int64, te: Int64, scale: Double, offset: Int64) -> (Int64, Int64)? {
    let result = frame_kernel_time_overlap(rs, re, ts, te, scale, offset)
    return result.valid == 0 ? nil : (result.start, result.end)
  }

  static func frameIndex(_ pts: [Int64], time: Int64, start: Int64 = 0, end: Int64 = Int64.max, mode: UInt32 = 0) -> Int? {
    let value = pts.withUnsafeBufferPointer {
      frame_kernel_frame_index($0.baseAddress, $0.count, time, start, end, mode)
    }
    return value < 0 ? nil : Int(value)
  }
  private static func raw(_ p: IOSComparisonViewport) -> FrameKernelViewport {
    FrameKernelViewport(x: Int32(p.x), top: Int32(p.top), width: Int32(p.width), height: Int32(p.height))
  }
  private static func viewport(_ p: FrameKernelViewport) -> IOSComparisonViewport {
    precondition(p.width > 0 && p.height > 0)
    return IOSComparisonViewport(x: Int(p.x), top: Int(p.top), width: Int(p.width), height: Int(p.height))
  }
  static func pane(width: Int, height: Int, gap: Int, reference: Bool, sideBySide: Bool) -> IOSComparisonViewport {
    viewport(frame_kernel_pane(Int32(width), Int32(height), Int32(gap), reference ? 1 : 0, sideBySide ? 1 : 0))
  }
  static func fit(_ pane: IOSComparisonViewport, aspect: Double) -> IOSComparisonViewport {
    viewport(frame_kernel_fit(raw(pane), aspect))
  }
  static func quad(_ pane: IOSComparisonViewport, _ fit: IOSComparisonViewport, zoom: Double, panX: Double, panY: Double) -> IOSComparisonQuad {
    let q = frame_kernel_quad(raw(pane), raw(fit), zoom, panX, panY)
    precondition(q.valid != 0)
    return IOSComparisonQuad(scaleX: q.scale_x, scaleY: q.scale_y, shiftX: q.shift_x, shiftY: q.shift_y)
  }

}

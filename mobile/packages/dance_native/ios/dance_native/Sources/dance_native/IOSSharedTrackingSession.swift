import Foundation
import FrameKernelC

/// DTO transport only. Session lifetime and byte buffers belong to Swift;
/// observation association and all track transitions execute inside Rust.
struct IOSSharedTrackingObservation {
  let bounds: SIMD4<Float>
  let confidence: Float
  let footY: Float
  let mask: [UInt8]?
  let maskWidth: Int
  let maskHeight: Int
  let transform: FrameKernelTransform
}
struct IOSSharedTrackingRegion {
  let id: Int
  let state: UInt32
  let missedFrames: Int
  let observationAge: Int
  let age: Int
  let observed: Bool
  let bounds: SIMD4<Float>
  let confidence: Float
  let footY: Float
  let occluders: Set<Int>
  let mask: [UInt8]?
  let maskWidth: Int
  let maskHeight: Int
  let transform: FrameKernelTransform?
  let anchor: SIMD4<Float>
  let dormant: Bool
}
struct IOSSharedTrackingClassEvidence {
  let selection: UInt32
  let detectionIndex: Int
  let residualIds: Set<Int>
  let conservativeUnknown: Bool
}
struct IOSSharedTrackingMotionEvidence {
  let id: Int
  let detectionIndex: Int
  let score: Float
  let boundsIoU: Float
  let maskIoU: Float
  let timestampUs: Int64
}
struct IOSSharedTrackingSnapshot {
  let regions: [IOSSharedTrackingRegion]
  let bodyEvidence: [IOSSharedTrackingClassEvidence]
  let faceEvidence: [IOSSharedTrackingClassEvidence]
  let suppressed: Set<Int>
  let hardClasses: [Int: UInt32]
  let motionEvidence: [IOSSharedTrackingMotionEvidence]
}
final class IOSSharedTrackingSession {
  private let id: UInt64
  private let lock = NSLock()
  init(options: FrameKernelTrackingOptions = frame_kernel_tracking_defaults()) throws {
    id = frame_kernel_tracking_create(options)
    guard id != 0 else { throw Self.failure() }
  }
  deinit { _ = frame_kernel_tracking_destroy(id) }
  private static func failure() -> NSError {
    NSError(domain: "FrameKernelTrackingTransport", code: 1,
      userInfo: [NSLocalizedDescriptionKey: "Invalid shared tracking input or snapshot."])
  }
  func configure(protected: Set<Int>, body: Set<Int>, face: Set<Int>, dormancy: Bool) throws {
    lock.lock(); defer { lock.unlock() }
    let p = protected.sorted().map(Int64.init), b = body.sorted().map(Int64.init), f = face.sorted().map(Int64.init)
    let result = p.withUnsafeBufferPointer { pp in b.withUnsafeBufferPointer { bp in f.withUnsafeBufferPointer { fp in
      frame_kernel_tracking_selection(id, pp.baseAddress, pp.count, bp.baseAddress, bp.count,
        fp.baseAddress, fp.count, dormancy ? 1 : 0)
    } } }
    guard result == 0 else { throw Self.failure() }
  }
  func step(action: UInt32, timestampUs: Int64, observations: [IOSSharedTrackingObservation],
            assignedIds: [Int] = []) throws -> IOSSharedTrackingSnapshot {
    lock.lock(); defer { lock.unlock() }
    var pixels: [UInt8] = []
    var values: [FrameKernelTrackingObservation] = []
    for item in observations {
      let offset = item.mask == nil ? UInt64.max : UInt64(pixels.count)
      if let mask = item.mask {
        guard item.maskWidth > 0, item.maskHeight > 0,
              item.maskWidth <= 65535, item.maskHeight <= 65535,
              item.maskWidth * item.maskHeight == mask.count else { throw Self.failure() }
        pixels.append(contentsOf: mask)
      }
      guard item.maskWidth >= 0, item.maskHeight >= 0, item.maskWidth <= 65535, item.maskHeight <= 65535 else { throw Self.failure() }
      values.append(FrameKernelTrackingObservation(
        bounds: FrameKernelRect(left: item.bounds.x, top: item.bounds.y,
          right: item.bounds.z, bottom: item.bounds.w), confidence: item.confidence,
        foot_y: item.footY, mask_offset: offset,
        mask_width: UInt32(item.maskWidth), mask_height: UInt32(item.maskHeight), transform: item.transform))
    }
    let assigned = assignedIds.map(Int64.init)
    let result = values.withUnsafeBufferPointer { v in pixels.withUnsafeBufferPointer { p in assigned.withUnsafeBufferPointer { a in
      frame_kernel_tracking_step(id, action, timestampUs, v.baseAddress, v.count,
        p.baseAddress, p.count, a.baseAddress, a.count)
    } } }
    guard result == 0 else { throw Self.failure() }
    let count = frame_kernel_tracking_snapshot(id, nil, 0)
    guard count >= 0, count <= 256 * 1024 * 1024 else { throw Self.failure() }
    var bytes = [UInt8](repeating: 0, count: Int(count))
    let copied = bytes.withUnsafeMutableBufferPointer { frame_kernel_tracking_snapshot(id, $0.baseAddress, $0.count) }
    guard copied == count else { throw Self.failure() }
    return try Self.decode(bytes)
  }
  private struct Reader {
    let bytes: [UInt8]
    var position = 0
    mutating func u() throws -> UInt32 {
      guard position <= bytes.count - 4 else { throw IOSSharedTrackingSession.failure() }
      let p = position; position += 4
      return UInt32(bytes[p]) | UInt32(bytes[p+1]) << 8 | UInt32(bytes[p+2]) << 16 | UInt32(bytes[p+3]) << 24
    }
    mutating func id() throws -> Int64 { let lo = try u(), hi = try u(); return Int64(bitPattern: UInt64(lo) | UInt64(hi) << 32) }
    mutating func f() throws -> Float { Float(bitPattern: try u()) }
    mutating func rect() throws -> SIMD4<Float> { try SIMD4(f(), f(), f(), f()) }
    mutating func ids() throws -> Set<Int> { var result = Set<Int>(); for _ in 0..<(try u()) { result.insert(Int(try id())) }; return result }
    mutating func classes() throws -> [IOSSharedTrackingClassEvidence] {
      var values: [IOSSharedTrackingClassEvidence] = []
      for _ in 0..<(try u()) { values.append(try IOSSharedTrackingClassEvidence(selection: u(), detectionIndex: Int(u()), residualIds: ids(), conservativeUnknown: u() != 0)) }
      return values
    }
  }
  private static func decode(_ bytes: [UInt8]) throws -> IOSSharedTrackingSnapshot {
    var r = Reader(bytes: bytes); guard try r.u() == 1 else { throw failure() }
    var regions: [IOSSharedTrackingRegion] = []
    for _ in 0..<(try r.u()) {
      let id = Int(try r.id()), state = try r.u(), missed = Int(try r.u()), observationAge = Int(try r.u())
      let age = Int(try r.u()), observed = try r.u() != 0, bounds = try r.rect(), confidence = try r.f(), foot = try r.f(), occluders = try r.ids()
      var mask: [UInt8]?, width = 0, height = 0, transform: FrameKernelTransform?
      if try r.u() != 0 {
        width = Int(try r.u()); height = Int(try r.u())
        transform = try FrameKernelTransform(scale: r.f(), pad_left: r.f(), pad_top: r.f(),
          source_width: r.u(), source_height: r.u(), input_size: r.u(), proto_size: r.u())
        let count = Int(try r.u())
        guard count == width * height, count <= bytes.count - r.position else { throw failure() }
        mask = Array(bytes[r.position..<(r.position+count)]); r.position += count
      }
      let anchor = try r.rect(), dormant = try r.u() != 0
      regions.append(IOSSharedTrackingRegion(id: id, state: state, missedFrames: missed,
        observationAge: observationAge, age: age, observed: observed, bounds: bounds,
        confidence: confidence, footY: foot, occluders: occluders, mask: mask,
        maskWidth: width, maskHeight: height, transform: transform, anchor: anchor, dormant: dormant))
    }
    let body = try r.classes(), face = try r.classes(), suppressed = try r.ids()
    var hard: [Int: UInt32] = [:]
    for _ in 0..<(try r.u()) { let index = Int(try r.u()); hard[index] = try r.u() }
    var motion: [IOSSharedTrackingMotionEvidence] = []
    for _ in 0..<(try r.u()) {
      motion.append(try IOSSharedTrackingMotionEvidence(id: Int(r.id()), detectionIndex: Int(r.u()),
        score: r.f(), boundsIoU: r.f(), maskIoU: r.f(), timestampUs: r.id()))
    }
    guard r.position == bytes.count else { throw failure() }
    return IOSSharedTrackingSnapshot(regions: regions, bodyEvidence: body, faceEvidence: face,
      suppressed: suppressed, hardClasses: hard, motionEvidence: motion)
  }
}

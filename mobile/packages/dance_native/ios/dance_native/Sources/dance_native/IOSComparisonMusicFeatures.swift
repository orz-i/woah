import Foundation
@_implementationOnly import FrameKernelC

enum IOSComparisonMusicError: Error, LocalizedError {
  case cancelled, tooLong, discontinuousAudio, noAudio
  case decode(String)
  var errorDescription: String? {
    switch self {
    case .cancelled: return "匹配已取消"
    case .tooLong: return "自动匹配暂支持十分钟内的视频；可以使用手动校准。"
    case .discontinuousAudio: return "音轨时间不连续，请手动校准。"
    case .noAudio: return "视频没有音轨"
    case .decode(let message): return message
    }
  }
}
struct IOSComparisonMusicFrame {
  let timeUs: Int64
  var spectrum: [Float]
  var onset: [Float]
  let peaks: [Int]
  var pitchSpectrum: [Float]
}
struct IOSComparisonMusicFeatures {
  let payload: Data
  private func u32(_ offset: Int) -> UInt32 { payload.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self).littleEndian } }
  private func i64(_ offset: Int) -> Int64 { payload.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: Int64.self).littleEndian } }
  var count: Int { Int(u32(8)) }
  var startUs: Int64 { count == 0 ? 0 : i64(12) }
  var endUs: Int64 { (count == 0 ? 0 : i64(12 + (count - 1) * 348)) + 10_000 }
  // Inspection only. The production matcher sends the packed replay directly.
  var frames: [IOSComparisonMusicFrame] {
    (0..<count).map { i in
      let start = 12 + i * 348
      return IOSComparisonMusicFrame(timeUs: i64(start),
        spectrum: (0..<16).map { Float(bitPattern: u32(start + 8 + $0 * 4)) },
        onset: (0..<16).map { Float(bitPattern: u32(start + 72 + $0 * 4)) },
        peaks: (0..<Int(u32(start + 136))).map { Int(u32(start + 140 + $0 * 4)) },
        pitchSpectrum: (0..<48).map { Float(bitPattern: u32(start + 156 + $0 * 4)) })
    }
  }
  init(payload: Data) { self.payload = payload }
  init(frames: [IOSComparisonMusicFrame]) {
    var bytes = Data([70,75,77,70,1,0,0,0])
    func append<T: FixedWidthInteger>(_ raw: T) { var value = raw.littleEndian; withUnsafeBytes(of: &value) { bytes.append(contentsOf: $0) } }
    append(UInt32(frames.count))
    for frame in frames {
      precondition(frame.spectrum.count == 16 && frame.onset.count == 16 && frame.peaks.count <= 4)
      append(frame.timeUs)
      for value in frame.spectrum + frame.onset { append(value.bitPattern) }
      append(UInt32(frame.peaks.count))
      for i in 0..<4 { append(UInt32(i < frame.peaks.count ? frame.peaks[i] : 0)) }
      for i in 0..<48 { append((i < frame.pitchSpectrum.count ? frame.pitchSpectrum[i] : 0).bitPattern) }
    }
    self.payload = bytes
  }
}

func iosComparisonMusicCheck(_ isCancelled: () -> Bool) throws {
  if isCancelled() { throw IOSComparisonMusicError.cancelled }
}
func iosComparisonAudioStatus(_ code: Int64) throws {
  switch code {
  case 0: return
  case -2: throw IOSComparisonMusicError.tooLong
  case -3: throw IOSComparisonMusicError.discontinuousAudio
  case -4: throw IOSComparisonMusicError.cancelled
  default: throw IOSComparisonMusicError.decode("音乐分析输入无效，请手动校准。")
  }
}

/// Chunk transport only; feature extraction and PCM normalization are Rust.
final class IOSComparisonMusicFeatureBuilder {
  static let rate = 16_000, hop = 160, fft = 1024, maxFrames = 60_000
  private var id: UInt64
  private var pending: [Float] = []
  init(originUs: Int64) {
    id = frame_kernel_audio_create(originUs)
    precondition(id != 0)
    pending.reserveCapacity(8192)
  }
  deinit { close() }
  private func close() { if id != 0 { frame_kernel_audio_destroy(id); id = 0 } }
  private func flush() throws {
    guard !pending.isEmpty else { return }
    let status = pending.withUnsafeBufferPointer { frame_kernel_audio_push(id, $0.baseAddress, $0.count, 0, 16000, 1, 0) }
    try iosComparisonAudioStatus(Int64(status)); pending.removeAll(keepingCapacity: true)
  }
  func add(_ value: Float) throws {
    guard id != 0, value.isFinite else { throw IOSComparisonMusicError.decode("音频样本无效。") }
    pending.append(value)
    if pending.count == 8192 { try flush() }
  }
  func addPCM(_ pcm: [Float], rate: UInt32, channels: UInt32, timeUs: Int64) throws {
    guard id != 0, pending.isEmpty else { throw IOSComparisonMusicError.decode("音频输入状态无效。") }
    let status = pcm.withUnsafeBufferPointer { frame_kernel_audio_push(id, $0.baseAddress, $0.count, 1, rate, channels, timeUs) }
    try iosComparisonAudioStatus(Int64(status))
  }
  func finish() throws -> IOSComparisonMusicFeatures {
    defer { close() }
    try flush()
    let size = frame_kernel_audio_finish(id, nil, 0)
    guard size >= 12 && size <= 20_880_012 else { try iosComparisonAudioStatus(size); throw IOSComparisonMusicError.decode("音乐特征不可用。") }
    var bytes = Data(count: Int(size))
    let written = bytes.withUnsafeMutableBytes { frame_kernel_audio_finish(id, $0.bindMemory(to: UInt8.self).baseAddress, $0.count) }
    guard written == size else { try iosComparisonAudioStatus(written); throw IOSComparisonMusicError.decode("音乐特征未完整读取。") }
    return IOSComparisonMusicFeatures(payload: bytes)
  }
}

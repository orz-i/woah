import AVFoundation
import AudioToolbox
import CoreMedia
import CoreVideo
import Foundation

/// Comparison-only encoder. It never creates the privacy / YOLO pipeline.
final class IOSComparisonExportPipeline {
  func execute(spec: IOSComparisonExportSpec, outputURL: URL,
    cancellation: IOSComparisonExportCancellation,
    progress: @escaping (Double) -> Void) async throws {
    try spec.validate()
    try cancellation.check()
    let practice = ComparisonPracticeDto(mirrorTeacher: spec.mirrorTeacher,
      mirrorLearner: spec.mirrorLearner, loopStartUs: nil, loopEndUs: nil, loopEnabled: false,
      teacherPicture: ComparisonPictureDto(zoom: spec.teacherPicture.zoom,
        panX: spec.teacherPicture.panX, panY: spec.teacherPicture.panY),
      learnerPicture: ComparisonPictureDto(zoom: spec.learnerPicture.zoom,
        panX: spec.learnerPicture.panX, panY: spec.learnerPicture.panY))
    let composition = try await IOSComparisonComposition.make(
      referenceURL: IOSComparisonFiles.resolve(spec.teacherURI),
      targetURL: IOSComparisonFiles.resolve(spec.learnerURI), offsetUs: spec.offsetUs,
      timeScale: spec.timeScale, rangeStartUs: spec.startUs, rangeEndUs: spec.endUs,
      width: spec.width, height: spec.height, gap: spec.gap,
      sideBySide: spec.sideBySide, practice: practice)
    // Reject a stale/out-of-range A–B request instead of silently shortening it.
    guard composition.durationUs == spec.durationUs else {
      throw IOSComparisonExportError.invalid("选段已超出当前视频的共同区间")
    }
    try await encode(composition: composition, spec: spec, outputURL: outputURL,
      cancellation: cancellation, progress: progress)
  }

  // Internal for the AVFoundation regression harness: the same writer exercises
  // a synthetic VFR composition without requiring a Flutter engine.
  func encode(composition: IOSComparisonComposition, spec: IOSComparisonExportSpec,
    outputURL: URL, cancellation: IOSComparisonExportCancellation,
    progress: @escaping (Double) -> Void) async throws {
    let duration = CMTime(value: spec.durationUs, timescale: 1_000_000)
    let asset = composition.asset
    let videoTracks = try await asset.loadTracks(withMediaType: .video)
    guard !videoTracks.isEmpty else { throw failure("没有可导出的视频画面") }
    let audioTracks = try await asset.loadTracks(withMediaType: .audio)
    let reader = try AVAssetReader(asset: asset)
    reader.timeRange = CMTimeRange(start: .zero, duration: duration)
    let videoOutput = AVAssetReaderVideoCompositionOutput(videoTracks: videoTracks,
      videoSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferIOSurfacePropertiesKey as String: [:]])
    videoOutput.videoComposition = composition.videoComposition
    videoOutput.alwaysCopiesSampleData = false
    guard reader.canAdd(videoOutput) else { throw failure("无法读取合成视频") }
    reader.add(videoOutput)

    let audioOutput: AVAssetReaderAudioMixOutput?
    if audioTracks.isEmpty {
      audioOutput = nil
    } else {
      let output = AVAssetReaderAudioMixOutput(audioTracks: audioTracks, audioSettings: [
        AVFormatIDKey: kAudioFormatLinearPCM,
        AVSampleRateKey: 48_000,
        AVNumberOfChannelsKey: 2,
        AVLinearPCMBitDepthKey: 16,
        AVLinearPCMIsFloatKey: false,
        AVLinearPCMIsBigEndianKey: false,
        AVLinearPCMIsNonInterleaved: false,
      ])
      output.alwaysCopiesSampleData = false
      guard reader.canAdd(output) else { throw failure("无法读取参考视频音频") }
      reader.add(output)
      audioOutput = output
    }
    let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
    writer.shouldOptimizeForNetworkUse = true
    writer.movieTimeScale = 1_000_000
    let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: spec.width,
      AVVideoHeightKey: spec.height,
      AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: max(2_000_000, min(20_000_000, spec.width * spec.height * 6)),
        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
        // DTS and PTS stay in display order; no nominal FPS is imposed on VFR.
        AVVideoAllowFrameReorderingKey: false,
        AVVideoMaxKeyFrameIntervalDurationKey: 2,
      ],
    ])
    videoInput.mediaTimeScale = 1_000_000
    videoInput.expectsMediaDataInRealTime = false
    let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: [
      AVFormatIDKey: kAudioFormatMPEG4AAC,
      AVSampleRateKey: 48_000,
      AVNumberOfChannelsKey: 2,
      AVEncoderBitRateKey: 128_000,
    ])
    audioInput.expectsMediaDataInRealTime = false
    guard writer.canAdd(videoInput), writer.canAdd(audioInput) else {
      throw failure("设备无法使用 H.264 / AAC 编码")
    }
    writer.add(videoInput)
    writer.add(audioInput)
    do {
      try cancellation.check()
      guard writer.startWriting() else { throw writer.error ?? failure("无法创建导出文件") }
      writer.startSession(atSourceTime: .zero)
      guard reader.startReading() else { throw reader.error ?? failure("无法解码对比视频") }
      let audio = try IOSComparisonExportPCM(output: audioOutput, durationUs: spec.durationUs)
      var currentVideo: CMSampleBuffer? = autoreleasepool { videoOutput.copyNextSampleBuffer() }
      var nextVideo: CMSampleBuffer? = autoreleasepool { videoOutput.copyNextSampleBuffer() }
      var videoFinished = false
      var audioFinished = false
      var videoCount = 0
      var lastPTS = CMTime.invalid
      var lastProgressTime = Date.distantPast
      while !videoFinished || !audioFinished {
        try cancellation.check()
        if reader.status == .failed { throw reader.error ?? failure("视频解码失败") }
        if writer.status == .failed { throw writer.error ?? failure("视频编码失败") }
        var appended = false
        if !videoFinished, videoInput.isReadyForMoreMediaData {
          if let sample = currentVideo {
            let pts = CMSampleBufferGetPresentationTimeStamp(sample)
            guard pts.isNumeric else { throw failure("视频帧时间戳无效") }
            let start = CMTimeMaximum(.zero, pts)
            let nextPTS = nextVideo.map { CMSampleBufferGetPresentationTimeStamp($0) } ?? duration
            let end = CMTimeMinimum(duration, nextPTS)
            if CMTimeCompare(start, duration) < 0, CMTimeCompare(end, start) > 0 {
              guard !lastPTS.isValid || CMTimeCompare(start, lastPTS) > 0 else {
                throw failure("视频帧时间戳未递增")
              }
              let timed = try Self.timedCopy(sample, pts: start,
                sampleDuration: CMTimeSubtract(end, start))
              guard videoInput.append(timed) else {
                throw writer.error ?? failure("无法写入对比视频帧")
              }
              videoCount += 1
              lastPTS = start
              let now = Date()
              if now.timeIntervalSince(lastProgressTime) >= 0.15 {
                progress(min(0.97, CMTimeGetSeconds(end) / CMTimeGetSeconds(duration) * 0.97))
                lastProgressTime = now
              }
            }
            currentVideo = nextVideo
            nextVideo = autoreleasepool { videoOutput.copyNextSampleBuffer() }
          } else {
            videoInput.markAsFinished()
            videoFinished = true
          }
          appended = true
        }
        if !audioFinished, audioInput.isReadyForMoreMediaData {
          if let sample = try audio.next() {
            guard audioInput.append(sample) else { throw writer.error ?? failure("无法写入参考音频") }
          } else {
            audioInput.markAsFinished()
            audioFinished = true
          }
          appended = true
        }
        if !appended { try await Task.sleep(nanoseconds: 2_000_000) }
      }
      guard videoCount > 0 else { throw failure("选段没有可导出的视频画面") }
      if reader.status == .failed { throw reader.error ?? failure("参考音频解码失败") }
      try cancellation.check()
      // The final video frame's explicit duration and this session boundary
      // prevent an encoder's default frame/AAC padding from extending the trim.
      writer.endSession(atSourceTime: duration)
      progress(0.98)
      try cancellation.check()
      await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
        writer.finishWriting { continuation.resume() }
      }
      try cancellation.check()
      guard writer.status == .completed else { throw writer.error ?? failure("导出文件未完成") }
      // Reopen before publication; a completed writer alone doesn't prove that
      // the playable movie contains both required tracks and the requested end.
      let result = AVURLAsset(url: outputURL)
      let writtenDuration = try await result.load(.duration)
      let writtenVideo = try await result.loadTracks(withMediaType: .video)
      let writtenAudio = try await result.loadTracks(withMediaType: .audio)
      guard !writtenVideo.isEmpty, !writtenAudio.isEmpty,
            abs(CMTimeGetSeconds(CMTimeSubtract(writtenDuration, duration))) <= 0.001 else {
        throw failure("导出文件时长或音视频轨道不完整")
      }
    } catch {
      reader.cancelReading()
      writer.cancelWriting()
      try? FileManager.default.removeItem(at: outputURL)
      throw error
    }
  }

  static func timedCopy(_ source: CMSampleBuffer, pts: CMTime,
    sampleDuration: CMTime) throws -> CMSampleBuffer {
    var timing = CMSampleTimingInfo(duration: sampleDuration,
      presentationTimeStamp: pts, decodeTimeStamp: .invalid)
    var result: CMSampleBuffer?
    let status = CMSampleBufferCreateCopyWithNewTiming(allocator: kCFAllocatorDefault,
      sampleBuffer: source, sampleTimingEntryCount: 1, sampleTimingArray: &timing,
      sampleBufferOut: &result)
    guard status == noErr, let result else {
      throw IOSComparisonExportError(code: "COMPARISON_EXPORT_TIMING", message: "无法保留视频时间戳")
    }
    return result
  }

  private func failure(_ text: String) -> IOSComparisonExportError {
    IOSComparisonExportError(code: "COMPARISON_EXPORT_FAILED", message: text)
  }
}

/// Reference PCM is normalized only to the AAC sample grid. Video timing remains
/// untouched. Empty edits, a short reference audio track, and absent audio all
/// become silence rather than removing the exported AAC track.
final class IOSComparisonExportPCM {
  private let output: AVAssetReaderAudioMixOutput?
  private let totalFrames: Int64
  private let format: CMAudioFormatDescription
  private var cursor: Int64 = 0
  private var pending: CMSampleBuffer?
  private var sourceEnded = false

  init(output: AVAssetReaderAudioMixOutput?, durationUs: Int64) throws {
    self.output = output
    totalFrames = (durationUs * 48_000 + 999_999) / 1_000_000
    var asbd = AudioStreamBasicDescription(mSampleRate: 48_000,
      mFormatID: kAudioFormatLinearPCM,
      mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked,
      mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4,
      mChannelsPerFrame: 2, mBitsPerChannel: 16, mReserved: 0)
    var description: CMAudioFormatDescription?
    let status = CMAudioFormatDescriptionCreate(allocator: kCFAllocatorDefault, asbd: &asbd,
      layoutSize: 0, layout: nil, magicCookieSize: 0, magicCookie: nil,
      extensions: nil, formatDescriptionOut: &description)
    guard status == noErr, let description else { throw Self.pcmError() }
    format = description
  }

  func next() throws -> CMSampleBuffer? {
    guard cursor < totalFrames else { return nil }
    while true {
      if pending == nil, !sourceEnded {
        pending = autoreleasepool { output?.copyNextSampleBuffer() }
        if pending == nil { sourceEnded = true }
      }
      guard let source = pending else {
        return try silence(frames: Int(min(1024, totalFrames - cursor)))
      }
      let pts = CMSampleBufferGetPresentationTimeStamp(source)
      guard pts.isNumeric else { throw Self.pcmError() }
      let sourceFrame = CMTimeConvertScale(pts, timescale: 48_000, method: .roundHalfAwayFromZero).value
      let frames = CMSampleBufferGetNumSamples(source)
      if sourceFrame > cursor {
        return try silence(frames: Int(min(1024, min(sourceFrame, totalFrames) - cursor)))
      }
      let skip = max(0, cursor - sourceFrame)
      if skip >= Int64(frames) { pending = nil; continue }
      let count = min(Int64(frames) - skip, totalFrames - cursor)
      var trimmed: CMSampleBuffer?
      let status = CMSampleBufferCopySampleBufferForRange(allocator: kCFAllocatorDefault,
        sampleBuffer: source, sampleRange: CFRange(location: Int(skip), length: Int(count)),
        sampleBufferOut: &trimmed)
      guard status == noErr, let trimmed else { throw Self.pcmError() }
      let result = try IOSComparisonExportPipeline.timedCopy(trimmed,
        pts: CMTime(value: cursor, timescale: 48_000),
        sampleDuration: CMTime(value: 1, timescale: 48_000))
      cursor += count
      pending = nil
      return result
    }
  }

  private func silence(frames: Int) throws -> CMSampleBuffer {
    var block: CMBlockBuffer?
    var status = CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault,
      memoryBlock: nil, blockLength: frames * 4, blockAllocator: kCFAllocatorDefault,
      customBlockSource: nil, offsetToData: 0, dataLength: frames * 4,
      flags: 0, blockBufferOut: &block)
    guard status == kCMBlockBufferNoErr, let block else { throw Self.pcmError() }
    status = CMBlockBufferFillDataBytes(with: 0, blockBuffer: block, offsetIntoDestination: 0,
      dataLength: frames * 4)
    guard status == kCMBlockBufferNoErr else { throw Self.pcmError() }
    var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 48_000),
      presentationTimeStamp: CMTime(value: cursor, timescale: 48_000), decodeTimeStamp: .invalid)
    var bytesPerFrame = 4
    var result: CMSampleBuffer?
    status = CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block,
      formatDescription: format, sampleCount: frames, sampleTimingEntryCount: 1,
      sampleTimingArray: &timing, sampleSizeEntryCount: 1, sampleSizeArray: &bytesPerFrame,
      sampleBufferOut: &result)
    guard status == noErr, let result else { throw Self.pcmError() }
    cursor += Int64(frames)
    return result
  }

  private static func pcmError() -> IOSComparisonExportError {
    IOSComparisonExportError(code: "COMPARISON_EXPORT_AUDIO", message: "无法准备参考视频音频")
  }
}

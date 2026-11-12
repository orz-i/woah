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
    let frames = try await IOSComparisonExportFrames(spec: spec, composition: composition,
      cancellation: cancellation)
    defer { frames.close() }
    let audioTracks = try await asset.loadTracks(withMediaType: .audio)
    let reader = try AVAssetReader(asset: asset)
    reader.timeRange = CMTimeRange(start: .zero, duration: duration)

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
    var operation = "start"
    var videoCount = 0
    var lastPTS = CMTime.invalid
    do {
      try cancellation.check()
      guard writer.startWriting() else { throw writer.error ?? failure("无法创建导出文件") }
      writer.startSession(atSourceTime: .zero)
      if audioOutput != nil, !reader.startReading() { throw reader.error ?? failure("无法解码参考音频") }
      let audio = try IOSComparisonExportPCM(output: audioOutput, durationUs: spec.durationUs)
      operation = "video_read"
      var currentVideo = try autoreleasepool { try frames.next() }
      var nextVideo = try autoreleasepool { try frames.next() }
      var videoFinished = false
      var audioFinished = false
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
              operation = "video_append"
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
            operation = "video_read"
            nextVideo = try autoreleasepool { try frames.next() }
          } else {
            videoInput.markAsFinished()
            videoFinished = true
          }
          appended = true
        }
        if !audioFinished, audioInput.isReadyForMoreMediaData {
          operation = "audio_read"
          if let sample = try audio.next() {
            operation = "audio_append"
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
      operation = "finish"
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
      #if DEBUG
      NSLog("WoahComparison pipeline_failure operation=%@ frames=%d last_pts=%f reader=%d writer=%d",
        operation, videoCount, CMTimeGetSeconds(lastPTS), reader.status.rawValue, writer.status.rawValue)
      #endif
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

/// Decode original tracks instead of cropped/scaled composition tracks. iOS 17's
/// composition reader can lose H.264 reorder dependencies near an edit's tail.
/// Only native decoding/rendering lives here; Rust maps each original source PTS.
private final class IOSComparisonExportFrames {
  private let reference: Source
  private let target: Source
  private let spec: IOSComparisonExportSpec
  private let start: CMTime
  private let end: CMTime
  private let instruction: IOSComparisonVideoInstruction
  private let compositor = IOSComparisonCompositor()
  private let pool: CVPixelBufferPool
  private let cancellation: IOSComparisonExportCancellation
  private var clock: CMTime

  init(spec: IOSComparisonExportSpec, composition: IOSComparisonComposition,
    cancellation: IOSComparisonExportCancellation) async throws {
    self.spec = spec
    self.cancellation = cancellation
    start = CMTime(value: composition.referenceStartUs, timescale: 1_000_000)
    end = CMTimeAdd(start, CMTime(value: spec.durationUs, timescale: 1_000_000))
    clock = start
    guard let instruction = composition.videoComposition.instructions.first as? IOSComparisonVideoInstruction else {
      throw IOSComparisonExportError.invalid("无法读取对比画面布局")
    }
    self.instruction = instruction
    var created: CVPixelBufferPool?
    let status = CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, [
      kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
      kCVPixelBufferWidthKey: spec.width, kCVPixelBufferHeightKey: spec.height,
      kCVPixelBufferIOSurfacePropertiesKey: [:], kCVPixelBufferMetalCompatibilityKey: true,
    ] as CFDictionary, &created)
    guard status == kCVReturnSuccess, let created else { throw IOSComparisonExportError.invalid("无法分配对比画面") }
    pool = created
    reference = try await Source(url: IOSComparisonFiles.resolve(spec.teacherURI),
      time: composition.referenceStartUs, cancellation: cancellation)
    do {
      target = try await Source(url: IOSComparisonFiles.resolve(spec.learnerURI),
        time: IOSComparisonTimeCore.map(composition.referenceStartUs, scale: spec.timeScale, offset: spec.offsetUs),
        cancellation: cancellation)
    } catch { reference.close(); throw error }
  }

  func close() { reference.close(); target.close() }

  func next() throws -> CMSampleBuffer? {
    try cancellation.check()
    guard CMTimeCompare(clock, end) < 0 else { return nil }
    guard let referenceUs = IOSComparisonTimeCore.map(clock),
          let targetUs = IOSComparisonTimeCore.map(clock, scale: spec.timeScale, offset: spec.offsetUs) else {
      throw IOSComparisonExportError.invalid("视频帧时间戳无效")
    }
    let r = try reference.frame(at: referenceUs)
    let t = try target.frame(at: targetUs)
    var pixel: CVPixelBuffer?
    guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &pixel) == kCVReturnSuccess,
          let pixel else { throw IOSComparisonExportError.invalid("无法分配导出画面") }
    compositor.render(reference: r, target: t, instruction: instruction, output: pixel)
    var format: CMVideoFormatDescription?
    guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault,
      imageBuffer: pixel, formatDescriptionOut: &format) == noErr, let format else {
      throw IOSComparisonExportError.invalid("导出画面格式无效")
    }
    let following = CMTimeMinimum(end, reference.nextTime ?? end)
    guard CMTimeCompare(following, clock) > 0 else { throw IOSComparisonExportError.invalid("视频帧时间戳未递增") }
    var timing = CMSampleTimingInfo(duration: CMTimeSubtract(following, clock),
      presentationTimeStamp: CMTimeSubtract(clock, start), decodeTimeStamp: .invalid)
    var sample: CMSampleBuffer?
    let status = CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault,
      imageBuffer: pixel, formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sample)
    guard status == noErr, let sample else { throw IOSComparisonExportError.invalid("无法读取导出画面") }
    clock = following
    return sample
  }

  private final class Source {
    private let reader: AVAssetReader
    private let output: AVAssetReaderTrackOutput
    private let cancellation: IOSComparisonExportCancellation
    private var current: CMSampleBuffer?
    private var following: CMSampleBuffer?
    var nextTime: CMTime? { following.map { CMSampleBufferGetPresentationTimeStamp($0) } }

    init(url: URL, time: Int64, cancellation: IOSComparisonExportCancellation) async throws {
      self.cancellation = cancellation
      let index = try IOSComparisonFrameIndex.read(url: url, isCancelled: { cancellation.reason != nil })
      let asset = AVURLAsset(url: url)
      guard let track = try await asset.loadTracks(withMediaType: .video).first,
            let first = index.frame(atOrBefore: time) else { throw IOSComparisonExportError.invalid("没有可导出的视频画面") }
      reader = try AVAssetReader(asset: asset)
      output = AVAssetReaderTrackOutput(track: track, outputSettings: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferIOSurfacePropertiesKey as String: [:]])
      output.alwaysCopiesSampleData = false
      let range = try await track.load(.timeRange)
      // Include the exact rational sample even when its microsecond PTS rounded
      // upward. Keep the original track's end and H.264 reorder dependencies.
      let begin = CMTimeMaximum(range.start, CMTime(value: max(0, first - 1), timescale: 1_000_000))
      reader.timeRange = CMTimeRange(start: begin, end: CMTimeRangeGetEnd(range))
      guard reader.canAdd(output) else { throw IOSComparisonExportError.invalid("无法读取源视频") }
      reader.add(output)
      guard reader.startReading() else { throw reader.error ?? IOSComparisonExportError.invalid("无法解码源视频") }
      do { current = try read(); following = try read() }
      catch { reader.cancelReading(); throw error }
    }

    func close() { reader.cancelReading(); current = nil; following = nil }

    private func read() throws -> CMSampleBuffer? {
      try cancellation.check()
      let value = autoreleasepool { output.copyNextSampleBuffer() }
      if reader.status == .failed { throw reader.error ?? IOSComparisonExportError.invalid("源视频解码失败") }
      return value
    }

    func frame(at time: Int64) throws -> CVPixelBuffer {
      while let following,
            let pts = IOSComparisonTimeCore.map(CMSampleBufferGetPresentationTimeStamp(following)), pts <= time {
        try cancellation.check()
        current = following
        self.following = try read()
      }
      guard let current, let pts = IOSComparisonTimeCore.map(CMSampleBufferGetPresentationTimeStamp(current)),
            pts <= time, let image = CMSampleBufferGetImageBuffer(current) else {
        throw IOSComparisonExportError.invalid("选段没有可读取的视频帧")
      }
      return image
    }
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

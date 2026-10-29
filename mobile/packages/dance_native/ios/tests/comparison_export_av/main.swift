import AVFoundation
import AudioToolbox
import CoreMedia
import CoreVideo
import Foundation

// macOS AVFoundation integration harness (no Flutter engine / Photos permission):
// swiftc -parse-as-library -D COMPARISON_EXPORT_STANDALONE \
//   IOSComparisonModels.swift IOSComparisonCompositor.swift IOSComparisonComposition.swift \
//   IOSComparisonExportSpec.swift IOSComparisonExportPipeline.swift \
//   tests/comparison_export_av/Fixtures.swift tests/comparison_export_av/main.swift -o comparison-export-av

@main
struct ComparisonExportAVRegression {
  static let cadence: [Int64] = [0, 40_000, 100_000, 133_333, 233_333, 450_000,
    483_333, 700_000, 966_667, 1_200_000]
  static let sourceDuration: Int64 = 1_333_337

  static func main() async throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("woah-comparison-export-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let silent = directory.appendingPathComponent("silent.mov")
    let reference = directory.appendingPathComponent("reference.mov")
    let target = directory.appendingPathComponent("target.mov")
    try await fixture(silent, frequency: nil)
    try await fixture(reference, frequency: 440, audioDelayUs: 200_000)
    try await fixture(target, frequency: 880)
    let noAudio = try spec(reference: silent, target: target, start: 0, end: sourceDuration)
    let silentOutput = directory.appendingPathComponent("silent.mp4")
    try await checkExport(noAudio, output: silentOutput)
    let silence = try await pcm(silentOutput)
    precondition(rms(silence) < 0.0001, "Target audio leaked into silent reference export")
    print("PASS H.264 + silent AAC, VFR cadence and microsecond final duration")

    let ranged = try spec(reference: reference, target: target, start: 100_000,
      end: 900_007, scale: 1.25, offset: 20_000)
    let rangedOutput = directory.appendingPathComponent("range.mp4")
    try await checkExport(ranged, output: rangedOutput)
    let sound = try await pcm(rangedOutput)
    precondition(rms(Array(sound.prefix(3_000))) < 0.001, "Reference audio's initial gap shifted away")
    let window = Array(sound[12_000..<min(sound.count, 26_400)])
    precondition(rms(window) > 0.05, "Reference music missing")
    precondition(power(window, frequency: 440) > power(window, frequency: 880) * 20,
      "Export does not contain reference-only, original-rate music")
    print("PASS A–B trim, affine target mapping, reference-only audio and delayed audio silence")

    let cancellation = IOSComparisonExportCancellation()
    let cancelledOutput = directory.appendingPathComponent("cancelled.partial.mp4")
    do {
      try await IOSComparisonExportPipeline().execute(spec: noAudio, outputURL: cancelledOutput,
        cancellation: cancellation) { value in if value > 0.1 { cancellation.cancel(.user) } }
      preconditionFailure("Cancelled export unexpectedly finished")
    } catch {
      precondition(cancellation.reason == .user)
      precondition(!FileManager.default.fileExists(atPath: cancelledOutput.path), "Partial export survived cancellation")
    }
    print("PASS cancellation stops reader/writer and removes unfinished file")
  }

  static func spec(reference: URL, target: URL, start: Int64, end: Int64,
    scale: Double = 1, offset: Int64 = 0) throws -> IOSComparisonExportSpec {
    let value = IOSComparisonExportSpec(projectId: "av-regression",
      teacherURI: reference.path, learnerURI: target.path, offsetUs: offset,
      timeScale: scale, startUs: start, endUs: end, width: 128, height: 64,
      gap: 4, sideBySide: true, mirrorTeacher: true, mirrorLearner: false,
      teacherPicture: .init(zoom: 1.5, panX: 0.2, panY: -0.1), learnerPicture: .init())
    try value.validate()
    return value
  }

  static func checkExport(_ spec: IOSComparisonExportSpec, output: URL) async throws {
    let practice = ComparisonPracticeDto(mirrorTeacher: spec.mirrorTeacher,
      mirrorLearner: spec.mirrorLearner, loopStartUs: nil, loopEndUs: nil, loopEnabled: false,
      teacherPicture: .init(zoom: spec.teacherPicture.zoom, panX: spec.teacherPicture.panX, panY: spec.teacherPicture.panY),
      learnerPicture: .init(zoom: 1, panX: 0, panY: 0))
    let composition = try await IOSComparisonComposition.make(referenceURL: URL(fileURLWithPath: spec.teacherURI),
      targetURL: URL(fileURLWithPath: spec.learnerURI), offsetUs: spec.offsetUs,
      timeScale: spec.timeScale, rangeStartUs: spec.startUs, rangeEndUs: spec.endUs,
      width: spec.width, height: spec.height, gap: spec.gap,
      sideBySide: spec.sideBySide, practice: practice)
    let reader = try AVAssetReader(asset: composition.asset)
    let tracks = try await composition.asset.loadTracks(withMediaType: .video)
    let composed = AVAssetReaderVideoCompositionOutput(videoTracks: tracks,
      videoSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
    composed.videoComposition = composition.videoComposition
    reader.add(composed)
    precondition(reader.startReading())
    var expected: [Double] = []
    while let sample = composed.copyNextSampleBuffer() {
      expected.append(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample)))
    }
    if reader.status == .failed { throw reader.error! }
    try await IOSComparisonExportPipeline().execute(spec: spec, outputURL: output,
      cancellation: IOSComparisonExportCancellation(), progress: { _ in })
    let asset = AVURLAsset(url: output)
    let duration = try await asset.load(.duration)
    precondition(abs(CMTimeGetSeconds(duration) - Double(spec.durationUs) / 1_000_000) <= 0.001)
    let video = try await asset.loadTracks(withMediaType: .video).first!
    let audio = try await asset.loadTracks(withMediaType: .audio).first!
    let videoFormat = try await video.load(.formatDescriptions).first!
    let audioFormat = try await audio.load(.formatDescriptions).first!
    precondition(CMFormatDescriptionGetMediaSubType(videoFormat) == kCMVideoCodecType_H264)
    precondition(CMFormatDescriptionGetMediaSubType(audioFormat) == kAudioFormatMPEG4AAC)
    let decoded = try AVAssetReader(asset: asset)
    // Compressed H.264 outputs also vend edit / duration marker buffers with
    // empty payloads and invalid timestamps. Compare decoded video frames.
    let videoOutput = AVAssetReaderTrackOutput(track: video, outputSettings: [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
    decoded.add(videoOutput)
    precondition(decoded.startReading())
    var actual: [Double] = []
    while let sample = videoOutput.copyNextSampleBuffer() {
      actual.append(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample)))
    }
    if decoded.status == .failed { throw decoded.error! }
    precondition(actual.count == expected.count && actual.count >= 4,
      "Changed VFR frame count: \(actual) vs \(expected)")
    for (a, b) in zip(actual, expected) { precondition(abs(a - b) < 0.00001, "Retimed VFR sample: \(a) vs \(b)") }
    let steps = zip(actual.dropFirst(), actual).map { $0.0 - $0.1 }
    precondition((steps.max()! - steps.min()!) > 0.03, "Fixture cadence unexpectedly became CFR")
  }

  static func fixture(_ url: URL, frequency: Double?, audioDelayUs: Int64 = 0) async throws {
    let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
    writer.movieTimeScale = 1_000_000
    let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
      AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 64, AVVideoHeightKey: 64,
      AVVideoCompressionPropertiesKey: [AVVideoAllowFrameReorderingKey: false]])
    video.mediaTimeScale = 1_000_000
    writer.add(video)
    let audio: AVAssetWriterInput?
    if frequency != nil {
      let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
        AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 48_000,
        AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 128_000])
      writer.add(input)
      audio = input
    } else { audio = nil }
    precondition(writer.startWriting())
    writer.startSession(atSourceTime: .zero)
    var index = 0
    var videoDone = false
    var audioDone = audio == nil
    let pcm = try IOSComparisonExportPCM(output: nil, durationUs: sourceDuration)
    while !videoDone || !audioDone {
      if writer.status == .failed { throw writer.error! }
      var madeProgress = false
      if !videoDone, video.isReadyForMoreMediaData {
        if index < cadence.count {
          var pixel: CVPixelBuffer?
          precondition(CVPixelBufferCreate(kCFAllocatorDefault, 64, 64, kCVPixelFormatType_32BGRA,
            [kCVPixelBufferIOSurfacePropertiesKey as String: [:]] as CFDictionary, &pixel) == kCVReturnSuccess)
          CVPixelBufferLockBaseAddress(pixel!, [])
          memset(CVPixelBufferGetBaseAddress(pixel!)!, Int32(index * 16 + 32),
            CVPixelBufferGetBytesPerRow(pixel!) * 64)
          CVPixelBufferUnlockBaseAddress(pixel!, [])
          var description: CMVideoFormatDescription?
          precondition(CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault,
            imageBuffer: pixel!, formatDescriptionOut: &description) == noErr)
          var timing = CMSampleTimingInfo(duration: CMTime(value:
            (index + 1 < cadence.count ? cadence[index + 1] : sourceDuration) - cadence[index], timescale: 1_000_000),
            presentationTimeStamp: CMTime(value: cadence[index], timescale: 1_000_000), decodeTimeStamp: .invalid)
          var sample: CMSampleBuffer?
          precondition(CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault,
            imageBuffer: pixel!, formatDescription: description!, sampleTiming: &timing,
            sampleBufferOut: &sample) == noErr)
          precondition(video.append(sample!))
          index += 1
        } else { video.markAsFinished(); videoDone = true }
        madeProgress = true
      }
      if !audioDone, let audio, audio.isReadyForMoreMediaData {
        if let sample = try pcm.next() {
          let start = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
          if start >= Double(audioDelayUs) / 1_000_000 {
            var samples = [Int16](repeating: 0, count: CMSampleBufferGetNumSamples(sample) * 2)
            for frame in 0..<samples.count / 2 {
              let value = Int16(sin((start + Double(frame) / 48_000) * frequency! * 2 * .pi) * 12_000)
              samples[frame * 2] = value; samples[frame * 2 + 1] = value
            }
            let block = CMSampleBufferGetDataBuffer(sample)!
            let status = samples.withUnsafeBytes {
              CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block,
                offsetIntoDestination: 0, dataLength: $0.count)
            }
            precondition(status == noErr && audio.append(sample))
          }
        } else { audio.markAsFinished(); audioDone = true }
        madeProgress = true
      }
      if !madeProgress { try await Task.sleep(nanoseconds: 1_000_000) }
    }
    writer.endSession(atSourceTime: CMTime(value: sourceDuration, timescale: 1_000_000))
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      writer.finishWriting { continuation.resume() }
    }
    guard writer.status == .completed else { throw writer.error! }
  }

  static func pcm(_ url: URL) async throws -> [Double] {
    let asset = AVURLAsset(url: url)
    let track = try await asset.loadTracks(withMediaType: .audio).first!
    let reader = try AVAssetReader(asset: asset)
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
      AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMBitDepthKey: 16,
      AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false,
      AVLinearPCMIsNonInterleaved: false])
    reader.add(output)
    precondition(reader.startReading())
    var result: [Double] = []
    while let sample = output.copyNextSampleBuffer() {
      let block = CMSampleBufferGetDataBuffer(sample)!
      var values = [Int16](repeating: 0, count: CMBlockBufferGetDataLength(block) / 2)
      let status = values.withUnsafeMutableBytes {
        CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: $0.count, destination: $0.baseAddress!)
      }
      precondition(status == noErr)
      for i in stride(from: 0, to: values.count, by: 2) { result.append(Double(values[i]) / 32768) }
    }
    if reader.status == .failed { throw reader.error! }
    return result
  }

  static func rms(_ values: [Double]) -> Double {
    sqrt(values.reduce(0) { $0 + $1 * $1 } / Double(max(1, values.count)))
  }
  static func power(_ values: [Double], frequency: Double) -> Double {
    var real = 0.0
    var imaginary = 0.0
    for (index, value) in values.enumerated() {
      let phase = Double(index) / 48_000 * frequency * 2 * .pi
      real += value * cos(phase); imaginary += value * sin(phase)
    }
    return real * real + imaginary * imaginary
  }
}

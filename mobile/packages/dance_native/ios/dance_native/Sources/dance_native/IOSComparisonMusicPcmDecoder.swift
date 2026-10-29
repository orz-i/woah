import AVFoundation
import Foundation

/// AVAssetReader performs the channel mix and sample-rate conversion. Each PCM
/// buffer's actual presentation timestamp is checked before streaming samples.
/// Apple references: AVAssetReaderTrackOutput; Linear PCM format settings;
/// CMSampleBufferGetPresentationTimeStamp; CMBlockBufferCopyDataBytes.
final class IOSComparisonMusicPcmDecoder {
  func features(url: URL, isCancelled: () -> Bool) throws -> IOSComparisonMusicFeatures {
    let securityScoped = url.isFileURL && url.startAccessingSecurityScopedResource()
    defer { if securityScoped { url.stopAccessingSecurityScopedResource() } }
    return try features(asset: AVURLAsset(url: url), isCancelled: isCancelled)
  }

  /// Internal asset overload also allows native tests of edit-list timestamps.
  /// This synchronous API runs on the caller's background worker, never the UI.
  func features(asset: AVAsset, isCancelled: () -> Bool) throws -> IOSComparisonMusicFeatures {
    try iosComparisonMusicCheck(isCancelled)
    guard let track = asset.tracks(withMediaType: .audio).first else {
      throw IOSComparisonMusicError.noAudio
    }
    let reader = try AVAssetReader(asset: asset)
    defer { reader.cancelReading() }
    let settings: [String: Any] = [
      AVFormatIDKey: kAudioFormatLinearPCM,
      AVSampleRateKey: Double(IOSComparisonMusicFeatureBuilder.rate),
      AVNumberOfChannelsKey: 1,
      AVLinearPCMBitDepthKey: 32,
      AVLinearPCMIsFloatKey: true,
      AVLinearPCMIsBigEndianKey: false,
      AVLinearPCMIsNonInterleaved: false,
    ]
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else {
      throw IOSComparisonMusicError.decode("无法读取音轨，请手动校准。")
    }
    reader.add(output)
    guard reader.startReading() else {
      throw reader.error ?? IOSComparisonMusicError.decode("音频解码失败，请手动校准。")
    }
    var builder: IOSComparisonMusicFeatureBuilder?
    var continuity = IOSComparisonMusicPCMContinuity()
    while true {
      try iosComparisonMusicCheck(isCancelled)
      let found = try autoreleasepool { () throws -> Bool in
        guard let sample = output.copyNextSampleBuffer() else { return false }
        let count = CMSampleBufferGetNumSamples(sample)
        guard count > 0 else { return true }
        guard let description = CMSampleBufferGetFormatDescription(sample),
              let format = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee,
              format.mFormatID == kAudioFormatLinearPCM,
              format.mSampleRate == Double(IOSComparisonMusicFeatureBuilder.rate),
              format.mChannelsPerFrame == 1,
              format.mBitsPerChannel == 32,
              format.mBytesPerFrame == 4,
              format.mFormatFlags & kAudioFormatFlagIsFloat != 0,
              format.mFormatFlags & kAudioFormatFlagIsBigEndian == 0,
              let block = CMSampleBufferGetDataBuffer(sample),
              CMBlockBufferGetDataLength(block) == count * MemoryLayout<Float>.size else {
          throw IOSComparisonMusicError.decode("音频格式不支持，请手动校准。")
        }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        guard pts.isNumeric else { throw IOSComparisonMusicError.discontinuousAudio }
        let converted = CMTimeConvertScale(pts, timescale: 1_000_000, method: .roundHalfAwayFromZero)
        guard converted.isNumeric, abs(Double(converted.value)) < Double(Int64.max) / 4 else {
          throw IOSComparisonMusicError.discontinuousAudio
        }
        let timeUs = converted.value
        try continuity.consume(timeUs: timeUs, sampleCount: count)
        if builder == nil { builder = IOSComparisonMusicFeatureBuilder(originUs: timeUs) }
        // CMBlockBuffer may be noncontiguous; copy into owned Float storage.
        var pcm = [Float](repeating: 0, count: count)
        let copyStatus = pcm.withUnsafeMutableBytes { bytes in
          CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: bytes.count, destination: bytes.baseAddress!)
        }
        guard copyStatus == kCMBlockBufferNoErr else {
          throw IOSComparisonMusicError.decode("无法读取音频样本，请手动校准。")
        }
        for (i, value) in pcm.enumerated() {
          if i % 4096 == 0 { try iosComparisonMusicCheck(isCancelled) }
          try builder!.add(value)
        }
        return true
      }
      if !found { break }
    }
    try iosComparisonMusicCheck(isCancelled)
    guard reader.status == .completed else {
      throw reader.error ?? IOSComparisonMusicError.decode("音频解码未完成，请手动校准。")
    }
    guard let builder else { throw IOSComparisonMusicError.noAudio }
    let features = builder.finish()
    try iosComparisonMusicCheck(isCancelled)
    return features
  }
}

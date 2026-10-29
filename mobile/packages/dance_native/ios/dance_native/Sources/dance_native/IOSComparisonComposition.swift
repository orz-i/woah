import AVFoundation
import CoreGraphics
import CoreMedia
import Foundation

/// One immutable edit/geometry snapshot is shared by AVPlayer and AVAssetReader.
struct IOSComparisonComposition {
  let asset: AVMutableComposition
  let videoComposition: AVMutableVideoComposition
  let referenceStartUs: Int64
  let durationUs: Int64
  let referenceAspect: Double
  let targetAspect: Double

  static func make(referenceURL: URL, targetURL: URL, offsetUs: Int64, timeScale: Double,
    rangeStartUs: Int64 = 0, rangeEndUs: Int64? = nil, width: Int, height: Int,
    gap: Int, sideBySide: Bool, practice: ComparisonPracticeDto) async throws -> IOSComparisonComposition {
    let layout = try IOSComparisonLayout(width: width, height: height, gap: gap, sideBySide: sideBySide)
    let referencePicture = try picture(practice.teacherPicture)
    let targetPicture = try picture(practice.learnerPicture)
    let referenceAsset = AVURLAsset(url: referenceURL)
    let targetAsset = AVURLAsset(url: targetURL)
    guard let referenceVideo = try await referenceAsset.loadTracks(withMediaType: .video).first,
          let targetVideo = try await targetAsset.loadTracks(withMediaType: .video).first else {
      throw failure("A comparison source has no video track.")
    }
    let referenceTrackRange = try await referenceVideo.load(.timeRange)
    let targetTrackRange = try await targetVideo.load(.timeRange)
    let referenceInterval = try interval(referenceTrackRange)
    let targetInterval = try interval(targetTrackRange)
    let overlap = try IOSComparisonTimeMapping.overlap(reference: referenceInterval,
      target: targetInterval, offsetUs: offsetUs, timeScale: timeScale)
    let range = try overlap.sourceRange(startUs: rangeStartUs, endUs: rangeEndUs)
    let duration = time(range.endUs - range.startUs)
    let referenceRange = CMTimeRange(start: time(range.startUs), duration: duration)
    let targetStart = overlap.mapping.targetTimeUs(referenceUs: range.startUs)
    let targetEnd = overlap.mapping.targetTimeUs(referenceUs: range.endUs)
    guard targetEnd > targetStart else { throw IOSComparisonModelError.invalidRange }
    let targetRange = CMTimeRange(start: time(targetStart), duration: time(targetEnd - targetStart))

    let referenceTransform = try await referenceVideo.load(.preferredTransform)
    let targetTransform = try await targetVideo.load(.preferredTransform)
    let referenceSize = try await referenceVideo.load(.naturalSize)
    let targetSize = try await targetVideo.load(.naturalSize)
    let referenceAspect = try aspect(referenceSize, transform: referenceTransform)
    let targetAspect = try aspect(targetSize, transform: targetTransform)
    let asset = AVMutableComposition()
    guard let reference = asset.addMutableTrack(withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid),
          let target = asset.addMutableTrack(withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid) else {
      throw failure("Could not allocate comparison video tracks.")
    }
    // Insert the reference without resampling or rebuilding its sample cadence.
    try reference.insertTimeRange(referenceRange, of: referenceVideo, at: .zero)
    try target.insertTimeRange(targetRange, of: targetVideo, at: .zero)
    // Target source time advances at timeScale per reference second. This is a
    // single affine music-rate edit, with integer-microsecond endpoint rounding.
    target.scaleTimeRange(CMTimeRange(start: .zero, duration: targetRange.duration), toDuration: duration)
    // Source orientation is applied exactly once by the custom compositor.
    reference.preferredTransform = .identity
    target.preferredTransform = .identity

    // Reference audio stays at its original rate and source offset. Intersect
    // its real edit range so delayed audio starts with silence, never a shift.
    if let sourceAudio = try await referenceAsset.loadTracks(withMediaType: .audio).first {
      let audioRange = try await sourceAudio.load(.timeRange)
      let available = CMTimeRangeGetIntersection(referenceRange, otherRange: audioRange)
      if available.isValid, !available.isEmpty {
        guard let audio = asset.addMutableTrack(withMediaType: .audio,
          preferredTrackID: kCMPersistentTrackID_Invalid) else {
          throw failure("Could not allocate the reference audio track.")
        }
        try audio.insertTimeRange(available, of: sourceAudio,
          at: CMTimeSubtract(available.start, referenceRange.start))
      }
    }

    let instruction = IOSComparisonVideoInstruction(timeRange: CMTimeRange(start: .zero, duration: duration),
      layout: layout,
      reference: IOSComparisonVideoLayer(trackID: reference.trackID, preferredTransform: referenceTransform,
        aspect: referenceAspect, mirror: practice.mirrorTeacher, picture: referencePicture),
      target: IOSComparisonVideoLayer(trackID: target.trackID, preferredTransform: targetTransform,
        aspect: targetAspect, mirror: practice.mirrorLearner, picture: targetPicture))
    let video = AVMutableVideoComposition()
    video.customVideoCompositorClass = IOSComparisonCompositor.self
    video.renderSize = CGSize(width: CGFloat(width), height: CGFloat(height))
    video.renderScale = 1
    video.instructions = [instruction]
    // Apple: nonempty edits derive frame times from this source track. The
    // frameDuration is only a fallback for empty edits; never force VFR to CFR.
    // https://developer.apple.com/documentation/avfoundation/avmutablevideocomposition/sourcetrackidforframetiming
    video.sourceTrackIDForFrameTiming = reference.trackID
    let minimum = try await referenceVideo.load(.minFrameDuration)
    video.frameDuration = minimum.isNumeric && CMTimeCompare(minimum, .zero) > 0
      ? minimum : CMTime(value: 1, timescale: 30)
    return IOSComparisonComposition(asset: asset, videoComposition: video,
      referenceStartUs: range.startUs, durationUs: range.endUs - range.startUs,
      referenceAspect: referenceAspect, targetAspect: targetAspect)
  }

  private static func picture(_ value: ComparisonPictureDto?) throws -> IOSComparisonPicture {
    guard let value else { return .identity }
    return try IOSComparisonPicture(zoom: value.zoom, panX: value.panX, panY: value.panY)
  }

  private static func interval(_ range: CMTimeRange) throws -> IOSComparisonSourceInterval {
    guard range.isValid, range.start.isNumeric, range.duration.isNumeric,
          CMTimeCompare(range.duration, .zero) > 0 else {
      throw IOSComparisonModelError.invalidSourceInterval
    }
    // Inward rounding never requests media outside a fractional edit boundary.
    let start = CMTimeConvertScale(range.start, timescale: 1_000_000, method: .roundTowardPositiveInfinity)
    let end = CMTimeConvertScale(CMTimeRangeGetEnd(range), timescale: 1_000_000, method: .roundTowardNegativeInfinity)
    return try IOSComparisonSourceInterval(startUs: start.value, endUs: end.value)
  }

  private static func time(_ microseconds: Int64) -> CMTime {
    CMTime(value: microseconds, timescale: 1_000_000)
  }

  private static func aspect(_ size: CGSize, transform: CGAffineTransform) throws -> Double {
    let bounds = CGRect(origin: .zero, size: size).applying(transform).standardized
    guard bounds.width.isFinite, bounds.height.isFinite, bounds.width > 0, bounds.height > 0,
          [transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty].allSatisfy({ $0.isFinite }) else {
      throw failure("Source video has invalid display geometry.")
    }
    return Double(bounds.width / bounds.height)
  }

  private static func failure(_ message: String) -> NSError {
    NSError(domain: "WoahComparisonComposition", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
}

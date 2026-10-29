import AVFoundation
import CoreGraphics
import CoreImage
import CoreMedia
import CoreVideo
import Foundation

// macOS smoke test, not an iPhone playback/performance test.
// swiftc IOSComparisonModels.swift IOSComparisonComposition.swift IOSComparisonCompositor.swift IOSComparisonFrameIndex.swift \
//   tests/comparison_render/BridgeStubs.swift tests/comparison_render/main.swift -o comparison_render

func fixture(_ url: URL, pointsUs: [Int64], endUs: Int64, reference: Bool,
  transform: CGAffineTransform = .identity) async throws {
  let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
  let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: 96, AVVideoHeightKey: 64,
    AVVideoCompressionPropertiesKey: [AVVideoAllowFrameReorderingKey: false],
  ])
  let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,
    sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
      kCVPixelBufferWidthKey as String: 96, kCVPixelBufferHeightKey as String: 64])
  input.transform = transform
  precondition(writer.canAdd(input))
  writer.add(input)
  precondition(writer.startWriting())
  writer.startSession(atSourceTime: .zero)
  for point in pointsUs {
    while !input.isReadyForMoreMediaData {
      if writer.status == .failed { throw writer.error! }
      try await Task.sleep(nanoseconds: 1_000_000)
    }
    var created: CVPixelBuffer?
    precondition(CVPixelBufferCreate(kCFAllocatorDefault, 96, 64, kCVPixelFormatType_32BGRA,
      [kCVPixelBufferIOSurfacePropertiesKey as String: [:]] as CFDictionary, &created) == kCVReturnSuccess)
    let buffer = created!
    CVPixelBufferLockBaseAddress(buffer, [])
    let pixels = CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to: UInt8.self)
    let stride = CVPixelBufferGetBytesPerRow(buffer)
    for y in 0..<64 {
      for x in 0..<96 {
        let pixel = pixels.advanced(by: y * stride + x * 4)
        // Reference left=red, right=blue. Target=green.
        pixel[0] = reference && x >= 48 ? 255 : 0
        pixel[1] = reference ? 0 : 255
        pixel[2] = reference && x < 48 ? 255 : 0
        pixel[3] = 255
      }
    }
    CVPixelBufferUnlockBaseAddress(buffer, [])
    precondition(adaptor.append(buffer, withPresentationTime: CMTime(value: point, timescale: 1_000_000)))
  }
  input.markAsFinished()
  writer.endSession(atSourceTime: CMTime(value: endUs, timescale: 1_000_000))
  await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
    writer.finishWriting { continuation.resume() }
  }
  guard writer.status == .completed else { throw writer.error! }
}

func render(_ composition: IOSComparisonComposition) async throws -> [(Int64, CVPixelBuffer)] {
  let reader = try AVAssetReader(asset: composition.asset)
  let tracks = try await composition.asset.loadTracks(withMediaType: .video)
  let output = AVAssetReaderVideoCompositionOutput(videoTracks: tracks,
    videoSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
  output.videoComposition = composition.videoComposition
  output.alwaysCopiesSampleData = false
  precondition(reader.canAdd(output))
  reader.add(output)
  precondition(reader.startReading())
  var result: [(Int64, CVPixelBuffer)] = []
  while let sample = output.copyNextSampleBuffer() {
    let time = CMTimeConvertScale(CMSampleBufferGetPresentationTimeStamp(sample), timescale: 1_000_000,
      method: .roundHalfAwayFromZero)
    guard let pixel = CMSampleBufferGetImageBuffer(sample) else { fatalError("No composed image") }
    result.append((time.value, pixel))
  }
  guard reader.status == .completed else {
    throw reader.error ?? NSError(domain: "ComparisonSmoke", code: 1)
  }
  return result
}

func color(_ buffer: CVPixelBuffer, _ x: Int, _ y: Int, _ red: Int, _ green: Int, _ blue: Int) {
  CVPixelBufferLockBaseAddress(buffer, .readOnly)
  defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
  let pixel = CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to: UInt8.self)
    .advanced(by: y * CVPixelBufferGetBytesPerRow(buffer) + x * 4)
  precondition(abs(Int(pixel[0]) - blue) < 35 && abs(Int(pixel[1]) - green) < 35 && abs(Int(pixel[2]) - red) < 35,
    "Unexpected color at (\(x),\(y)): RGB \(pixel[2]),\(pixel[1]),\(pixel[0])")
}

func sameColor(_ actual: CVPixelBuffer, _ x: Int, _ y: Int, expected: CVPixelBuffer, _ expectedX: Int, _ expectedY: Int) {
  CVPixelBufferLockBaseAddress(expected, .readOnly)
  let pixel = CVPixelBufferGetBaseAddress(expected)!.assumingMemoryBound(to: UInt8.self)
    .advanced(by: expectedY * CVPixelBufferGetBytesPerRow(expected) + expectedX * 4)
  let rgb = (Int(pixel[2]), Int(pixel[1]), Int(pixel[0]))
  CVPixelBufferUnlockBaseAddress(expected, .readOnly)
  color(actual, x, y, rgb.0, rgb.1, rgb.2)
}

func decodedImage(_ url: URL) async throws -> CVPixelBuffer {
  let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
  generator.appliesPreferredTrackTransform = true
  let generated = try await generator.image(at: .zero).image
  var expectedBuffer: CVPixelBuffer?
  precondition(CVPixelBufferCreate(kCFAllocatorDefault, generated.width, generated.height,
    kCVPixelFormatType_32BGRA, nil, &expectedBuffer) == kCVReturnSuccess)
  CIContext().render(CIImage(cgImage: generated), to: expectedBuffer!)
  return expectedBuffer!
}

func run() async throws {
  let directory = FileManager.default.temporaryDirectory.appendingPathComponent("woah-composition-\(UUID().uuidString)")
  try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
  defer { try? FileManager.default.removeItem(at: directory) }
  let reference = directory.appendingPathComponent("reference.mov")
  let target = directory.appendingPathComponent("target.mov")
  try await fixture(reference, pointsUs: [0, 40_000, 110_000, 150_000], endUs: 200_000, reference: true)
  try await fixture(target, pointsUs: [0, 30_000, 90_000, 180_000, 250_000, 390_000], endUs: 400_000, reference: false)
  let index = try IOSComparisonFrameIndex.read(url: reference, isCancelled: { false })
  precondition(index.presentationTimesUs == [0, 40_000, 110_000, 150_000])
  precondition(index.frame(atOrBefore: 70_000) == 40_000)
  precondition(index.adjacent(to: 40_000, forward: true, startUs: 0, endUs: 200_000) == 110_000)
  precondition(index.adjacent(to: 120_000, forward: false, startUs: 0, endUs: 200_000) == 40_000)
  precondition(index.adjacent(to: 150_000, forward: true, startUs: 0, endUs: 200_000) == nil)
  precondition(index.adjacent(to: 110_000, forward: false, startUs: 50_000, endUs: 140_000) == nil)
  var probes = 0
  do {
    _ = try IOSComparisonFrameIndex.read(url: reference, isCancelled: { probes += 1; return probes >= 2 })
    fatalError("Frame index cancellation was ignored")
  } catch is CancellationError { }
  let targetImage = try await decodedImage(target)
  let normal = try await IOSComparisonComposition.make(referenceURL: reference, targetURL: target,
    offsetUs: 0, timeScale: 2, width: 204, height: 100, gap: 4, sideBySide: true,
    practice: ComparisonPracticeDto())
  precondition(normal.referenceStartUs == 0 && normal.durationUs == 200_000)
  precondition(normal.referenceAspect == 1.5 && normal.targetAspect == 1.5)
  let frames = try await render(normal)
  precondition(frames.map(\.0) == [0, 40_000, 110_000, 150_000], "Reference VFR cadence changed: \(frames.map(\.0))")
  let first = frames[0].1
  color(first, 25, 50, 255, 0, 0)
  color(first, 75, 50, 0, 0, 255)
  // Compare decoded media, rather than assuming an encoder's unspecified
  // primaries reproduce literal RGB values exactly after color conversion.
  sameColor(first, 155, 50, expected: targetImage, 48, 32)
  color(first, 101, 50, 0, 0, 0)
  color(first, 25, 5, 0, 0, 0)

  let mirrored = try await IOSComparisonComposition.make(referenceURL: reference, targetURL: target,
    offsetUs: 0, timeScale: 2, rangeStartUs: 50_000, rangeEndUs: 140_000,
    width: 204, height: 100, gap: 4, sideBySide: true,
    practice: ComparisonPracticeDto(mirrorTeacher: true, teacherPicture: ComparisonPictureDto(zoom: 2, panX: 0, panY: 0)))
  precondition(mirrored.referenceStartUs == 50_000 && mirrored.durationUs == 90_000)
  let selected = try await render(mirrored)
  precondition(selected.map(\.0) == [0, 60_000], "Selected VFR cadence changed: \(selected.map(\.0))")
  color(selected[0].1, 25, 50, 0, 0, 255)
  color(selected[0].1, 75, 50, 255, 0, 0)
  color(selected[0].1, 101, 50, 0, 0, 0) // Zoom must not cover the divider.
  sameColor(selected[0].1, 155, 50, expected: targetImage, 48, 32)

  // Compare a rotated track against AVFoundation's own preferred-transform
  // image generator, so Core Image coordinate conventions cannot hide a flip.
  let rotatedURL = directory.appendingPathComponent("rotated.mov")
  try await fixture(rotatedURL, pointsUs: [0, 40_000, 110_000, 150_000], endUs: 200_000, reference: true,
    transform: CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 64, ty: 0))
  let rotated = try await IOSComparisonComposition.make(referenceURL: rotatedURL, targetURL: target,
    offsetUs: 0, timeScale: 2, width: 100, height: 204, gap: 4, sideBySide: false,
    practice: ComparisonPracticeDto())
  precondition(abs(rotated.referenceAspect - 2.0 / 3) < 0.00001)
  let rotatedFrames = try await render(rotated)
  let rotatedImage = try await decodedImage(rotatedURL)
  sameColor(rotatedFrames[0].1, 50, 25, expected: rotatedImage,
    CVPixelBufferGetWidth(rotatedImage) / 2, CVPixelBufferGetHeight(rotatedImage) / 4)
  sameColor(rotatedFrames[0].1, 50, 75, expected: rotatedImage,
    CVPixelBufferGetWidth(rotatedImage) / 2, CVPixelBufferGetHeight(rotatedImage) * 3 / 4)
  color(rotatedFrames[0].1, 50, 101, 0, 0, 0)
  print("Comparison AVFoundation VFR/rate/trim/mirror/orientation/pane composition smoke passed (macOS)")
}

Task {
  do { try await run(); exit(0) }
  catch { fputs("\(error)\n", stderr); exit(1) }
}
dispatchMain()

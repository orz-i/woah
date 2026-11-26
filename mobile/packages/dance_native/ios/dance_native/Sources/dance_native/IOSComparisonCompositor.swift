import AVFoundation
import CoreGraphics
import CoreImage
import CoreMedia
import CoreVideo
import Foundation

struct IOSComparisonVideoLayer {
  let trackID: CMPersistentTrackID
  let preferredTransform: CGAffineTransform
  let aspect: Double
  let mirror: Bool
  let picture: IOSComparisonPicture
}

/// M0 input: top-down alpha in display-oriented source space and fixed placement.
/// A runtime track ID alone is not a durable selection.
struct IOSComparisonCutoutPlacement: Sendable {
  let anchor: SIMD4<Float>
  let x: Double
  let y: Double
  let subjectHeight: Double
  let mirror: Bool
  let opacity: Double
}
struct IOSComparisonCutoutLayer: Sendable {
  let sourcePtsUs: Int64
  let width: Int
  let height: Int
  let alpha: [UInt8]
  let placement: IOSComparisonCutoutPlacement

  var valid: Bool {
    sourcePtsUs >= 0 && (1...4096).contains(width) && (1...4096).contains(height)
      && alpha.count == width * height && placement.opacity.isFinite
      && (0...1).contains(placement.opacity)
  }
}
struct IOSComparisonCutoutFrame {
  let referencePtsUs: Int64
  let targetPtsUs: Int64
  let reference: IOSComparisonCutoutLayer?
  let target: IOSComparisonCutoutLayer?

  func layer(referenceRole: Bool) -> IOSComparisonCutoutLayer? {
    let value = referenceRole ? reference : target
    guard let value, value.valid,
          value.sourcePtsUs == (referenceRole ? referencePtsUs : targetPtsUs) else { return nil }
    return value
  }
}

/// AVFoundation's composition request exposes image buffers, not original
/// track sample timestamps. Check the independently decoded sample signature
/// in addition to the exact source-index lookup before using its alpha.
struct IOSComparisonSourceStamp: Sendable {
  let width: Int
  let height: Int
  let samples: [UInt8]

  init?(_ pixel: CVPixelBuffer) {
    guard CVPixelBufferGetPixelFormatType(pixel) == kCVPixelFormatType_32BGRA,
          CVPixelBufferLockBaseAddress(pixel, .readOnly) == kCVReturnSuccess else { return nil }
    defer { CVPixelBufferUnlockBaseAddress(pixel, .readOnly) }
    guard let base = CVPixelBufferGetBaseAddress(pixel)?.assumingMemoryBound(to: UInt8.self) else { return nil }
    width = CVPixelBufferGetWidth(pixel); height = CVPixelBufferGetHeight(pixel)
    guard width > 0, height > 0 else { return nil }
    let stride = CVPixelBufferGetBytesPerRow(pixel)
    // Dense enough to sample small dancers, still bounded independently of
    // source resolution. This is a mismatch guard, not a unique frame hash.
    let grid = 32
    var values: [UInt8] = []; values.reserveCapacity(grid*grid*3)
    for y in 0..<grid { for x in 0..<grid {
      let offset = ((2*y+1)*height/(2*grid))*stride + ((2*x+1)*width/(2*grid))*4
      values.append(contentsOf: [base[offset],base[offset+1],base[offset+2]])
    } }
    samples = values
  }
  func agrees(with other: Self) -> Bool {
    guard width == other.width, height == other.height, samples.count == other.samples.count else { return false }
    let differences = zip(samples,other.samples).map { abs(Int($0.0)-Int($0.1)) }
    return (differences.max() ?? 255) <= 12 && differences.reduce(0,+) <= differences.count*2
  }
}

struct IOSComparisonCachedCutout: Sendable {
  let stamp: IOSComparisonSourceStamp
  let layer: IOSComparisonCutoutLayer?
}
struct IOSComparisonCutoutTrackCache: Sendable {
  let entries: [Int64: IOSComparisonCachedCutout]
  let times: [Int64]
  var visibleCount: Int { entries.values.filter { $0.layer != nil }.count }
  func entry(at time: Int64) -> (Int64,IOSComparisonCachedCutout)? {
    guard let i = IOSComparisonTimeCore.frameIndex(times,time: time),
          let entry = entries[times[i]] else { return nil }
    return (times[i],entry)
  }
}
struct IOSComparisonCutoutCache: Sendable {
  let reference: IOSComparisonCutoutTrackCache
  let target: IOSComparisonCutoutTrackCache
  let referenceStartUs: Int64
  let offsetUs: Int64
  let timeScale: Double
  let window: Range<Int64>?
  init(reference: IOSComparisonCutoutTrackCache,target: IOSComparisonCutoutTrackCache,
    referenceStartUs: Int64,offsetUs: Int64,timeScale: Double,window: Range<Int64>? = nil) {
    self.reference=reference;self.target=target;self.referenceStartUs=referenceStartUs
    self.offsetUs=offsetUs;self.timeScale=timeScale;self.window=window
  }
  func frame(at compositionTime: CMTime, referenceBuffer: CVPixelBuffer,
             targetBuffer: CVPixelBuffer) -> IOSComparisonCutoutFrame {
    if let window {
      guard let time=IOSComparisonTimeCore.map(compositionTime),window.contains(time) else {
        return IOSComparisonCutoutFrame(referencePtsUs:-1,targetPtsUs:-1,reference:nil,target:nil)
      }
    }
    let source = CMTimeAdd(compositionTime,CMTime(value: referenceStartUs,timescale: 1_000_000))
    let r = IOSComparisonTimeCore.map(source).flatMap { reference.entry(at: $0) }
    let t = IOSComparisonTimeCore.map(source,scale: timeScale,offset: offsetUs).flatMap { target.entry(at: $0) }
    func layer(_ value: (Int64,IOSComparisonCachedCutout)?, _ buffer: CVPixelBuffer) -> IOSComparisonCutoutLayer? {
      guard let value, let stamp = IOSComparisonSourceStamp(buffer), value.1.stamp.agrees(with: stamp) else { return nil }
      return value.1.layer
    }
    return IOSComparisonCutoutFrame(referencePtsUs: r?.0 ?? -1,targetPtsUs: t?.0 ?? -1,
      reference: layer(r,referenceBuffer),target: layer(t,targetBuffer))
  }
}

final class IOSComparisonVideoInstruction: NSObject, AVVideoCompositionInstructionProtocol {
  let timeRange: CMTimeRange
  let enablePostProcessing = false
  let containsTweening = false
  let passthroughTrackID = kCMPersistentTrackID_Invalid
  let requiredSourceTrackIDs: [NSValue]?
  let layout: IOSComparisonLayout
  let reference: IOSComparisonVideoLayer
  let target: IOSComparisonVideoLayer
  let cutout: IOSComparisonCutoutCache?

  init(timeRange: CMTimeRange, layout: IOSComparisonLayout,
    reference: IOSComparisonVideoLayer, target: IOSComparisonVideoLayer, cutout: IOSComparisonCutoutCache? = nil) {
    self.timeRange = timeRange
    self.layout = layout
    self.reference = reference
    self.target = target
    self.cutout = cutout
    requiredSourceTrackIDs = [NSNumber(value: reference.trackID), NSNumber(value: target.trackID)]
    super.init()
  }
}

/// AVFoundation supplies the two decoded frames at one composition time. Both
/// panes are submitted in one buffer, for the player and the offline reader.
@objc(IOSComparisonCompositor)
final class IOSComparisonCompositor: NSObject, AVVideoCompositing {
  let sourcePixelBufferAttributes: [String: Any]? = [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
  ]
  let requiredPixelBufferAttributesForRenderContext: [String: Any] = [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
    kCVPixelBufferIOSurfacePropertiesKey as String: [:],
    kCVPixelBufferMetalCompatibilityKey as String: true,
  ]
  private let queue = DispatchQueue(label: "art.gaoge.woah.comparison.compositor")
  private let context = CIContext(options: [.cacheIntermediates: false])
  private let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
  private let cancellationLock = NSLock()
  private var generation: UInt64 = 0

  func renderContextChanged(_ newRenderContext: AVVideoCompositionRenderContext) {
    // Every request owns its render context and buffer pool; no stale pool is cached.
  }

  func startRequest(_ request: AVAsynchronousVideoCompositionRequest) {
    let requestedGeneration = currentGeneration()
    queue.async { [self] in
      autoreleasepool {
        guard currentGeneration() == requestedGeneration else {
          request.finishCancelledRequest()
          return
        }
        do {
          guard let instruction = request.videoCompositionInstruction as? IOSComparisonVideoInstruction,
                let reference = request.sourceFrame(byTrackID: instruction.reference.trackID),
                let target = request.sourceFrame(byTrackID: instruction.target.trackID),
                let output = request.renderContext.newPixelBuffer() else {
            throw NSError(domain: "WoahComparisonCompositor", code: 1,
              userInfo: [NSLocalizedDescriptionKey: "Could not obtain both comparison frames and a render buffer."])
          }
          render(reference: reference, target: target, instruction: instruction, output: output,
            renderTransform: request.renderContext.renderTransform,
            cutout: instruction.cutout?.frame(at: request.compositionTime, referenceBuffer: reference, targetBuffer: target))
          if currentGeneration() == requestedGeneration {
            request.finish(withComposedVideoFrame: output)
          } else {
            request.finishCancelledRequest()
          }
        } catch {
          if currentGeneration() == requestedGeneration {
            request.finish(with: error)
          } else {
            request.finishCancelledRequest()
          }
        }
      }
    }
  }

  /// The player and the export's independent source readers use one renderer.
  func render(reference: CVPixelBuffer, target: CVPixelBuffer,
    instruction: IOSComparisonVideoInstruction, output: CVPixelBuffer,
    renderTransform: CGAffineTransform = .identity,
    cutout: IOSComparisonCutoutFrame? = nil) {
    let layout = instruction.layout
    let canvas = CGRect(x: 0, y: 0, width: CGFloat(layout.width), height: CGFloat(layout.height))
    var image = CIImage(color: .black).cropped(to: canvas)
    if let cutout {
      // Never fall back to unmasked video for missing or stale masks.
      if let layer = cutout.layer(referenceRole: true),
         let foreground = cutoutImage(reference, layer: instruction.reference, cutout: layer, layout: layout) {
        image = foreground.composited(over: image)
      }
      if let layer = cutout.layer(referenceRole: false),
         let foreground = cutoutImage(target, layer: instruction.target, cutout: layer, layout: layout) {
        image = foreground.composited(over: image)
      }
    } else {
      image = pane(reference, layer: instruction.reference, reference: true, layout: layout).composited(over: image)
      image = pane(target, layer: instruction.target, reference: false, layout: layout).composited(over: image)
    }
    let bounds = CGRect(x: 0, y: 0, width: CGFloat(CVPixelBufferGetWidth(output)), height: CGFloat(CVPixelBufferGetHeight(output)))
    // Conjugate AVFoundation's top-left render transform into Core Image coordinates.
    image = image.transformed(by: CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: CGFloat(layout.height)))
      .transformed(by: renderTransform)
      .transformed(by: CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: bounds.height))
    image = image.composited(over: CIImage(color: .black).cropped(to: bounds)).cropped(to: bounds)
    context.render(image, to: output, bounds: bounds, colorSpace: colorSpace)
  }

  func cancelAllPendingVideoCompositionRequests() {
    cancellationLock.lock()
    generation &+= 1
    cancellationLock.unlock()
  }

  private func currentGeneration() -> UInt64 {
    cancellationLock.lock()
    defer { cancellationLock.unlock() }
    return generation
  }

  static func oriented(_ buffer: CVPixelBuffer, transform: CGAffineTransform) -> CIImage {
    let source = CIImage(cvPixelBuffer: buffer)
    // Track transforms use top-left video coordinates. Conjugate the transform
    // into Core Image's bottom-left coordinates before normalizing its extent.
    // Directly applying a 90-degree track transform reverses the displayed turn.
    var image = source
      .transformed(by: CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: source.extent.maxY + source.extent.minY))
      .transformed(by: transform)
      .transformed(by: CGAffineTransform(scaleX: 1, y: -1))
    let extent = image.extent.standardized
    image = image.transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
    return image
  }

  private func cutoutImage(_ buffer: CVPixelBuffer, layer: IOSComparisonVideoLayer,
    cutout: IOSComparisonCutoutLayer, layout: IOSComparisonLayout) -> CIImage? {
    let placement = cutout.placement
    guard let quad = IOSComparisonTimeCore.cutoutQuad(width: layout.width, height: layout.height,
      aspect: layer.aspect, anchor: placement.anchor, x: placement.x, y: placement.y,
      subjectHeight: placement.subjectHeight, mirror: placement.mirror) else { return nil }
    var source = Self.oriented(buffer, transform: layer.preferredTransform)
    let extent = source.extent
    // Build an alpha-only signal via premultiplied RGBA. CGImage has top-down
    // scanlines, matching the canonical mask; alpha is independent of color space.
    var pixels = [UInt8](repeating: 0, count: cutout.alpha.count * 4)
    for (index, value) in cutout.alpha.enumerated() {
      pixels[index * 4 + 3] = UInt8((Double(value) * placement.opacity).rounded())
    }
    guard let provider = CGDataProvider(data: Data(pixels) as CFData),
          let cgMask = CGImage(width: cutout.width, height: cutout.height,
            bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: cutout.width * 4,
            space: colorSpace, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) else { return nil }
    let mask = CIImage(cgImage: cgMask).transformed(by: CGAffineTransform(
      scaleX: extent.width / CGFloat(cutout.width), y: extent.height / CGFloat(cutout.height)))
    source = source.applyingFilter("CIBlendWithAlphaMask", parameters: [
      kCIInputBackgroundImageKey: CIImage(color: .clear).cropped(to: extent),
      kCIInputMaskImageKey: mask,
    ]).cropped(to: extent)
    if placement.mirror {
      source = source.transformed(by: CGAffineTransform(a: -1, b: 0, c: 0, d: 1, tx: extent.width, ty: 0))
    }
    let width = CGFloat(quad.scaleX) * CGFloat(layout.width)
    let height = CGFloat(quad.scaleY) * CGFloat(layout.height)
    let left = (1 + CGFloat(quad.shiftX) - CGFloat(quad.scaleX)) * CGFloat(layout.width) / 2
    let bottom = (1 + CGFloat(quad.shiftY) - CGFloat(quad.scaleY)) * CGFloat(layout.height) / 2
    return source.transformed(by: CGAffineTransform(scaleX: width / extent.width, y: height / extent.height))
      .transformed(by: CGAffineTransform(translationX: left, y: bottom))
      .cropped(to: CGRect(x: 0, y: 0, width: layout.width, height: layout.height))
  }

  private func pane(_ buffer: CVPixelBuffer, layer: IOSComparisonVideoLayer,
    reference: Bool, layout: IOSComparisonLayout) -> CIImage {
    let pane = layout.pane(reference: reference)
    let fit = layout.fit(reference: reference, aspect: layer.aspect)
    let quad = layer.picture.quad(pane: pane, fit: fit)
    // Use the same Float quad as Android, including odd-pixel fit rounding.
    let width = Double(quad.scaleX) * Double(pane.width)
    let height = Double(quad.scaleY) * Double(pane.height)
    let left = Double(pane.x) + (1 + Double(quad.shiftX) - Double(quad.scaleX)) * Double(pane.width) / 2
    let top = Double(pane.top) + (1 - Double(quad.shiftY) - Double(quad.scaleY)) * Double(pane.height) / 2
    var image = Self.oriented(buffer, transform: layer.preferredTransform)
    let extent = image.extent
    if layer.mirror {
      image = image.transformed(by: CGAffineTransform(a: -1, b: 0, c: 0, d: 1, tx: extent.width, ty: 0))
    }
    image = image.transformed(by: CGAffineTransform(scaleX: CGFloat(width) / extent.width, y: CGFloat(height) / extent.height))
      .transformed(by: CGAffineTransform(translationX: CGFloat(left), y: CGFloat(Double(layout.height) - top - height)))
    // Zoom can fill the pane, but can never paint the divider or the other pane.
    let clip = CGRect(x: CGFloat(pane.x), y: CGFloat(layout.height - pane.top - pane.height),
      width: CGFloat(pane.width), height: CGFloat(pane.height))
    return image.cropped(to: clip)
  }
}

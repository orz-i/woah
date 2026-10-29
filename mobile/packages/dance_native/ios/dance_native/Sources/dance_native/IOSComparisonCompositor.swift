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

final class IOSComparisonVideoInstruction: NSObject, AVVideoCompositionInstructionProtocol {
  let timeRange: CMTimeRange
  let enablePostProcessing = false
  let containsTweening = false
  let passthroughTrackID = kCMPersistentTrackID_Invalid
  let requiredSourceTrackIDs: [NSValue]?
  let layout: IOSComparisonLayout
  let reference: IOSComparisonVideoLayer
  let target: IOSComparisonVideoLayer

  init(timeRange: CMTimeRange, layout: IOSComparisonLayout,
    reference: IOSComparisonVideoLayer, target: IOSComparisonVideoLayer) {
    self.timeRange = timeRange
    self.layout = layout
    self.reference = reference
    self.target = target
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
          let layout = instruction.layout
          let canvas = CGRect(x: 0, y: 0, width: CGFloat(layout.width), height: CGFloat(layout.height))
          var image = CIImage(color: .black).cropped(to: canvas)
          image = pane(reference, layer: instruction.reference, reference: true, layout: layout)
            .composited(over: image)
          image = pane(target, layer: instruction.target, reference: false, layout: layout)
            .composited(over: image)

          let bounds = CGRect(x: 0, y: 0, width: CGFloat(CVPixelBufferGetWidth(output)), height: CGFloat(CVPixelBufferGetHeight(output)))
          // AVFoundation's renderTransform uses a top-left origin. Convert from
          // and back to Core Image's bottom-left coordinates around that step.
          // https://developer.apple.com/documentation/avfoundation/avvideocompositionrendercontext/rendertransform
          image = image.transformed(by: CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: CGFloat(layout.height)))
            .transformed(by: request.renderContext.renderTransform)
            .transformed(by: CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: bounds.height))
          image = image.composited(over: CIImage(color: .black).cropped(to: bounds)).cropped(to: bounds)
          context.render(image, to: output, bounds: bounds, colorSpace: colorSpace)
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
    let source = CIImage(cvPixelBuffer: buffer)
    // Track transforms use top-left video coordinates. Conjugate the transform
    // into Core Image's bottom-left coordinates before normalizing its extent.
    // Directly applying a 90-degree track transform reverses the displayed turn.
    var image = source
      .transformed(by: CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: source.extent.maxY + source.extent.minY))
      .transformed(by: layer.preferredTransform)
      .transformed(by: CGAffineTransform(scaleX: 1, y: -1))
    let extent = image.extent.standardized
    image = image.transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
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

import CoreGraphics
import Foundation
import Metal

final class IOSMetalPreviewRenderer {
  private let device: MTLDevice
  private let commandQueue: MTLCommandQueue
  private let pipeline: MTLComputePipelineState

  init() throws {
    guard let device = MTLCreateSystemDefaultDevice(),
          let commandQueue = device.makeCommandQueue() else {
      throw PigeonError(
        code: "METAL_UNAVAILABLE",
        message: "Metal is unavailable for iOS preview rendering.",
        details: nil
      )
    }
    let library: MTLLibrary
    do {
      library = try device.makeLibrary(source: Self.kernelSource, options: nil)
    } catch {
      throw PigeonError(
        code: "METAL_SHADER_COMPILE_FAILED",
        message: "Could not compile the iOS preview Metal kernel.",
        details: String(describing: error)
      )
    }
    guard let function = library.makeFunction(name: "woahPreviewKernel") else {
      throw PigeonError(
        code: "METAL_SHADER_COMPILE_FAILED",
        message: "The iOS preview Metal kernel entry point is missing.",
        details: nil
      )
    }
    do {
      pipeline = try device.makeComputePipelineState(function: function)
    } catch {
      throw PigeonError(
        code: "METAL_PIPELINE_FAILED",
        message: "Could not create the iOS preview Metal compute pipeline.",
        details: String(describing: error)
      )
    }
    self.device = device
    self.commandQueue = commandQueue
  }

  private static func faceRect(
    _ region: IOSFacePrivacyEllipse,
    sourceWidth: Int,
    sourceHeight: Int
  ) -> SIMD4<Float> {
    let width = Float32(max(1, sourceWidth))
    let height = Float32(max(1, sourceHeight))
    let left = max(0, region.centerX - region.radiusX)
    let top = max(0, region.centerY - region.radiusY)
    let right = min(width, region.centerX + region.radiusX)
    let bottom = min(height, region.centerY + region.radiusY)
    return SIMD4<Float>(
      Float(left / width),
      Float(top / height),
      Float(right / width),
      Float(bottom / height)
    )
  }

  func render(
    source: CGImage,
    persons: [IOSPreviewPerson],
    preprocess: IOSYoloPreprocessResult,
    fullBodyIds: Set<Int>,
    faceOnlyIds: Set<Int>,
    effects: EffectConfigDto,
    faceRegions: [Int: IOSFacePrivacyEllipse] = [:],
    outputWidth: Int? = nil,
    outputHeight: Int? = nil
  ) throws -> CGImage {
    let sourceWidth = max(1, source.width)
    let sourceHeight = max(1, source.height)
    let previewWidth: Int
    let previewHeight: Int
    if let outputWidth, let outputHeight, outputWidth > 0, outputHeight > 0 {
      previewWidth = max(2, outputWidth)
      previewHeight = max(2, outputHeight)
    } else {
      previewWidth = min(sourceWidth, 1280)
      previewHeight = max(
        1,
        Int((Double(previewWidth) * Double(sourceHeight) / Double(sourceWidth)).rounded())
      )
    }

    let sourceBytes = try Self.rgbaBytes(
      image: source,
      width: previewWidth,
      height: previewHeight
    )
    let renderInputs = Self.buildPrivacyInputs(
      persons: persons,
      preprocess: preprocess,
      fullBodyIds: fullBodyIds,
      faceOnlyIds: faceOnlyIds,
      faceRegions: faceRegions,
      sourceWidth: sourceWidth,
      sourceHeight: sourceHeight,
      previewWidth: previewWidth,
      previewHeight: previewHeight
    )

    let sourceTexture = try makeTexture(
      width: previewWidth,
      height: previewHeight,
      pixelFormat: .rgba8Unorm,
      usage: [.shaderRead],
      bytes: sourceBytes,
      bytesPerRow: previewWidth * 4
    )
    let maskTexture = try makeTexture(
      width: previewWidth,
      height: previewHeight,
      pixelFormat: .r8Unorm,
      usage: [.shaderRead],
      bytes: renderInputs.privacyMask,
      bytesPerRow: previewWidth
    )
    let outputDescriptor = MTLTextureDescriptor.texture2DDescriptor(
      pixelFormat: .rgba8Unorm,
      width: previewWidth,
      height: previewHeight,
      mipmapped: false
    )
    outputDescriptor.storageMode = .shared
    outputDescriptor.usage = [.shaderWrite]
    guard let outputTexture = device.makeTexture(descriptor: outputDescriptor) else {
      throw renderError("Could not allocate Metal preview output texture.")
    }
    guard let commandBuffer = commandQueue.makeCommandBuffer(),
          let encoder = commandBuffer.makeComputeCommandEncoder() else {
      throw renderError("Could not allocate Metal preview command encoder.")
    }

    let requestedFillMode = effects.fillMode.lowercased()
    var fillMode = Self.fillModeValue(requestedFillMode)
    var opacity = Float(max(0.0, min(1.0, effects.opacity)))
    let stickerEnabled = (effects.faceStickerEnabled || requestedFillMode == "sticker")
      && !renderInputs.faceRects.isEmpty
    if requestedFillMode == "sticker" {
      // Match Android's fail-closed sticker behavior: transparent asset holes
      // never expose the face. A solid privacy layer is rendered first and the
      // opaque sticker is composited over it.
      fillMode = 0
      opacity = 1.0
    }

    var fillColor = Self.argbColor(effects.fillColorArgb)
    var borderColor = Self.argbColor(effects.borderColorArgb)
    var borderWidth = Float(max(0.0, effects.borderWidth))
    var blurStrength = Float(max(1.0, effects.blurStrength))
    var stickerFlag: UInt32 = stickerEnabled ? 1 : 0
    var faceRectCount = UInt32(renderInputs.faceRects.count)
    var faceRects = renderInputs.faceRects.isEmpty
      ? [SIMD4<Float>(repeating: 0)]
      : renderInputs.faceRects

    encoder.setComputePipelineState(pipeline)
    encoder.setTexture(sourceTexture, index: 0)
    encoder.setTexture(maskTexture, index: 1)
    encoder.setTexture(outputTexture, index: 2)
    encoder.setBytes(&fillMode, length: MemoryLayout<UInt32>.size, index: 0)
    encoder.setBytes(&fillColor, length: MemoryLayout<SIMD4<Float>>.size, index: 1)
    encoder.setBytes(&borderColor, length: MemoryLayout<SIMD4<Float>>.size, index: 2)
    encoder.setBytes(&opacity, length: MemoryLayout<Float>.size, index: 3)
    encoder.setBytes(&borderWidth, length: MemoryLayout<Float>.size, index: 4)
    encoder.setBytes(&blurStrength, length: MemoryLayout<Float>.size, index: 5)
    encoder.setBytes(&stickerFlag, length: MemoryLayout<UInt32>.size, index: 6)
    encoder.setBytes(&faceRectCount, length: MemoryLayout<UInt32>.size, index: 7)
    faceRects.withUnsafeBytes { raw in
      if let base = raw.baseAddress {
        encoder.setBytes(base, length: raw.count, index: 8)
      }
    }

    let threadWidth = max(1, pipeline.threadExecutionWidth)
    let threadHeight = max(1, pipeline.maxTotalThreadsPerThreadgroup / threadWidth)
    encoder.dispatchThreads(
      MTLSize(width: previewWidth, height: previewHeight, depth: 1),
      threadsPerThreadgroup: MTLSize(width: threadWidth, height: threadHeight, depth: 1)
    )
    encoder.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    if let error = commandBuffer.error {
      throw PigeonError(
        code: "METAL_RENDER_FAILED",
        message: "Metal preview command failed.",
        details: String(describing: error)
      )
    }

    var outputBytes = [UInt8](repeating: 0, count: previewWidth * previewHeight * 4)
    outputBytes.withUnsafeMutableBytes { raw in
      if let base = raw.baseAddress {
        outputTexture.getBytes(
          base,
          bytesPerRow: previewWidth * 4,
          from: MTLRegionMake2D(0, 0, previewWidth, previewHeight),
          mipmapLevel: 0
        )
      }
    }
    return try Self.image(
      rgbaBytes: outputBytes,
      width: previewWidth,
      height: previewHeight
    )
  }

  private func makeTexture(
    width: Int,
    height: Int,
    pixelFormat: MTLPixelFormat,
    usage: MTLTextureUsage,
    bytes: [UInt8],
    bytesPerRow: Int
  ) throws -> MTLTexture {
    let descriptor = MTLTextureDescriptor.texture2DDescriptor(
      pixelFormat: pixelFormat,
      width: width,
      height: height,
      mipmapped: false
    )
    descriptor.storageMode = .shared
    descriptor.usage = usage
    guard let texture = device.makeTexture(descriptor: descriptor) else {
      throw renderError("Could not allocate Metal preview texture.")
    }
    bytes.withUnsafeBytes { raw in
      if let base = raw.baseAddress {
        texture.replace(
          region: MTLRegionMake2D(0, 0, width, height),
          mipmapLevel: 0,
          withBytes: base,
          bytesPerRow: bytesPerRow
        )
      }
    }
    return texture
  }

  private struct PrivacyInputs {
    let privacyMask: [UInt8]
    let faceRects: [SIMD4<Float>]
  }

  private static func buildPrivacyInputs(
    persons: [IOSPreviewPerson],
    preprocess: IOSYoloPreprocessResult,
    fullBodyIds: Set<Int>,
    faceOnlyIds: Set<Int>,
    faceRegions: [Int: IOSFacePrivacyEllipse],
    sourceWidth: Int,
    sourceHeight: Int,
    previewWidth: Int,
    previewHeight: Int
  ) -> PrivacyInputs {
    let fullBodyPersons = persons.filter { fullBodyIds.contains($0.id) }
    let effectiveFaceOnlyIds = faceOnlyIds.subtracting(fullBodyIds)
    let facePersons = persons.filter { effectiveFaceOnlyIds.contains($0.id) }
    let effectiveFullBodyMasks = fullBodyPersons.map { target in
      effectivePrivacyMask(
        for: target,
        allPersons: persons,
        fullBodyIds: fullBodyIds
      )
    }

    var mask = [UInt8](repeating: 0, count: previewWidth * previewHeight)
    if !fullBodyPersons.isEmpty {
      for previewY in 0..<previewHeight {
        let sourceY = (Float32(previewY) + 0.5)
          * Float32(sourceHeight) / Float32(previewHeight)
        let modelY = sourceY * preprocess.scale + preprocess.padTop
        let protoY = Int(floor(
          Double(modelY / Float32(preprocess.inputSize) * Float32(IOSYoloPostprocessor.protoSize))
        ))
        guard protoY >= 0, protoY < IOSYoloPostprocessor.protoSize else { continue }
        for previewX in 0..<previewWidth {
          let sourceX = (Float32(previewX) + 0.5)
            * Float32(sourceWidth) / Float32(previewWidth)
          let modelX = sourceX * preprocess.scale + preprocess.padLeft
          let protoX = Int(floor(
            Double(modelX / Float32(preprocess.inputSize) * Float32(IOSYoloPostprocessor.protoSize))
          ))
          guard protoX >= 0, protoX < IOSYoloPostprocessor.protoSize else { continue }
          let protoIndex = protoY * IOSYoloPostprocessor.protoSize + protoX
          var value: UInt8 = 0
          for fullMask in effectiveFullBodyMasks {
            value = max(value, fullMask[protoIndex])
          }
          mask[previewY * previewWidth + previewX] = value
        }
      }
    }

    var regionsToRender: [IOSFacePrivacyEllipse] = []
    regionsToRender.reserveCapacity(facePersons.count + faceRegions.count)
    for person in facePersons {
      let region = faceRegions[person.id]
        ?? IOSFacePrivacyGeometry.fallbackEllipse(person.detection)
      guard let region else { continue }
      regionsToRender.append(region)
    }
    for syntheticId in faceRegions.keys.filter({ $0 < 0 }).sorted() {
      if let region = faceRegions[syntheticId] {
        regionsToRender.append(region)
      }
    }

    var faceRects: [SIMD4<Float>] = []
    faceRects.reserveCapacity(regionsToRender.count)
    for region in regionsToRender {
      let rect = faceRect(
        region,
        sourceWidth: sourceWidth,
        sourceHeight: sourceHeight
      )
      faceRects.append(rect)
      let x1 = max(0, min(previewWidth, Int(floor(Double(rect.x) * Double(previewWidth)))))
      let y1 = max(0, min(previewHeight, Int(floor(Double(rect.y) * Double(previewHeight)))))
      let x2 = max(x1, min(previewWidth, Int(ceil(Double(rect.z) * Double(previewWidth)))))
      let y2 = max(y1, min(previewHeight, Int(ceil(Double(rect.w) * Double(previewHeight)))))
      for y in y1..<y2 {
        let row = y * previewWidth
        let sourceY = (Float32(y) + 0.5) * Float32(sourceHeight) / Float32(previewHeight)
        let dy = (sourceY - region.centerY) / max(1, region.radiusY)
        for x in x1..<x2 {
          let sourceX = (Float32(x) + 0.5) * Float32(sourceWidth) / Float32(previewWidth)
          let dx = (sourceX - region.centerX) / max(1, region.radiusX)
          if dx * dx + dy * dy <= 1 {
            mask[row + x] = 255
          }
        }
      }
    }
    return PrivacyInputs(privacyMask: mask, faceRects: faceRects)
  }

  /// Single-frame subset of Android PrivacyOcclusionResolver. Without temporal
  /// depth evidence, ambiguous overlap always keeps privacy. Only an unselected
  /// person whose bbox overlaps >=10%, whose mask overlaps >2%, and whose feet
  /// are >=10% of the shorter person height lower in frame may carve an eroded
  /// raw-mask core. This avoids the unsafe "all overlap means foreground" rule.
  private static func effectivePrivacyMask(
    for target: IOSPreviewPerson,
    allPersons: [IOSPreviewPerson],
    fullBodyIds: Set<Int>
  ) -> [UInt8] {
    var effective = dilate(target.detection.mask, radius: 1)
    // A temporal predicted fallback is already the conservative privacy
    // boundary for an unresolved selected identity. Do not let the preview-only
    // single-frame foreground carve punch holes into that fail-closed mask.
    if target.conservativePrivacyFallback {
      return effective
    }
    let targetHeight = max(10, target.detection.y2 - target.detection.y1)
    for candidate in allPersons where !fullBodyIds.contains(candidate.id) {
      let candidateHeight = max(10, candidate.detection.y2 - candidate.detection.y1)
      let overlap = bboxOverlapRatio(target.detection, candidate.detection)
      guard overlap >= 0.10 else { continue }
      let normalizedFootDelta = (candidate.detection.y2 - target.detection.y2)
        / min(targetHeight, candidateHeight)
      guard normalizedFootDelta >= 0.10 else { continue }
      guard maskOverlapRatio(effective, candidate.detection.mask) > 0.02 else { continue }

      let occluder = erode(candidate.detection.mask, radius: 1)
      for index in effective.indices {
        let privacy = Int(effective[index])
        let carve = 255 - Int(occluder[index])
        effective[index] = UInt8(max(0, min(255, privacy * carve / 255)))
      }
    }
    return effective
  }

  private static func bboxOverlapRatio(
    _ first: IOSYoloDetection,
    _ second: IOSYoloDetection
  ) -> Float32 {
    let left = max(first.x1, second.x1)
    let top = max(first.y1, second.y1)
    let right = min(first.x2, second.x2)
    let bottom = min(first.y2, second.y2)
    let intersection = max(0, right - left) * max(0, bottom - top)
    let firstArea = max(0, first.x2 - first.x1) * max(0, first.y2 - first.y1)
    let secondArea = max(0, second.x2 - second.x1) * max(0, second.y2 - second.y1)
    let denominator = min(firstArea, secondArea)
    return denominator > 0 ? intersection / denominator : 0
  }

  private static func maskOverlapRatio(_ first: [UInt8], _ second: [UInt8]) -> Float32 {
    guard first.count == second.count, !first.isEmpty else { return 0 }
    var firstPixels = 0
    var overlapPixels = 0
    for index in first.indices {
      let firstVisible = first[index] >= 39
      if firstVisible { firstPixels += 1 }
      if firstVisible && second[index] >= 39 { overlapPixels += 1 }
    }
    return firstPixels > 0 ? Float32(overlapPixels) / Float32(firstPixels) : 0
  }

  /// Phase 3 intentionally uses a conservative head ROI until the iOS face
  /// detector/temporal resolver is ported. It is allowed to cover extra hair or
  /// shoulder pixels but must not expose a selected face.
  private static func conservativeFaceRect(
    _ detection: IOSYoloDetection,
    sourceWidth: Int,
    sourceHeight: Int
  ) -> SIMD4<Float> {
    let width = max(1, detection.x2 - detection.x1)
    let height = max(1, detection.y2 - detection.y1)
    let horizontalPad = width * 0.03
    let verticalPad = height * 0.03
    let left = max(0, detection.x1 - horizontalPad)
    let right = min(Float32(sourceWidth), detection.x2 + horizontalPad)
    let top = max(0, detection.y1 - verticalPad)
    let bottom = min(Float32(sourceHeight), detection.y1 + height * 0.40)
    return SIMD4<Float>(
      Float(left / Float32(sourceWidth)),
      Float(top / Float32(sourceHeight)),
      Float(right / Float32(sourceWidth)),
      Float(bottom / Float32(sourceHeight))
    )
  }

  private static func dilate(_ input: [UInt8], radius: Int) -> [UInt8] {
    guard radius > 0 else { return input }
    let width = IOSYoloPostprocessor.protoSize
    let height = IOSYoloPostprocessor.protoSize
    guard input.count == width * height else { return input }
    var output = [UInt8](repeating: 0, count: input.count)
    for y in 0..<height {
      let minY = max(0, y - radius)
      let maxY = min(height - 1, y + radius)
      for x in 0..<width {
        let minX = max(0, x - radius)
        let maxX = min(width - 1, x + radius)
        var maximum: UInt8 = 0
        for neighborY in minY...maxY {
          let row = neighborY * width
          for neighborX in minX...maxX {
            maximum = max(maximum, input[row + neighborX])
          }
        }
        output[y * width + x] = maximum
      }
    }
    return output
  }

  private static func erode(_ input: [UInt8], radius: Int) -> [UInt8] {
    guard radius > 0 else { return input }
    let width = IOSYoloPostprocessor.protoSize
    let height = IOSYoloPostprocessor.protoSize
    guard input.count == width * height else { return input }
    var output = [UInt8](repeating: 0, count: input.count)
    for y in 0..<height {
      let minY = max(0, y - radius)
      let maxY = min(height - 1, y + radius)
      for x in 0..<width {
        let minX = max(0, x - radius)
        let maxX = min(width - 1, x + radius)
        var minimum: UInt8 = 255
        for neighborY in minY...maxY {
          let row = neighborY * width
          for neighborX in minX...maxX {
            minimum = min(minimum, input[row + neighborX])
          }
        }
        output[y * width + x] = minimum
      }
    }
    return output
  }

  private static func fillModeValue(_ value: String) -> UInt32 {
    switch value {
    case "blur": return 2
    case "gradient": return 3
    case "mosaic": return 5
    default: return 0
    }
  }

  private static func argbColor(_ value: Int64) -> SIMD4<Float> {
    let raw = UInt32(truncatingIfNeeded: value)
    return SIMD4<Float>(
      Float((raw >> 16) & 0xff) / 255.0,
      Float((raw >> 8) & 0xff) / 255.0,
      Float(raw & 0xff) / 255.0,
      Float((raw >> 24) & 0xff) / 255.0
    )
  }

  private static func rgbaBytes(
    image: CGImage,
    width: Int,
    height: Int
  ) throws -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: width * height * 4)
    let created = bytes.withUnsafeMutableBytes { raw -> Bool in
      guard let base = raw.baseAddress,
            let context = CGContext(
              data: base,
              width: width,
              height: height,
              bitsPerComponent: 8,
              bytesPerRow: width * 4,
              space: CGColorSpaceCreateDeviceRGB(),
              bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue
                | CGImageAlphaInfo.premultipliedLast.rawValue
            ) else {
        return false
      }
      context.translateBy(x: 0, y: CGFloat(height))
      context.scaleBy(x: 1, y: -1)
      context.interpolationQuality = .high
      context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
      return true
    }
    guard created else {
      throw PigeonError(
        code: "PREVIEW_FRAME_CONVERSION_FAILED",
        message: "Could not convert the iOS preview frame to RGBA pixels.",
        details: nil
      )
    }
    return bytes
  }

  private static func image(
    rgbaBytes: [UInt8],
    width: Int,
    height: Int
  ) throws -> CGImage {
    let data = Data(rgbaBytes) as CFData
    guard let provider = CGDataProvider(data: data),
          let image = CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(
              rawValue: CGBitmapInfo.byteOrder32Big.rawValue
                | CGImageAlphaInfo.premultipliedLast.rawValue
            ),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
          ) else {
      throw PigeonError(
        code: "PREVIEW_FRAME_CONVERSION_FAILED",
        message: "Could not create the rendered iOS preview image.",
        details: nil
      )
    }
    return image
  }

  private func renderError(_ message: String) -> PigeonError {
    PigeonError(code: "METAL_RENDER_FAILED", message: message, details: nil)
  }

  private static let kernelSource = #"""
#include <metal_stdlib>
using namespace metal;

static inline int2 clampPixel(int2 p, uint width, uint height) {
  return int2(
    clamp(p.x, 0, int(width) - 1),
    clamp(p.y, 0, int(height) - 1)
  );
}

static inline float ellipseMask(float2 local) {
  float2 d = (local - float2(0.5)) / float2(0.50, 0.48);
  return dot(d, d) <= 1.0 ? 1.0 : 0.0;
}

kernel void woahPreviewKernel(
  texture2d<float, access::read> source [[texture(0)]],
  texture2d<float, access::read> privacyMask [[texture(1)]],
  texture2d<float, access::write> output [[texture(2)]],
  constant uint &fillMode [[buffer(0)]],
  constant float4 &fillColor [[buffer(1)]],
  constant float4 &borderColor [[buffer(2)]],
  constant float &opacity [[buffer(3)]],
  constant float &borderWidth [[buffer(4)]],
  constant float &blurStrength [[buffer(5)]],
  constant uint &stickerEnabled [[buffer(6)]],
  constant uint &faceRectCount [[buffer(7)]],
  constant float4 *faceRects [[buffer(8)]],
  uint2 gid [[thread_position_in_grid]]
) {
  uint width = output.get_width();
  uint height = output.get_height();
  if (gid.x >= width || gid.y >= height) return;

  float4 original = source.read(gid);
  float maskValue = privacyMask.read(gid).r;
  float4 color = original;
  float blendAlpha = smoothstep(0.15, 0.85, maskValue) * opacity;

  if (blendAlpha > 0.001) {
    float4 effectColor = original;
    if (fillMode == 0) {
      effectColor = float4(fillColor.rgb, 1.0);
    } else if (fillMode == 2) {
      int stepSize = max(2, int(round(blurStrength * 2.0)));
      float4 sum = float4(0.0);
      float count = 0.0;
      for (int oy = -2; oy <= 2; ++oy) {
        for (int ox = -2; ox <= 2; ++ox) {
          int2 samplePosition = clampPixel(
            int2(gid) + int2(ox * stepSize, oy * stepSize),
            width,
            height
          );
          sum += source.read(uint2(samplePosition));
          count += 1.0;
        }
      }
      effectColor = sum / count;
    } else if (fillMode == 3) {
      float vertical = (float(gid.y) + 0.5) / float(height);
      effectColor = mix(fillColor, float4(0.5, 0.2, 0.9, 1.0), vertical);
    } else if (fillMode == 5) {
      int block = max(4, int(round(blurStrength * 4.0)));
      uint2 blockOrigin = uint2(
        min(width - 1, uint((int(gid.x) / block) * block)),
        min(height - 1, uint((int(gid.y) / block) * block))
      );
      effectColor = source.read(blockOrigin);
    }
    color = mix(color, effectColor, blendAlpha);
  }

  if (borderWidth > 0.1 && borderColor.a > 0.01) {
    int radius = max(1, int(round(borderWidth * 3.0)));
    int2 p = int2(gid);
    int2 offsets[8] = {
      int2(0, radius), int2(0, -radius), int2(radius, 0), int2(-radius, 0),
      int2(radius, radius), int2(-radius, radius), int2(radius, -radius), int2(-radius, -radius)
    };
    float neighbor = 0.0;
    for (uint i = 0; i < 8; ++i) {
      uint2 samplePosition = uint2(clampPixel(p + offsets[i], width, height));
      neighbor = max(neighbor, privacyMask.read(samplePosition).r);
    }
    float outline = clamp(neighbor - maskValue, 0.0, 1.0);
    float outlineAlpha = smoothstep(0.10, 0.80, outline) * borderColor.a;
    color = mix(color, float4(borderColor.rgb, 1.0), outlineAlpha);
  }

  if (stickerEnabled == 1 && faceRectCount > 0) {
    float2 uv = float2(
      (float(gid.x) + 0.5) / float(width),
      (float(gid.y) + 0.5) / float(height)
    );
    for (uint index = 0; index < faceRectCount; ++index) {
      float4 rect = faceRects[index];
      if (uv.x < rect.x || uv.x > rect.z || uv.y < rect.y || uv.y > rect.w) continue;
      float2 local = float2(
        (uv.x - rect.x) / max(0.0001, rect.z - rect.x),
        (uv.y - rect.y) / max(0.0001, rect.w - rect.y)
      );
      if (ellipseMask(local) < 0.5) continue;

      // Privacy-safe built-in sunglasses sticker. The face oval is opaque;
      // dark lenses and bridge are painted over it, so no transparent hole can
      // reveal identity even before the dedicated face detector is ported.
      float4 sticker = float4(1.0, 0.84, 0.0, 1.0);
      float2 centered = local - float2(0.5);
      float edge = dot(centered / float2(0.50, 0.48), centered / float2(0.50, 0.48));
      if (edge > 0.84) sticker = float4(0.12, 0.12, 0.12, 1.0);
      bool leftLens = local.x >= 0.18 && local.x <= 0.46 && local.y >= 0.31 && local.y <= 0.56;
      bool rightLens = local.x >= 0.54 && local.x <= 0.82 && local.y >= 0.31 && local.y <= 0.56;
      bool bridge = local.x >= 0.44 && local.x <= 0.56 && local.y >= 0.39 && local.y <= 0.47;
      if (leftLens || rightLens || bridge) {
        sticker = float4(0.02, 0.02, 0.02, 1.0);
      }
      color = sticker;
    }
  }

  output.write(float4(color.rgb, 1.0), gid);
}
"""#
}

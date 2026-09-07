import Foundation
import Metal
import UIKit
import VideoToolbox

enum IOSDeviceCapabilities {
  private static let candidateEncodeSizes: [(Int32, Int32)] = [
    (3840, 2160),
    (2560, 1440),
    (1920, 1080),
    (1280, 720),
  ]

  static func detect() -> NativeCapabilitiesDto {
    let h264Supported = VTIsHardwareEncodeSupported(kCMVideoCodecType_H264)
    let hevcSupported = VTIsHardwareEncodeSupported(kCMVideoCodecType_HEVC)
    var supportedCodecs: [CMVideoCodecType] = []
    if h264Supported {
      supportedCodecs.append(kCMVideoCodecType_H264)
    }
    if hevcSupported {
      supportedCodecs.append(kCMVideoCodecType_HEVC)
    }
    let maxSize = maximumHardwareEncodeSize(for: supportedCodecs)
    let inferenceBackends = IOSYoloRuntimeSupport.candidateBackendNames()

    return NativeCapabilitiesDto(
      platform: "ios",
      osVersion: UIDevice.current.systemVersion,
      gpuSupported: MTLCreateSystemDefaultDevice() != nil,
      h264Encoder: h264Supported,
      hevcEncoder: hevcSupported,
      maxEncodeWidth: Int64(maxSize.width),
      maxEncodeHeight: Int64(maxSize.height),
      cpuCores: Int64(ProcessInfo.processInfo.processorCount),
      recommendedProfile: "balanced",
      // Phase 1 has a real isolated YOLO runtime, so expose only backends for
      // which both the runtime module and staged model resource are present.
      // Public processing profiles remain empty until analyze/preview/export
      // are accepted and connected to the product flow.
      supportedProfiles: [],
      inferenceBackends: inferenceBackends
    )
  }

  private static func maximumHardwareEncodeSize(
    for codecs: [CMVideoCodecType]
  ) -> (width: Int32, height: Int32) {
    guard !codecs.isEmpty else { return (0, 0) }

    for size in candidateEncodeSizes {
      if codecs.contains(where: { canCreateHardwareSession(
        codec: $0,
        width: size.0,
        height: size.1
      ) }) {
        return (size.0, size.1)
      }
    }
    return (0, 0)
  }

  private static func canCreateHardwareSession(
    codec: CMVideoCodecType,
    width: Int32,
    height: Int32
  ) -> Bool {
    let specification = [
      kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder as String: true,
    ] as CFDictionary
    var session: VTCompressionSession?
    let status = VTCompressionSessionCreate(
      allocator: kCFAllocatorDefault,
      width: width,
      height: height,
      codecType: codec,
      encoderSpecification: specification,
      imageBufferAttributes: nil,
      compressedDataAllocator: nil,
      outputCallback: nil,
      refcon: nil,
      compressionSessionOut: &session
    )
    if let createdSession = session {
      VTCompressionSessionInvalidate(createdSession)
    }
    return status == noErr && session != nil
  }
}

import Foundation
import Metal
import UIKit
import VideoToolbox

enum IOSDeviceCapabilities {
  private struct HardwareEncoder {
    let codec: CMVideoCodecType
    let encoderID: String
  }

  private static let candidateEncodeSizes: [(Int32, Int32)] = [
    (3840, 2160),
    (2560, 1440),
    (1920, 1080),
    (1280, 720),
  ]

  static func detect() -> NativeCapabilitiesDto {
    let hardwareEncoders = availableHardwareEncoders()
    let h264Supported = hardwareEncoders.contains { $0.codec == kCMVideoCodecType_H264 }
    let hevcSupported = hardwareEncoders.contains { $0.codec == kCMVideoCodecType_HEVC }
    let maxSize = maximumHardwareEncodeSize(for: hardwareEncoders)
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
    for encoders: [HardwareEncoder]
  ) -> (width: Int32, height: Int32) {
    guard !encoders.isEmpty else { return (0, 0) }

    for size in candidateEncodeSizes {
      if encoders.contains(where: { canCreateHardwareSession(
        encoder: $0,
        width: size.0,
        height: size.1
      ) }) {
        return (size.0, size.1)
      }
    }
    return (0, 0)
  }

  private static func availableHardwareEncoders() -> [HardwareEncoder] {
    var rawList: CFArray?
    guard VTCopyVideoEncoderList(nil, &rawList) == noErr,
          let rawList else {
      return []
    }

    let dictionaries = rawList as NSArray
    var result: [HardwareEncoder] = []
    result.reserveCapacity(dictionaries.count)
    for case let dictionary as NSDictionary in dictionaries {
      guard let codecNumber = dictionary[kVTVideoEncoderList_CodecType] as? NSNumber,
            let hardwareNumber = dictionary[kVTVideoEncoderList_IsHardwareAccelerated] as? NSNumber,
            hardwareNumber.boolValue,
            let encoderID = dictionary[kVTVideoEncoderList_EncoderID] as? String else {
        continue
      }
      result.append(HardwareEncoder(
        codec: CMVideoCodecType(codecNumber.uint32Value),
        encoderID: encoderID
      ))
    }
    return result
  }

  private static func canCreateHardwareSession(
    encoder: HardwareEncoder,
    width: Int32,
    height: Int32
  ) -> Bool {
    let specification = [
      kVTVideoEncoderSpecification_EncoderID as String: encoder.encoderID,
    ] as CFDictionary
    var session: VTCompressionSession?
    let status = VTCompressionSessionCreate(
      allocator: kCFAllocatorDefault,
      width: width,
      height: height,
      codecType: encoder.codec,
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

import Foundation

enum IOSModelResources {
  static let yoloModelFileName = "yolo11n-seg-fp16"
  static let yoloModelExtension = "tflite"

  static func yoloModelURL() -> URL? {
    for bundle in candidateBundles() {
      if let direct = bundle.url(
        forResource: yoloModelFileName,
        withExtension: yoloModelExtension
      ) {
        return direct
      }

      if let nestedURL = bundle.url(
        forResource: "dance_native_models",
        withExtension: "bundle"
      ),
         let nested = Bundle(url: nestedURL),
         let modelURL = nested.url(
           forResource: yoloModelFileName,
           withExtension: yoloModelExtension
         ) {
        return modelURL
      }
    }
    return nil
  }

  private static func candidateBundles() -> [Bundle] {
    var bundles: [Bundle] = [Bundle(for: DanceNativePlugin.self), Bundle.main]
#if SWIFT_PACKAGE
    bundles.insert(Bundle.module, at: 0)
#endif
    return bundles
  }
}

import Foundation

enum IOSModelResources {
  static let yoloModelFileName = "yolo11n-seg-fp16"
  static let yoloModelExtension = "tflite"
  static let yoloPhase1FixtureFileName = "yolo_phase1_test_frame"
  static let yoloPhase1FixtureExtension = "jpg"

  static func yoloModelURL() -> URL? {
    resourceURL(
      fileName: yoloModelFileName,
      fileExtension: yoloModelExtension
    )
  }

  static func yoloPhase1FixtureURL() -> URL? {
    resourceURL(
      fileName: yoloPhase1FixtureFileName,
      fileExtension: yoloPhase1FixtureExtension
    )
  }

  private static func resourceURL(
    fileName: String,
    fileExtension: String
  ) -> URL? {
    for bundle in candidateBundles() {
      if let direct = bundle.url(
        forResource: fileName,
        withExtension: fileExtension
      ) {
        return direct
      }

      if let nestedURL = bundle.url(
        forResource: "dance_native_models",
        withExtension: "bundle"
      ),
         let nested = Bundle(url: nestedURL),
         let modelURL = nested.url(
           forResource: fileName,
           withExtension: fileExtension
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

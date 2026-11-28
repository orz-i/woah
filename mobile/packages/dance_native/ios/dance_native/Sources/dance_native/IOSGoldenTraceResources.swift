import Foundation

enum IOSGoldenTraceResources {
  static let trackingFileName = "tracking_golden_v1"

  static func trackingURL() -> URL? {
    for bundle in candidateBundles() {
      if let direct = bundle.url(
        forResource: trackingFileName,
        withExtension: "json"
      ) {
        return direct
      }
      if let directNested = bundle.url(
        forResource: trackingFileName,
        withExtension: "json",
        subdirectory: "GoldenTraces"
      ) {
        return directNested
      }

      if let nestedURL = bundle.url(
        forResource: "dance_native_golden_traces",
        withExtension: "bundle"
      ),
         let nested = Bundle(url: nestedURL) {
        if let traceURL = nested.url(
          forResource: trackingFileName,
          withExtension: "json"
        ) {
          return traceURL
        }
        if let nestedTraceURL = nested.url(
          forResource: trackingFileName,
          withExtension: "json",
          subdirectory: "GoldenTraces"
        ) {
          return nestedTraceURL
        }
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

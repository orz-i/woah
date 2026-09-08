import Foundation

enum IOSGoldenTraceResources {
  static let phase5TrackingFileName = "phase5_tracking_golden"

  static func phase5TrackingURL() -> URL? {
    for bundle in candidateBundles() {
      if let direct = bundle.url(
        forResource: phase5TrackingFileName,
        withExtension: "json"
      ) {
        return direct
      }
      if let directNested = bundle.url(
        forResource: phase5TrackingFileName,
        withExtension: "json",
        subdirectory: "GoldenTraces"
      ) {
        return directNested
      }

      if let nestedURL = bundle.url(
        forResource: "dance_native_phase5",
        withExtension: "bundle"
      ),
         let nested = Bundle(url: nestedURL) {
        if let traceURL = nested.url(
          forResource: phase5TrackingFileName,
          withExtension: "json"
        ) {
          return traceURL
        }
        if let nestedTraceURL = nested.url(
          forResource: phase5TrackingFileName,
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

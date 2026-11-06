import Foundation
struct IOSCachedBBox {
  let left: Double, top: Double, right: Double, bottom: Double
}
struct IOSCachedPerson { let id: Int; let bbox: IOSCachedBBox }
struct IOSAnalysisMetadata { let persons: [IOSCachedPerson] }

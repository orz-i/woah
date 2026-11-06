import AVFoundation
import CryptoKit
import Foundation
import ImageIO
import Vision

enum AuditError: Error { case invalidSource }
guard CommandLine.arguments.count == 3 else {
  fatalError("usage: privacy-source-faces source.mp4 output.jsonl")
}
let source = URL(fileURLWithPath: CommandLine.arguments[1])
let output = URL(fileURLWithPath: CommandLine.arguments[2])
let asset = AVURLAsset(url: source)
guard let track = asset.tracks(withMediaType: .video).first,
      track.preferredTransform.isIdentity else { throw AuditError.invalidSource }
let reader = try AVAssetReader(asset: asset)
let decoded = AVAssetReaderTrackOutput(track: track, outputSettings: [
  kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
])
decoded.alwaysCopiesSampleData = false
reader.add(decoded)
guard reader.startReading() else { throw reader.error ?? AuditError.invalidSource }
FileManager.default.createFile(atPath: output.path, contents: nil)
let handle = try FileHandle(forWritingTo: output)
defer { handle.closeFile() }
func append(_ object: [String: Any]) throws {
  let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
  handle.write(data); handle.write(Data([10]))
}
let digest = SHA256.hash(data: try Data(contentsOf: source, options: .mappedIfSafe)).map { String(format:"%02x",$0) }.joined()
try append(["kind":"header","source":source.lastPathComponent,"sha256":digest,
            "detector":"macOS Vision VNDetectFaceRectanglesRequest CPU; source pixels only"])
var index = 0
while let sample = decoded.copyNextSampleBuffer() {
  try autoreleasepool {
    guard let buffer = CMSampleBufferGetImageBuffer(sample) else { throw AuditError.invalidSource }
    let request = VNDetectFaceRectanglesRequest()
    request.usesCPUOnly = true
    let handler = VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up, options: [:])
    try handler.perform([request])
    let width = Double(CVPixelBufferGetWidth(buffer)), height = Double(CVPixelBufferGetHeight(buffer))
    let faces = (request.results ?? []).map { observation -> [String:Any] in
      let b = observation.boundingBox
      return ["bbox":[b.minX*width,(1-b.maxY)*height,b.maxX*width,(1-b.minY)*height],
              "confidence":observation.confidence]
    }
    try append(["kind":"frame","frame":index,"pts":CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample)),
                "width":width,"height":height,"faces":faces])
    index += 1
  }
}
guard reader.status == .completed else { throw reader.error ?? AuditError.invalidSource }
try append(["kind":"finished","decoded_frames":index])
print("SOURCE_FACE_SCAN_FINISHED \(source.lastPathComponent) frames=\(index)")

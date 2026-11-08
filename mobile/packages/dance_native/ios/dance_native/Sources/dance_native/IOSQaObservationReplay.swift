import Foundation
import CryptoKit

/// QA-only local observations, approved individually after baseline review.
/// The caller always preprocesses actual native pixels; tracking/masks are never cached.
final class IOSQaObservationReplay {
  private let directory: URL
  private let contract: String
  private static let lock = NSRecursiveLock()
  private static let active: IOSQaObservationReplay? = {
#if DEBUG && targetEnvironment(simulator)
    do {
      let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      let control = documents.appendingPathComponent("qa_observation_replay.txt")
      guard FileManager.default.fileExists(atPath: control.path) else { return nil }
      let lines = try String(contentsOf: control, encoding: .utf8).split(separator: "\n").map(String.init)
      guard lines.count == 3, lines[0] == "qa-observation-v1",
        validHash(lines[1]), validHash(lines[2]), let executable = Bundle.main.executableURL,
        digest(try Data(contentsOf: executable)) == lines[1] else { return nil }
      return IOSQaObservationReplay(directory: documents.appendingPathComponent("qa-observations/\(lines[2])"),
        contract: lines[2])
    } catch { return nil }
#else
    return nil
#endif
  }()
  static func open() -> IOSQaObservationReplay? { active }
  init(directory: URL, contract: String) { self.directory=directory; self.contract=contract }

  func key(kind: String, dimensions: String, input: Data) -> String {
    var hash = SHA256()
    hash.update(data: Data("qa-observation-v1|\(contract)|\(kind)|\(dimensions)|".utf8))
    hash.update(data: input)
    return hash.finalize().map {String(format: "%02x", $0)}.joined()
  }

  func read<T: Decodable>(_ key: String, as type: T.Type, validate: (T)->Bool) -> T? {
    Self.lock.lock(); defer {Self.lock.unlock()}
    do {
      guard !FileManager.default.fileExists(atPath: path(key+".conflict").path),
        FileManager.default.fileExists(atPath: path(key+".approved").path) else {return nil}
      let expected = try String(contentsOf: path(key+".approved"), encoding: .utf8)
        .trimmingCharacters(in: .whitespacesAndNewlines)
      guard Self.validHash(expected) else {throw invalid()}
      let size = try FileManager.default.attributesOfItem(atPath: path(key+".bin").path)[.size] as? NSNumber
      guard let size, size.int64Value > 0, size.int64Value <= 32*1024*1024 else {throw invalid()}
      let bytes = try Data(contentsOf: path(key+".bin"))
      guard !bytes.isEmpty, bytes.count <= 32*1024*1024, Self.digest(bytes) == expected else {throw invalid()}
      let result = try PropertyListDecoder().decode(type, from: bytes)
      guard validate(result) else {throw invalid()}
      event(key, "replay", expected)
      return result
    } catch {event(key, "rejected", ""); return nil}
  }

  func record<T: Encodable>(_ key: String, _ value: T) {
    Self.lock.lock(); defer {Self.lock.unlock()}
    do {
      let encoder = PropertyListEncoder(); encoder.outputFormat = .binary
      let bytes = try encoder.encode(value)
      guard !bytes.isEmpty, bytes.count <= 32*1024*1024 else {throw invalid()}
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      let destination = path(key+".bin"), sha = Self.digest(bytes)
      if FileManager.default.fileExists(atPath: destination.path) {
        if Self.digest(try Data(contentsOf: destination)) != sha {
          try Data(sha.utf8).write(to: path(key+".conflict"), options: .atomic)
          try? FileManager.default.removeItem(at: path(key+".approved"))
          event(key, "conflict", sha); return
        }
      } else { try bytes.write(to: destination, options: .atomic) }
      event(key, "inference", sha)
    } catch {event(key, "record_failed", "")}
  }

  private func event(_ key: String, _ operation: String, _ payload: String) {
    do {
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      let file = path("events.tsv")
      if !FileManager.default.fileExists(atPath: file.path) { FileManager.default.createFile(atPath: file.path, contents: nil) }
      let handle = try FileHandle(forWritingTo: file); defer {try? handle.close()}
      try handle.seekToEnd()
      try handle.write(contentsOf: Data("\(Int64(Date().timeIntervalSince1970*1000))\t\(ProcessInfo.processInfo.processIdentifier)\t\(key)\t\(operation)\t\(payload)\n".utf8))
    } catch {}
  }
  private func path(_ name: String) -> URL {directory.appendingPathComponent(name)}
  private func invalid() -> NSError {NSError(domain:"QA_OBSERVATION_INVALID",code:1)}
  private static func validHash(_ value: String) -> Bool {
    value.count == 64 && value.allSatisfy {("0"..."9").contains($0) || ("a"..."f").contains($0)}
  }
  private static func digest(_ bytes: Data) -> String {SHA256.hash(data: bytes).map {String(format:"%02x",$0)}.joined()}
}

/// Float bits and raw masks remain exact; local files never leave their source host.
struct IOSQaYoloObservation: Codable {
  let boxes: [[UInt32]]
  let masks: [Data]
  init(_ detections: [IOSYoloDetection]) {
    boxes = detections.map {[$0.x1,$0.y1,$0.x2,$0.y2,$0.confidence].map(\.bitPattern)}
    masks = detections.map {Data($0.mask)}
  }
  func detections() -> [IOSYoloDetection]? {
    guard boxes.count <= 256, boxes.count == masks.count else {return nil}
    var result = [IOSYoloDetection]()
    for (row,mask) in zip(boxes,masks) {
      guard row.count == 5, mask.isEmpty || mask.count == 160*160 else {return nil}
      let f = row.map {Float(bitPattern:$0)}
      guard f.allSatisfy(\.isFinite), f[2]>=f[0],f[3]>=f[1],(0...1).contains(f[4]) else {return nil}
      result.append(IOSYoloDetection(x1:f[0],y1:f[1],x2:f[2],y2:f[3],confidence:f[4],mask:Array(mask)))
    }
    return result
  }
}

struct IOSQaFaceObservation: Codable {
  let bits: [UInt32]
  init(_ values: [Float]) {bits=values.map(\.bitPattern)}
  func values() -> [Float]? {
    guard bits.count <= 2304*17,bits.count % 17 == 0 else {return nil}
    let f = bits.map {Float(bitPattern:$0)}
    guard f.allSatisfy(\.isFinite) else {return nil}
    for i in stride(from:0,to:f.count,by:17) {
      guard f[i+2]>f[i],f[i+3]>f[i+1],(0...1).contains(f[i+4]) else {return nil}
    }
    return f
  }
}


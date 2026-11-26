import Foundation
import CryptoKit

/// Explicit little-endian records, no Swift ABI/padding persisted.
struct IOSCutoutBytes {
  var data=Data()
  private var offset=0
  init(_ data:Data=Data()){self.data=data}
  mutating func put<T:FixedWidthInteger>(_ value:T) {
    var bits=value.littleEndian
    withUnsafeBytes(of:&bits){data.append(contentsOf:$0)}
  }
  mutating func putFloat(_ value:Float){put(value.bitPattern)}
  mutating func putBytes(_ value:[UInt8]){data.append(contentsOf:value)}
  mutating func get<T:FixedWidthInteger>(_ type:T.Type) throws -> T {
    let n=MemoryLayout<T>.size
    guard offset<=data.count-n else {throw IOSComparisonCutoutExportUnavailable()}
    let raw=data.withUnsafeBytes{$0.loadUnaligned(fromByteOffset:offset,as:T.self)}
    offset+=n;return T(littleEndian:raw)
  }
  mutating func getFloat() throws -> Float {Float(bitPattern:try get(UInt32.self))}
  mutating func getBytes(_ n:Int) throws -> [UInt8] {
    guard n>=0,offset<=data.count-n else {throw IOSComparisonCutoutExportUnavailable()}
    defer{offset+=n}
    return Array(data[offset..<(offset+n)])
  }
  var remaining:Int{data.count-offset}
}

/// Process-scoped, unlinked scratch. Offsets are kept in memory, not pixels.
/// A killed process releases the descriptor and its anonymous storage.
final class IOSComparisonCutoutDisk: @unchecked Sendable {
  struct Record:Sendable {let offset:UInt64;let length:Int}
  private let lock=NSLock()
  private let handle:FileHandle
  private let budget:UInt64
  private var end:UInt64=0
  private var closed=false
  init(directory:URL=FileManager.default.temporaryDirectory,budget:UInt64) throws {
    self.budget=budget
    let url=directory.appendingPathComponent("woah-cutout-\(UUID().uuidString).scratch")
    guard FileManager.default.createFile(atPath:url.path,contents:nil) else {throw IOSComparisonCutoutExportUnavailable()}
    let opened:FileHandle
    do {opened=try FileHandle(forUpdating:url)}
    catch {try? FileManager.default.removeItem(at:url);throw error}
    do {try FileManager.default.removeItem(at:url)}
    catch {try? opened.close();try? FileManager.default.removeItem(at:url);throw error}
    handle=opened
  }
  deinit{close()}
  var bytes:UInt64{lock.lock();defer{lock.unlock()};return end}
  func append(_ payload:Data) throws -> Record {
    lock.lock();defer{lock.unlock()}
    guard !closed,!payload.isEmpty,payload.count<=8*1024*1024,
          end+UInt64(payload.count+36)<=budget else {throw IOSComparisonCutoutExportUnavailable()}
    let record=Record(offset:end,length:payload.count)
    var header=IOSCutoutBytes()
    header.put(UInt32(payload.count));header.putBytes(Array(SHA256.hash(data:payload)))
    try handle.seek(toOffset:end);try handle.write(contentsOf:header.data);try handle.write(contentsOf:payload)
    end+=UInt64(payload.count+36)
    return record
  }
  func read(_ record:Record) throws -> Data {
    lock.lock();defer{lock.unlock()}
    guard !closed,record.length>0,record.length<=8*1024*1024,
          record.offset<=end,UInt64(record.length+36)<=end-record.offset else {throw IOSComparisonCutoutExportUnavailable()}
    try handle.seek(toOffset:record.offset)
    guard let raw=try handle.read(upToCount:36),raw.count==36 else {throw IOSComparisonCutoutExportUnavailable()}
    var header=IOSCutoutBytes(raw)
    guard try header.get(UInt32.self)==UInt32(record.length) else {throw IOSComparisonCutoutExportUnavailable()}
    let digest=try header.getBytes(32)
    guard let payload=try handle.read(upToCount:record.length),payload.count==record.length,
          Array(SHA256.hash(data:payload))==digest else {throw IOSComparisonCutoutExportUnavailable()}
    return payload
  }
  func close(){
    lock.lock();defer{lock.unlock()}
    if !closed {closed=true;try? handle.close()}
  }
}

/// Immutable after sealing. Only two 32-sample chunks of selected alpha and
/// stamp data remain resident; exact PTS is checked on every record load.
final class IOSComparisonCutoutDiskTrack: @unchecked Sendable {
  static let chunk=32
  private let disk:IOSComparisonCutoutDisk
  private let lock=NSLock()
  let times:[Int64]
  private var records:[IOSComparisonCutoutDisk.Record?]
  private var visible:[Bool]
  private var count=0
  private var visibleValue=0
  var visibleCount:Int{lock.lock();defer{lock.unlock()};return visibleValue}
  var analyzedCount:Int{lock.lock();defer{lock.unlock()};return count}
  private var sealed=false
  private var chunks:[Int:[IOSComparisonCachedCutout?]]=[:]
  private var lru:[Int]=[]
  private var fixed:IOSComparisonCutoutPlacement?
  init(times:[Int64],directory:URL=FileManager.default.temporaryDirectory) throws {
    self.times=times
    records=Array(repeating:nil,count:times.count)
    visible=Array(repeating:false,count:times.count)
    disk=try IOSComparisonCutoutDisk(directory:directory,budget:256*1024*1024)
  }
  var bytes:UInt64{disk.bytes}
  private func index(_ pts:Int64)->Int? {
    guard let i=IOSComparisonTimeCore.frameIndex(times,time:pts),times[i]==pts else{return nil}
    return i
  }
  func add(_ pts:Int64,stamp:IOSComparisonSourceStamp,layer:IOSComparisonCutoutLayer?,
           placement:IOSComparisonCutoutPlacement) throws {
    lock.lock();defer{lock.unlock()}
    guard !sealed,let i=index(pts),records[i]==nil else {throw IOSComparisonCutoutExportUnavailable()}
    if fixed==nil{fixed=placement}
    guard stamp.samples.count==3072 else {throw IOSComparisonCutoutExportUnavailable()}
    var value=IOSCutoutBytes()
    value.put(pts);value.put(UInt32(stamp.width));value.put(UInt32(stamp.height));value.putBytes(stamp.samples)
    if let layer {
      guard layer.valid,layer.sourcePtsUs==pts,layer.width<=384,layer.height<=384 else {throw IOSComparisonCutoutExportUnavailable()}
      value.put(UInt32(layer.width));value.put(UInt32(layer.height));value.putBytes(layer.alpha)
    } else {value.put(UInt32(0));value.put(UInt32(0))}
    records[i]=try disk.append(value.data)
    visible[i]=layer != nil;count+=1
    if layer != nil{visibleValue+=1}
  }
  func seal(anchor:Int64) throws {
    lock.lock();defer{lock.unlock()}
    guard !sealed,count==times.count,let i=index(anchor),visible[i],fixed != nil else {throw IOSComparisonCutoutExportUnavailable()}
    sealed=true
  }
  func exact(_ pts:Int64)->IOSComparisonCachedCutout? {
    lock.lock();defer{lock.unlock()}
    guard sealed,let i=index(pts) else{return nil}
    let chunk=i/Self.chunk
    do {
      if chunks[chunk]==nil {
        chunks[chunk]=try (chunk*Self.chunk..<min((chunk+1)*Self.chunk,times.count)).map { j in
          guard let record=records[j] else {throw IOSComparisonCutoutExportUnavailable()}
          var data=IOSCutoutBytes(try disk.read(record))
          guard try data.get(Int64.self)==times[j] else {throw IOSComparisonCutoutExportUnavailable()}
          let sw=Int(try data.get(UInt32.self)),sh=Int(try data.get(UInt32.self))
          let stamp=IOSComparisonSourceStamp(width:sw,height:sh,samples:try data.getBytes(3072))
          let w=Int(try data.get(UInt32.self)),h=Int(try data.get(UInt32.self))
          let layer:IOSComparisonCutoutLayer?
          if w==0 && h==0 {layer=nil}
          else {
            guard (1...384).contains(w),(1...384).contains(h),let fixed else {throw IOSComparisonCutoutExportUnavailable()}
            layer=IOSComparisonCutoutLayer(sourcePtsUs:times[j],width:w,height:h,alpha:try data.getBytes(w*h),placement:fixed)
          }
          guard data.remaining==0 else {throw IOSComparisonCutoutExportUnavailable()}
          return IOSComparisonCachedCutout(stamp:stamp,layer:layer)
        }
      }
      lru.removeAll{$0==chunk};lru.append(chunk)
      if lru.count>2{chunks.removeValue(forKey:lru.removeFirst())}
      return chunks[chunk]?[i%Self.chunk]
    } catch {return nil}
  }
}

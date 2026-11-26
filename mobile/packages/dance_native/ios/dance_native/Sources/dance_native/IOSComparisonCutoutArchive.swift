import Foundation
import CryptoKit
import Darwin

/// Cache format version is independent of the project JSON schema. Bump on
/// incompatible preprocessing, tracking, alpha or decoder changes.
struct IOSComparisonCutoutArchiveKey:Sendable {
  let origin:Int64,offset:Int64
  let scale:Double
  let window:Range<Int64>
  let mirrorR:Bool,mirrorT:Bool
  let referenceDigest:Data,targetDigest:Data,modelDigest:Data,buildDigest:Data
  var encoded:String {
    ["ios-cutout-v1","xnnpack",ProcessInfo.processInfo.operatingSystemVersionString,
      buildDigest.base64EncodedString(),String(origin),String(offset),String(scale.bitPattern),
      String(window.lowerBound),String(window.upperBound),String(mirrorR),String(mirrorT),
      referenceDigest.base64EncodedString(),targetDigest.base64EncodedString(),modelDigest.base64EncodedString()].joined(separator:"|")
  }
}
struct IOSComparisonCutoutSelection:Sendable {
  let reference:IOSComparisonCutoutSeed,target:IOSComparisonCutoutSeed
}
struct IOSComparisonCutoutRestored:Sendable {
  let selection:IOSComparisonCutoutSelection
  let cache:IOSComparisonCutoutCache
}

/// App-private evictable cache. Only final, complete tracks are written.
/// Initial previews, raw detections and source videos never persist here.
/// iOS additionally stores the existing bounded sample stamp required by the
/// compositor. Verify the whole archive before constructing B1 scratch.
enum IOSComparisonCutoutArchive {
  private static let lock=NSLock()
  private static let maxBytes:UInt64=512*1024*1024
  private static let expiry:TimeInterval=30*24*60*60
  private static let magic:UInt32=0x57434131

  private static func root()throws->URL{
    let base=try FileManager.default.url(for:.cachesDirectory,in:.userDomainMask,appropriateFor:nil,create:true)
    var url=base.appendingPathComponent("comparison-cutout-v1",isDirectory:true)
    try FileManager.default.createDirectory(at:url,withIntermediateDirectories:true)
    var flags=URLResourceValues();flags.isExcludedFromBackup=true
    try url.setResourceValues(flags)
    return url
  }
  private static func validId(_ id:String)->Bool {
    IOSComparisonExportSpec.validIdentifier(id)
  }
  private static func file(_ id:String)throws->URL{
    guard validId(id) else{throw IOSComparisonCutoutExportUnavailable()}
    return try root().appendingPathComponent(id+".cutout")
  }
  static func exists(_ id:String)throws->Bool{
    FileManager.default.fileExists(atPath:try file(id).path)
  }
  private static func maintain(_ root:URL,protected:URL?=nil){
    let fm=FileManager.default
    let files=(try? fm.contentsOfDirectory(at:root,includingPropertiesForKeys:[.isRegularFileKey,.fileSizeKey,.contentModificationDateKey])) ?? []
    for f in files where f.lastPathComponent.hasPrefix("stage-") && f.pathExtension=="tmp"{try? fm.removeItem(at:f)}
    let now=Date()
    let rows=files.compactMap{url->(URL,UInt64,Date)? in
      guard url.pathExtension=="cutout",validId(url.deletingPathExtension().lastPathComponent),
        let values=try? url.resourceValues(forKeys:[.isRegularFileKey,.fileSizeKey,.contentModificationDateKey]),
        values.isRegularFile==true,let bytes=values.fileSize,bytes>=0 else{return nil}
      return (url,UInt64(bytes),values.contentModificationDate ?? .distantPast)
    }.sorted{$0.2<$1.2}
    var total=rows.reduce(UInt64(0)){$0+$1.1},count=rows.count
    for (url,size,date) in rows where url != protected {
      if now.timeIntervalSince(date)>expiry || total>maxBytes || count>4{
        if (try? fm.removeItem(at:url)) != nil {total-=size;count-=1}
      }
    }
  }
  private static func check(_ current:()->Bool)throws{if !current(){throw CancellationError()}}
  private static func read(_ handle:FileHandle,_ size:Int)throws->Data{
    guard size>=0,size<=256*1024 else{throw IOSComparisonCutoutExportUnavailable()}
    var result=Data();result.reserveCapacity(size)
    while result.count<size{
      guard let data=try handle.read(upToCount:size-result.count),!data.isEmpty else{throw IOSComparisonCutoutExportUnavailable()}
      result.append(data)
    }
    return result
  }
  private static func hash(_ file:URL,_ length:UInt64,current:()->Bool)throws->Data{
    let handle=try FileHandle(forReadingFrom:file);defer{try? handle.close()}
    var md=SHA256(),remaining=length
    while remaining>0{
      try check(current)
      let data=try read(handle,Int(min(remaining,256*1024)));md.update(data:data);remaining-=UInt64(data.count)
    }
    return Data(md.finalize())
  }
  private static func seedBytes(_ seed:IOSComparisonCutoutSeed)->Data{
    var b=IOSCutoutBytes();b.put(seed.ptsUs);b.putFloat(seed.x);b.putFloat(seed.y);return b.data
  }
  private static func readSeed(_ handle:FileHandle)throws->IOSComparisonCutoutSeed {
    var b=IOSCutoutBytes(try read(handle,16))
    let seed=IOSComparisonCutoutSeed(ptsUs:try b.get(Int64.self),x:try b.getFloat(),y:try b.getFloat())
    guard seed.ptsUs>=0,seed.x.isFinite,seed.y.isFinite,(0..<1).contains(seed.x),(0..<1).contains(seed.y) else{
      throw IOSComparisonCutoutExportUnavailable()
    }
    return seed
  }
  private static func writeTrack(_ handle:FileHandle,_ track:IOSComparisonCutoutTrackCache,
    _ seed:IOSComparisonCutoutSeed,current:()->Bool)throws{
    guard (1...3600).contains(track.times.count),let anchor=track.exact(seed.ptsUs)?.layer,anchor.valid,
          anchor.width<=256,anchor.height<=256 else{throw IOSComparisonCutoutExportUnavailable()}
    let p=anchor.placement
    var header=IOSCutoutBytes();header.put(UInt32(track.times.count));header.put(UInt32(anchor.width));header.put(UInt32(anchor.height))
    for i in 0..<4{header.putFloat(p.anchor[i])}
    try handle.write(contentsOf:header.data)
    for pts in track.times {
      try check(current)
      guard let entry=track.exact(pts),let layer=entry.layer,layer.valid,layer.sourcePtsUs==pts,
        layer.width==anchor.width,layer.height==anchor.height,layer.placement.anchor==p.anchor,
        entry.stamp.samples.count==3072,entry.stamp.width>0,entry.stamp.height>0 else{throw IOSComparisonCutoutExportUnavailable()}
      var record=IOSCutoutBytes()
      record.put(pts);record.put(UInt32(entry.stamp.width));record.put(UInt32(entry.stamp.height))
      record.putBytes(entry.stamp.samples);record.putBytes(layer.alpha)
      try handle.write(contentsOf:record.data)
    }
  }
  private static func readTrack(_ handle:FileHandle,times:[Int64],seed:IOSComparisonCutoutSeed,
    reference:Bool,mirror:Bool,digest:Data,current:()->Bool)throws->IOSComparisonCutoutTrackCache{
    var header=IOSCutoutBytes(try read(handle,28))
    let n=Int(try header.get(UInt32.self)),w=Int(try header.get(UInt32.self)),h=Int(try header.get(UInt32.self))
    let b=try (0..<4).map{_ in try header.getFloat()}
    guard n==times.count,(1...3600).contains(n),(1...256).contains(w),(1...256).contains(h),
          b.allSatisfy({$0.isFinite && (0...1).contains($0)}),b[2]>b[0],b[3]>b[1] else{
      throw IOSComparisonCutoutExportUnavailable()
    }
    let p=IOSComparisonCutoutPlacement(anchor:SIMD4(b[0],b[1],b[2],b[3]),x:reference ? 0.27:0.73,y:0.88,
      subjectHeight:0.70,mirror:mirror,opacity:1)
    guard IOSComparisonTimeCore.cutoutQuad(width:960,height:540,aspect:Double(w)/Double(h),anchor:p.anchor,
      x:p.x,y:p.y,subjectHeight:p.subjectHeight,mirror:mirror) != nil else{throw IOSComparisonCutoutExportUnavailable()}
    let disk=try IOSComparisonCutoutDiskTrack(times:times)
    for pts in times {
      try check(current)
      var record=IOSCutoutBytes(try read(handle,16+3072+w*h))
      guard try record.get(Int64.self)==pts else{throw IOSComparisonCutoutExportUnavailable()}
      let sw=Int(try record.get(UInt32.self)),sh=Int(try record.get(UInt32.self))
      guard (1...32768).contains(sw),(1...32768).contains(sh) else{throw IOSComparisonCutoutExportUnavailable()}
      let stamp=IOSComparisonSourceStamp(width:sw,height:sh,samples:try record.getBytes(3072))
      let alpha=try record.getBytes(w*h)
      guard alpha.contains(where:{$0 != 0}) else{throw IOSComparisonCutoutExportUnavailable()}
      let layer=IOSComparisonCutoutLayer(sourcePtsUs:pts,width:w,height:h,alpha:alpha,placement:p)
      try disk.add(pts,stamp:stamp,layer:layer,placement:p)
    }
    try disk.seal(anchor:seed.ptsUs)
    return IOSComparisonCutoutTrackCache(disk:disk,fingerprint:digest)
  }
  static func save(_ id:String,key:IOSComparisonCutoutArchiveKey,selection:IOSComparisonCutoutSelection,
    cache:IOSComparisonCutoutCache,current:()->Bool)throws->Bool{
    lock.lock();defer{lock.unlock()}
    let target=try file(id),dir=target.deletingLastPathComponent()
    maintain(dir,protected:target)
    guard cache.complete,cache.reference.fingerprint==key.referenceDigest,
      cache.target.fingerprint==key.targetDigest else{throw IOSComparisonCutoutExportUnavailable()}
    let stage=dir.appendingPathComponent("stage-\(UUID().uuidString).tmp")
    guard FileManager.default.createFile(atPath:stage.path,contents:nil,attributes:[.protectionKey:FileProtectionType.completeUntilFirstUserAuthentication]) else{
      throw IOSComparisonCutoutExportUnavailable()
    }
    defer{try? FileManager.default.removeItem(at:stage)}
    let handle=try FileHandle(forWritingTo:stage);defer{try? handle.close()}
    let keyData=Data(key.encoded.utf8)
    guard keyData.count<=16_384 else{throw IOSComparisonCutoutExportUnavailable()}
    var header=IOSCutoutBytes();header.put(magic);header.put(UInt32(keyData.count));header.data.append(keyData)
    header.data.append(seedBytes(selection.reference));header.data.append(seedBytes(selection.target))
    try handle.write(contentsOf:header.data)
    try writeTrack(handle,cache.reference,selection.reference,current:current)
    try writeTrack(handle,cache.target,selection.target,current:current)
    let size=try handle.offset();guard size>0,size<=maxBytes-32 else{throw IOSComparisonCutoutExportUnavailable()}
    let digest=try hash(stage,size,current:current)
    try handle.write(contentsOf:digest);try handle.synchronize();try handle.close()
    try check(current)
    guard Darwin.rename(stage.path,target.path)==0 else{throw IOSComparisonCutoutExportUnavailable()}
    try? FileManager.default.setAttributes([.modificationDate:Date()],ofItemAtPath:target.path)
    maintain(dir,protected:target)
    return true
  }
  static func restore(_ id:String,key:IOSComparisonCutoutArchiveKey,
    referenceTimes:(IOSComparisonCutoutSeed)throws->[Int64],targetTimes:(IOSComparisonCutoutSeed)throws->[Int64],
    current:()->Bool)throws->IOSComparisonCutoutRestored? {
    lock.lock();defer{lock.unlock()}
    let url=try file(id)
    maintain(url.deletingLastPathComponent())
    guard FileManager.default.fileExists(atPath:url.path) else{return nil}
    do {
      let handle=try FileHandle(forReadingFrom:url);defer{try? handle.close()}
      let size=try handle.seekToEnd()
      guard size>=33,size<=maxBytes else{throw IOSComparisonCutoutExportUnavailable()}
      try handle.seek(toOffset:size-32)
      let expected=try read(handle,32)
      guard try hash(url,size-32,current:current)==expected else{throw IOSComparisonCutoutExportUnavailable()}
      try handle.seek(toOffset:0)
      var header=IOSCutoutBytes(try read(handle,8))
      guard try header.get(UInt32.self)==magic else{throw IOSComparisonCutoutExportUnavailable()}
      let length=Int(try header.get(UInt32.self))
      guard (1...16384).contains(length),try read(handle,length)==Data(key.encoded.utf8) else{throw IOSComparisonCutoutExportUnavailable()}
      let selection=IOSComparisonCutoutSelection(reference:try readSeed(handle),target:try readSeed(handle))
      let r=try readTrack(handle,times:referenceTimes(selection.reference),seed:selection.reference,
        reference:true,mirror:key.mirrorR,digest:key.referenceDigest,current:current)
      let t=try readTrack(handle,times:targetTimes(selection.target),seed:selection.target,
        reference:false,mirror:key.mirrorT,digest:key.targetDigest,current:current)
      guard try handle.offset()==size-32,try read(handle,32)==expected else{throw IOSComparisonCutoutExportUnavailable()}
      try check(current)
      try? FileManager.default.setAttributes([.modificationDate:Date()],ofItemAtPath:url.path)
      return IOSComparisonCutoutRestored(selection:selection,cache:IOSComparisonCutoutCache(reference:r,target:t,
        referenceStartUs:key.origin,offsetUs:key.offset,timeScale:key.scale,window:key.window))
    } catch is CancellationError{throw CancellationError()}
    catch {try? FileManager.default.removeItem(at:url);return nil}
  }
  static func forget(_ id:String)throws{
    lock.lock();defer{lock.unlock()}
    let url=try file(id)
    if FileManager.default.fileExists(atPath:url.path){try FileManager.default.removeItem(at:url)}
  }
}

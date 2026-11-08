import Foundation
import CryptoKit
func check(_ condition:Bool,_ message:String) {if !condition {fatalError(message)}}
let root=FileManager.default.temporaryDirectory.appendingPathComponent("qa-observation-synthetic-"+UUID().uuidString)
try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
defer {try? FileManager.default.removeItem(at:root)}
let cache=IOSQaObservationReplay(directory:root,contract:String(repeating:"a",count:64))
let data=[Float(0),Float(1)].withUnsafeBytes {Data($0)}
let key=cache.key(kind:"synthetic",dimensions:"2,2|cpu",input:data)
check(key != cache.key(kind:"synthetic",dimensions:"2,2|gpu",input:data),"backend key collision")
let b=IOSQaObservationReplay(directory:root,contract:String(repeating:"b",count:64))
check(key != b.key(kind:"synthetic",dimensions:"2,2|cpu",input:data),"contract key collision")
let detection=IOSYoloDetection(x1:-0.0,y1:1.25,x2:17.5,y2:38,confidence:0.75,
 mask:Array(repeating:96,count:160*160))
let value=IOSQaYoloObservation([detection])
cache.record(key,value)
check(cache.read(key,as:IOSQaYoloObservation.self,validate:{$0.detections() != nil}) == nil,"pending entry replayed")
let payload=root.appendingPathComponent(key+".bin")
let bytes=try Data(contentsOf:payload)
let digest=SHA256.hash(data:bytes).map{String(format:"%02x",$0)}.joined()
let approval=root.appendingPathComponent(key+".approved")
try Data(digest.utf8).write(to:approval)
let restored=cache.read(key,as:IOSQaYoloObservation.self,validate:{$0.detections() != nil})!.detections()!.first!
check(restored.x1.bitPattern == detection.x1.bitPattern && restored.mask == detection.mask,"lossy observation codec")
try Data([1,2,3]).write(to:payload)
check(cache.read(key,as:IOSQaYoloObservation.self,validate:{$0.detections() != nil}) == nil,"corrupted entry replayed")
try bytes.write(to:payload)
cache.record(key,IOSQaYoloObservation([]))
try Data(digest.utf8).write(to:approval)
check(cache.read(key,as:IOSQaYoloObservation.self,validate:{$0.detections() != nil}) == nil,"conflicting entry replayed")
check(IOSQaObservationReplay.open() == nil,"ordinary host enabled QA replay")
print("QA_OBSERVATION_REPLAY_SYNTHETIC=PASS lossless_pending_corrupt_conflicting_and_incompatible_inputs")


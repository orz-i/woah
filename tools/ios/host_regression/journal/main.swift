import Foundation

func require(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() { fatalError(message) }
}
func status(_ id: String, _ state: String = "preparing", frame: Int64 = 0) -> JobStatusDto {
  JobStatusDto(jobId:id,state:state,currentFrame:frame,totalFrames:40,fps:12.0,progress:Double(frame)/40)
}
let home=URL(fileURLWithPath:NSHomeDirectory(),isDirectory:true)
let testRoot=home.appendingPathComponent("Library/Caches/woah-ios-journal-regression-\(UUID().uuidString)",isDirectory:true)
let jobs=testRoot.appendingPathComponent("jobs")
let outputs=testRoot.appendingPathComponent("qa_outputs")
try FileManager.default.createDirectory(at:outputs,withIntermediateDirectories:true)
defer {try? FileManager.default.removeItem(at:testRoot)}
let id="job_1793800000001_A1B2C3D4"
let final=outputs.appendingPathComponent("export_1.mp4")
let temp=outputs.appendingPathComponent(".export_1.00000000-1111-2222-3333-444444444444.partial.mp4")
let sidecar=outputs.appendingPathComponent(temp.lastPathComponent+".sb-1234-abcd")
let unrelated=outputs.appendingPathComponent(".other.11111111-1111-1111-1111-111111111111.partial.mp4")
let source=outputs.appendingPathComponent("input.mp4")
let journal=IOSExportJobJournal(root:jobs)
try journal.begin(status(id))
try journal.rememberPaths(jobId:id,final:final,temp:temp)
try journal.update(status(id,"exporting",frame:8))
try Data("partial".utf8).write(to:temp)
try Data("sidecar".utf8).write(to:sidecar)
try Data("other".utf8).write(to:unrelated)
try Data("source".utf8).write(to:source)
print("PRE_TERMINATION_EXISTS",FileManager.default.fileExists(atPath:temp.path),FileManager.default.fileExists(atPath:sidecar.path))
let recovered=IOSExportJobJournal(root:jobs)
let record=recovered.status(id)
require(record?.state == "interrupted","expected restored interrupted state")
require(record?.errorCode == "EXPORT_INTERRUPTED","expected stable error code")
require(record?.currentFrame == 8,"last persisted frame lost")
require(record?.outputUri == nil,"partial output URI leaked")
require(!FileManager.default.fileExists(atPath:temp.path),"exact partial was not cleaned")
require(!FileManager.default.fileExists(atPath:sidecar.path),"sidecar was not cleaned")
require(FileManager.default.fileExists(atPath:source.path),"source was deleted")
require(FileManager.default.fileExists(atPath:unrelated.path),"unrelated partial deleted")
print("INTERRUPTED_RECOVERY=PASS frame=\(record!.currentFrame) state=\(record!.state) code=\(record!.errorCode!)")

let id2="job_1793800000002_E5F6A7B8"
let final2=outputs.appendingPathComponent("export_2.mp4")
let temp2=outputs.appendingPathComponent(".export_2.99999999-1111-2222-3333-444444444444.partial.mp4")
try recovered.begin(status(id2))
try recovered.rememberPaths(jobId:id2,final:final2,temp:temp2)
try Data("completed-movie".utf8).write(to:final2)
var completed=status(id2,"completed",frame:40)
completed.outputUri=final2.absoluteString
try recovered.update(completed)
let reboot=IOSExportJobJournal(root:jobs)
require(reboot.status(id2)?.state == "completed","completed job not restored")
require(FileManager.default.fileExists(atPath:final2.path),"completed final movie deleted")
print("COMPLETED_OUTPUT_RETAINED=PASS")

let id3="job_1793800000003_C1D2E3F4"
try reboot.begin(status(id3))
var rejected=false
do {
  try reboot.rememberPaths(jobId:id3,final:final,temp:URL(fileURLWithPath:"/tmp/.export_3.aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.partial.mp4"))
} catch { rejected=true }
require(rejected,"path outside app home accepted")
require(FileManager.default.fileExists(atPath:source.path),"malicious path check touched source")
print("EXTERNAL_PATH_REJECTED=PASS")
print("IOS_EXPORT_JOURNAL_TEST=PASS")

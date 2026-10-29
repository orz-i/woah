package art.gaoge.dance.engine.comparison

import android.content.Context
import android.net.Uri
import art.gaoge.dance.engine.bridge.comparison.ComparisonExportDto
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Debug-only, isolated synthetic job: prove the same service used by the Flutter dialog. */
internal object ComparisonExportManagerProbe {
    fun run(context: Context,interruptBeforeRetry: Boolean=false) {
        val root=File(context.filesDir,"comparison-export-20261026")
        val spec=ComparisonExportSpec("instrumented_manager",File(root,"teacher.mp4").toURI().toString(),
            File(root,"learner.mp4").toURI().toString(),1_200_000,.8,0,23_000_000,1080,1800,8,
            false,true,false,ComparisonPicture(1.3,-.25,.4),ComparisonPicture(1.6,.4,-.3))
        val manager=ComparisonExportManager.get(context)
        fun waitUntil(ready: (ComparisonExportDto)->Boolean): ComparisonExportDto {
            val deadline=System.nanoTime()+120_000_000_000L
            while (System.nanoTime()<deadline) {
                val status=requireNotNull(manager.latest())
                if (ready(status)) return status
                check(status.state !in listOf("failed","interrupted")) { "Service result: ${status.state} ${status.error}" }
                Thread.sleep(25)
            }
            error("Service did not finish")
        }
        val first=manager.start(spec)
        waitUntil { it.progress>.22 || it.state=="completed" }
        val cancelled=if (interruptBeforeRetry) {
            manager.interrupted(first.jobId)
            requireNotNull(manager.latest())
        } else {
            manager.cancel(first.jobId)
            waitUntil { it.state in listOf("cancelled","completed") }
        }
        check(cancelled.state==(if (interruptBeforeRetry) "interrupted" else "cancelled") &&
            cancelled.outputPath==null && cancelled.savedUri==null)
        val retry=manager.retry(first.jobId)
        check(retry.jobId!=first.jobId && retry.startUs==first.startUs && retry.endUs==first.endUs)
        val done=waitUntil { it.state=="completed" }
        val file=File(requireNotNull(done.outputPath));val uri=Uri.parse(requireNotNull(done.savedUri))
        check(file.length()>0)
        check(manager.publish(done.jobId).savedUri==done.savedUri)
        fun hash(bytes: java.io.InputStream): String = bytes.use { stream ->
            val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
            while (true) { val size=stream.read(buffer);if (size<0) break;digest.update(buffer,0,size) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        check(hash(file.inputStream())==hash(context.contentResolver.openInputStream(uri)!!))
        val report=JSONObject().put("cancelState",cancelled.state).put("jobId",done.jobId)
            .put("interruptedBeforeRetry",interruptBeforeRetry)
            .put("state",done.state).put("savedUri",done.savedUri).put("bytes",file.length())
            .put("albumHashMatches",true).put("repeatedSaveSameUri",true).put("spec",spec.toJson())
        File(context.cacheDir,"comparison-export-probe/manager-report-private.json").writeText(report.toString())
        // Only remove the exact synthetic row just created by this test.
        check(context.contentResolver.delete(uri,null,null)==1)
    }
}

package art.gaoge.dance.engine.comparison

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only device checks, shared by AndroidJUnitRunner and the SDK instrumentation entry. */
internal object ComparisonExportDeviceProbe {
    fun run(context: Context,root: File,outputRoot: File) {
        outputRoot.mkdirs()
        val reference=File(root,"teacher.mp4");val target=File(root,"learner.mp4")
        check(reference.isFile && target.isFile)
        val base=ComparisonExportSpec("instrumented",reference.toURI().toString(),target.toURI().toString(),
            1_200_000,.8,1_234_567,6_765_431,384,640,4,true,true,false,
            ComparisonPicture(1.3,-.25,.4),ComparisonPicture(1.6,.4,-.3))
        val cases=mutableListOf(
            "ab" to base,
            "canvas-ab" to base.copy(width=1080,height=1402,gap=8),
            "full" to base.copy(startUs=0,endUs=23_000_000,sideBySide=false,mirrorTeacher=false,mirrorLearner=true,
                teacherPicture=ComparisonPicture(),learnerPicture=ComparisonPicture()),
            "inverse" to base.copy(teacherUri=base.learnerUri,learnerUri=base.teacherUri,
                offsetUs=-1_500_000,timeScale=1.25,sideBySide=false,mirrorTeacher=false,mirrorLearner=true),
            "silent" to base.copy(teacherUri=File(root,"silent-reference.mp4").toURI().toString(),startUs=1_111_111,endUs=3_456_789),
        )
        if (File(root,"real-reference.mp4").isFile && File(root,"real-target.mp4").isFile) cases.add("real" to base.copy(
            teacherUri=File(root,"real-reference.mp4").toURI().toString(),
            learnerUri=File(root,"real-target.mp4").toURI().toString(),sideBySide=false))
        for ((label,spec) in cases) {
            val output=File(outputRoot,"$label.mp4");val packets=File(outputRoot,"$label.aacbin")
            var last=0.0
            ComparisonExportPipeline(context).execute(spec,output,packets,AtomicBoolean(false)) { _,p ->
                check(p>=last);last=p
            }
            check(output.length()>0);check(!packets.exists())
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                check(extractor.trackCount==2)
                val video=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="video/avc" }
                check(spec.width==extractor.getTrackFormat(video).getInteger(MediaFormat.KEY_WIDTH))
                extractor.selectTrack(video)
                var previous=-1L;var count=0
                while (extractor.sampleTime>=0) {
                    val pts=extractor.sampleTime
                    if (count==0) check(pts==0L)
                    check(pts>previous && pts<spec.endUs-spec.startUs)
                    previous=pts;count++;extractor.advance()
                }
                check(count>50)
            } finally { extractor.release() }
            File(outputRoot,"$label-record-private.json").writeText(JSONObject().put("spec",spec.toJson()).toString())
        }
        val cancelled=AtomicBoolean(false)
        val partial=File(outputRoot,"cancelled.partial.mp4");val packets=File(outputRoot,"cancelled.aacbin")
        try {
            ComparisonExportPipeline(context).execute(base,partial,packets,cancelled) { _,p -> if (p>.25) cancelled.set(true) }
            error("Cancellation did not stop export")
        } catch (_: ComparisonExportCancelled) { /* expected */ }
        check(!partial.exists());check(!packets.exists())
    }
}

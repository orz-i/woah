package art.gaoge.dance.engine.comparison

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.media.MediaExtractor
import android.media.MediaFormat
import art.gaoge.dance.engine.bridge.comparison.*
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.PluginRegistry
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ComparisonNativeApi(private val context: Context,private val registry: TextureRegistry)
    : DanceComparisonApi, PluginRegistry.ActivityResultListener {
    private val main=Handler(Looper.getMainLooper())
    private var binding: ActivityPluginBinding?=null
    private var picker: kotlinx.coroutines.CancellableContinuation<ComparisonSourceDto?>?=null
    private var session: ComparisonSession?=null
    private val store=ComparisonProjectStore(File(context.noBackupFilesDir,"comparison-projects"))
    private val alignmentGeneration=AtomicLong(0)
    private val requestCode=7301
    fun attach(value: ActivityPluginBinding) { binding=value;value.addActivityResultListener(this) }
    fun detach() {
        binding?.removeActivityResultListener(this);binding=null
        picker?.let { if (it.isActive) it.resume(null) };picker=null
        session?.background()
    }
    fun background() { session?.background() }
    fun foreground() { session?.foreground() }
    override suspend fun pickVideo(role: String): ComparisonSourceDto? {
        require(role in listOf("teacher","learner"))
        val activity=binding?.activity ?: error("请回到前台后选择视频")
        check(picker==null) { "系统选择器已经打开" }
        session?.background()
        return suspendCancellableCoroutine { continuation ->
            picker=continuation
            continuation.invokeOnCancellation { main.post { if (picker===continuation) picker=null } }
            val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type="video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            try { activity.startActivityForResult(intent,requestCode) }
            catch (error: Exception) { picker=null;continuation.resumeWithException(error) }
        }
    }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?): Boolean {
        if (requestCode!=this.requestCode) return false
        val result=picker;picker=null
        if (result==null || !result.isActive) return true
        val uri=data?.data
        if (resultCode!=Activity.RESULT_OK || uri==null) { result.resume(null);return true }
        runCatching { context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION) }.onFailure {
            result.resumeWithException(IllegalStateException("此来源不能长期读取，请通过系统文件选择器选择本地视频"));return true
        }
        // Probe is isolated from the platform thread and never decodes video pictures.
        Thread({
            val info=runCatching { probe(uri) }
            main.post { if (result.isActive) result.resumeWith(info) }
        },"WoahComparisonSource").start()
        return true
    }
    private fun probe(uri: Uri): ComparisonSourceDto {
        var name="所选视频"
        runCatching { context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if (it.moveToFirst()) name=it.getString(0) ?: name
        } }
        val extractor=MediaExtractor()
        try {
            if (uri.scheme=="content") extractor.setDataSource(context,uri,null)
            else extractor.setDataSource(uri.path ?: error("源文件不可用"))
            var duration=0L;var audio=false
            for (i in 0 until extractor.trackCount) {
                val f=extractor.getTrackFormat(i);val mime=f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && duration==0L) duration=f.getLong(MediaFormat.KEY_DURATION)
                if (mime.startsWith("audio/")) audio=true
            }
            require(duration in 1..86_400_000_000L) { "请选择含有效视频轨的视频" }
            return ComparisonSourceDto(uri.toString(),name,duration,audio)
        } finally { extractor.release() }
    }
    override suspend fun loadProjects(): List<String> = withContext(Dispatchers.IO) {
        val saved=store.load()
        if (saved.isNotEmpty() || !art.gaoge.dance.engine.BuildConfig.DEBUG) saved
        else {
            // Development-only migration reuses the two already granted picker URIs.
            // No album scan, media copy or URI disclosure is involved.
            val previous=File(context.noBackupFilesDir,"comparison-m0-selection.json")
            runCatching {
                if (previous.isFile) {
                    val value=JSONObject(previous.readText())
                    val teacher=probe(Uri.parse(value.getString("teacher_uri")))
                    val learner=probe(Uri.parse(value.getString("learner_uri")))
                    val anchor=value.optJSONObject("manual_music_anchor")
                    val offset=value.optLong("manual_offset_us",0L)
                    fun source(s: ComparisonSourceDto)=JSONObject().apply {
                        put("uri",s.uri);put("name",s.name);put("durationUs",s.durationUs);put("hasAudio",s.hasAudio)
                    }
                    store.save(JSONObject().apply {
                        put("schemaVersion",1);put("id","comparison_"+java.util.UUID.randomUUID())
                        put("title","我的舞蹈对比");put("teacher",source(teacher));put("learner",source(learner))
                        put("offsetUs",offset);put("alignment",if (anchor!=null) "manual" else "unmatched")
                        put("teacherAnchorUs",anchor?.optLong("teacherUs") ?: JSONObject.NULL)
                        put("learnerAnchorUs",anchor?.optLong("learnerUs") ?: JSONObject.NULL)
                        put("positionUs",0);put("speed",1.0);put("updatedAtMs",System.currentTimeMillis())
                    }.toString())
                }
            }
            store.load()
        }
    }
    override suspend fun saveProject(projectJson: String) = withContext(Dispatchers.IO) { store.save(projectJson) }
    override suspend fun open(teacherUri: String,learnerUri: String,offsetUs: Long,
        positionUs: Long,speed: Double,width: Long,height: Long,sideBySide: Boolean): ComparisonPlaybackDto {
        require(width in 2..4096 && height in 2..4096)
        session?.close()
        return ComparisonSession(context,registry,teacherUri,learnerUri,offsetUs,
            positionUs,speed,width.toInt(),height.toInt(),sideBySide).also { session=it }.state()
    }
    override suspend fun control(command: ComparisonCommand,valueUs: Long,speed: Double): ComparisonPlaybackDto =
        requireNotNull(session) { "请先选择两段视频" }.control(command,valueUs,speed)
    override suspend fun resize(width: Long,height: Long,sideBySide: Boolean): ComparisonPlaybackDto {
        require(width in 2..4096 && height in 2..4096)
        return requireNotNull(session).resize(width.toInt(),height.toInt(),sideBySide)
    }
    override suspend fun getState(): ComparisonPlaybackDto = session?.state() ?:
        ComparisonPlaybackDto(-1,"empty",0,0,0,0,1.0,null)
    override suspend fun alignMusic(teacherUri: String,learnerUri: String): MusicAlignmentDto {
        session?.control(ComparisonCommand.PAUSE,0,1.0)
        val generation=alignmentGeneration.incrementAndGet()
        val started=android.os.SystemClock.elapsedRealtime()
        return withContext(Dispatchers.Default) {
            val current={ alignmentGeneration.get()==generation && !Thread.currentThread().isInterrupted }
            try {
                val teacher=ComparisonPcmDecoder(context).features(teacherUri,current)
                debug("music teacher_frames="+teacher.frames.size+" origin_us="+teacher.startUs)
                val learner=ComparisonPcmDecoder(context).features(learnerUri,current)
                debug("music learner_frames="+learner.frames.size+" origin_us="+learner.startUs)
                check(current()) { "匹配已取消" }
                val result=ComparisonMusicMatcher.match(teacher,learner,current)
                debug("music status="+result.status+" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-started)+
                    " candidates="+result.candidates.joinToString { "offset_us="+it.offsetUs+" score="+it.score+
                        " windows="+it.consistentWindows+" span_s="+it.supportedSeconds+" residual_ms="+it.residualMs })
                MusicAlignmentDto(result.status,result.message,result.candidates.map {
                    MusicCandidateDto(it.offsetUs,it.score,it.consistentWindows,it.supportedSeconds,it.residualMs)
                })
            } catch (error: ComparisonAlignmentCancelled) { throw error }
            catch (error: ComparisonNoAudio) {
                MusicAlignmentDto("no_audio","视频没有可供匹配的音轨，可以手动校准时间。",emptyList())
            } catch (error: Exception) {
                // Never log selected URIs or source names. Stack locations suffice for diagnosis.
                debug("music failed="+error.javaClass.simpleName+" locations="+error.stackTrace.take(8).joinToString())
                MusicAlignmentDto("error","本次音乐匹配未完成，可以重试或手动校准。",emptyList())
            }
        }
    }
    private fun debug(message: String) {
        if (art.gaoge.dance.engine.BuildConfig.DEBUG) android.util.Log.i("WoahComparison",message)
    }
    override suspend fun cancelAlignment() { alignmentGeneration.incrementAndGet() }
    override suspend fun close() { alignmentGeneration.incrementAndGet();session?.close();session=null }
    fun dispose() { alignmentGeneration.incrementAndGet();detach();session?.close();session=null }
}

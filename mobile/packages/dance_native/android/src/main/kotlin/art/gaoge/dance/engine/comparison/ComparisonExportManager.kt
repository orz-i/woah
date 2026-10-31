package art.gaoge.dance.engine.comparison

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import art.gaoge.dance.engine.bridge.comparison.ComparisonExportDto
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class ComparisonExportRecord(val id: String,val spec: ComparisonExportSpec,
    val state: String="queued",val stage: String="准备导出",val progress: Double=0.0,
    val savedUri: String?=null,val error: String?=null,val pendingUri: String?=null) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
    val active get() = state in setOf("queued","encoding","publishing","cancelling")
    fun recovered() = if (active) copy(state="interrupted",stage="导出已中断",error="上次导出已中断，可以重试") else this
    fun json() = JSONObject().apply {
        put("id",id);put("spec",spec.toJson());put("state",state);put("stage",stage);put("progress",progress)
        put("savedUri",savedUri ?: JSONObject.NULL);put("error",error ?: JSONObject.NULL)
        put("pendingUri",pendingUri ?: JSONObject.NULL)
    }
    companion object {
        fun parse(p: JSONObject) = ComparisonExportRecord(p.getString("id"),ComparisonExportSpec.fromJson(p.getJSONObject("spec")),
            p.getString("state"),p.getString("stage"),p.getDouble("progress"),
            if (p.isNull("savedUri")) null else p.getString("savedUri"),if (p.isNull("error")) null else p.getString("error"),
            if (p.isNull("pendingUri")) null else p.getString("pendingUri"))
    }
}

/** One comparison export, independent of privacy/segmentation jobs and project files. */
internal class ComparisonExportManager private constructor(private val context: Context) {
    private val directory=File(context.filesDir,"comparison-exports").apply { mkdirs() }
    private val temporary=File(context.cacheDir,"comparison-exports").apply { mkdirs() }
    private val metadata=AtomicFile(File(File(context.noBackupFilesDir,"comparison-exports").apply { mkdirs() },"latest.json"))
    private var current: ComparisonExportRecord?=runCatching {
        ComparisonExportRecord.parse(JSONObject(metadata.readFully().toString(Charsets.UTF_8)))
    }.getOrNull()
    private var cancelled=AtomicBoolean(false)
    private var lastPersist=0L
    init {
        current?.takeIf { it.active }?.let {
            val published=it.pendingUri?.takeIf { uri -> pendingState(it,Uri.parse(uri))==0 && output(it.id).isFile }
            if (published!=null) current=it.copy(state="completed",stage="已保存到相册",progress=1.0,
                savedUri=published,pendingUri=null,error=null)
            else {
                cleanupPending(it);current=it.recovered().copy(pendingUri=null);output(it.id).delete()
            }
            partial(it.id).delete();packets(it.id).delete();persist()
        }
        // Never guess ownership if the last export metadata was unreadable.
        if (current!=null) prunePrivateOutputs(current!!.id)
    }
    /** Only this app's exact export row may be recovered or removed. */
    private fun pendingState(job: ComparisonExportRecord,uri: Uri): Int? {
        if (Build.VERSION.SDK_INT<29 || !uri.toString().startsWith(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).toString()+"/") ||
            uri.lastPathSegment?.toLongOrNull()==null) return null
        return runCatching {
            context.contentResolver.query(uri,arrayOf(MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.RELATIVE_PATH,MediaStore.Video.Media.OWNER_PACKAGE_NAME,
                MediaStore.Video.Media.IS_PENDING),null,null,null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0)!="Woah_Comparison_${job.id.take(8)}.mp4" ||
                    cursor.getString(1).trimEnd('/')!="Movies/Woah" || cursor.getString(2)!=context.packageName) null
                else cursor.getInt(3)
            }
        }.getOrNull()
    }
    private fun cleanupPending(job: ComparisonExportRecord) {
        job.pendingUri?.let { value ->
            val uri=Uri.parse(value)
            if (pendingState(job,uri)==1) runCatching { context.contentResolver.delete(uri,null,null) }
        }
    }
    private fun output(id: String)=File(directory,"$id.mp4")
    private fun partial(id: String)=File(directory,"$id.partial.mp4")
    private fun packets(id: String)=File(temporary,"$id.aacbin")
    private fun prunePrivateOutputs(keepId: String, replacedId: String?=null) {
        // Older versions retained unreachable completed files. Sweep only
        // managed, regular MP4s aged at least seven days, plus the one output
        // explicitly replaced by starting this new job. Never touch MediaStore.
        val cutoff=System.currentTimeMillis()-7L*24*60*60*1000
        directory.listFiles()?.forEach { file ->
            val ownedFile=runCatching {
                file.isFile && file.canonicalFile==File(directory.canonicalFile,file.name)
            }.getOrDefault(false)
            if (ownedFile && file.name.matches(Regex("[A-Za-z0-9_-]{1,80}\\.mp4")) &&
                file.name!="$keepId.mp4" &&
                ((replacedId!=null && file.name=="$replacedId.mp4") ||
                    (file.lastModified()>0 && file.lastModified()<=cutoff))) {
                runCatching { file.delete() }
            }
        }
    }
    @Synchronized private fun persist() {
        val value=current ?: return
        val stream=metadata.startWrite()
        try { stream.write(value.json().toString().toByteArray(Charsets.UTF_8));metadata.finishWrite(stream) }
        catch (e: Throwable) { metadata.failWrite(stream);throw e }
        lastPersist=System.currentTimeMillis()
    }
    @Synchronized fun latest(): ComparisonExportDto? = current?.let { dto(it) }
    @Synchronized private fun status(id: String): ComparisonExportDto? = current?.takeIf { it.id==id }?.let { dto(it) }
    private fun dto(value: ComparisonExportRecord) = ComparisonExportDto(value.id,value.spec.projectId,value.state,value.stage,
        value.progress,value.spec.startUs,value.spec.endUs,value.spec.width.toLong(),value.spec.height.toLong(),
        if (value.state=="completed" && output(value.id).isFile) output(value.id).absolutePath else null,value.savedUri,art.gaoge.dance.engine.diagnostics.NativeDiagnosticPrivacy.comparisonMessage(value.error))
    @Synchronized private fun requireJob(id: String) = requireNotNull(current?.takeIf { it.id==id }) { "导出任务已变化，请重新打开导出" }
    @Synchronized fun start(spec: ComparisonExportSpec, replacePreviousUnsaved: Boolean=false): ComparisonExportDto {
        check(current?.active!=true) { "已有对比视频正在导出" }
        val previous=current
        if (previous?.state=="completed" && previous.savedUri==null &&
            output(previous.id).isFile && !replacePreviousUnsaved) {
            error("上次导出尚未保存，请先保存或确认替换旧成片")
        }
        val job=ComparisonExportRecord(UUID.randomUUID().toString(),spec)
        val oldCancellation=cancelled
        current=job;cancelled=AtomicBoolean(false)
        try { persist() }
        catch (e: Throwable) { current=previous;cancelled=oldCancellation;throw e }
        try { ComparisonExportService.start(context,job.id) }
        catch (e: Throwable) {
            // If Android refused the foreground service, retain the old job
            // and its sole local movie so a failed launch never discards data.
            if (previous!=null) { current=previous;cancelled=oldCancellation;persist() }
            else { current=job.copy(state="failed",stage="导出未启动",error="无法启动导出，请回到应用后重试");persist() }
            throw e
        }
        // The new job owns the latest record. Only now can the user-confirmed
        // predecessor be retired; orphan cleanup is best effort.
        runCatching {
            previous?.let { cleanupPending(it) }
            prunePrivateOutputs(job.id,previous?.id)
        }
        return dto(current!!)
    }
    @Synchronized fun retry(id: String): ComparisonExportDto {
        val job=requireJob(id)
        check(job.state in setOf("failed","cancelled","interrupted"))
        return start(job.spec)
    }
    @Synchronized fun cancel(id: String): ComparisonExportDto {
        val job=requireJob(id)
        if (job.active) { cancelled.set(true);current=job.copy(state="cancelling",stage="正在取消");persist() }
        return dto(current!!)
    }
    @Synchronized fun interrupted(id: String?=null) {
        current?.takeIf { it.active && (id==null || it.id==id) }?.let { cancelled.set(true);current=it.recovered();persist() }
    }
    @Synchronized private fun progress(id: String,stage: String,value: Double): ComparisonExportDto? {
        val job=current?.takeIf { it.id==id && it.active } ?: return null
        if (cancelled.get()) return null
        current=job.copy(state="encoding",stage=stage,progress=maxOf(job.progress,value.coerceIn(0.0,.98)))
        if (System.currentTimeMillis()-lastPersist>=500) persist()
        return dto(current!!)
    }
    fun execute(id: String,notify: (ComparisonExportDto)->Unit) {
        // Keep this worker's cancellation token even if an interrupted job is retried.
        val (job,cancellation)=synchronized(this) {
            val value=current?.takeIf { it.id==id && it.active } ?: return
            value to cancelled
        }
        try {
            ComparisonExportPipeline(context).execute(job.spec,partial(id),packets(id),cancellation) { stage,p ->
                progress(id,stage,p)?.let(notify)
            }
            synchronized(this) {
                if (cancellation.get() || current?.id!=id) throw ComparisonExportCancelled()
                check(partial(id).renameTo(output(id))) { "无法保存导出文件" }
                current=requireJob(id).copy(state=if (Build.VERSION.SDK_INT>=29) "publishing" else "completed",
                    stage=if (Build.VERSION.SDK_INT>=29) "保存到相册" else "导出完成",progress=.99);persist()
            }
            if (Build.VERSION.SDK_INT>=29) publish(id)
        } catch (error: Throwable) {
            partial(id).delete();packets(id).delete()
            synchronized(this) {
                val value=current?.takeIf { it.id==id }
                if (value?.state!="completed") output(id).delete()
                if (value!=null && value.state!="completed") {
                    cleanupPending(value)
                    val stopped=cancellation.get() || error is ComparisonExportCancelled || error is ComparisonGlCancelled
                    current=if (value.state=="interrupted") value else value.copy(
                        state=if (stopped) "cancelled" else "failed",stage=if (stopped) "已取消导出" else "导出失败",
                        pendingUri=null,error=if (stopped) null else if (error.message=="存储空间不足") "存储空间不足，请释放空间后重试"
                            else "导出未完成，请重试或重新关联源视频")
                    persist()
                }
            }
            if (art.gaoge.dance.engine.BuildConfig.DEBUG) android.util.Log.i("WoahComparisonExport",
                "result=${status(id)?.state} cause=${error.javaClass.simpleName} locations=${error.stackTrace.take(6).joinToString()}")
        } finally { packets(id).delete();status(id)?.let(notify) }
    }
    /** MediaStore ownership avoids storage permission prompts on Android 10+. */
    fun publish(id: String): ComparisonExportDto {
        val (job,cancellation)=synchronized(this) {
            val value=requireJob(id)
            check(value.state in setOf("completed","publishing"))
            check(output(id).isFile)
            value.savedUri?.let { uri ->
                if (runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(uri),"r")?.use { true }==true }.getOrDefault(false)) return dto(value)
            }
            check(Build.VERSION.SDK_INT>=29)
            current=value.copy(state="publishing",stage="保存到相册",progress=.99);persist();value to cancelled
        }
        var uri: Uri?=null
        try {
            val values=ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME,"Woah_Comparison_${id.take(8)}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Woah");put(MediaStore.Video.Media.IS_PENDING,1)
            }
            val resolver=context.contentResolver
            uri=resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values)
                ?: error("无法创建相册条目")
            synchronized(this) {
                if (cancellation.get()) throw ComparisonExportCancelled()
                current=requireJob(id).copy(pendingUri=uri.toString());persist()
            }
            resolver.openOutputStream(uri)!!.use { destination ->
                output(id).inputStream().use { source ->
                    val bytes=ByteArray(128*1024)
                    while (true) {
                        if (cancellation.get()) throw ComparisonExportCancelled()
                        val n=source.read(bytes);if (n<0) break;destination.write(bytes,0,n)
                    }
                }
            }
            synchronized(this) {
                if (cancellation.get() || current?.id!=id) throw ComparisonExportCancelled()
                check(resolver.update(uri,ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING,0) },null,null)>0)
                current=requireJob(id).copy(state="completed",stage="已保存到相册",progress=1.0,savedUri=uri.toString(),pendingUri=null,error=null);persist()
            }
        } catch (e: Throwable) {
            uri?.let { runCatching { context.contentResolver.delete(it,null,null) } }
            synchronized(this) {
                if (cancellation.get() || current?.id!=id) throw ComparisonExportCancelled()
                current=job.copy(state="completed",stage="导出完成",progress=1.0,pendingUri=null,error="视频已导出，保存相册失败，可以重试保存");persist()
            }
        }
        return status(id) ?: throw ComparisonExportCancelled()
    }
    fun copyTo(id: String,uri: Uri): ComparisonExportDto {
        val file=synchronized(this) { check(requireJob(id).state=="completed");output(id) }
        context.contentResolver.openOutputStream(uri,"w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        synchronized(this) { current=requireJob(id).copy(savedUri=uri.toString(),error=null,stage="视频已保存");persist() }
        return latest()!!
    }
    fun open(id: String) {
        val uri=synchronized(this) { requireNotNull(requireJob(id).savedUri) }
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri),"video/mp4");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
    companion object {
        @Volatile private var instance: ComparisonExportManager?=null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: ComparisonExportManager(context.applicationContext).also { instance=it }
        }
    }
}

package art.gaoge.dance.engine.comparison

import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.AtomicFile
import android.view.Choreographer
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import art.gaoge.dance.engine.BuildConfig
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Isolated M0 alternative: ONE audio player; two codecs; one GL window surface. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ComparisonGlM0Activity : Activity() {
    companion object {
        private const val PICK_TEACHER=401
        private const val PICK_LEARNER=402
        private const val MAX_OFFSET_US=300_000_000L
    }
    private var teacherUri: Uri?=null
    private var learnerUri: Uri?=null
    private var audio: ExoPlayer?=null
    private var engine: ComparisonGlEngine?=null
    private var teacherInfo: ComparisonVideoDescription?=null
    private var learnerInfo: ComparisonVideoDescription?=null
    private lateinit var videoSurface: SurfaceView
    private lateinit var stage: FrameLayout
    private lateinit var middleLabel: TextView
    private lateinit var title: TextView
    private lateinit var stateText: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var toggleButton: Button
    private lateinit var speedButton: Button
    private lateinit var choreographer: Choreographer
    private val handler=Handler(Looper.getMainLooper())
    private var session=0L
    private var surfaceReady=false
    private var videoReady=false
    private var renderStalled=false
    private var foreground=false
    private var offsetUs=0L
    private var playbackSpeed=1f
    private var wantToPlay=false
    private var dragActive=false
    private var sourceError: String?=null
    private var overlap: ComparisonOverlap?=null
    private var pendingCompareUs=0L
    private var lastTickTeacherUs=0L
    private var seekEvents=0
    private var bufferingEvents=0
    private var rendererStalls=0
    private var clockBacksteps=0
    private var maxClockBackstepUs=0L
    private val audioClock=ComparisonAudioClock()
    private var closeFailures=0
    @Volatile private var selectionRevision=0L
    private var sourceExportWorker: Thread?=null
    @Volatile private var sourceExportStatus="NOT_REQUESTED"
    private val testReceiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            when (intent.getStringExtra("action")) {
                "stall" -> engine?.injectRenderStall(intent.getLongExtra("duration_ms",150))
                "export_selected_sources" -> exportSelectedSources(intent.getStringExtra("request_id"))
            }
        }
    }

    private val refresh=object: Runnable {
        override fun run() { updateStatus(); handler.postDelayed(this,250L) }
    }
    private val frames=object: Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!foreground) return
            val range=overlap
            val player=audio
            if (range!=null && player!=null && videoReady && !renderStalled && player.isPlaying) {
                val sampleNs=System.nanoTime()
                var target=audioClock.sample(player.currentPosition*1000L,sampleNs,playbackSpeed)
                    .coerceIn(range.teacherStartUs,range.teacherEndUs-1)
                if (target<lastTickTeacherUs) {
                    clockBacksteps++
                    maxClockBackstepUs=maxOf(maxClockBackstepUs,lastTickTeacherUs-target)
                    target=lastTickTeacherUs
                }
                lastTickTeacherUs=target
                engine?.render(target,offsetUs,sampleNs)
            } else audioClock.reset()
            choreographer.postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        offsetUs=(savedInstanceState?.getLong("offset_us")
            ?: intent.getLongExtra("offset_us",0)).coerceIn(-MAX_OFFSET_US,MAX_OFFSET_US)
        // Persist only explicit system-picker choices. Fixture intents override
        // the pair as a whole and never replace the remembered phone selection.
        val sources = when {
            savedInstanceState != null -> JSONObject().apply {
                put("teacher_uri",savedInstanceState.getString("teacher_uri"))
                put("learner_uri",savedInstanceState.getString("learner_uri"))
            }
            intent.hasExtra("teacher_uri") || intent.hasExtra("learner_uri") -> JSONObject().apply {
                put("teacher_uri",intent.getStringExtra("teacher_uri"))
                put("learner_uri",intent.getStringExtra("learner_uri"))
            }
            else -> rememberedSources()
        }
        teacherUri=sources.optString("teacher_uri").takeIf { it.isNotBlank() }?.let(Uri::parse)
        learnerUri=sources.optString("learner_uri").takeIf { it.isNotBlank() }?.let(Uri::parse)
        playbackSpeed=savedInstanceState?.getFloat("speed") ?: 1f
        pendingCompareUs=savedInstanceState?.getLong("compare_us") ?: 0
        choreographer=Choreographer.getInstance()
        buildView()
        speedButton.text="速度 ${playbackSpeed}×"
        handler.post(refresh)
        val filter=IntentFilter("art.gaoge.dance.M0_GL_TEST")
        // Selected-media copying is callable by ADB/system debugging only.
        // Ordinary apps must not be able to trigger copying of a picked URI.
        if (Build.VERSION.SDK_INT>=33)
            registerReceiver(testReceiver,filter,android.Manifest.permission.DUMP,null,Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(testReceiver,filter,android.Manifest.permission.DUMP,null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("offset_us",offsetUs)
        outState.putString("teacher_uri",teacherUri?.toString())
        outState.putString("learner_uri",learnerUri?.toString())
        outState.putLong("compare_us",currentCompareUs())
        outState.putFloat("speed",playbackSpeed)
        super.onSaveInstanceState(outState)
    }

    private fun text(message: String, sizeSp: Float = 14f): TextView =
        TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = sizeSp
            setPadding(14, 8, 14, 8)
        }

    private fun action(label: String, click: () -> Unit): Button =
        Button(this).apply {
            this.text = label
            textSize = 12f
            setOnClickListener { click() }
        }

    private fun actions(vararg buttons: Button): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@ComparisonGlM0Activity).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEach { addView(it) }
            })
        }

    private fun buildView() {
        val parent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(21, 21, 23))
        }
        title = text("M0 GL｜老师 / 自己 · 共同音频时钟", 17f)
        parent.addView(title)
        parent.addView(text("老师 · 音频主时钟"))
        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoSurface = SurfaceView(this)
        stage.addView(videoSurface, FrameLayout.LayoutParams(-1,-1))
        middleLabel = text("自己 · 静音，按音乐偏移映射").apply {
            setBackgroundColor(Color.rgb(21,21,23))
        }
        stage.addView(middleLabel,FrameLayout.LayoutParams(-1,-2))
        parent.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        stage.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> resizeStage() }
        videoSurface.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady=true
                if (teacherUri!=null && learnerUri!=null) openPair()
            }
            override fun surfaceChanged(holder: SurfaceHolder,format: Int,w: Int,h: Int) {
                resizeStage()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pendingCompareUs=currentCompareUs()
                surfaceReady=false
                closePlayers()
            }
        })

        stateText = text("选择两段视频。M0 不进行自动音乐识别。").apply {
            minLines=2
            maxLines=2
        }
        stateText.gravity = Gravity.CENTER
        parent.addView(stateText)
        progressBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { dragActive = true }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    dragActive = false
                    val bounds = overlap ?: return
                    seekCompareUs(bounds.durationUs * seekBar.progress / 1000L)
                }
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit
            })
        }
        parent.addView(progressBar)
        parent.addView(actions(
            action("选择老师") { pickVideo(PICK_TEACHER) },
            action("选择自己") { pickVideo(PICK_LEARNER) },
            action("从头") { seekCompareUs(0L) },
            action("保存诊断") { saveReport() }
        ))
        toggleButton = action("播放") { togglePlayback() }
        speedButton = action("速度 1×") { cycleSpeed() }
        parent.addView(actions(
            toggleButton, speedButton,
            action("退 10 秒") { jump(-10_000_000L) },
            action("进 10 秒") { jump(10_000_000L) }
        ))
        parent.addView(actions(
            action("偏移 -100ms") { changeOffset(-100_000L) },
            action("偏移 -10ms") { changeOffset(-10_000L) },
            action("偏移 +10ms") { changeOffset(10_000L) },
            action("偏移 +100ms") { changeOffset(100_000L) }
        ))
        setContentView(parent)
    }

    private fun pickVideo(which: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, which)
    }

    @Deprecated("M0 intentionally uses the framework document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        when (requestCode) {
            PICK_TEACHER -> teacherUri = uri
            PICK_LEARNER -> learnerUri = uri
            else -> return
        }
        selectionRevision++
        val selected=rememberedSources().apply {
            put(if (requestCode==PICK_TEACHER) "teacher_uri" else "learner_uri",uri.toString())
        }
        runCatching { writeAtomic(selectionFile(),selected) }.onFailure {
            Log.w("WoahComparisonGL","Could not remember picker selection (${it.javaClass.simpleName})")
        }
        pendingCompareUs=0L
        // Invalidate the previous pairing even if the second source is missing.
        closePlayers()
        if (teacherUri != null && learnerUri != null) openPair()
    }

    private fun selectionFile() = File(noBackupFilesDir,"comparison-m0-selection.json")

    private fun rememberedSources(): JSONObject = runCatching {
        JSONObject(AtomicFile(selectionFile()).readFully().toString(Charsets.UTF_8))
    }.getOrElse { JSONObject() }

    private fun writeAtomic(file: File,value: JSONObject) {
        file.parentFile?.mkdirs()
        val atomic=AtomicFile(file)
        val stream=atomic.startWrite()
        try {
            stream.write(value.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Throwable) { atomic.failWrite(stream); throw e }
    }

    /** Copy only the current two selected URIs to local app evidence storage.
     * No URI/name is exported, no network API is invoked, and playback is paused first.
     */
    private fun exportSelectedSources(requestId: String?) {
        if (sourceExportWorker?.isAlive==true) return
        val a=teacherUri
        val b=learnerUri
        val revision=selectionRevision
        val id=requestId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
            ?: UUID.randomUUID().toString()
        val parent=File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir,"comparison-m0")
        val manifest=File(parent,"selected-sources-$id.json")
        if (a==null || b==null) {
            sourceExportStatus="NO_SELECTION"
            runCatching { writeAtomic(manifest,JSONObject().apply {
                put("status",sourceExportStatus); put("requestId",id)
            }) }
            return
        }
        wantToPlay=false; audio?.pause(); audioClock.reset()
        sourceExportStatus="COPYING"
        try {
            writeAtomic(manifest,JSONObject().apply {
                put("status",sourceExportStatus); put("requestId",id)
            })
        } catch (e: Exception) {
            sourceExportStatus="FAILED"
            Log.w("WoahComparisonGL","Could not start local source evidence (${e.javaClass.simpleName})")
            return
        }
        sourceExportWorker=Thread({
            try {
                val directory=File(parent,"sources_${UUID.randomUUID()}")
                check(directory.mkdirs()) { "Could not create local evidence folder" }
                val sources=JSONArray()
                for ((role,uri) in listOf("teacher" to a,"learner" to b)) {
                    val target=File(directory,"$role.media")
                    val input=contentResolver.openInputStream(uri) ?: error("Selected source unavailable")
                    val result=ComparisonSelectedSourceCopy.copy(input,target) {
                        !isDestroyed && revision==selectionRevision
                    }
                    sources.put(JSONObject().apply {
                        put("role",role); put("relativePath",directory.name+"/"+target.name)
                        put("bytes",result.bytes); put("sha256",result.sha256)
                    })
                }
                check(!isDestroyed && revision==selectionRevision) { "Selected pair changed" }
                sourceExportStatus="READY"
                writeAtomic(manifest,JSONObject().apply {
                    put("status",sourceExportStatus); put("requestId",id)
                    put("buildSha",BuildConfig.GIT_COMMIT_SHA)
                    put("scope","only_current_selected_sources_local_debug_evidence")
                    put("sources",sources)
                })
            } catch (e: Exception) {
                sourceExportStatus="FAILED"
                runCatching { writeAtomic(manifest,JSONObject().apply {
                    put("status",sourceExportStatus); put("requestId",id)
                    put("errorType",e.javaClass.simpleName)
                }) }
            }
        },"WoahM0SelectedSources").apply { start() }
    }

    private fun resizeStage() {
        if (stage.width<=0 || stage.height<=0 || middleLabel.height<=0) return
        val params=middleLabel.layoutParams as FrameLayout.LayoutParams
        val top=(stage.height-middleLabel.height)/2
        if (params.topMargin!=top) {
            params.topMargin=top
            middleLabel.layoutParams=params
        }
        engine?.resize(stage.width,stage.height,middleLabel.height)
    }

    private fun openPair() {
        if (!surfaceReady || teacherUri==null || learnerUri==null) return
        closePlayers()
        sourceError=null
        val generation=++session
        val player=ExoPlayer.Builder(this).build().apply {
            trackSelectionParameters=trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO,true).build()
            setMediaItem(MediaItem.fromUri(teacherUri!!))
            setSeekParameters(SeekParameters.EXACT)
            setPlaybackSpeed(playbackSpeed)
            volume=1f
        }
        audio=player
        player.addListener(object: Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (generation!=session) return
                if (playbackState==Player.STATE_BUFFERING) bufferingEvents++
                if (playbackState==Player.STATE_ENDED) wantToPlay=false
                reconcile()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (generation==session) fail(error)
            }
        })
        engine=ComparisonGlEngine(this,videoSurface.holder.surface,
            stage.width,stage.height,middleLabel.height,
            opened={ a,b ->
                if (generation==session) {
                    teacherInfo=a; learnerInfo=b
                    if (!a.hasAudio) fail(IllegalStateException("Teacher audio track required"))
                    else {
                        overlap=ComparisonTimeMapping.overlap(
                            SourceInterval(0,a.durationUs),SourceInterval(0,b.durationUs),offsetUs)
                        if (overlap==null) fail(IllegalStateException("No common interval"))
                        else seekCompareUs(pendingCompareUs)
                    }
                }
            },
            failed={ if (generation==session) fail(it) },
            stalled={
                if (generation==session) {
                    rendererStalls++; renderStalled=true; player.pause()
                }
            },
            recovered={
                if (generation==session && renderStalled) {
                    val range=overlap
                    if (range!=null) {
                        val target=maxOf(lastTickTeacherUs,player.currentPosition*1000L)
                            .coerceIn(range.teacherStartUs,range.teacherEndUs-1)
                        engine?.render(target,offsetUs,System.nanoTime()) {
                            if (generation==session) {
                                renderStalled=false; lastTickTeacherUs=target; reconcile()
                            }
                        }
                    }
                }
            })
        engine!!.open(teacherUri!!,learnerUri!!)
        player.prepare()
    }

    private fun fail(error: Throwable) {
        Log.e("WoahComparisonGL","Shared-clock backend failed",error)
        // Keep source URIs and file names out of diagnostic exports.
        sourceError="共享后端失败 (${error.javaClass.simpleName})，详见本机 logcat"
        wantToPlay=false; audio?.pause(); updateStatus()
    }

    private fun reconcile() {
        val player=audio ?: return
        if (sourceError!=null || !videoReady || renderStalled || overlap==null ||
            player.playbackState!=Player.STATE_READY || !foreground || !wantToPlay) {
            if (player.playWhenReady) player.pause()
        } else if (!player.playWhenReady) player.play()
    }

    private fun currentCompareUs(): Long {
        val range=overlap ?: return pendingCompareUs
        return ((audio?.currentPosition ?: 0)*1000L-range.teacherStartUs)
            .coerceIn(0,range.durationUs)
    }

    private fun seekCompareUs(requestedUs: Long) {
        val range=overlap ?: return
        val player=audio ?: return
        val compare=requestedUs.coerceIn(0,range.durationUs-1)
        pendingCompareUs=compare
        seekEvents++; videoReady=false; renderStalled=false
        player.pause()
        audioClock.reset()
        val target=range.teacherTimeUs(compare)
        lastTickTeacherUs=target
        player.seekTo(target/1000L)
        val generation=session
        engine?.seek(target,offsetUs) {
            if (generation==session) {
                videoReady=true
                resizeStage()
                reconcile(); updateStatus()
            }
        }
    }

    private fun togglePlayback() {
        val range=overlap ?: return
        wantToPlay=!wantToPlay
        if (!wantToPlay) audio?.pause()
        else {
            engine?.resetDiagnostics()
            if (currentCompareUs()>=range.durationUs-150_000L) seekCompareUs(0)
            reconcile()
        }
        updateStatus()
    }
    private fun jump(deltaUs: Long) { seekCompareUs(currentCompareUs()+deltaUs) }
    private fun cycleSpeed() {
        playbackSpeed=when(playbackSpeed) { 1f->.75f; .75f->.5f; else->1f }
        engine?.resetDiagnostics()
        audioClock.reset()
        audio?.setPlaybackSpeed(playbackSpeed)
        speedButton.text="速度 ${playbackSpeed}×"
        updateStatus()
    }
    private fun changeOffset(deltaUs: Long) {
        val compare=currentCompareUs()
        offsetUs=(offsetUs+deltaUs).coerceIn(-MAX_OFFSET_US,MAX_OFFSET_US)
        val a=teacherInfo; val b=learnerInfo
        if (a!=null && b!=null) {
            overlap=ComparisonTimeMapping.overlap(SourceInterval(0,a.durationUs),
                SourceInterval(0,b.durationUs),offsetUs)
            if (overlap!=null) seekCompareUs(compare)
            else { wantToPlay=false; videoReady=false; audio?.pause() }
        }
        updateStatus()
    }

    private fun updateStatus() {
        if (!::stateText.isInitialized) return
        val range=overlap
        val current=currentCompareUs()
        val state=when {
            sourceError!=null -> sourceError
            teacherUri==null || learnerUri==null -> "请选择老师和自己的视频"
            !videoReady || renderStalled || audio?.playbackState==Player.STATE_BUFFERING ->
                "缓冲中（两侧共同等待）"
            wantToPlay -> "播放中"
            else -> "已暂停"
        }
        val teacherUs=audio?.currentPosition?.times(1000L) ?: 0L
        stateText.text=String.format(Locale.US,
            "%s · 偏移 %+.3fs · %.2f×\n公共 %.2fs / %.2fs · 位置 T %.3fs / L %.3fs",
            state,offsetUs/1e6,playbackSpeed,current/1e6,
            (range?.durationUs ?: 0L)/1e6,teacherUs/1e6,(teacherUs+offsetUs)/1e6)
        if (!dragActive && range!=null) progressBar.progress=(1000L*current/range.durationUs).toInt()
        toggleButton.text=if (wantToPlay) "暂停" else "播放"
        if (wantToPlay && range!=null && current>=range.durationUs-30_000L) {
            wantToPlay=false; audio?.pause()
        }
    }

    private fun saveReport() {
        val generation=session
        val base=JSONObject().apply {
            put("schema","woah.comparison.m0.gl.v1")
            put("backend","shared_audio_dual_mediacodec_single_gl")
            put("buildSha",BuildConfig.GIT_COMMIT_SHA)
            put("media3","1.9.2")
            put("device","${Build.MANUFACTURER} ${Build.MODEL}")
            put("apiLevel",Build.VERSION.SDK_INT)
            put("offsetUs",offsetUs); put("speed",playbackSpeed)
            put("sourceError",sourceError ?: JSONObject.NULL)
            put("audioClockSource","single_media3_audio_position_bounded_monotonic_interpolation")
            put("audioSelected",audio?.audioFormat!=null)
            put("audioPlayerCount",if (audio!=null) 1 else 0)
            put("audioVideoTrackDisabled",true)
            put("videoWindowSurfaces",1)
            put("teacherDurationMs",(teacherInfo?.durationUs ?: 0)/1000)
            put("learnerDurationMs",(learnerInfo?.durationUs ?: 0)/1000)
            put("teacherFrameRate",teacherInfo?.fps ?: -1)
            put("learnerFrameRate",learnerInfo?.fps ?: -1)
            put("teacherVideoWidth",teacherInfo?.width ?: -1)
            put("teacherVideoHeight",teacherInfo?.height ?: -1)
            put("learnerVideoWidth",learnerInfo?.width ?: -1)
            put("learnerVideoHeight",learnerInfo?.height ?: -1)
            put("overlapDurationUs",overlap?.durationUs ?: 0)
            put("sourceExportStatus",sourceExportStatus)
            put("seekEvents",seekEvents); put("bufferingEvents",bufferingEvents)
            put("rendererStalls",rendererStalls); put("resourceCloseFailures",closeFailures)
            put("clockBacksteps",clockBacksteps); put("maxClockBackstepUs",maxClockBackstepUs)
            put("maxAudioPublishedStepUs",audioClock.maxPublishedStepUs)
            put("maxAudioInterpolationAheadUs",audioClock.maxAheadUs)
            put("audioInterpolationBoundUs",15_000)
            put("processPssKb",Debug.getPss())
            put("evidenceType","joint_gl_submission_source_pts_only")
            put("actualDisplayAcceptance","NOT_PROVEN")
        }
        engine?.report { stats ->
            if (generation==session) {
                stats.keys().forEach { base.put(it,stats.get(it)) }
                try {
                    val folder=File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir,
                        "comparison-m0")
                    check(folder.isDirectory || folder.mkdirs())
                    val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
                    File(folder,"gl_m0_$stamp.json").writeText(base.toString(2))
                    File(folder,"latest.json").writeText(base.toString(2))
                    stateText.text="M0 GL 诊断已保存"
                } catch (e: Exception) { stateText.text="诊断写入失败: ${e.javaClass.simpleName}" }
            }
        }
    }

    private fun closePlayers() {
        ++session
        wantToPlay=false; videoReady=false; renderStalled=false
        audio?.release(); audio=null
        runCatching { engine?.close() }.onFailure {
            closeFailures++
            Log.e("WoahComparisonGL","Resource release failed",it)
        }
        engine=null; overlap=null; teacherInfo=null; learnerInfo=null
    }
    override fun onResume() {
        super.onResume()
        foreground=true
        choreographer.postFrameCallback(frames)
    }
    override fun onPause() {
        pendingCompareUs=currentCompareUs()
        foreground=false; wantToPlay=false; audio?.pause(); audioClock.reset()
        choreographer.removeFrameCallback(frames)
        super.onPause()
    }
    override fun onDestroy() {
        sourceExportWorker?.interrupt()
        unregisterReceiver(testReceiver)
        handler.removeCallbacksAndMessages(null)
        choreographer.removeFrameCallback(frames)
        closePlayers()
        super.onDestroy()
    }
}

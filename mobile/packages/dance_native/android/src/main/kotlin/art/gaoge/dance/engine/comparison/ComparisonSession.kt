package art.gaoge.dance.engine.comparison

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import art.gaoge.dance.engine.bridge.comparison.ComparisonCanvasLayoutDto
import art.gaoge.dance.engine.bridge.comparison.ComparisonCutoutPreviewDto
import art.gaoge.dance.engine.bridge.comparison.ComparisonCutoutResultDto
import art.gaoge.dance.engine.bridge.comparison.ComparisonCutoutSeedDto
import art.gaoge.dance.engine.inference.YoloLiteRtSegmenter
import art.gaoge.dance.engine.litert.LiteRtAccelerator
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import art.gaoge.dance.engine.bridge.comparison.ComparisonCommand
import art.gaoge.dance.engine.bridge.comparison.ComparisonPlaybackDto
import art.gaoge.dance.engine.bridge.comparison.ComparisonPracticeDto
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One product session owns the clock, codecs and output texture. Main-thread commands only. */
@androidx.annotation.OptIn(markerClass=[UnstableApi::class])
internal class ComparisonSession(
    private val context: Context,
    registry: TextureRegistry,
    private val teacherUri: String,
    private val learnerUri: String,
    initialOffsetUs: Long,
    initialPositionUs: Long,
    initialSpeed: Double,
    width: Int, height: Int,
    private var horizontal: Boolean,
    private var practice: ComparisonPracticeDto,
    initialTimeScale: Double = 1.0,
) : AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val producer=registry.createSurfaceProducer()
    private val choreographer=Choreographer.getInstance()
    private val clock=ComparisonAudioClock()
    private var audio: ExoPlayer?=null
    private var engine: ComparisonGlEngine?=null
    private var a: ComparisonVideoDescription?=null
    private var b: ComparisonVideoDescription?=null
    private var range: ComparisonOverlap?=null
    private var offsetUs=initialOffsetUs
    private var timeScale=initialTimeScale
    private var pendingUs=initialPositionUs.coerceAtLeast(0)
    private var speed=initialSpeed.toFloat()
    private var ready=false
    private var stalled=false
    private var wantsPlay=false
    private var foreground=true
    private var closed=false
    private var generation=0L
    private var error: String?=null
    private var surfaceAvailable=true
    private var width=width
    private var height=height
    private var lastTeacherUs=0L
    private var silentPlaying=false
    private var silentAnchorNs=0L
    private var silentAnchorTeacherUs=0L
    private val cutoutRevision=AtomicLong(0)
    private var cutoutCache: ComparisonCutoutCache?=null
    private var cutoutWindow: ComparisonLoop?=null
    private var cutoutExportSuspended=false
    private var cutoutPreparing=false
    private var cutoutDone: CompletableDeferred<Unit>?=null
    private var referenceInspection: ComparisonCutoutAnalysis.Inspection?=null
    private var targetInspection: ComparisonCutoutAnalysis.Inspection?=null
    val hasCutout: Boolean get() = cutoutCache!=null || cutoutPreparing
    private var pendingStep: CancellableContinuation<ComparisonPlaybackDto>?=null
    private fun loop(): ComparisonLoop? {
        val start=practice.loopStartUs; val end=practice.loopEndUs
        val duration=range?.durationUs ?: return null
        return if (practice.loopEnabled && start!=null && end!=null &&
            start>=0 && end>start && end<=duration) ComparisonLoop(start,end) else null
    }
    private val frames=object: Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (closed) return
            val bounds=range
            if (bounds!=null && ready && !stalled && foreground && wantsPlay && isClockPlaying()) {
                val now=System.nanoTime()
                val raw=teacherPositionUs(now)
                val target=(audio?.let { clock.sample(raw,now,speed) } ?: raw)
                    .coerceIn(bounds.teacherStartUs,bounds.teacherEndUs-1)
                val repeat=loop()
                if (repeat!=null && (repeat.reached(raw-bounds.teacherStartUs) ||
                    repeat.reached(target-bounds.teacherStartUs))) {
                    if (art.gaoge.dance.engine.BuildConfig.DEBUG) android.util.Log.i(
                        "WoahComparison","loop_restart a_us=${repeat.startUs} b_us=${repeat.endUs} speed=$speed")
                    seek(repeat.startUs)
                } else if (raw>=bounds.teacherEndUs-1) {
                    pendingUs=bounds.durationUs-1; wantsPlay=false; pauseClock()
                } else {
                    lastTeacherUs=maxOf(lastTeacherUs,target)
                    engine?.render(lastTeacherUs,offsetUs,now,timeScale)
                }
            } else clock.reset()
            choreographer.postFrameCallback(this)
        }
    }
    init {
        ComparisonMusicMapping(offsetUs,timeScale)
        require(width>=2 && height>=2 && speed in listOf(.5f,.75f,1f))
        producer.setSize(width,height)
        producer.setCallback(object: TextureRegistry.SurfaceProducer.Callback {
            override fun onSurfaceAvailable() { main.post {
                if (!closed) { surfaceAvailable=true; if (engine==null) buildEngine() }
            } }
            override fun onSurfaceCleanup() { main.post {
                if (!closed) { pendingUs=positionUs(); pauseClock()
                    surfaceAvailable=false; releaseEngine() }
            } }
        })
        choreographer.postFrameCallback(frames)
        buildEngine()
    }
    private fun teacherPositionUs(now: Long=System.nanoTime()): Long =
        audio?.currentPosition?.times(1000L) ?: if (silentPlaying)
            silentAnchorTeacherUs+((now-silentAnchorNs)/1000.0*speed).toLong()
        else silentAnchorTeacherUs
    private fun isClockPlaying() = audio?.isPlaying ?: silentPlaying
    private fun positionUs(): Long {
        val bounds=range ?: return pendingUs
        if (!wantsPlay || !ready) return pendingUs.coerceIn(0,bounds.durationUs-1)
        return (teacherPositionUs()-bounds.teacherStartUs).coerceIn(0,bounds.durationUs-1)
    }
    private fun pauseClock() {
        if (silentPlaying) { silentAnchorTeacherUs=teacherPositionUs(); silentPlaying=false }
        audio?.pause(); clock.reset()
    }
    private fun reconcile() {
        val canPlay=wantsPlay && foreground && ready && !stalled && error==null &&
            (audio==null || audio?.playbackState==Player.STATE_READY)
        if (canPlay) {
            audio?.play()
            if (audio==null && !silentPlaying) {
                silentAnchorNs=System.nanoTime(); silentPlaying=true
            }
        } else pauseClock()
    }
    private fun fail(cause: Throwable) {
        if (closed) return
        pendingUs=positionUs()
        android.util.Log.w("WoahComparison", art.gaoge.dance.engine.diagnostics.NativeDiagnosticPrivacy.reason(cause, "comparison"))
        error="视频暂时无法播放，请重新关联源视频后重试。"
        wantsPlay=false; ready=false; pauseClock()
        pendingStep?.let { if (it.isActive) it.resumeWithException(cause) }; pendingStep=null
    }
    private fun buildEngine() {
        if (closed || !surfaceAvailable || cutoutExportSuspended) return
        releaseEngine(); error=null
        val current=++generation
        engine=ComparisonGlEngine(context,producer.surface,width,height,8,
            opened=sourceReady@{ teacher,learner -> if (current==generation && !closed) {
                a=teacher; b=learner
                range=runCatching { ComparisonTimeMapping.overlap(SourceInterval(teacher.startUs,teacher.durationUs),
                    SourceInterval(learner.startUs,learner.durationUs),offsetUs,timeScale) }.getOrElse {
                        fail(it);return@sourceReady
                    }
                if (range==null) { error="两段视频在此偏移下没有公共区间，请调整校准。"; ready=false }
                else {
                    if (teacher.hasAudio) {
                        audio=ExoPlayer.Builder(context).build().apply {
                            trackSelectionParameters=trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO,true).build()
                            setSeekParameters(SeekParameters.EXACT)
                            setMediaItem(MediaItem.fromUri(Uri.parse(teacherUri)))
                            setPlaybackSpeed(speed)
                            addListener(object: Player.Listener {
                                override fun onPlaybackStateChanged(state: Int) { if (current==generation) {
                                    if (state==Player.STATE_ENDED) {
                                        val repeat=loop()
                                        if (repeat!=null && wantsPlay) { seek(repeat.startUs);return }
                                        pendingUs=(range?.durationUs?.minus(1) ?: pendingUs).coerceAtLeast(0)
                                        wantsPlay=false
                                    }
                                    reconcile()
                                } }
                                override fun onPlayerError(error: PlaybackException) { if (current==generation) fail(error) }
                            })
                            prepare()
                        }
                    }
                    seek(pendingUs)
                }
            } },
            failed={ if (current==generation) fail(it) },
            stalled={ if (current==generation) { stalled=true; pauseClock() } },
            recovered={ if (current==generation && stalled) {
                val bounds=range
                if (bounds!=null) {
                    val target=maxOf(lastTeacherUs,teacherPositionUs()).coerceIn(bounds.teacherStartUs,bounds.teacherEndUs-1)
                    engine?.render(target,offsetUs,System.nanoTime(),timeScale) { if (current==generation) {
                        lastTeacherUs=target;stalled=false;reconcile()
                    } }
                }
            } },
            initialSideBySide=horizontal,
            initialMirrorTeacher=practice.mirrorTeacher,initialMirrorLearner=practice.mirrorLearner,
            initialTeacherPicture=ComparisonPicture.fromDto(practice.teacherPicture),
            initialLearnerPicture=ComparisonPicture.fromDto(practice.learnerPicture),
            cutoutProvider=cutoutCache?.let { cache -> { r,t -> cache.frame(r,t) } })
        engine!!.open(Uri.parse(teacherUri),Uri.parse(learnerUri))
    }
    private fun seek(valueUs: Long) {
        val bounds=range ?: run { pendingUs=valueUs.coerceAtLeast(0); return }
        val window=cutoutWindow
        pendingUs=valueUs.coerceIn(window?.startUs ?: 0,window?.endUs?.minus(1) ?: (bounds.durationUs-1))
        ready=false; stalled=false; pauseClock()
        val teacher=bounds.teacherTimeUs(pendingUs)
        silentAnchorTeacherUs=teacher; lastTeacherUs=teacher
        audio?.seekTo(teacher/1000L)
        val current=generation
        engine?.seek(teacher,offsetUs,timeScale) { if (current==generation && !closed) {
            ready=true; reconcile()
        } }
    }
    fun control(command: ComparisonCommand,valueUs: Long,newSpeed: Double): ComparisonPlaybackDto {
        check(!closed && (!cutoutPreparing || command==ComparisonCommand.PAUSE)) { "人物分析进行中" }
        if(command!=ComparisonCommand.PAUSE) {referenceInspection=null;targetInspection=null}
        when (command) {
            ComparisonCommand.PLAY -> {
                val bounds=range
                val repeat=loop()
                if (repeat!=null) {
                    if (!repeat.contains(positionUs())) seek(repeat.startUs)
                } else if (bounds!=null && positionUs()>=bounds.durationUs-100_000) seek(0)
                wantsPlay=true; reconcile()
            }
            ComparisonCommand.PAUSE -> { pendingUs=positionUs(); wantsPlay=false; pauseClock() }
            ComparisonCommand.SEEK -> seek(valueUs)
            ComparisonCommand.SET_OFFSET -> setAlignment(valueUs,1.0)
            ComparisonCommand.SET_SPEED -> {
                require(newSpeed in listOf(.5,.75,1.0))
                val before=teacherPositionUs(); pauseClock(); speed=newSpeed.toFloat()
                silentAnchorTeacherUs=before;audio?.setPlaybackSpeed(speed);reconcile()
            }
        }
        if (art.gaoge.dance.engine.BuildConfig.DEBUG) {
            android.util.Log.i("WoahComparison","command="+command+" position_us="+positionUs()+
                " offset_us="+offsetUs+" time_scale="+timeScale+" speed="+speed+" phase="+state().phase)
            if (command==ComparisonCommand.PAUSE) engine?.report { report ->
                report.remove("samples")
                android.util.Log.i("WoahComparison","playback_report="+report.toString())
            }
        }
        return state()
    }
    fun setAlignment(valueUs: Long,scale: Double): ComparisonPlaybackDto {
        check(!closed && !hasCutout) { "请先退出抠像画布" }
        ComparisonMusicMapping(valueUs,scale)
        val teacher=a; val learner=b
        val previous=range
        val sourcePosition=(previous?.teacherStartUs ?: 0)+positionUs()
        val next=if (teacher!=null && learner!=null) requireNotNull(
            ComparisonTimeMapping.overlap(SourceInterval(teacher.startUs,teacher.durationUs),
                SourceInterval(learner.startUs,learner.durationUs),valueUs,scale)) {
                    "两段视频没有共同音乐区间，请检查校准。"
                } else null
        wantsPlay=false;pauseClock();offsetUs=valueUs;timeScale=scale
        range=next;error=null
        if (previous!=null && next!=null) {
            val start=practice.loopStartUs?.let {
                previous.teacherStartUs+it-next.teacherStartUs
            }
            val end=practice.loopEndUs?.let {
                previous.teacherStartUs+it-next.teacherStartUs
            }
            val available=start!=null && start<next.durationUs &&
                (if (end==null) start>=0 else end>0 && end>start)
            practice=practice.copy(loopStartUs=if (available) start!!.coerceIn(0,next.durationUs-1) else null,
                loopEndUs=if (available) end?.coerceIn(0,next.durationUs) else null,
                loopEnabled=practice.loopEnabled && available && end!=null)
            seek(sourcePosition-next.teacherStartUs)
        }
        return state()
    }
    fun setPractice(value: ComparisonPracticeDto): ComparisonPlaybackDto {
        check(!cutoutPreparing) { "人物分析进行中" }
        check(!hasCutout || value==practice) { "请先退出抠像画布再调整选段或画面" }
        if(value!=practice){referenceInspection=null;targetInspection=null}
        val teacherPicture=ComparisonPicture.fromDto(value.teacherPicture)
        val learnerPicture=ComparisonPicture.fromDto(value.learnerPicture)
        val duration=range?.durationUs
        require(value.loopStartUs==null || (value.loopStartUs!!>=0 &&
            (duration==null || value.loopStartUs!!<duration)))
        require(value.loopEndUs==null || (value.loopEndUs!!>(value.loopStartUs ?: 0) &&
            (duration==null || value.loopEndUs!!<=duration)))
        require(!value.loopEnabled || (value.loopStartUs!=null && value.loopEndUs!=null))
        val loopChanged=value.loopEnabled && (!practice.loopEnabled ||
            value.loopStartUs!=practice.loopStartUs || value.loopEndUs!=practice.loopEndUs)
        practice=value
        engine?.setPicture(value.mirrorTeacher,value.mirrorLearner,teacherPicture,learnerPicture)
        if (loopChanged) loop()?.let { if (!it.contains(positionUs())) seek(it.startUs) }
        return state()
    }
    suspend fun stepFrame(forward: Boolean): ComparisonPlaybackDto {
        check(ready && pendingStep==null && !cutoutPreparing) { "请等待画面就绪" }
        val bounds=requireNotNull(range)
        val window=cutoutWindow
        val stepBounds=if(window==null)bounds else bounds.copy(
            teacherStartUs=bounds.teacherStartUs+window.startUs,
            teacherEndUs=bounds.teacherStartUs+window.endUs)
        pendingUs=positionUs(); wantsPlay=false; pauseClock(); ready=false
        return suspendCancellableCoroutine { continuation ->
            pendingStep=continuation
            engine!!.stepFrame(forward,stepBounds,bounds.teacherTimeUs(pendingUs)) { target ->
                pendingUs=target-bounds.teacherStartUs
                silentAnchorTeacherUs=target; lastTeacherUs=target
                audio?.seekTo(target/1000L)
                ready=true
                val result=state()
                if (art.gaoge.dance.engine.BuildConfig.DEBUG) android.util.Log.i("WoahComparison",
                    "frame_step forward=$forward position_us=${result.positionUs} reference_pts_us=${result.teacherFrameUs} target_pts_us=${result.learnerFrameUs} time_scale=$timeScale")
                pendingStep=null
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }
    suspend fun inspectCutout(reference: Boolean,value: ComparisonCutoutSeedDto): ComparisonCutoutPreviewDto {
        check(!closed && ready && !hasCutout && foreground && !wantsPlay)
        val pair=requireNotNull(engine?.presentedFrame)
        require((if(reference)pair.first else pair.second)==value.sourcePtsUs) { "选人画面已变化" }
        val seed=ComparisonCutoutSeed(value.sourcePtsUs,value.x.toFloat(),value.y.toFloat())
        val description=requireNotNull(if(reference)a else b)
        val uri=if(reference)teacherUri else learnerUri
        val revision=cutoutRevision.incrementAndGet()
        cutoutPreparing=true
        val done=CompletableDeferred<Unit>();cutoutDone=done
        try {
            val inspection=withContext(Dispatchers.Default) {
                val scope=currentCoroutineContext()
                val current={scope.isActive && cutoutRevision.get()==revision}
                YoloLiteRtSegmenter(context,requestedAccelerator=LiteRtAccelerator.CPU,cpuNumThreads=4).use {
                    it.initialize()
                    ComparisonCutoutAnalysis(context).inspect(uri,description,seed,it,current)
                }
            }
            check(!closed && foreground && cutoutRevision.get()==revision)
            val shown=requireNotNull(engine?.presentedFrame)
            require((if(reference)shown.first else shown.second)==seed.ptsUs)
            if(reference)referenceInspection=inspection else targetInspection=inspection
            return inspection.preview
        } finally {
            if(cutoutDone===done){cutoutPreparing=false;cutoutDone=null}
            done.complete(Unit)
        }
    }

    suspend fun prepareCutout(reference: ComparisonCutoutSeedDto,target: ComparisonCutoutSeedDto,layout: ComparisonCanvasLayoutDto?): ComparisonCutoutResultDto {
        val requestedLayout=layout?.let { ComparisonCanvasLayout.fromDto(it) }
        check(!closed && ready && !hasCutout && foreground && !wantsPlay) { "请暂停并等待画面就绪" }
        val bounds=requireNotNull(range)
        val teacher=requireNotNull(a);val learner=requireNotNull(b)
        val pair=requireNotNull(engine?.presentedFrame)
        require(pair.first==reference.sourcePtsUs && pair.second==target.sourcePtsUs) { "选人画面已变化" }
        val ra=ComparisonCutoutSeed(reference.sourcePtsUs,reference.x.toFloat(),reference.y.toFloat())
        val ta=ComparisonCutoutSeed(target.sourcePtsUs,target.x.toFloat(),target.y.toFloat())
        val selectedWindow=loop() ?: ComparisonLoop(0,bounds.durationUs)
        require(selectedWindow.contains(positionUs())) { "请在 A/B 选段内选人" }
        val first=selectedWindow.startUs;val last=selectedWindow.endUs-1
        val mirrorR=practice.mirrorTeacher;val mirrorT=practice.mirrorLearner
        val inspectedR=referenceInspection;val inspectedT=targetInspection
        val revision=cutoutRevision.incrementAndGet()
        cutoutPreparing=true
        val done=CompletableDeferred<Unit>();cutoutDone=done
        var pendingCache:ComparisonCutoutCache?=null
        try {
            val cache=withContext(Dispatchers.Default) {
                val scope=currentCoroutineContext()
                val current={scope.isActive && cutoutRevision.get()==revision}
                val segmenter=YoloLiteRtSegmenter(context,requestedAccelerator=LiteRtAccelerator.CPU,cpuNumThreads=4)
                segmenter.use {
                    it.initialize()
                    val analysis=ComparisonCutoutAnalysis(context)
                    val r=analysis.analyze(teacherUri,teacher,bounds.teacherTimeUs(first),bounds.teacherTimeUs(last),ra,true,mirrorR,it,current,inspectedR)
                    try {
                        val t=analysis.analyze(learnerUri,learner,bounds.learnerTimeUs(first),bounds.learnerTimeUs(last),ta,false,mirrorT,it,current,inspectedT)
                        ComparisonCutoutCache(r,t).also{pendingCache=it}
                    } catch(error:Throwable){r.close();throw error}
                }
            }
            if(closed || !foreground || cutoutRevision.get()!=revision || range!=bounds) {
                cache.close();error("人物分析已取消")
            }
            cache.setLayout(requestedLayout)
            cutoutCache=cache;pendingCache=null;cutoutWindow=selectedWindow
            referenceInspection=null;targetInspection=null
            pendingUs=positionUs()
            buildEngine()
            return ComparisonCutoutResultDto(state(),cache.reference.visibleCount.toLong(),cache.reference.frameCount.toLong(),
                cache.target.visibleCount.toLong(),cache.target.frameCount.toLong())
        } finally {
            pendingCache?.close()
            if(cutoutDone===done) {cutoutPreparing=false;cutoutDone=null}
            done.complete(Unit)
        }
    }

    suspend fun setCutoutLayout(layout: ComparisonCanvasLayoutDto): ComparisonPlaybackDto {
        check(!closed && ready && !cutoutPreparing && foreground && !wantsPlay)
        val cache=requireNotNull(cutoutCache)
        val renderer=requireNotNull(engine)
        val value=ComparisonCanvasLayout.fromDto(layout)
        val revision=cutoutRevision.incrementAndGet()
        val done=CompletableDeferred<Unit>();cutoutDone=done;cutoutPreparing=true
        val previous=cache.setLayout(value)
        try {
            kotlinx.coroutines.withTimeout(10_000L) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    renderer.redrawCutout { result ->
                        if(continuation.isActive) result.fold(
                            onSuccess={continuation.resume(Unit)},onFailure={continuation.resumeWithException(it)})
                    }
                }
            }
            check(!closed && foreground && cutoutRevision.get()==revision && engine===renderer)
            return state()
        } catch (error:Throwable) {
            cache.setLayout(previous)
            if(!closed && engine===renderer && cutoutCache===cache)renderer.redrawCutout {}
            throw error
        } finally {
            if(cutoutDone===done){cutoutPreparing=false;cutoutDone=null}
            done.complete(Unit)
        }
    }

    fun freezeCutoutExport(spec:ComparisonExportSpec):ComparisonCutoutExportSnapshot {
        check(!closed && ready && !wantsPlay && !cutoutPreparing && !cutoutExportSuspended)
        val cache=requireNotNull(cutoutCache)
        val bounds=requireNotNull(range)
        val window=requireNotNull(cutoutWindow)
        require(spec.cutout && spec.teacherUri==teacherUri && spec.learnerUri==learnerUri &&
            spec.offsetUs==offsetUs && spec.timeScale==timeScale &&
            spec.startUs==window.startUs && spec.endUs==window.endUs)
        cache.requireComplete()
        val frozen=cache.frozen()
        return ComparisonCutoutExportSnapshot(frozen,bounds.teacherStartUs)
    }

    fun suspendCutoutForExport() {
        check(cutoutCache!=null && !cutoutExportSuspended)
        pendingUs=positionUs();wantsPlay=false;pauseClock();cutoutExportSuspended=true
        releaseEngine()
    }
    fun suspendCutoutForRetry() {if(!cutoutExportSuspended)suspendCutoutForExport()}
    fun resumeCutoutPreview():ComparisonPlaybackDto {
        check(!closed && cutoutCache!=null)
        if(cutoutExportSuspended) {cutoutExportSuspended=false;buildEngine()}
        return state()
    }

    suspend fun clearCutout(): ComparisonPlaybackDto {
        val revision=cutoutRevision.incrementAndGet()
        cutoutDone?.await()
        if(closed || cutoutRevision.get()!=revision)return state()
        referenceInspection=null;targetInspection=null
        if(cutoutCache!=null) {
            pendingUs=positionUs();wantsPlay=false;pauseClock()
            val old=cutoutCache;cutoutCache=null;cutoutWindow=null;cutoutExportSuspended=false
            try { buildEngine() } finally { old?.close() }
        }
        return state()
    }

    fun resize(newWidth: Int,newHeight: Int,sideBySide: Boolean): ComparisonPlaybackDto {
        require(newWidth in 2..4096 && newHeight in 2..4096)
        if(cutoutExportSuspended) return state()
        if (width==newWidth && height==newHeight) {
            if (horizontal!=sideBySide) {
                horizontal=sideBySide
                engine?.resize(width,height,8,horizontal)
            }
        } else {
            // Preserve playback intent and the music position through a rotated
            // output Surface. Backgrounding clears wantsPlay separately.
            pendingUs=positionUs();pauseClock()
            releaseEngine();width=newWidth;height=newHeight;horizontal=sideBySide
            producer.setSize(width,height)
            if (surfaceAvailable) buildEngine()
        }
        return state()
    }
    fun background() { if (!closed) {
        cutoutRevision.incrementAndGet();referenceInspection=null;targetInspection=null
        pendingUs=positionUs();wantsPlay=false;foreground=false;pauseClock()
    } }
    fun foreground() { foreground=true }
    fun state(): ComparisonPlaybackDto {
        val phase=when { closed->"closed"; error!=null->"error"; !ready->if (wantsPlay) "buffering" else "opening"
            wantsPlay && (!isClockPlaying() || stalled)->"buffering"
            wantsPlay->"playing"; else->"paused" }
        val presented=engine?.presentedFrame
        fun aspect(d: ComparisonVideoDescription?)=d?.let {
            if (it.rotation%180==0) it.width.toDouble()/it.height else it.height.toDouble()/it.width
        }
        return ComparisonPlaybackDto(producer.id(),phase,positionUs(),range?.durationUs ?: 0,
            range?.teacherStartUs ?: 0,offsetUs,speed.toDouble(),error,presented?.first,presented?.second,aspect(a),aspect(b),timeScale)
    }
    private fun releaseEngine() {
        pendingStep?.let { if (it.isActive) it.resumeWithException(IllegalStateException("会话已重建")) }; pendingStep=null
        ++generation;ready=false;stalled=false
        audio?.release();audio=null;silentPlaying=false
        runCatching { engine?.close() }.onFailure { fail(it) };engine=null
        a=null;b=null;range=null
    }
    override fun close() {
        if (closed) return
        pendingUs=positionUs();wantsPlay=false;pauseClock();closed=true
        val old=cutoutCache
        cutoutRevision.incrementAndGet();cutoutPreparing=false;cutoutCache=null;cutoutWindow=null;referenceInspection=null;targetInspection=null
        choreographer.removeFrameCallback(frames)
        try {releaseEngine();producer.setCallback(null);producer.release()} finally {old?.close()}
    }
}

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
import art.gaoge.dance.engine.bridge.comparison.ComparisonCommand
import art.gaoge.dance.engine.bridge.comparison.ComparisonPlaybackDto
import art.gaoge.dance.engine.bridge.comparison.ComparisonPracticeDto
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.CancellableContinuation
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
        if (closed || !surfaceAvailable) return
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
            initialLearnerPicture=ComparisonPicture.fromDto(practice.learnerPicture))
        engine!!.open(Uri.parse(teacherUri),Uri.parse(learnerUri))
    }
    private fun seek(valueUs: Long) {
        val bounds=range ?: run { pendingUs=valueUs.coerceAtLeast(0); return }
        pendingUs=valueUs.coerceIn(0,bounds.durationUs-1)
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
        check(!closed)
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
        check(!closed)
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
        check(ready && pendingStep==null) { "请等待画面就绪" }
        val bounds=requireNotNull(range)
        pendingUs=positionUs(); wantsPlay=false; pauseClock(); ready=false
        return suspendCancellableCoroutine { continuation ->
            pendingStep=continuation
            engine!!.stepFrame(forward,bounds,bounds.teacherTimeUs(pendingUs)) { target ->
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
    fun resize(newWidth: Int,newHeight: Int,sideBySide: Boolean): ComparisonPlaybackDto {
        require(newWidth in 2..4096 && newHeight in 2..4096)
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
        choreographer.removeFrameCallback(frames)
        releaseEngine();producer.setCallback(null);producer.release()
    }
}

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
import io.flutter.view.TextureRegistry

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
    private val frames=object: Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (closed) return
            val bounds=range
            if (bounds!=null && ready && !stalled && foreground && wantsPlay && isClockPlaying()) {
                val now=System.nanoTime()
                val raw=teacherPositionUs(now)
                if (raw>=bounds.teacherEndUs-1) {
                    pendingUs=bounds.durationUs-1; wantsPlay=false; pauseClock()
                } else {
                    val target=(audio?.let { clock.sample(raw,now,speed) } ?: raw)
                        .coerceIn(bounds.teacherStartUs,bounds.teacherEndUs-1)
                    lastTeacherUs=maxOf(lastTeacherUs,target)
                    engine?.render(lastTeacherUs,offsetUs,now)
                }
            } else clock.reset()
            choreographer.postFrameCallback(this)
        }
    }
    init {
        require(width>=2 && height>=2 && speed in listOf(.5f,.75f,1f))
        producer.setSize(width,height)
        producer.setCallback(object: TextureRegistry.SurfaceProducer.Callback {
            override fun onSurfaceAvailable() { main.post {
                if (!closed) { surfaceAvailable=true; if (engine==null) buildEngine() }
            } }
            override fun onSurfaceCleanup() { main.post {
                if (!closed) { pendingUs=positionUs(); wantsPlay=false; pauseClock()
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
        android.util.Log.w("WoahComparison","Comparison session failed (${cause.javaClass.simpleName})")
        error="视频暂时无法播放，请重新关联源视频后重试。"
        wantsPlay=false; ready=false; pauseClock()
    }
    private fun buildEngine() {
        if (closed || !surfaceAvailable) return
        releaseEngine(); error=null
        val current=++generation
        engine=ComparisonGlEngine(context,producer.surface,width,height,8,
            opened=sourceReady@{ teacher,learner -> if (current==generation && !closed) {
                a=teacher; b=learner
                range=runCatching { ComparisonTimeMapping.overlap(SourceInterval(teacher.startUs,teacher.durationUs),
                    SourceInterval(learner.startUs,learner.durationUs),offsetUs) }.getOrElse {
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
                                    if (state==Player.STATE_ENDED) wantsPlay=false
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
                    engine?.render(target,offsetUs,System.nanoTime()) { if (current==generation) {
                        lastTeacherUs=target;stalled=false;reconcile()
                    } }
                }
            } },
            initialSideBySide=horizontal)
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
        engine?.seek(teacher,offsetUs) { if (current==generation && !closed) {
            ready=true; reconcile()
        } }
    }
    fun control(command: ComparisonCommand,valueUs: Long,newSpeed: Double): ComparisonPlaybackDto {
        check(!closed)
        when (command) {
            ComparisonCommand.PLAY -> {
                val bounds=range
                if (bounds!=null && positionUs()>=bounds.durationUs-100_000) seek(0)
                wantsPlay=true; reconcile()
            }
            ComparisonCommand.PAUSE -> { pendingUs=positionUs(); wantsPlay=false; pauseClock() }
            ComparisonCommand.SEEK -> seek(valueUs)
            ComparisonCommand.SET_OFFSET -> {
                require(valueUs in -86_400_000_000L..86_400_000_000L)
                val oldPosition=positionUs(); wantsPlay=false; pauseClock(); offsetUs=valueUs
                val teacher=a;val learner=b
                if (teacher!=null && learner!=null) {
                    range=ComparisonTimeMapping.overlap(SourceInterval(teacher.startUs,teacher.durationUs),
                        SourceInterval(learner.startUs,learner.durationUs),offsetUs)
                    if (range==null) { error="两段视频在此偏移下没有公共区间，请调整校准。";ready=false }
                    else { error=null;seek(oldPosition) }
                }
            }
            ComparisonCommand.SET_SPEED -> {
                require(newSpeed in listOf(.5,.75,1.0))
                val before=teacherPositionUs(); pauseClock(); speed=newSpeed.toFloat()
                silentAnchorTeacherUs=before;audio?.setPlaybackSpeed(speed);reconcile()
            }
        }
        if (art.gaoge.dance.engine.BuildConfig.DEBUG) {
            android.util.Log.i("WoahComparison","command="+command+" position_us="+positionUs()+
                " offset_us="+offsetUs+" speed="+speed+" phase="+state().phase)
            if (command==ComparisonCommand.PAUSE) engine?.report { report ->
                report.remove("samples")
                android.util.Log.i("WoahComparison","playback_report="+report.toString())
            }
        }
        return state()
    }
    fun resize(newWidth: Int,newHeight: Int,sideBySide: Boolean): ComparisonPlaybackDto {
        require(newWidth in 2..4096 && newHeight in 2..4096)
        if (width!=newWidth || height!=newHeight || horizontal!=sideBySide) {
            pendingUs=positionUs();wantsPlay=false;pauseClock()
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
        val phase=when { closed->"closed"; error!=null->"error"; !ready->"opening"
            wantsPlay && (!isClockPlaying() || stalled)->"buffering"
            wantsPlay->"playing"; else->"paused" }
        return ComparisonPlaybackDto(producer.id(),phase,positionUs(),range?.durationUs ?: 0,
            range?.teacherStartUs ?: 0,offsetUs,speed.toDouble(),error)
    }
    private fun releaseEngine() {
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

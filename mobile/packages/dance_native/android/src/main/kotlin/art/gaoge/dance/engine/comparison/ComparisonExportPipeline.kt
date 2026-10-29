package art.gaoge.dance.engine.comparison

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Hardware output, driven by real reference frame ends rather than a synthetic FPS loop. */
internal class ComparisonExportPipeline(private val context: Context) {
    fun execute(spec: ComparisonExportSpec,partial: File,packets: File,cancelled: AtomicBoolean,
        progress: (String,Double)->Unit) {
        fun current() { if (cancelled.get()) throw ComparisonExportCancelled() }
        fun <T> await(pump: ()->Unit,start: (((T)->Unit),(Throwable)->Unit)->Unit): T {
            val latch=CountDownLatch(1);val value=AtomicReference<Result<T>>()
            start({ value.set(Result.success(it));latch.countDown() },{ value.set(Result.failure(it));latch.countDown() })
            val deadline=System.nanoTime()+15_000_000_000L
            while (!latch.await(40,TimeUnit.MILLISECONDS)) {
                current();pump();check(System.nanoTime()<deadline) { "合成画面未响应" }
            }
            current();return value.get().getOrThrow()
        }
        current()
        partial.parentFile!!.mkdirs();packets.parentFile!!.mkdirs()
        val fps=nominalFps(spec.teacherUri)
        val bitrate=(spec.width.toDouble()*spec.height*fps*.13).toLong().coerceIn(4_000_000,80_000_000).toInt()
        val estimate=(bitrate/8.0+24000)*(spec.endUs-spec.startUs)/1e6*1.2+32*1024*1024
        check(StatFs(partial.parentFile!!.absolutePath).availableBytes>estimate) { "存储空间不足" }
        val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,spec.width,spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,bitrate);setInteger(MediaFormat.KEY_FRAME_RATE,fps.roundToInt())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
            if (Build.VERSION.SDK_INT>=29) setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
        }
        var muxer: MediaMuxer?=null;var muxerStarted=false
        var videoTrack=-1
        val frames=ComparisonExportFrames()
        var encoder: ComparisonExportEncoder?=null;var gl: ComparisonGlEngine?=null
        var success=false
        try {
            encoder=ComparisonExportEncoder(format,true,::current,{ out ->
                videoTrack=muxer!!.addTrack(out);muxer!!.start();muxerStarted=true
            },{ bytes,info ->
                check(muxerStarted)
                frames.accept(info.presentationTimeUs)
                check(info.presentationTimeUs>=0 && info.presentationTimeUs<spec.endUs-spec.startUs)
                muxer!!.writeSampleData(videoTrack,bytes,info)
            })
            val ready=CountDownLatch(1)
            val descriptions=AtomicReference<Pair<ComparisonVideoDescription,ComparisonVideoDescription>>()
            val failure=AtomicReference<Throwable?>()
            gl=ComparisonGlEngine(context,encoder.surface!!,spec.width,spec.height,spec.gap,
                opened={ a,b -> descriptions.set(a to b);ready.countDown() },
                failed={ failure.set(it);ready.countDown() },stalled={},recovered={},
                initialSideBySide=spec.sideBySide,initialMirrorTeacher=spec.mirrorTeacher,
                initialMirrorLearner=spec.mirrorLearner,initialTeacherPicture=spec.teacherPicture,
                initialLearnerPicture=spec.learnerPicture,recordable=true,exportCurrent={ !cancelled.get() })
            gl.open(Uri.parse(spec.teacherUri),Uri.parse(spec.learnerUri))
            val openDeadline=System.nanoTime()+15_000_000_000L
            while (!ready.await(40,TimeUnit.MILLISECONDS)) { current();check(System.nanoTime()<openDeadline) }
            failure.get()?.let { throw it }
            val (a,b)=descriptions.get() ?: error("视频无法读取")
            val overlap=requireNotNull(ComparisonTimeMapping.overlap(SourceInterval(a.startUs,a.durationUs),
                SourceInterval(b.startUs,b.durationUs),spec.offsetUs,spec.timeScale))
            val range=spec.sourceRange(overlap)
            progress("准备音轨",0.01)
            val audio=ComparisonExportAudio(context).encode(spec.teacherUri,range,packets,::current) {
                progress("准备音轨",.01+it*.19)
            }
            current()
            muxer=MediaMuxer(partial.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val audioTrack=muxer.addTrack(audio.format)
            var reference=range.startUs
            while (reference<range.endUs) {
                current();encoder.drain()
                val at=reference
                frames.submit(at-range.startUs)
                // A synchronous encoder Surface can block while its output is full.
                // Drain on this worker while GL waits, rather than dropping input.
                val frame=await<ComparisonFrameInterval>({ encoder.drain() }) { done,failed ->
                    gl.exportFrame(at,spec.offsetUs,spec.timeScale,at-range.startUs,done,failed)
                }
                encoder.drain()
                check(frame.endUs>reference)
                reference=frame.endUs
                progress("合成画面",.2+.74*((minOf(reference,range.endUs)-range.startUs).toDouble()/(range.endUs-range.startUs)))
            }
            encoder.endVideo();current()
            frames.finish();check(muxerStarted)
            val duration=range.endUs-range.startUs
            endTrack(muxer,videoTrack,duration)
            progress("封装视频",.96)
            var firstAudio: Long?=null
            DataInputStream(BufferedInputStream(packets.inputStream())).use { input ->
                var last=Long.MIN_VALUE;var count=0
                while (true) {
                    current()
                    val pts=try { input.readLong() } catch (_: EOFException) { break }
                    val flags=input.readInt();val size=input.readInt()
                    check(size in 1..1_048_576)
                    val bytes=ByteArray(size);input.readFully(bytes)
                    if (pts<duration) {
                        check(pts>=last)
                        if (firstAudio==null) firstAudio=pts
                        val info=MediaCodec.BufferInfo().apply { set(0,size,pts,flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()) }
                        muxer.writeSampleData(audioTrack,ByteBuffer.wrap(bytes),info);last=pts;count++
                    }
                }
                check(count>0)
            }
            // MPEG4Writer normalizes negative AAC samples before its EOS-duration
            // calculation. Express EOS in that same normalized media timescale.
            endTrack(muxer,audioTrack,duration+maxOf(0L,-requireNotNull(firstAudio)))
            muxer.stop();muxerStarted=false;muxer.release();muxer=null
            current();validate(partial,duration,maxOf(0L,-requireNotNull(firstAudio)))
            if (art.gaoge.dance.engine.BuildConfig.DEBUG) android.util.Log.i("WoahComparisonExport",
                "encoded frames=${frames.encoded} duration_us=$duration width=${spec.width} height=${spec.height} time_scale=${spec.timeScale}")
            success=true
        } finally {
            encoder?.close()
            runCatching { gl?.close() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() };packets.delete()
            if (!success) partial.delete()
        }
    }
    private fun nominalFps(uri: String): Double {
        val extractor=MediaExtractor()
        try {
            val source=Uri.parse(uri)
            if (source.scheme=="content") extractor.setDataSource(context,source,null) else extractor.setDataSource(source.path!!)
            val f=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
            val fps=if (f.containsKey(MediaFormat.KEY_FRAME_RATE))
                runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() } else 30.0
            return if (fps.isFinite() && fps>0) fps else 30.0
        } finally { extractor.release() }
    }
    private fun endTrack(muxer: MediaMuxer,track: Int,duration: Long) {
        val end=MediaCodec.BufferInfo().apply { set(0,0,duration,MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
        muxer.writeSampleData(track,ByteBuffer.allocateDirect(0),end)
    }
    private fun validate(file: File,duration: Long,audioPrerollUs: Long) {
        check(file.length()>0)
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val video=formats.single { it.getString(MediaFormat.KEY_MIME)==MediaFormat.MIMETYPE_VIDEO_AVC }
            val audio=formats.single { it.getString(MediaFormat.KEY_MIME)==MediaFormat.MIMETYPE_AUDIO_AAC }
            // MediaExtractor exposes the raw AAC media duration including edit-list preroll.
            check(kotlin.math.abs(audio.getLong(MediaFormat.KEY_DURATION)-audioPrerollUs-duration)<2_000) {
                "Audio media duration=${audio.getLong(MediaFormat.KEY_DURATION)} preroll=$audioPrerollUs expected=$duration"
            }
            check(kotlin.math.abs(video.getLong(MediaFormat.KEY_DURATION)-duration)<100_000)
        } finally { extractor.release() }
    }
}

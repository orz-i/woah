package art.gaoge.dance.engine.comparison

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.roundToLong

internal data class ComparisonPcmSlice(val skip: Int,val frames: Int,val outputFrame: Long)
internal object ComparisonExportAudioTiming {
    fun frameCount(durationUs: Long,rate: Int) = (durationUs*rate/1e6).roundToLong().coerceAtLeast(1)
    fun slice(pts: Long,frames: Int,range: SourceInterval,rate: Int): ComparisonPcmSlice {
        fun ceil(value: Long) = -Math.floorDiv(-value,1_000_000L)
        val first=ceil((range.startUs-pts)*rate).coerceIn(0,frames.toLong()).toInt()
        val end=ceil((range.endUs-pts)*rate).coerceIn(first.toLong(),frames.toLong()).toInt()
        val at=((pts-range.startUs)*rate/1e6+first).roundToLong().coerceAtLeast(0)
        return ComparisonPcmSlice(first,end-first,at)
    }
}

internal data class ComparisonEncodedAudio(val format: MediaFormat,val packets: File,val samples: Long,val rate: Int)

/** Source-time PCM -> sample-exact reference window -> AAC spool. Bounded memory, 1x. */
internal class ComparisonExportAudio(private val context: Context) {
    private class Writer(private val file: File,val rate: Int,val channels: Int,
        durationUs: Long,private val ensureCurrent: ()->Unit,private val progress: (Double)->Unit) : AutoCloseable {
        private val stream=FileOutputStream(file)
        private val output=DataOutputStream(BufferedOutputStream(stream))
        private var format: MediaFormat?=null
        val total=ComparisonExportAudioTiming.frameCount(durationUs,rate)
        var cursor=0L;private set
        private val encoder: ComparisonExportEncoder
        init {
            val config=ComparisonAacPriming.format(rate,channels)
            try {
                val priming=ComparisonAacPriming.measure(rate,channels,ensureCurrent)
                encoder=ComparisonExportEncoder(config,false,ensureCurrent,{ format=it },{ bytes,info ->
                    output.writeLong(info.presentationTimeUs-priming.delayUs);output.writeInt(info.flags);output.writeInt(info.size)
                    val data=ByteArray(info.size);bytes.get(data);output.write(data)
                },codecName=priming.codecName)
            } catch (e: Throwable) { output.close();throw e }
        }
        fun silence(until: Long) {
            val blank=ShortArray(1024*channels)
            while (cursor<minOf(total,until)) {
                ensureCurrent()
                val n=minOf(1024L,minOf(total,until)-cursor).toInt()
                encoder.pcm(blank,n,channels,cursor,rate);cursor+=n
                progress(cursor.toDouble()/total)
            }
        }
        fun samples(data: ShortArray,frames: Int) {
            val count=minOf(frames.toLong(),total-cursor).toInt()
            if (count>0) { encoder.pcm(data,count,channels,cursor,rate);cursor+=count;progress(cursor.toDouble()/total) }
        }
        fun finish(): ComparisonEncodedAudio {
            silence(total)
            // Codec EOS alone may not flush all AAC overlap/priming. Feed a bounded
            // tail, then the muxer clips packets to the requested content interval.
            val blank=ShortArray(1024*channels)
            repeat(10) { encoder.pcm(blank,1024,channels,cursor,rate);cursor+=1024 }
            encoder.endAudio(cursor*1_000_000L/rate)
            output.flush();stream.fd.sync()
            return ComparisonEncodedAudio(requireNotNull(format),file,total,rate)
        }
        override fun close() { encoder.close();output.close() }
    }

    fun encode(uri: String,range: SourceInterval,file: File,ensureCurrent: ()->Unit,
        progress: (Double)->Unit): ComparisonEncodedAudio {
        val extractor=MediaExtractor();var decoder: MediaCodec?=null;var writer: Writer?=null
        val duration=range.endUs-range.startUs
        try {
            val source=Uri.parse(uri)
            if (source.scheme=="content") extractor.setDataSource(context,source,null)
            else extractor.setDataSource(source.path ?: error("源视频不可用"))
            val track=(0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
            }
            if (track==null) {
                writer=Writer(file,48000,2,duration,ensureCurrent,progress)
                return writer.finish()
            }
            extractor.selectTrack(track)
            val inputFormat=extractor.getTrackFormat(track)
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
            extractor.seekTo(range.startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            decoder=MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inputFormat,null,null,0);decoder.start()
            val info=MediaCodec.BufferInfo()
            var inputEnded=false;var outputEnded=false
            var channels=0;var rate=0;var encoding=AudioFormat.ENCODING_PCM_16BIT
            var lastOutput=SystemClock.elapsedRealtime()
            fun configure(format: MediaFormat) {
                val newRate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val newChannels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                require(newRate in 8000..96000 && newChannels in 1..8)
                check(rate==0 || (rate==newRate && channels==newChannels)) { "参考音轨格式发生变化" }
                rate=newRate;channels=newChannels
                encoding=if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    else AudioFormat.ENCODING_PCM_16BIT
                require(encoding in listOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT))
                if (writer==null) writer=Writer(file,rate,if (channels<=2) channels else 1,duration,ensureCurrent,progress)
            }
            while (!outputEnded) {
                ensureCurrent()
                if (!inputEnded) {
                    val index=decoder.dequeueInputBuffer(0)
                    if (index>=0) {
                        val bytes=decoder.getInputBuffer(index)!!;bytes.clear()
                        val size=if (extractor.sampleTime>=range.endUs) -1 else extractor.readSampleData(bytes,0)
                        if (size<0) {
                            decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true
                        } else {
                            decoder.queueInputBuffer(index,0,size,extractor.sampleTime,0);extractor.advance()
                        }
                    }
                }
                val index=decoder.dequeueOutputBuffer(info,5000)
                if (index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) configure(decoder.outputFormat)
                if (index>=0) {
                    lastOutput=SystemClock.elapsedRealtime()
                    try {
                        if (info.size>0) {
                            if (writer==null) configure(decoder.outputFormat)
                            val bytes=if (encoding==AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames=info.size/(bytes*channels)
                            val slice=ComparisonExportAudioTiming.slice(info.presentationTimeUs,frames,range,rate)
                            val out=writer!!
                            if (slice.frames>0) {
                                out.silence(slice.outputFrame)
                                val overlap=(out.cursor-slice.outputFrame).coerceIn(0,slice.frames.toLong()).toInt()
                                val count=slice.frames-overlap
                                val pcm=decoder.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                                pcm.position(info.offset+(slice.skip+overlap)*channels*bytes)
                                val samples=ShortArray(count*out.channels)
                                for (frame in 0 until count) {
                                    var mixed=0L
                                    for (channel in 0 until channels) {
                                        val sample=if (encoding==AudioFormat.ENCODING_PCM_FLOAT)
                                            (pcm.float*32768f).toInt().coerceIn(-32768,32767) else pcm.short.toInt()
                                        if (channels<=2) samples[frame*out.channels+channel]=sample.toShort()
                                        else mixed+=sample
                                    }
                                    if (channels>2) samples[frame]=(mixed/channels).toInt().toShort()
                                }
                                out.samples(samples,count)
                            }
                        }
                        outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0 ||
                            info.presentationTimeUs>=range.endUs || (writer?.cursor ?: 0)>= (writer?.total ?: Long.MAX_VALUE)
                    } finally { decoder.releaseOutputBuffer(index,false) }
                }
                check(SystemClock.elapsedRealtime()-lastOutput<5000) { "参考音轨解码超时" }
            }
            if (writer==null) configure(inputFormat)
            return writer!!.finish()
        } finally {
            runCatching { decoder?.stop() };runCatching { decoder?.release() };extractor.release();writer?.close()
        }
    }
}

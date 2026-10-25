package art.gaoge.dance.engine.comparison

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decode only the selected audio track; streaming resampling retains source-time origin. */
@androidx.annotation.OptIn(markerClass=[UnstableApi::class])
internal class ComparisonPcmDecoder(private val context: Context) {
    fun features(uriString: String,current: ()->Boolean): MusicFeatures {
        val extractor=MediaExtractor()
        var codec: MediaCodec?=null
        var sonic: SonicAudioProcessor?=null
        var builder: ComparisonMusicFeatureBuilder?=null
        try {
            val uri=Uri.parse(uriString)
            if (uri.scheme=="content") extractor.setDataSource(context,uri,null)
            else extractor.setDataSource(uri.path ?: error("源文件不可用"))
            val track=(0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
            } ?: throw ComparisonNoAudio()
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            val mime=format.getString(MediaFormat.KEY_MIME) ?: throw ComparisonNoAudio()
            format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
            codec=MediaCodec.createDecoderByType(mime)
            codec.configure(format,null,null,0);codec.start()
            val info=MediaCodec.BufferInfo()
            var channels=0;var inputRate=0;var encoding=AudioFormat.ENCODING_PCM_16BIT
            var inputEnded=false;var outputEnded=false;var lastOutput=SystemClock.elapsedRealtime()
            fun configure(output: MediaFormat) {
                val nextRate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val nextChannels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                check(channels==0 || (channels==nextChannels && inputRate==nextRate)) { "音频格式发生变化，请手动校准。" }
                channels=nextChannels;inputRate=nextRate
                encoding=if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                check(channels in 1..8 && encoding in listOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT))
                if (sonic==null) sonic=ComparisonMusicResampling.create(inputRate)
            }
            fun drain() {
                val output=sonic!!.output.order(ByteOrder.nativeOrder())
                while (output.remaining()>=2) builder!!.add(output.short/32768f)
            }
            while (!outputEnded) {
                if (!current()) throw ComparisonAlignmentCancelled()
                if (!inputEnded) {
                    val i=codec.dequeueInputBuffer(0)
                    if (i>=0) {
                        val buffer=codec.getInputBuffer(i)!!;buffer.clear()
                        val size=extractor.readSampleData(buffer,0)
                        if (size<0) { codec.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true }
                        else { codec.queueInputBuffer(i,0,size,extractor.sampleTime,0);extractor.advance() }
                    }
                }
                val index=codec.dequeueOutputBuffer(info,5000)
                if (index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) configure(codec.outputFormat)
                if (index>=0) {
                    lastOutput=SystemClock.elapsedRealtime()
                    try {
                        if (info.size>0) {
                            if (sonic==null) configure(codec.outputFormat)
                            if (builder==null) builder=ComparisonMusicFeatureBuilder(info.presentationTimeUs)
                            val output=codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                            output.position(info.offset);output.limit(info.offset+info.size)
                            val bytes=if (encoding==AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val count=info.size/(bytes*channels)
                            val mono=ByteBuffer.allocateDirect(count*2).order(ByteOrder.nativeOrder())
                            repeat(count) {
                                var sum=0.0
                                repeat(channels) { sum+=if (encoding==AudioFormat.ENCODING_PCM_FLOAT) output.float*32768.0 else output.short.toDouble() }
                                mono.putShort((sum/channels).toInt().coerceIn(-32768,32767).toShort())
                            }
                            mono.flip()
                            while (mono.hasRemaining()) { sonic!!.queueInput(mono);drain() }
                        }
                        outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    } finally { codec.releaseOutputBuffer(index,false) }
                }
                check(SystemClock.elapsedRealtime()-lastOutput<5000) { "音频解码超时，请手动校准。" }
            }
            if (builder==null || sonic==null) throw ComparisonNoAudio()
            sonic!!.queueEndOfStream();drain()
            if (!current()) throw ComparisonAlignmentCancelled()
            return builder!!.finish()
        } finally {
            runCatching { codec?.stop() };runCatching { codec?.release() }
            sonic?.reset();extractor.release()
        }
    }
}


/** Media3 1.9+ requires the stream-metadata overload; no-argument flush throws. */
@androidx.annotation.OptIn(markerClass=[UnstableApi::class])
internal object ComparisonMusicResampling {
    fun create(inputRate: Int) = SonicAudioProcessor().apply {
        setOutputSampleRateHz(ComparisonMusicFeatureBuilder.RATE)
        configure(AudioProcessor.AudioFormat(inputRate,1,C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }
}

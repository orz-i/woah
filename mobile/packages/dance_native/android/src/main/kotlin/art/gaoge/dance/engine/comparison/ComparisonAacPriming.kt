package art.gaoge.dance.engine.comparison

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class ComparisonAacDelay(val codecName: String,val delayUs: Long)

/** Some AAC encoders timestamp their priming PCM as content and omit encoder-delay.
 * Measure the selected codec with a short, known impulse once per audio configuration.
 * The probe never uses either dance video's music or changes the music mapping.
 */
internal object ComparisonAacPriming {
    private data class Packet(val pts: Long,val bytes: ByteArray)
    private val cached=mutableMapOf<Pair<Int,Int>,ComparisonAacDelay>()
    fun format(rate: Int,channels: Int)=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,rate,channels).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE,minOf(if (channels==1) 96_000 else 192_000,rate*6*channels))
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192)
    }
    @Synchronized fun measure(rate: Int,channels: Int,current: ()->Unit): ComparisonAacDelay {
        current();cached[rate to channels]?.let { return it }
        val packets=mutableListOf<Packet>();var encoded: MediaFormat?=null
        val encoder=ComparisonExportEncoder(format(rate,channels),false,current,{ encoded=it },{ bytes,info ->
            check(packets.size<100)
            val copy=ByteArray(info.size);bytes.get(copy);packets.add(Packet(info.presentationTimeUs,copy))
        })
        val name=encoder.name
        try {
            val pulse=ShortArray(12288*channels)
            for (channel in 0 until channels) pulse[4096*channels+channel]=24576
            encoder.pcm(pulse,12288,channels,0,rate);encoder.endAudio(12288*1_000_000L/rate)
        } finally { encoder.close() }
        val input=requireNotNull(encoded).apply {
            // Observe the raw AAC delay, independently of gapless playback metadata.
            setInteger(MediaFormat.KEY_ENCODER_DELAY,0);setInteger(MediaFormat.KEY_ENCODER_PADDING,0)
            setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
        }
        val decoder=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var started=false;var peak=0;var peakUs=0L
        try {
            decoder.configure(input,null,null,0);decoder.start();started=true
            var sent=0;var ended=false;var eos=false
            val info=MediaCodec.BufferInfo();val deadline=System.nanoTime()+5_000_000_000L
            while (!eos) {
                current();check(System.nanoTime()<deadline) { "AAC 编码延迟检查超时" }
                if (!ended) {
                    val index=decoder.dequeueInputBuffer(0)
                    if (index>=0) {
                        if (sent==packets.size) {
                            decoder.queueInputBuffer(index,0,0,12288*1_000_000L/rate,MediaCodec.BUFFER_FLAG_END_OF_STREAM);ended=true
                        } else {
                            val packet=packets[sent++];val buffer=decoder.getInputBuffer(index)!!;buffer.clear();buffer.put(packet.bytes)
                            decoder.queueInputBuffer(index,0,packet.bytes.size,packet.pts,0)
                        }
                    }
                }
                val index=decoder.dequeueOutputBuffer(info,5000)
                if (index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out=decoder.outputFormat
                    check(out.getInteger(MediaFormat.KEY_SAMPLE_RATE)==rate && out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==channels)
                    check(!out.containsKey(MediaFormat.KEY_PCM_ENCODING) || out.getInteger(MediaFormat.KEY_PCM_ENCODING)==AudioFormat.ENCODING_PCM_16BIT)
                }
                if (index>=0) {
                    try {
                        if (info.size>0) {
                        val buffer=decoder.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder());buffer.position(info.offset)
                        for (frame in 0 until info.size/(2*channels)) for (channel in 0 until channels) {
                            val value=abs(buffer.short.toInt())
                            if (value>peak) { peak=value;peakUs=info.presentationTimeUs+(frame*1_000_000.0/rate).roundToLong() }
                        }
                        }
                        eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    } finally { decoder.releaseOutputBuffer(index,false) }
                }
            }
            val delay=peakUs-(4096*1_000_000.0/rate).roundToLong()
            check(peak>2000 && delay in -100..(8192*1_000_000L/rate)) { "无法确认 AAC 编码延迟" }
            return ComparisonAacDelay(name,delay).also { cached[rate to channels]=it }
        } finally { if (started) runCatching { decoder.stop() };decoder.release() }
    }
}

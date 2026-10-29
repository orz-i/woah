package art.gaoge.dance.engine.comparison

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded codec I/O shared by the comparison's AAC and surface H.264 encoders. */
internal class ComparisonExportEncoder(
    format: MediaFormat,
    surfaceInput: Boolean,
    private val ensureCurrent: ()->Unit,
    private val onFormat: (MediaFormat)->Unit,
    private val onSample: (ByteBuffer,MediaCodec.BufferInfo)->Unit,
    codecName: String?=null,
) : AutoCloseable {
    private val codec=if (codecName==null) MediaCodec.createEncoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        else MediaCodec.createByCodecName(codecName)
    val name: String=codec.name
    private val info=MediaCodec.BufferInfo()
    val surface: Surface?
    private var started=false
    private var eos=false
    private var formatted=false
    init {
        var inputSurface: Surface?=null
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface=if (surfaceInput) codec.createInputSurface() else null
            surface=inputSurface
            codec.start();started=true
        } catch (e: Throwable) { runCatching { inputSurface?.release() };runCatching { codec.release() };throw e }
    }
    fun drain(untilEnd: Boolean=false) {
        var deadline=System.nanoTime()+10_000_000_000L
        while (!eos) {
            ensureCurrent()
            when (val index=codec.dequeueOutputBuffer(info,if (untilEnd) 10_000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEnd) return
                    check(System.nanoTime()<deadline) { "编码器未能完成输出" }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!formatted) { "编码格式发生变化" };formatted=true;onFormat(codec.outputFormat)
                }
                else -> if (index>=0) {
                    try {
                        if (info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                            val bytes=codec.getOutputBuffer(index)!!
                            bytes.position(info.offset);bytes.limit(info.offset+info.size)
                            onSample(bytes,info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0) eos=true
                        deadline=System.nanoTime()+10_000_000_000L
                    } finally { codec.releaseOutputBuffer(index,false) }
                }
            }
        }
    }
    private fun input(): Int {
        val deadline=System.nanoTime()+5_000_000_000L
        while (true) {
            ensureCurrent();drain()
            val index=codec.dequeueInputBuffer(10_000)
            if (index>=0) return index
            check(System.nanoTime()<deadline) { "音轨编码器未响应" }
        }
    }
    fun pcm(samples: ShortArray,frames: Int,channels: Int,firstFrame: Long,rate: Int) {
        var frame=0
        while (frame<frames) {
            val index=input();val buffer=codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
            buffer.clear()
            val count=minOf(frames-frame,buffer.remaining()/(channels*2))
            check(count>0)
            for (i in frame*channels until (frame+count)*channels) buffer.putShort(samples[i])
            codec.queueInputBuffer(index,0,count*channels*2,(firstFrame+frame)*1_000_000L/rate,0)
            frame+=count;drain()
        }
    }
    fun endAudio(timeUs: Long) {
        val index=input();codec.queueInputBuffer(index,0,0,timeUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM);drain(true)
    }
    fun endVideo() { codec.signalEndOfInputStream();drain(true) }
    override fun close() {
        if (started) runCatching { codec.stop() }
        runCatching { codec.release() };runCatching { surface?.release() };started=false
    }
}

package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.core.FrameKernel
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class ComparisonAlignmentCancelled : RuntimeException("匹配已取消")
internal class ComparisonAlignmentTooLong : RuntimeException("自动匹配暂支持十分钟内的视频；可以使用手动校准。")
internal class ComparisonDiscontinuousAudio : RuntimeException("音轨时间不连续，请手动校准。")
internal class ComparisonNoAudio : RuntimeException("视频没有音轨")
internal data class MusicFrame(val timeUs:Long,val spectrum:FloatArray,val onset:FloatArray,val peaks:IntArray,val pitchSpectrum:FloatArray=FloatArray(0))

/** Versioned feature replay, not an independent native feature implementation. */
internal class MusicFeatures private constructor(val payload:ByteArray,val distinctHashes:Int=0) {
    private val data get()=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val count:Int get()=data.getInt(8)
    val startUs:Long get()=if(count==0)0L else data.getLong(12)
    val endUs:Long get()=(if(count==0)0L else data.getLong(12+(count-1)*348))+10_000L
    val frames:List<MusicFrame> = object:AbstractList<MusicFrame>() {
        override val size:Int get()=count
        override fun get(index:Int):MusicFrame {
            require(index in 0 until count)
            val b=data;b.position(12+index*348)
            val time=b.long;val spectrum=FloatArray(16){b.float};val onset=FloatArray(16){b.float}
            val n=b.int;val peaks=IntArray(4){b.int}.take(n).toIntArray();val pitch=FloatArray(48){b.float}
            return MusicFrame(time,spectrum,onset,peaks,pitch)
        }
    }
    constructor(frames:List<MusicFrame>,distinctHashes:Int=0):this(pack(frames),distinctHashes)
    companion object {
        fun fromPayload(payload:ByteArray)=MusicFeatures(payload)
        private fun pack(frames:List<MusicFrame>):ByteArray {
            val b=ByteBuffer.allocate(12+frames.size*348).order(ByteOrder.LITTLE_ENDIAN)
            b.put(byteArrayOf(70,75,77,70,1,0,0,0));b.putInt(frames.size)
            for(f in frames) {
                require(f.spectrum.size==16 && f.onset.size==16 && f.peaks.size<=4)
                b.putLong(f.timeUs);f.spectrum.forEach{b.putFloat(it)};f.onset.forEach{b.putFloat(it)}
                b.putInt(f.peaks.size);repeat(4){b.putInt(f.peaks.getOrElse(it){0})}
                repeat(48){b.putFloat(f.pitchSpectrum.getOrElse(it){0f})}
            }
            return b.array()
        }
    }
}

internal class ComparisonMusicFeatureBuilder(originUs:Long):AutoCloseable {
    companion object {const val RATE=16000;const val HOP=160;const val FFT=1024;const val MAX_FRAMES=60_000}
    private var id=FrameKernel.musicCreateNative(originUs)
    private val pending=FloatArray(8192)
    private var count=0
    init {check(id!=0L)}
    private fun checked(code:Int) {
        when(code) {0->Unit;-2->throw ComparisonAlignmentTooLong();-3->throw ComparisonDiscontinuousAudio();-4->throw ComparisonAlignmentCancelled();else->throw IllegalArgumentException("Invalid shared audio input")}
    }
    fun add(value:Float) {
        require(value.isFinite());check(id!=0L)
        pending[count++]=value;if(count==pending.size)flush()
    }
    private fun flush() {
        if(count>0) {checked(FrameKernel.musicPushNative(id,pending.copyOf(count),0,RATE,1,0));count=0}
    }
    fun addPcm(values:FloatArray,rate:Int,channels:Int,timeUs:Long) {
        check(id!=0L && count==0)
        checked(FrameKernel.musicPushNative(id,values,1,rate,channels,timeUs))
    }
    fun finish():MusicFeatures {
        try {flush();return MusicFeatures.fromPayload(FrameKernel.musicFinishNative(id))}
        finally {close()}
    }
    override fun close() {if(id!=0L){FrameKernel.musicDestroyNative(id);id=0L}}
}

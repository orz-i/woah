package art.gaoge.dance.engine.comparison

import kotlin.math.*

internal class ComparisonAlignmentCancelled : RuntimeException("匹配已取消")
internal class ComparisonNoAudio : RuntimeException("视频没有音轨")
internal data class MusicFrame(val timeUs: Long,val spectrum: FloatArray,val onset: FloatArray,val peaks: IntArray)
internal data class MusicFeatures(val frames: List<MusicFrame>,val distinctHashes: Int=0) {
    val startUs get()=frames.firstOrNull()?.timeUs ?: 0L
    val endUs get()=(frames.lastOrNull()?.timeUs ?: 0L)+10_000
}

/** Streaming mono 16kHz PCM -> 10ms features. No song database or ML runtime. */
internal class ComparisonMusicFeatureBuilder(private val originUs: Long) {
    companion object { const val RATE=16000;const val HOP=160;const val FFT=1024;const val MAX_FRAMES=60_000 }
    private val ring=DoubleArray(FFT)
    private val real=DoubleArray(FFT)
    private val imaginary=DoubleArray(FFT)
    private val window=DoubleArray(FFT) { .5-.5*cos(2*PI*it/(FFT-1)) }
    private val cosine=DoubleArray(FFT/2) { cos(-2*PI*it/FFT) }
    private val sine=DoubleArray(FFT/2) { sin(-2*PI*it/FFT) }
    private val reverse=IntArray(FFT) { Integer.reverse(it) ushr 22 }
    private val edges=intArrayOf(2,4,6,9,13,19,27,38,54,76,108,152,214,300,380,450,512)
    private val frames=ArrayList<MusicFrame>()
    private var samples=0L
    private var previous=FloatArray(16)
    fun add(value: Float) {
        ring[(samples%FFT).toInt()]=value.toDouble();samples++
        if (samples>=FFT && (samples-FFT)%HOP==0L) extract()
    }
    private fun extract() {
        check(frames.size<MAX_FRAMES) { "自动匹配暂支持十分钟内的视频；可以使用手动校准。" }
        val first=(samples%FFT).toInt()
        for (i in 0 until FFT) { real[reverse[i]]=ring[(first+i)%FFT]*window[i];imaginary[i]=0.0 }
        var length=2
        while (length<=FFT) {
            val half=length/2;val stride=FFT/length
            var start=0
            while (start<FFT) {
                for (j in 0 until half) {
                    val p=start+j;val q=p+half;val c=cosine[j*stride];val s=sine[j*stride]
                    val r=real[q]*c-imaginary[q]*s;val im=real[q]*s+imaginary[q]*c
                    real[q]=real[p]-r;imaginary[q]=imaginary[p]-im
                    real[p]+=r;imaginary[p]+=im
                }
                start+=length
            }
            length*=2
        }
        val power=DoubleArray(FFT/2) { real[it]*real[it]+imaginary[it]*imaginary[it] }
        val bands=FloatArray(16) { band ->
            var sum=0.0
            for (i in edges[band] until edges[band+1]) sum+=power[i]
            ln(1+sum/(edges[band+1]-edges[band])*100).toFloat()
        }
        val onset=FloatArray(16) { max(0f,bands[it]-previous[it]) }
        previous=bands.copyOf()
        val mean=power.average()
        val peaks=(4 until 500).filter { power[it]>power[it-1] && power[it]>=power[it+1] && power[it]>mean*3 }
            .sortedByDescending { power[it] }.take(4).toIntArray()
        frames.add(MusicFrame(originUs+(samples-FFT/2)*1_000_000/RATE,bands,onset,peaks))
    }
    fun finish(): MusicFeatures {
        if (frames.isEmpty()) return MusicFeatures(emptyList())
        val means=DoubleArray(16);val deviations=DoubleArray(16)
        for (row in frames) for (b in 0..15) means[b]+=row.spectrum[b]
        for (b in 0..15) means[b]/=frames.size
        for (row in frames) for (b in 0..15) deviations[b]+=(row.spectrum[b]-means[b]).pow(2)
        for (b in 0..15) deviations[b]=sqrt(deviations[b]/frames.size).coerceAtLeast(.25)
        for (row in frames) {
            for (b in 0..15) row.spectrum[b]=((row.spectrum[b]-means[b])/deviations[b]).toFloat()
            normalize(row.spectrum);normalize(row.onset)
        }
        return MusicFeatures(frames)
    }
    private fun normalize(values: FloatArray) {
        val norm=sqrt(values.sumOf { it.toDouble()*it })
        if (norm>1e-8) for (i in values.indices) values[i]=(values[i]/norm).toFloat()
    }
}

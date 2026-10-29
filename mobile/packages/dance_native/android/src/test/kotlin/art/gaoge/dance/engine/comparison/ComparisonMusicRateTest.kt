package art.gaoge.dance.engine.comparison

import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class ComparisonMusicRateTest {
    private fun music(seconds: Double,rate: Double=1.0,preservePitch: Boolean=true,seed: Int=7): FloatArray {
        val random=Random(seed)
        val notes=DoubleArray(400) { 160.0+random.nextInt(800) }
        return FloatArray((seconds*16000).toInt()) { i ->
            val t=i/16000.0;val beat=(t*rate*8).toInt().coerceAtMost(notes.lastIndex)
            val phase=t*(if (preservePitch) 1.0 else rate)*notes[beat]*2*PI
            val e=(1-(t*rate*8)%1).pow(.4)
            (e*(.25*sin(phase)+.12*sin(phase*1.5)+.07*sin(phase*2.23))).toFloat()
        }
    }
    private fun features(pcm: FloatArray,origin: Long=0): MusicFeatures {
        val b=ComparisonMusicFeatureBuilder(origin);pcm.forEach(b::add);return b.finish()
    }
    private fun verify(rate: Double,pitch: Boolean,delay: Int=11200,origin: Long=30_000) {
        val original=music(20.0)
        val changed=music(20.0/rate,rate,pitch)
        val target=FloatArray(delay+changed.size) { if (it<delay) 0f else changed[it-delay]*.6f }
        val result=ComparisonMusicMatcher.match(features(original,origin),features(target,90_000))
        assertEquals("matched",result.status,"rate=$rate pitch=$pitch $result")
        val best=result.candidates.first();val a=1/rate;val b=90_000+delay/16000.0*1e6-a*origin
        for (t in listOf(2_000_000L,10_000_000L,18_000_000L))
            assertTrue(abs(best.timeScale*t+best.offsetUs-(a*t+b))<35_000,"t=$t rate=$rate pitch=$pitch $result")
    }
    @Test fun slightClockDriftIsAContinuousRate() = verify(1.007,true)
    @Test fun negativeInterceptAndNonzeroSourceOrigins() = verify(1.25,false,11200,5_000_000)
    @Test fun fasterMusicWithPitchPreserved() = verify(1.25,true)
    @Test fun slowMusicWithPitchPreserved() = verify(.75,true)
    @Test fun nonPresetMusicRate() = verify(.83,true)
    @Test fun speedChangeThatAlsoShiftsPitch() = verify(1.25,false)
    @Test fun slowMusicThatAlsoShiftsPitch() = verify(.75,false)
    @Test fun aHalfSpeedVersion() = verify(.5,true)
    @Test fun aDoubleSpeedVersion() = verify(2.0,false)
    @Test fun changingTempoCannotBecomeOneConfidentMapping() {
        val song=music(20.0)
        val first=music(10.0,1.0)
        val second=music(20.0/1.25,1.25).copyOfRange((10.0/1.25*16000).toInt(),(20.0/1.25*16000).toInt())
        val result=ComparisonMusicMatcher.match(features(song),features(first+second))
        assertNotEquals("matched",result.status,result.toString())
    }
    @Test fun affineOverlapAndInversePreserveMusicCorrespondence() {
        val a=ComparisonMusicMapping(1_200_000,.8)
        val inverse=a.inverse()
        for (r in listOf(0L,2_033_333L,17_999_999L))
            assertTrue(abs(inverse.learnerTimeUs(a.learnerTimeUs(r))-r)<=1)
        val range=ComparisonTimeMapping.overlap(SourceInterval(0,20_000_000),SourceInterval(0,15_000_000),a.offsetUs,a.timeScale)!!
        assertTrue(range.mapping.learnerTimeUs(range.teacherEndUs-1)<15_000_000)
        val before=a.learnerTimeUs(5_000_000)
        val lateMove=before+120_000
        assertEquals(120_000,lateMove-before) // no motion signal can influence mapping
    }
    @Test fun invalidScaleNeverReachesDecoders() {
        for (scale in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,.49,2.01))
            assertFailsWith<IllegalArgumentException> { ComparisonMusicMapping(0,scale) }
    }
}

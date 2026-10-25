package art.gaoge.dance.engine.comparison

import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class ComparisonMusicMatcherTest {
    private fun music(seconds: Int,seed: Int=7): FloatArray {
        val random=Random(seed)
        val notes=DoubleArray(seconds*8) { 160.0+random.nextInt(800) }
        return FloatArray(seconds*16000) { i ->
            val t=i/16000.0;val beat=(t*8).toInt().coerceAtMost(notes.lastIndex)
            val phase=t*notes[beat]*2*PI
            val envelope=(1-(t*8)%1).pow(.4)
            (envelope*(.25*sin(phase)+.12*sin(phase*1.5)+.07*sin(phase*2.23))+
                .03*random.nextDouble(-1.0,1.0)*exp(-((t*4)%1)*20)).toFloat()
        }
    }
    private fun features(pcm: FloatArray,originUs: Long=0): MusicFeatures {
        val builder=ComparisonMusicFeatureBuilder(originUs)
        pcm.forEach(builder::add)
        return builder.finish()
    }
    @Test fun findsPositiveMusicOffsetDespiteGainAndNoiseWithoutUsingVideo() {
        val original=music(9)
        val random=Random(18)
        val learner=FloatArray(16000+original.size) { i -> if (i<16000) 0f else
            original[i-16000]*.55f+(random.nextFloat()-.5f)*.003f }
        val result=ComparisonMusicMatcher.match(features(original),features(learner))
        assertEquals("matched",result.status,result.toString())
        assertTrue(abs(result.candidates.first().offsetUs-1_000_000)<25_000,result.toString())
        assertTrue(result.candidates.first().consistentWindows>=2)
    }
    @Test fun retainsNegativeOffsetAndNonzeroAudioSourceOrigins() {
        val original=music(8)
        val teacher=FloatArray(16000+original.size) { i -> if (i<16000) 0f else original[i-16000] }
        val result=ComparisonMusicMatcher.match(features(teacher,30_000),features(original,10_000))
        assertEquals("matched",result.status,result.toString())
        assertTrue(abs(result.candidates.first().offsetUs+1_020_000)<25_000,result.toString())
    }
    @Test fun rejectsSilenceAndUnrelatedMusic() {
        val reference=features(music(7))
        assertEquals("no_match",ComparisonMusicMatcher.match(reference,features(FloatArray(112000))).status)
        assertEquals("no_match",ComparisonMusicMatcher.match(reference,features(music(7,982))).status)
    }
    @Test fun repeatedMusicReturnsAmbiguityInsteadOfInventingConfidence() {
        val motif=music(4)
        val teacher=FloatArray(motif.size*3) { motif[it%motif.size] }
        val learner=FloatArray(16000+motif.size*4) { if (it<16000) 0f else motif[(it-16000)%motif.size] }
        val result=ComparisonMusicMatcher.match(features(teacher),features(learner))
        assertEquals("ambiguous",result.status,result.toString())
        assertTrue(result.candidates.size>=2)
    }
    @Test fun findsAPartiallyOverlappingMusicPassageWithDifferentSourceTrims() {
        val song=music(18)
        val teacher=song.copyOfRange(2*16000,13*16000)
        val learner=FloatArray(11200+11*16000) { if (it<11200) 0f else song[5*16000+it-11200] }
        val result=ComparisonMusicMatcher.match(features(teacher),features(learner))
        assertEquals("matched",result.status,result.toString())
        assertTrue(abs(result.candidates.first().offsetUs+2_300_000)<25_000,result.toString())
    }
    @Test fun aTempoChangeCannotBePublishedAsAConfidentConstantOffset() {
        val song=music(12)
        val changed=FloatArray((song.size/1.025).toInt()) { song[(it*1.025).toInt()] }
        val result=ComparisonMusicMatcher.match(features(song),features(changed))
        assertNotEquals("matched",result.status,result.toString())
    }
    @Test fun cancellationPropagatesBeforePublishingAMatch() {
        val sample=features(music(4))
        assertFailsWith<ComparisonAlignmentCancelled> {
            ComparisonMusicMatcher.match(sample,sample) { false }
        }
    }
}

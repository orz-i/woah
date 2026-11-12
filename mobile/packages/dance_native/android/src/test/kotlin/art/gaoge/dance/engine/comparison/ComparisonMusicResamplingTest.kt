package art.gaoge.dance.engine.comparison

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ComparisonMusicResamplingTest {
    @Test fun sameRatePcmUsesTheSameFeatureBytesAsNormalizedSamples() {
        val pcm=FloatArray(8192) { sin(it*.17).toFloat()*.5f }
        val direct=ComparisonMusicFeatureBuilder(123000).use { b->pcm.forEach(b::add);b.finish() }
        val decoded=ComparisonMusicFeatureBuilder(0).use { b->b.addPcm(pcm,16000,1,123000);b.finish() }
        assertTrue(direct.payload.contentEquals(decoded.payload))
    }
    @Test fun sourceRatesAndChunkBoundariesKeepOriginAndTailFrames() {
        for(rate in listOf(44100,48000)) {
            val pcm=FloatArray(rate) { sin(it*.07).toFloat()*.25f }
            val whole=ComparisonMusicFeatureBuilder(0).use { b->b.addPcm(pcm,rate,1,321000);b.finish() }
            val chunks=ComparisonMusicFeatureBuilder(0).use { b->
                for(start in pcm.indices step 317) b.addPcm(pcm.copyOfRange(start,minOf(pcm.size,start+317)),rate,1,321000+start*1_000_000L/rate)
                b.finish()
            }
            assertTrue(whole.payload.contentEquals(chunks.payload))
            assertEquals(94,whole.count)
            assertEquals(353000L,whole.startUs)
            assertEquals(1293000L,whole.endUs)
        }
    }
    @Test fun nativePcmGapsAndFormatChangesNeverBecomeMusic() {
        ComparisonMusicFeatureBuilder(0).use { b->
            b.addPcm(FloatArray(1600),16000,1,5_000_000)
            b.addPcm(FloatArray(1600),16000,1,5_100_005)
            assertFailsWith<ComparisonDiscontinuousAudio> { b.addPcm(FloatArray(1600),16000,1,5_500_000) }
        }
        ComparisonMusicFeatureBuilder(0).use { b->
            b.addPcm(FloatArray(480),48000,1,0)
            assertFailsWith<ComparisonDiscontinuousAudio> { b.addPcm(FloatArray(441),44100,1,10000) }
        }
    }
}

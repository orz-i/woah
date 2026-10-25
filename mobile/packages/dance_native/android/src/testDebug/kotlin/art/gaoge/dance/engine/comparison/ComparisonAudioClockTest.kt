package art.gaoge.dance.engine.comparison

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

class ComparisonAudioClockTest {
    @Test fun resamplesTenMillisecondPublicationsAtDisplayCadence() {
        val clock=ComparisonAudioClock()
        var previous=0L
        for (i in 0 until 180) {
            val ns=i*16_666_667L
            val raw=(ns/10_000_000L)*10_000L
            val actual=clock.sample(raw,ns,1f)
            assertTrue(abs(actual-ns/1000)<2L)
            if (i>0) assertTrue(actual-previous in 16_665L..16_669L)
            previous=actual
        }
    }
    @Test fun aStalledPublishedClockCannotRunAway() {
        val clock=ComparisonAudioClock()
        for (i in 0 until 180) {
            val result=clock.sample(1_000_000L,i*16_666_667L,1f)
            assertTrue(result in 1_000_000L..1_015_000L)
        }
        assertTrue(clock.maxAheadUs<=15_000L)
    }
    @Test fun seekResetDiscardsOldAnchors() {
        val clock=ComparisonAudioClock()
        clock.sample(20_000_000,0,1f)
        clock.reset()
        assertEquals(1_000_000L,clock.sample(1_000_000,1_000_000_000,1f))
    }
    @Test fun speedChangeResetsAndThenFollowsHalfSpeed() {
        val clock=ComparisonAudioClock()
        clock.sample(1_000_000,0,1f)
        for (i in 0 until 90) {
            val ns=2_000_000_000L+i*16_666_667L
            val raw=3_000_000L+((ns-2_000_000_000L)/10_000_000L)*5000L
            val result=clock.sample(raw,ns,.5f)
            assertTrue(abs(result-(3_000_000L+(ns-2_000_000_000L)/2000L))<2L)
        }
    }
}

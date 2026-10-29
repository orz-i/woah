package art.gaoge.dance.engine.comparison

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ComparisonMusicResamplingTest {
    @Test fun sameRatePcmBypassesInactiveSonicWithoutLosingSamples() {
        val processor=ComparisonMusicResampling.create(16000)
        val input=java.nio.ByteBuffer.allocateDirect(10).order(java.nio.ByteOrder.nativeOrder())
        val samples=listOf(-32768,-16000,0,16000,32767)
        samples.forEach { input.putShort(it.toShort()) };input.flip()
        val output=ArrayList<Float>()
        ComparisonMusicResampling.queue(processor,input,output::add)
        ComparisonMusicResampling.end(processor,output::add)
        assertEquals(samples.map { it/32768f },output)
        processor.reset()
    }
    @Test fun resamplesBothRealSourceRatesAndDrainsTheirTails() {
        for (rate in listOf(44100,48000)) {
            val processor=ComparisonMusicResampling.create(rate)
            val input=ByteBuffer.allocateDirect(rate*2).order(ByteOrder.nativeOrder())
            repeat(rate) { input.putShort(if (it%100<50) 8000.toShort() else (-8000).toShort()) }
            input.flip()
            var count=0
            ComparisonMusicResampling.queue(processor,input) { count++ }
            ComparisonMusicResampling.end(processor) { count++ }
            assertTrue(count in 15998..16002,"$rate Hz -> $count output samples")
            processor.reset()
        }
    }
}

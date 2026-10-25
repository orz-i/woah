package art.gaoge.dance.engine.comparison

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertTrue

class ComparisonMusicResamplingTest {
    @Test fun resamplesBothRealSourceRatesAndDrainsTheirTails() {
        for (rate in listOf(44100,48000)) {
            val processor=ComparisonMusicResampling.create(rate)
            val input=ByteBuffer.allocateDirect(rate*2).order(ByteOrder.nativeOrder())
            repeat(rate) { input.putShort(if (it%100<50) 8000.toShort() else (-8000).toShort()) }
            input.flip()
            processor.queueInput(input)
            var count=processor.output.remaining()/2
            processor.queueEndOfStream()
            while (!processor.isEnded) count+=processor.output.remaining()/2
            assertTrue(count in 15998..16002,"$rate Hz -> $count output samples")
            processor.reset()
        }
    }
}

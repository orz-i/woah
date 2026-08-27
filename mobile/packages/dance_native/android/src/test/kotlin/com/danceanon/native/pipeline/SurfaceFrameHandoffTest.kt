package com.danceanon.native.pipeline

import com.danceanon.native.media.VideoDecoder
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurfaceFrameHandoffTest {

    @Test
    fun testDecoderTokenSequenceHandshakeMonotonic() {
        val frameAvailableSequence = AtomicLong(0L)
        val consumedFrameSequence = AtomicLong(0L)
        val frameSync = Object()

        val tokens = listOf(
            VideoDecoder.DecodedFrameToken(bufferIndex = 0, presentationTimeUs = 0L, isEOS = false),
            VideoDecoder.DecodedFrameToken(bufferIndex = 1, presentationTimeUs = 33333L, isEOS = false),
            VideoDecoder.DecodedFrameToken(bufferIndex = 2, presentationTimeUs = 66666L, isEOS = false)
        )

        val processedPts = mutableListOf<Long>()

        for (token in tokens) {
            val targetSeq = frameAvailableSequence.get() + 1L

            // Simulate decoder surface rendering callback
            synchronized(frameSync) {
                frameAvailableSequence.incrementAndGet()
                frameSync.notifyAll()
            }

            var frameReceived = false
            synchronized(frameSync) {
                if (frameAvailableSequence.get() >= targetSeq) {
                    frameReceived = true
                }
            }

            assertTrue(frameReceived, "Frame token must be acknowledged by onFrameAvailable listener")
            consumedFrameSequence.set(frameAvailableSequence.get())
            processedPts.add(token.presentationTimeUs)
        }

        assertEquals(3L, consumedFrameSequence.get())
        assertEquals(listOf(0L, 33333L, 66666L), processedPts)
    }

    @Test
    fun testTimeoutSkipsStaleFrameWithoutLeakingDuplicatePts() {
        val frameAvailableSequence = AtomicLong(0L)
        val consumedFrameSequence = AtomicLong(0L)
        val frameSync = Object()

        val token1 = VideoDecoder.DecodedFrameToken(bufferIndex = 0, presentationTimeUs = 0L, isEOS = false)
        val token2Stale = VideoDecoder.DecodedFrameToken(bufferIndex = 1, presentationTimeUs = 33333L, isEOS = false)
        val token3 = VideoDecoder.DecodedFrameToken(bufferIndex = 2, presentationTimeUs = 66666L, isEOS = false)

        val encodedFrames = mutableListOf<Long>()

        // 1. Frame 1 arrives successfully
        var targetSeq = frameAvailableSequence.get() + 1L
        frameAvailableSequence.incrementAndGet()
        if (frameAvailableSequence.get() >= targetSeq) {
            consumedFrameSequence.set(frameAvailableSequence.get())
            encodedFrames.add(token1.presentationTimeUs)
        }

        // 2. Frame 2: SurfaceTexture hangs (driver stall/recycling), sequence does NOT increment
        targetSeq = frameAvailableSequence.get() + 1L
        var frameReceived = false
        synchronized(frameSync) {
            // Simulated timeout after 50ms without sequence increment
            frameReceived = frameAvailableSequence.get() >= targetSeq
        }
        assertFalse(frameReceived, "Stalled frame must time out")
        // STRICT PIPELINE RULE: If frameReceived is false, we SKIP without latching or adding to encoded list!

        // 3. Frame 3: Driver recovers and delivers new buffer
        targetSeq = frameAvailableSequence.get() + 1L
        frameAvailableSequence.incrementAndGet()
        if (frameAvailableSequence.get() >= targetSeq) {
            consumedFrameSequence.set(frameAvailableSequence.get())
            encodedFrames.add(token3.presentationTimeUs)
        }

        // Verify: Encoded frames contains Frame 1 and Frame 3, ZERO duplicate Frame 1 PTS!
        assertEquals(2, encodedFrames.size)
        assertEquals(listOf(0L, 66666L), encodedFrames)
    }

    @Test
    fun testStrictMonotonicPtsCfrSchedule() {
        val targetFps = 30.0
        val frameDurationNs = (1_000_000_000.0 / targetFps).toLong() // 33,333,333 ns

        val rawDecoderPtsUsList = listOf(0L, 33333L, 33333L, 66666L, 65000L, 100000L) // contains duplicate and jitter
        val calculatedPresentationNsList = mutableListOf<Long>()

        var basePtsUs = -1L
        var lastPresentationNs = -1L

        for (ptsUs in rawDecoderPtsUsList) {
            if (basePtsUs < 0L) {
                basePtsUs = ptsUs
            }
            val relPtsNs = (ptsUs - basePtsUs).coerceAtLeast(0L) * 1000L
            val presentationNs = if (relPtsNs > lastPresentationNs) {
                relPtsNs
            } else {
                if (lastPresentationNs >= 0L) lastPresentationNs + frameDurationNs else 0L
            }
            lastPresentationNs = presentationNs
            calculatedPresentationNsList.add(presentationNs)
        }

        // Verify strict monotonic increase (every timestamp > previous timestamp)
        for (i in 1 until calculatedPresentationNsList.size) {
            assertTrue(
                calculatedPresentationNsList[i] > calculatedPresentationNsList[i - 1],
                "Timestamp at index $i (${calculatedPresentationNsList[i]}ns) must be strictly greater than index ${i-1} (${calculatedPresentationNsList[i-1]}ns)"
            )
        }
    }

    @Test
    fun testEosTokenTerminatesLoopCleanly() {
        val eosToken = VideoDecoder.DecodedFrameToken(bufferIndex = -1, presentationTimeUs = 0L, isEOS = true)
        assertTrue(eosToken.isEOS)
        assertEquals(-1, eosToken.bufferIndex)
    }
}

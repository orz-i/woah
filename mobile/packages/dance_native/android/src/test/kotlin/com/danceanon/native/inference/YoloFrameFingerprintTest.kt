package com.danceanon.native.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class YoloFrameFingerprintTest {
    @Test
    fun `byte fingerprints separate exact and coarse pixel changes`() {
        val base = ByteBuffer.allocate(4096)
        repeat(base.capacity()) { base.put(it, 0x21.toByte()) }

        val tinyChange = ByteBuffer.allocate(4096)
        repeat(tinyChange.capacity()) { tinyChange.put(it, 0x21.toByte()) }
        tinyChange.put(2049, 0x22.toByte())

        assertNotEquals(
            YoloFrameFingerprint.sampledByteHash(base),
            YoloFrameFingerprint.sampledByteHash(tinyChange)
        )
        assertEquals(
            YoloFrameFingerprint.sampledByteHash(base, shiftRight = 4),
            YoloFrameFingerprint.sampledByteHash(tinyChange, shiftRight = 4)
        )
    }

    @Test
    fun `float fingerprints expose quantization scale of divergence`() {
        val base = FloatArray(2048) { 0.5f }
        val close = base.copyOf().also { it[1024] = 0.5006f }

        assertEquals(
            YoloFrameFingerprint.sampledFloatHash(base, scale = 100.0),
            YoloFrameFingerprint.sampledFloatHash(close, scale = 100.0)
        )
        assertNotEquals(
            YoloFrameFingerprint.sampledFloatHash(base, scale = 1_000.0),
            YoloFrameFingerprint.sampledFloatHash(close, scale = 1_000.0)
        )
    }
}

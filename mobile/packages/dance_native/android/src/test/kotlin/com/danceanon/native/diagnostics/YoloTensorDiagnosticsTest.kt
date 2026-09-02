package com.danceanon.native.diagnostics

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class YoloTensorDiagnosticsTest {

    @Test
    fun associationMaskSummaryUsesTrackManagerThresholdWithoutMutatingBufferPosition() {
        val buffer = ByteBuffer.allocateDirect(6)
        buffer.put(byteArrayOf(0, 127, 128.toByte(), 129.toByte(), 130.toByte(), 255.toByte()))
        buffer.position(3)

        val beforePosition = buffer.position()
        val summary = YoloTensorDiagnostics.associationMaskSummary(buffer)

        assertEquals(beforePosition, buffer.position())
        assertEquals(3, summary.foregroundPixels)
        assertEquals(4, summary.nearThresholdPixels)

        val expectedBinary = ByteBuffer.allocateDirect(6).apply {
            put(byteArrayOf(0, 0, 0, 255.toByte(), 255.toByte(), 255.toByte()))
            rewind()
        }
        assertEquals(
            YoloTensorDiagnostics.associationMaskSummary(expectedBinary).sha256,
            summary.sha256
        )
    }
}

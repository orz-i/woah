package com.danceanon.native.diagnostics

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

    @Test
    fun fullExportGeometrySignatureDoesNotScanOrHashMaskBytes() {
        val maskBuffer = ByteBuffer.allocateDirect(4).apply {
            put(byteArrayOf(1, 129.toByte(), 200.toByte(), 255.toByte()))
            rewind()
        }
        val detection = PersonDetection(
            bbox = FloatRect(10.03125f, 20.0f, 30.09375f, 40.0f),
            confidence = 0.87654f,
            mask = NativeMask(
                width = 2,
                height = 2,
                buffer = maskBuffer,
                originalWidth = 640,
                originalHeight = 640
            )
        )

        val signature = YoloTensorDiagnostics.geometryDetectionSignature(listOf(detection)).single()

        assertEquals(0, signature["index"])
        assertEquals(8765, signature["confidence_q1e4"])
        assertEquals(listOf(161, 320, 482, 640), signature["bbox_q0_0625px"])
        assertEquals(2, signature["mask_width"])
        assertEquals(2, signature["mask_height"])
        assertFalse(signature.containsKey("mask_sha256"))
        assertFalse(signature.containsKey("mask_assoc_binary_sha256"))
        assertFalse(signature.containsKey("mask_assoc_foreground_pixels"))
        assertFalse(signature.containsKey("mask_assoc_near_threshold_pixels"))
        assertEquals(0, maskBuffer.position())
    }
}

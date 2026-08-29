package com.danceanon.native.face

import com.danceanon.native.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FaceRoiCandidateSelectorTest {
    @Test
    fun `central target wins over higher confidence neighboring face`() {
        val faces = listOf(
            FaceObservation(FloatRect(108f, 88f, 148f, 128f), 0.46f),
            FaceObservation(FloatRect(208f, 65f, 236f, 93f), 0.90f)
        )

        val selected = assertNotNull(
            FaceRoiCandidateSelector.select(
                faces = faces,
                roiWidth = 256,
                roiHeight = 256,
                anchorX = 0.5f,
                anchorY = 0.5f
            )
        )

        assertEquals(0, selected.faceIndex)
    }

    @Test
    fun `off anchor neighbor is rejected`() {
        val selected = FaceRoiCandidateSelector.select(
            faces = listOf(FaceObservation(FloatRect(205f, 50f, 240f, 85f), 0.99f)),
            roiWidth = 256,
            roiHeight = 256,
            anchorX = 0.5f,
            anchorY = 0.5f
        )
        assertNull(selected)
    }

    @Test
    fun `shifted anchor supports target near frame boundary`() {
        val selected = assertNotNull(
            FaceRoiCandidateSelector.select(
                faces = listOf(FaceObservation(FloatRect(24f, 100f, 64f, 140f), 0.50f)),
                roiWidth = 256,
                roiHeight = 256,
                anchorX = 0.18f,
                anchorY = 0.5f
            )
        )
        assertEquals(0, selected.faceIndex)
    }
}

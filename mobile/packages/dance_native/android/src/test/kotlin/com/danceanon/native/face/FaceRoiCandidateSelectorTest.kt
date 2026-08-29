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

    @Test
    fun `near tie around target anchor is rejected as ambiguous`() {
        val selected = FaceRoiCandidateSelector.select(
            faces = listOf(
                FaceObservation(FloatRect(105f, 105f, 137f, 137f), 0.70f),
                FaceObservation(FloatRect(119f, 111f, 151f, 143f), 0.99f)
            ),
            roiWidth = 256,
            roiHeight = 256,
            anchorX = 0.5f,
            anchorY = 0.5f
        )
        assertNull(selected)
    }

    @Test
    fun `well separated central target still wins over neighbor`() {
        val selected = assertNotNull(
            FaceRoiCandidateSelector.select(
                faces = listOf(
                    FaceObservation(FloatRect(112f, 96f, 144f, 128f), 0.50f),
                    FaceObservation(FloatRect(184f, 80f, 216f, 112f), 0.99f)
                ),
                roiWidth = 256,
                roiHeight = 256,
                anchorX = 0.5f,
                anchorY = 0.5f
            )
        )
        assertEquals(0, selected.faceIndex)
    }
}

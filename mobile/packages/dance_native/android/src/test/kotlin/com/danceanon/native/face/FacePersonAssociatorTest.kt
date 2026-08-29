package com.danceanon.native.face

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FacePersonAssociatorTest {
    @Test
    fun `matches each face to the corresponding nearby person`() {
        val persons = listOf(
            person(10, FloatRect(100f, 100f, 300f, 600f)),
            person(20, FloatRect(340f, 90f, 540f, 600f))
        )
        val faces = listOf(
            FaceObservation(FloatRect(160f, 120f, 235f, 210f), 0.95f),
            FaceObservation(FloatRect(400f, 115f, 475f, 205f), 0.92f)
        )

        val result = FacePersonAssociator.associate(faces, persons)

        assertEquals(2, result.matches.size)
        assertEquals(setOf(10, 20), result.matches.map { it.trackId }.toSet())
        assertTrue(result.unmatchedFaceIndices.isEmpty())
    }

    @Test
    fun `rejects a face-like box located in lower body`() {
        val persons = listOf(person(7, FloatRect(100f, 100f, 300f, 700f)))
        val lowerBody = FaceObservation(FloatRect(155f, 480f, 235f, 570f), 0.99f)

        val result = FacePersonAssociator.associate(listOf(lowerBody), persons)

        assertTrue(result.matches.isEmpty())
        assertEquals(listOf(0), result.unmatchedFaceIndices)
    }

    @Test
    fun `allows partially clipped face above person box`() {
        val persons = listOf(person(3, FloatRect(100f, 80f, 300f, 600f)))
        val clipped = FaceObservation(FloatRect(155f, 45f, 240f, 135f), 0.90f)

        val result = FacePersonAssociator.associate(listOf(clipped), persons)

        assertEquals(1, result.matches.size)
        assertEquals(3, result.matches.single().trackId)
    }

    @Test
    fun `one person cannot own two simultaneous face detections`() {
        val persons = listOf(person(1, FloatRect(100f, 100f, 320f, 650f)))
        val faces = listOf(
            FaceObservation(FloatRect(165f, 125f, 245f, 215f), 0.95f),
            FaceObservation(FloatRect(170f, 130f, 250f, 220f), 0.60f)
        )

        val result = FacePersonAssociator.associate(faces, persons)

        assertEquals(1, result.matches.size)
        assertEquals(1, result.unmatchedFaceIndices.size)
    }

    private fun person(id: Int, bbox: FloatRect) = TrackedPerson(
        id = id,
        bbox = bbox,
        mask = null,
        confidence = 0.9f,
        state = TrackState.ACTIVE
    )
}

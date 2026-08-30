package com.danceanon.native.privacy

import com.danceanon.native.inference.FloatRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FacePrivacyTemporalStabilizerTest {
    private val person = FloatRect(100f, 100f, 300f, 700f)

    @Test
    fun `fallback after trusted detection keeps conservative size without generic jump`() {
        val stabilizer = FacePrivacyTemporalStabilizer()
        val detected = FacePrivacyEllipse(200f, 170f, 45f, 55f, FacePrivacyRegionSource.DETECTED_FACE)
        stabilizer.stabilize(1, detected, person, 0L)

        val genericFallback = FacePrivacyEllipse(200f, 184f, 110f, 130f, FacePrivacyRegionSource.YOLO_HEAD_FALLBACK)
        val fallback = stabilizer.stabilize(1, genericFallback, person, 33_333L)

        assertEquals(FacePrivacyRegionSource.YOLO_HEAD_FALLBACK, fallback.source)
        assertTrue(fallback.radiusX < genericFallback.radiusX * 0.85f, "fallback X remained too close to generic head size")
        assertTrue(fallback.radiusY < genericFallback.radiusY * 0.85f, "fallback Y remained too close to generic head size")
        assertTrue(fallback.radiusX >= 54f, "fallback must remain larger than trusted face")
        assertTrue(fallback.radiusY >= 66f, "fallback must remain larger than trusted face")
    }

    @Test
    fun `detector recovery shrinks gradually instead of snapping`() {
        val stabilizer = FacePrivacyTemporalStabilizer()
        val detected = FacePrivacyEllipse(200f, 170f, 45f, 55f, FacePrivacyRegionSource.DETECTED_FACE)
        stabilizer.stabilize(1, detected, person, 0L)
        val fallback = stabilizer.stabilize(
            1,
            FacePrivacyEllipse(200f, 184f, 110f, 130f, FacePrivacyRegionSource.YOLO_HEAD_FALLBACK),
            person,
            33_333L
        )
        val recovered = stabilizer.stabilize(1, detected, person, 66_666L)

        assertTrue(recovered.radiusX > detected.radiusX, "recovery should not snap immediately to the smaller detected size")
        assertTrue(recovered.radiusX < fallback.radiusX, "recovery should move toward detected size")
        assertTrue(recovered.radiusY > detected.radiusY)
        assertTrue(recovered.radiusY < fallback.radiusY)
    }

    @Test
    fun `fallback center follows current yolo head rather than stale detector center`() {
        val stabilizer = FacePrivacyTemporalStabilizer()
        stabilizer.stabilize(
            4,
            FacePrivacyEllipse(180f, 170f, 45f, 55f, FacePrivacyRegionSource.DETECTED_FACE),
            person,
            0L
        )
        val movedPerson = FloatRect(220f, 100f, 420f, 700f)
        val rawFallback = FacePrivacyEllipse(320f, 184f, 110f, 130f, FacePrivacyRegionSource.YOLO_HEAD_FALLBACK)
        val output = stabilizer.stabilize(4, rawFallback, movedPerson, 33_333L)

        assertEquals(rawFallback.centerX, output.centerX)
        assertEquals(rawFallback.centerY, output.centerY)
    }

    @Test
    fun `fallback without trusted detection keeps original conservative geometry`() {
        val stabilizer = FacePrivacyTemporalStabilizer()
        val rawFallback = FacePrivacyEllipse(200f, 184f, 110f, 130f, FacePrivacyRegionSource.YOLO_HEAD_FALLBACK)
        val output = stabilizer.stabilize(7, rawFallback, person, 0L)
        assertEquals(rawFallback, output)
    }

    @Test
    fun `retaining active tracks drops old stabilization history`() {
        val stabilizer = FacePrivacyTemporalStabilizer()
        stabilizer.stabilize(
            1,
            FacePrivacyEllipse(200f, 170f, 45f, 55f, FacePrivacyRegionSource.DETECTED_FACE),
            person,
            0L
        )
        stabilizer.retainTracks(emptySet())
        val rawFallback = FacePrivacyEllipse(200f, 184f, 110f, 130f, FacePrivacyRegionSource.YOLO_HEAD_FALLBACK)
        assertEquals(rawFallback, stabilizer.stabilize(1, rawFallback, person, 33_333L))
    }
}

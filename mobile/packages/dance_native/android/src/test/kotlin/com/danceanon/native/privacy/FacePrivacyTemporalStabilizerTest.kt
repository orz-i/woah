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
        assertTrue(fallback.radiusX > detected.radiusX, "fallback must remain larger than trusted face")
        assertTrue(fallback.radiusY > detected.radiusY, "fallback must remain larger than trusted face")
        assertTrue(fallback.radiusX <= detected.radiusX * 1.40f)
        assertTrue(fallback.radiusY <= detected.radiusY * 1.40f)
    }

    @Test
    fun `trusted face size does not explode when current person bbox expands`() {
        val stabilizer = FacePrivacyTemporalStabilizer()
        val detected = FacePrivacyEllipse(200f, 170f, 45f, 55f, FacePrivacyRegionSource.DETECTED_FACE)
        stabilizer.stabilize(1, detected, person, 0L)

        val mergedPerson = FloatRect(40f, 80f, 440f, 780f)
        val genericFallback = FacePrivacyEllipse(
            240f,
            178f,
            220f,
            260f,
            FacePrivacyRegionSource.YOLO_HEAD_FALLBACK
        )
        val fallback = stabilizer.stabilize(1, genericFallback, mergedPerson, 33_333L)

        assertTrue(
            fallback.radiusX <= detected.radiusX * 1.45f,
            "merged person bbox must not double trusted face width: ${fallback.radiusX}"
        )
        assertTrue(
            fallback.radiusY <= detected.radiusY * 1.45f,
            "merged person bbox must not double trusted face height: ${fallback.radiusY}"
        )
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
    fun `implausible one frame face jump is bounded relative to stable person motion`() {
        val stabilizer = FacePrivacyTemporalStabilizer()
        val first = FacePrivacyEllipse(200f, 170f, 45f, 55f, FacePrivacyRegionSource.DETECTED_FACE)
        stabilizer.stabilize(11, first, person, 0L)

        val badSwitch = FacePrivacyEllipse(380f, 170f, 45f, 55f, FacePrivacyRegionSource.DETECTED_FACE)
        val output = stabilizer.stabilize(11, badSwitch, person, 16_666L)

        assertTrue(output.centerX > first.centerX, "bounded output should still move toward newest evidence")
        assertTrue(
            output.centerX < 260f,
            "180 px one-frame residual must be clamped to a face-scale step, got ${output.centerX}"
        )
        assertEquals(first.centerY, output.centerY)
    }

    @Test
    fun `whole person translation is not slowed by relative face jump gate`() {
        val stabilizer = FacePrivacyTemporalStabilizer()
        val first = FacePrivacyEllipse(180f, 170f, 45f, 55f, FacePrivacyRegionSource.DETECTED_FACE)
        stabilizer.stabilize(12, first, person, 0L)

        val movedPerson = FloatRect(220f, 100f, 420f, 700f)
        val translated = FacePrivacyEllipse(300f, 170f, 45f, 55f, FacePrivacyRegionSource.PREDICTED_FACE)
        val output = stabilizer.stabilize(12, translated, movedPerson, 16_666L)

        assertEquals(translated.centerX, output.centerX)
        assertEquals(translated.centerY, output.centerY)
    }

    @Test
    fun `observed bbox top jump with stable feet is not trusted as person translation`() {
        val stabilizer = FacePrivacyTemporalStabilizer()
        val first = FacePrivacyEllipse(200f, 170f, 45f, 55f, FacePrivacyRegionSource.DETECTED_FACE)
        stabilizer.stabilize(14, first, person, 0L)

        // Same lower edge / feet, but the detector suddenly loses 100 px of the
        // upper silhouette. A top-edge-based body anchor would treat this as a
        // +100 px person translation and bypass the residual face gate.
        val topJitteredPerson = FloatRect(100f, 200f, 300f, 700f)
        val topProjectedFace = FacePrivacyEllipse(200f, 270f, 45f, 55f, FacePrivacyRegionSource.PREDICTED_FACE)
        val output = stabilizer.stabilize(
            trackId = 14,
            rawRegion = topProjectedFace,
            personBbox = topJitteredPerson,
            ptsUs = 16_666L,
            personObservedThisFrame = true
        )

        assertTrue(output.centerY > first.centerY)
        assertTrue(
            output.centerY < 230f,
            "bbox top-edge jitter must be treated as residual face motion, got ${output.centerY}"
        )
    }

    @Test
    fun `unobserved tracker jump cannot bypass face position gate as whole person motion`() {
        val stabilizer = FacePrivacyTemporalStabilizer()
        val first = FacePrivacyEllipse(180f, 170f, 45f, 55f, FacePrivacyRegionSource.DETECTED_FACE)
        stabilizer.stabilize(13, first, person, 0L)

        val predictedJump = FloatRect(280f, 100f, 480f, 700f)
        val raw = FacePrivacyEllipse(360f, 170f, 45f, 55f, FacePrivacyRegionSource.PREDICTED_FACE)
        val output = stabilizer.stabilize(
            trackId = 13,
            rawRegion = raw,
            personBbox = predictedJump,
            ptsUs = 16_666L,
            personObservedThisFrame = false
        )

        assertTrue(output.centerX > first.centerX)
        assertTrue(
            output.centerX < 300f,
            "unobserved bbox prediction must not make a 180 px face jump look like trusted whole-person motion: ${output.centerX}"
        )
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

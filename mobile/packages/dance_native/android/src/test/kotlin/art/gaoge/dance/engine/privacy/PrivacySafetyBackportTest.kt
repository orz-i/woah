package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** This backport tests only observed FULL_BODY mask fail-closed; no mask-aging or carve changes. */
class PrivacySafetyBackportTest {
    private fun mask(value: Int): NativeMask {
        val width = 64
        val height = 64
        val buffer = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        repeat(width * height) { buffer.put(0.toByte()) }
        for (y in 12 until 38) {
            for (x in 9 until 20) buffer.put(y * width + x, value.toByte())
        }
        buffer.rewind()
        return NativeMask(width, height, buffer, 640, 640)
    }

    private fun person(
        id: Int = 1,
        pixels: NativeMask? = mask(255),
        observed: Boolean = true
    ) = TrackedPerson(
        id = id,
        bbox = FloatRect(90f, 120f, 200f, 380f),
        mask = pixels,
        confidence = 0.90f,
        state = if (observed) TrackState.ACTIVE else TrackState.LOST,
        observedThisFrame = observed
    )

    @Test
    fun missingCurrentSelectedFullBodyMaskMustFailClosed() {
        val missing = person(pixels = null)
        assertEquals(
            listOf(1),
            PrivacyOcclusionResolver.observedSelectedWithoutMasks(listOf(missing), setOf(1))
        )
        assertTrue(
            PrivacyOcclusionResolver.observedSelectedWithoutMasks(listOf(missing), setOf(2)).isEmpty(),
            "Other/face-only tracks must not enter the FULL_BODY guard"
        )
        assertTrue(
            PrivacyOcclusionResolver.observedSelectedWithoutMasks(
                listOf(missing.copy(observedThisFrame = false)), setOf(1)
            ).isEmpty(),
            "Previously observed LOST tracks are not current missing-mask violations"
        )
    }

    @Test
    fun onlyMaskPixelsAtOrAboveRealTightThresholdAreRenderable() {
        for (value in listOf(0, 64, 127)) {
            assertEquals(
                listOf(1),
                PrivacyOcclusionResolver.observedSelectedWithoutMasks(
                    listOf(person(pixels = mask(value))), setOf(1)
                ),
                "No visible FULL_BODY mask pixel at intensity $value"
            )
        }
        for (value in listOf(128, 255)) {
            assertTrue(
                PrivacyOcclusionResolver.observedSelectedWithoutMasks(
                    listOf(person(pixels = mask(value))), setOf(1)
                ).isEmpty(),
                "Renderable FULL_BODY mask at intensity $value must not abort"
            )
        }
    }

    @Test
    fun truncatedMaskBufferIsRejectedWithoutReadingOutOfBounds() {
        val malformed = mask(255).also { it.buffer.limit(10) }
        assertEquals(
            listOf(1),
            PrivacyOcclusionResolver.observedSelectedWithoutMasks(
                listOf(person(pixels = malformed)), setOf(1)
            )
        )
    }

    @Test
    fun multipleProtectedTracksReportOnlyCurrentInvalidMasks() {
        val records = listOf(
            person(id = 1, pixels = mask(255)),
            person(id = 2, pixels = null),
            person(id = 3, pixels = mask(127)),
            person(id = 4, pixels = null, observed = false)
        )
        assertEquals(
            listOf(2, 3),
            PrivacyOcclusionResolver.observedSelectedWithoutMasks(records, setOf(1, 2, 3, 4))
        )
    }
}

package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VerifiedUnselectedForegroundHistoryTest {
    private val sourceBbox = FloatRect(518f, 293f, 692f, 624f)

    private fun mask(value: Int = 255, sourceWidth: Int = 1280): NativeMask {
        val bytes = ByteBuffer.allocateDirect(160 * 160)
        repeat(160 * 160) { bytes.put(value.toByte()) }
        bytes.rewind()
        return NativeMask(
            width = 160, height = 160, buffer = bytes,
            originalWidth = sourceWidth, originalHeight = 720
        )
    }

    private fun root(
        id: Int = 2,
        observed: Boolean = true,
        bbox: FloatRect = sourceBbox,
        frameMask: NativeMask = mask()
    ) = TrackedPerson(
        id = id, bbox = bbox, mask = frameMask, confidence = 0.85f,
        age = 1200, state = if (observed) TrackState.ACTIVE else TrackState.LOST,
        observedThisFrame = observed
    )

    @Test
    fun requiresTwoRecentObservationsOfSameUnselectedIdentity() {
        val timeline = VerifiedUnselectedForegroundHistory()
        assertNull(timeline.priorEvidenceAndRecord(
            listOf(root()), setOf(0, 1, 3, 4), 39700000L, 2
        ))
        assertNull(timeline.priorEvidenceAndRecord(
            listOf(root()), setOf(0, 1, 3, 4), 39733333L, 2
        ))
        val accepted = assertNotNull(timeline.priorEvidenceAndRecord(
            listOf(root()), setOf(0, 1, 3, 4), 39766666L, 2
        ))
        assertEquals(2, accepted.trackId)
        assertEquals(2, accepted.previousMasks.size)
        val pixel = 80 * 160 + 80
        assertEquals(255, accepted.previousMasks[0].buffer.get(pixel).toInt() and 0xFF)

        // No fresh observation cannot carry temporal foreground ownership.
        assertNull(timeline.priorEvidenceAndRecord(
            listOf(root(observed = false)), setOf(0, 1, 3, 4), 39800000L, 2
        ))
        assertNull(timeline.priorEvidenceAndRecord(
            listOf(root()), setOf(0, 1, 3, 4), 39833333L, 2
        ))
    }

    @Test
    fun identitiesSourceMapsAndLargeBboxMotionNeverReusePriorMasks() {
        val timeline = VerifiedUnselectedForegroundHistory()
        timeline.priorEvidenceAndRecord(listOf(root()), setOf(0, 1, 3, 4), 100000L, 2)
        timeline.priorEvidenceAndRecord(listOf(root()), setOf(0, 1, 3, 4), 133333L, 2)
        assertNull(timeline.priorEvidenceAndRecord(
            listOf(root(id = 5)), setOf(0, 1, 3, 4), 166666L, 2
        ))
        assertNull(timeline.priorEvidenceAndRecord(
            listOf(root(id = 5)), setOf(0, 1, 3, 4), 183333L, 2
        ))
        assertNull(timeline.priorEvidenceAndRecord(
            listOf(root(frameMask = mask(sourceWidth = 960))),
            setOf(0, 1, 3, 4), 200000L, 2
        ))
        assertNull(timeline.priorEvidenceAndRecord(
            listOf(root(bbox = FloatRect(0f, 0f, 100f, 100f))),
            setOf(0, 1, 3, 4), 233333L, 2
        ))
    }

    @Test
    fun copiesHistoricalPixelsWithoutMutatingSourceMaskBuffers() {
        val timeline = VerifiedUnselectedForegroundHistory()
        val first = mask()
        timeline.priorEvidenceAndRecord(listOf(root(frameMask = first)), setOf(0, 1, 3, 4), 100000L, 2)
        first.buffer.put(80 * 160 + 80, 0.toByte())
        timeline.priorEvidenceAndRecord(listOf(root()), setOf(0, 1, 3, 4), 133333L, 2)
        val accepted = assertNotNull(timeline.priorEvidenceAndRecord(
            listOf(root()), setOf(0, 1, 3, 4), 166666L, 2
        ))
        assertEquals(
            255,
            accepted.previousMasks[0].buffer.get(80 * 160 + 80).toInt() and 0xFF
        )
    }
}

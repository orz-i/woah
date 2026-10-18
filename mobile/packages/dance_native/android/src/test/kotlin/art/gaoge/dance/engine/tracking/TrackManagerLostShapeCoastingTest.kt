package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.PersonDetection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackManagerLostShapeCoastingTest {
    @Test
    fun previouslyObservedTrackKeepsPhysicalShapeAcrossRepeatedLostFrames() {
        val config = TrackingConfig(maxMissedFrames = 25)
        val tracker = TrackManager(config, diagnosticsEnabled = false)
        val startUs = 17_000_000L
        fun detection(height: Float): PersonDetection {
            val cy = 500f
            return PersonDetection(
                FloatRect(565f, cy - height / 2f, 715f, cy + height / 2f),
                confidence = .90f
            )
        }
        tracker.initializeWithAssignedIds(listOf(detection(300f)), listOf(2))

        // Detector temporarily observes a shrinking person box; a velocity
        // estimate is valid while fresh observations are still being committed.
        for (i in 1..12) {
            val pts = startUs + i * 33_333L
            val observed = tracker.update(listOf(detection(300f - i * 3f)), pts).single()
            assertEquals(2, observed.id)
            assertTrue(observed.observedThisFrame)
        }

        val firstMissPts = startUs + 13 * 33_333L
        val firstMiss = tracker.update(emptyList(), firstMissPts).single()
        assertEquals(TrackState.LOST, firstMiss.state)
        val firstMissHeight = firstMiss.bbox.height
        assertTrue(firstMissHeight > 200f)

        for (i in 14..25) {
            val current = tracker.update(emptyList(), startUs + i * 33_333L).single()
            assertEquals(TrackState.LOST, current.state)
            assertEquals(firstMissHeight, current.bbox.height, .03f)
            assertTrue(current.bbox.height > 0.80f * 264f)
        }
    }
}

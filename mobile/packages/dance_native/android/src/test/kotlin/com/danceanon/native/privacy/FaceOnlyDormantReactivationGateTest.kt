package com.danceanon.native.privacy

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceOnlyDormantReactivationGateTest {
    @Test
    fun `first fresh motion sample stays pending while dormant`() {
        val gate = FaceOnlyDormantReactivationGate()

        val decision = gate.update(
            activeTrackIds = setOf(1),
            dormantTrackIds = setOf(1),
            observedTrackIds = emptySet(),
            freshMotionTrackIds = setOf(1),
            ptsUs = 1_000_000L
        )

        assertEquals(emptySet(), decision.confirmedTrackIds)
        assertEquals(setOf(1), decision.pendingTrackIds)
    }

    @Test
    fun `second fresh motion sample within existing bridge confirms reactivation`() {
        val gate = FaceOnlyDormantReactivationGate()

        gate.update(
            activeTrackIds = setOf(3),
            dormantTrackIds = setOf(3),
            observedTrackIds = emptySet(),
            freshMotionTrackIds = setOf(3),
            ptsUs = 2_000_000L
        )
        val decision = gate.update(
            activeTrackIds = setOf(3),
            dormantTrackIds = setOf(3),
            observedTrackIds = emptySet(),
            freshMotionTrackIds = setOf(3),
            ptsUs = 2_033_355L
        )

        assertEquals(setOf(3), decision.confirmedTrackIds)
        assertEquals(emptySet(), decision.pendingTrackIds)
    }

    @Test
    fun `single stale sample cannot confirm a much later reactivation`() {
        val gate = FaceOnlyDormantReactivationGate()

        gate.update(
            activeTrackIds = setOf(1),
            dormantTrackIds = setOf(1),
            observedTrackIds = emptySet(),
            freshMotionTrackIds = setOf(1),
            ptsUs = 1_000_000L
        )
        val decision = gate.update(
            activeTrackIds = setOf(1),
            dormantTrackIds = setOf(1),
            observedTrackIds = emptySet(),
            freshMotionTrackIds = setOf(1),
            ptsUs = 1_350_000L
        )

        assertEquals(emptySet(), decision.confirmedTrackIds)
        assertEquals(setOf(1), decision.pendingTrackIds)
    }

    @Test
    fun `recent bbox bridge without a second fresh sample does not confirm`() {
        val gate = FaceOnlyDormantReactivationGate()

        gate.update(
            activeTrackIds = setOf(2),
            dormantTrackIds = setOf(2),
            observedTrackIds = emptySet(),
            freshMotionTrackIds = setOf(2),
            ptsUs = 3_000_000L
        )
        val decision = gate.update(
            activeTrackIds = setOf(2),
            dormantTrackIds = setOf(2),
            observedTrackIds = emptySet(),
            freshMotionTrackIds = emptySet(),
            ptsUs = 3_050_000L
        )

        assertEquals(emptySet(), decision.confirmedTrackIds)
        assertEquals(setOf(2), decision.pendingTrackIds)
    }

    @Test
    fun `exact YOLO observation clears pending reactivation probe`() {
        val gate = FaceOnlyDormantReactivationGate()

        gate.update(
            activeTrackIds = setOf(5),
            dormantTrackIds = setOf(5),
            observedTrackIds = emptySet(),
            freshMotionTrackIds = setOf(5),
            ptsUs = 4_000_000L
        )
        val decision = gate.update(
            activeTrackIds = setOf(5),
            dormantTrackIds = setOf(5),
            observedTrackIds = setOf(5),
            freshMotionTrackIds = emptySet(),
            ptsUs = 4_016_678L
        )

        assertEquals(emptySet(), decision.confirmedTrackIds)
        assertEquals(emptySet(), decision.pendingTrackIds)
    }
}

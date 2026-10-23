package art.gaoge.dance.engine.comparison

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComparisonFrameProbeTest {
    @Test fun pairsByPlannedReleaseAndMeasuresMediaOffsetNotCallbackOrder() {
        val probe = ComparisonFrameProbe()
        val epoch = probe.startNewEpoch()
        for (index in 0 until 90) {
            val plannedNs = 1_000_000_000L + index * 33_333_333L
            val sourceUs = index * 33_333L
            // Deliberately submit learner's observation before teacher's.
            probe.add(ComparisonFrameProbe.Side.LEARNER, epoch,
                sourceUs + 3_000_000L, plannedNs + 5_000_000L)
            probe.add(ComparisonFrameProbe.Side.TEACHER, epoch, sourceUs, plannedNs)
        }
        val report = probe.snapshot(3_000_000L)
        assertEquals(90, report.teacherFrames)
        assertEquals(90, report.learnerFrames)
        assertEquals(59, report.pairedFrames)
        assertEquals(0L, report.p95AbsMediaErrorUs)
        assertEquals(5_000L, report.p95PlannedReleaseSkewUs)
    }

    @Test fun mismatchedReleaseTimesCannotMasqueradeAsGoodSync() {
        val probe = ComparisonFrameProbe()
        val epoch = probe.startNewEpoch()
        for (i in 0 until 90) {
            val nanos = 1_000_000_000L + i * 100_000_000L
            probe.add(ComparisonFrameProbe.Side.TEACHER, epoch, i * 100_000L, nanos)
            probe.add(ComparisonFrameProbe.Side.LEARNER, epoch, i * 100_000L,
                nanos + 40_000_000L)
        }
        assertEquals(0, probe.snapshot(0).pairedFrames)
    }

    @Test fun epochChangeRejectsOldObservations() {
        val probe = ComparisonFrameProbe()
        val old = probe.startNewEpoch()
        val now = probe.startNewEpoch()
        probe.add(ComparisonFrameProbe.Side.TEACHER, old, 0, 1)
        probe.add(ComparisonFrameProbe.Side.TEACHER, now, 0, 1)
        val result = probe.snapshot(0)
        assertEquals(1, result.teacherFrames)
        assertNull(result.p95AbsMediaErrorUs)
    }
}

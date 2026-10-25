package art.gaoge.dance.engine.comparison

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComparisonGlTimingTest {
    @Test fun negativeOffsetStartsTeacherAtOneSecondAndLearnerAtZero() {
        val overlap=ComparisonTimeMapping.overlap(SourceInterval(0,12_000_000),
            SourceInterval(0,11_000_000),-1_000_000)!!
        val teacher=ComparisonFrameInterval(1_000_000,1_041_667)
        val learner=ComparisonFrameInterval(0,41_667)
        val target=overlap.teacherTimeUs(20_000)
        assertTrue(teacher.contains(target))
        assertTrue(learner.contains(target+overlap.offsetUs))
        assertEquals(0L,learner.startUs-teacher.startUs-overlap.offsetUs)
        assertFalse(teacher.contains(1_041_667))
    }
    @Test fun coverageUsesActualPtsAcrossDifferentFrameIntervals() {
        val teacher=ComparisonFrameInterval(18_000,79_000)
        val learner=ComparisonFrameInterval(3_041_000,3_074_000)
        assertTrue(teacher.contains(48_000))
        assertTrue(learner.contains(48_000+3_000_000))
        assertFalse(learner.contains(79_000+3_000_000))
    }
    @Test fun oneWindowPreservesExistingCaptureRectangles() {
        val teacher=ComparisonGlLayout.viewport(1080,1478,73,640,360,true)
        val learner=ComparisonGlLayout.viewport(1080,1478,73,1280,720,false)
        assertEquals(ComparisonViewport(0,47,1080,607),teacher)
        assertEquals(ComparisonViewport(0,823,1080,607),learner)
        assertEquals(307,260+teacher.top)
        assertEquals(1083,260+learner.top)
    }
    @Test fun staleObservationsCannotEnterNewSeekEpoch() {
        val probe=ComparisonGlDiagnostics()
        val old=probe.reset(); val now=probe.reset()
        probe.add(old,ComparisonGlDiagnostics.Sample(0,3_100_000,0,3_000_000,
            1_000_000_000,1000,true))
        for (i in 0 until 90) probe.add(now,ComparisonGlDiagnostics.Sample(
            i*33_333L,i*33_333L+3_000_000,i*33_333L,3_000_000,
            1_000_000_000+i*33_333_333L,2000,true))
        val summary=probe.snapshot()
        assertEquals(59,summary.samples)
        assertEquals(0L,summary.p95Us)
        assertEquals(0,summary.coverageMisses)
        assertEquals(2000L,summary.p95ClockAgeUs)
    }
    @Test fun windowExportIsBoundedAndRetainsCoverageFailures() {
        val probe=ComparisonGlDiagnostics()
        val epoch=probe.reset()
        for (i in 0 until 3600) probe.add(epoch,ComparisonGlDiagnostics.Sample(
            i*1_000_000L,i*1_000_000L+3_000_000,i*1_000_000L,3_000_000,
            1_000_000_000+i*1_000_000_000L,2000,i!=300))
        val summary=probe.snapshot()
        assertEquals(120,summary.windows.size)
        assertEquals(1,summary.coverageMisses)
        assertEquals(3599,summary.samples)
    }
}

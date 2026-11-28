package art.gaoge.dance.engine.privacy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaceHeadProbeScheduleTest {
    @Test fun promotesOnlyConsecutiveHeadWinsAndDemotesOnNarrowSuccess() {
        val s = FaceHeadProbeSchedule()
        s.begin(1, 0); s.record(1, headWon = true, narrowWon = false)
        s.begin(1, 33_333)
        assertFalse(s.preferHead(1))
        s.record(1, headWon = true, narrowWon = false)
        s.begin(1, 66_666)
        assertTrue(s.preferHead(1))
        s.record(1, headWon = false, narrowWon = true)
        s.begin(1, 99_999)
        assertFalse(s.preferHead(1))
    }

    @Test fun missesNeverRenewPreferenceAndUnrelatedOwnerCannotPromote() {
        val s = FaceHeadProbeSchedule()
        s.begin(1, 0); s.record(2, true, false); s.record(1, true, false)
        s.begin(1, 33_333); s.record(1, false, false)
        s.begin(1, 66_666); s.record(1, true, false)
        assertFalse(s.preferHead(1))
        s.begin(1, 100_000); s.record(1, true, false)
        for (t in listOf(133_333L, 166_666L, 200_000L, 233_333L)) {
            s.begin(1, t); assertTrue(s.preferHead(1)); s.record(1, false, false)
        }
        s.begin(1, 266_666)
        assertFalse(s.preferHead(1))
    }

    @Test fun seekGapDeselectionAndOwnerChangeClearHistory() {
        for ((owner, time) in listOf(1 to 0L, 1 to 300_000L, null to 66_666L, 2 to 66_666L)) {
            val s = FaceHeadProbeSchedule()
            s.begin(1,0); s.record(1,true,false)
            s.begin(1,33_333); s.record(1,true,false)
            assertTrue(s.preferHead(1))
            s.begin(owner,time)
            assertFalse(s.preferHead(1)); assertFalse(s.preferHead(2))
        }
    }
}

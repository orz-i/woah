package art.gaoge.dance.engine.core

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.PersonDetection
import art.gaoge.dance.engine.tracking.TrackManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
class TrackingReplayTransportTest {
    @Test fun latePreviewCarriesAllHistoryAndBackwardSeekResetsTheSession() {
        val first=FrameKernel.replayPlan(4,true,true,0L,null,23_200_000L,40_000L)
        assertEquals(581,first.count);assertEquals(23_200_000L,first.time(580))
        val rewind=FrameKernel.replayPlan(4,true,true,0L,23_200_000L,11_600_000L,40_000L)
        assertEquals(true,rewind.reset);assertEquals(0L,rewind.time(0))
    }
    @Test fun aMissingAnalysisAnchorCannotBeReplacedWithAFreshPersonId() {
        assertFailsWith<IllegalArgumentException>{FrameKernel.replayPlan(0,true,false,null,null,23_200_000L,40_000L)}
        val uniform=FrameKernel.replayPlan(2,false,true,null,null,23_200_000L,40_000L)
        assertEquals(false,uniform.required)
    }
    @Test fun aNewEntrantCannotReuseAnAbsentAnalysisRootIdentity() {
        fun detection(x:Float)=PersonDetection(FloatRect(x,0f,x+100f,200f),0.9f)
        TrackManager().use{tracker->
            tracker.initializeWithAssignedIds(listOf(detection(0f)),listOf(0))
            tracker.reserveNextIdentity(8)
            val persons=tracker.update(listOf(detection(0f),detection(1000f)),40_000L)
            assertEquals(setOf(0,8),persons.map{it.id}.toSet())
        }
    }
}

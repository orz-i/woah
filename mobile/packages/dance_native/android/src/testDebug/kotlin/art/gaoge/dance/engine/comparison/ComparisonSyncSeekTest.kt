package art.gaoge.dance.engine.comparison

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComparisonSyncSeekTest {
    @Test fun retriesAFutureBFrameSyncPointWithoutMovingTheRequestedTarget() {
        val requests=mutableListOf<Long>()
        val result=ComparisonSyncSeek.atOrBefore(5_573_780) { requested ->
            requests.add(requested)
            if (requested > 5_521_233) 5_604_656 else 4_670_389
        }
        assertEquals(listOf(5_573_780L,4_573_780L),requests)
        assertEquals(4_670_389L,result.syncPtsUs)
        assertEquals(1,result.retries)
    }
    @Test fun leavesAlreadyPrecedingSyncSeeksUnchangedIncludingZeroAndNegativeOffsets() {
        for (target in listOf(0L,1_000_000L,5_573_780L)) {
            var calls=0
            val result=ComparisonSyncSeek.atOrBefore(target) { requested ->
                calls++; assertEquals(target,requested); maxOf(0L,target-1000)
            }
            assertEquals(1,calls)
            assertEquals(0,result.retries)
        }
    }
    @Test fun boundsRetriesAndFailsRatherThanAcceptingAFutureFrame() {
        var calls=0
        assertFailsWith<IllegalStateException> {
            ComparisonSyncSeek.atOrBefore(30_000_000) { calls++;30_100_000 }
        }
        assertEquals(5,calls)
        calls=0
        assertFailsWith<IllegalStateException> {
            ComparisonSyncSeek.atOrBefore(1000) { calls++;2000 }
        }
        assertEquals(2,calls)
    }
    @Test fun propagatesCancellationAndRejectsMissingSyncSamples() {
        assertFailsWith<ComparisonGlCancelled> {
            ComparisonSyncSeek.atOrBefore(5_000_000) { throw ComparisonGlCancelled() }
        }
        assertFailsWith<IllegalStateException> { ComparisonSyncSeek.atOrBefore(5_000_000) { -1 } }
    }
}

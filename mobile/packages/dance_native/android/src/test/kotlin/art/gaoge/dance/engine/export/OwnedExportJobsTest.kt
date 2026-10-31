package art.gaoge.dance.engine.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnedExportJobsTest {
    @Test fun fastWorkerCannotReinsertCompletedJob() {
        val jobs = OwnedExportJobs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val order = mutableListOf<String>()
        jobs.launch(scope, "a", cleanup = { order.add("close") }, onCompletion = { _, closed ->
            assertTrue(closed); order.add("finish")
        }) {
            assertTrue(jobs.contains("a"))
            order.add("body")
        }
        assertEquals(listOf("body", "close", "finish"), order)
        assertTrue(jobs.isEmpty())
        scope.cancel()
    }

    @Test fun cancelledParentBeforeBodyEntryStillFinishesOnce() {
        val jobs = OwnedExportJobs()
        val parent = SupervisorJob().also { it.cancel() }
        val completed = AtomicInteger()
        val closed = AtomicInteger()
        jobs.launch(CoroutineScope(parent + Dispatchers.Unconfined), "a",
            cleanup = { closed.incrementAndGet() },
            onCompletion = { _, clean -> assertTrue(clean); completed.incrementAndGet() }
        ) { error("cancelled worker must not run") }
        assertEquals(1, closed.get())
        assertEquals(1, completed.get())
        assertTrue(jobs.isEmpty())
    }

    @Test fun cancellationDoesNotCloseOrReleaseBlockedNativeWork() = runBlocking {
        val jobs = OwnedExportJobs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val drain = CountDownLatch(1)
        val closed = AtomicInteger()
        val finished = CompletableDeferred<Unit>()
        jobs.launch(scope, "a", cleanup = { closed.incrementAndGet() }, onCompletion = { _, clean ->
            assertTrue(clean); finished.complete(Unit)
        }) {
            entered.countDown()
            check(drain.await(3, TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            scope.cancel()
            assertEquals(0, closed.get())
            assertFalse(finished.isCompleted)
            assertTrue(jobs.contains("a"))
            drain.countDown()
            withTimeout(2000) { finished.await() }
            assertEquals(1, closed.get())
            assertTrue(jobs.isEmpty())
        } finally { drain.countDown(); scope.cancel() }
    }

    @Test fun failedCleanupDoesNotReleaseNativeLane() = runBlocking {
        val jobs = OwnedExportJobs()
        val gate = PreviewExportResourceGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        gate.reserveExport("a")
        jobs.launch(scope, "a", cleanup = { error("close failed") }, onCompletion = { error, clean ->
            assertFalse(clean)
            assertTrue(error is IllegalStateException)
            if (clean) gate.finishExport("a")
        }) { Unit }
        assertTrue(gate.ownsExport("a"))
        assertTrue(jobs.isEmpty())
        scope.cancel()
    }

    @Test fun oldServiceCancellationNeverTouchesNewReservation() = runBlocking {
        val oldJobs = OwnedExportJobs()
        val gate = PreviewExportResourceGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        gate.reserveExport("old")
        oldJobs.launch(scope, "old", cleanup = {}, onCompletion = { _, clean ->
            if (clean) gate.finishExport("old")
        }) { Unit }
        gate.reserveExport("new")
        val cancelled = mutableListOf<String>()
        oldJobs.cancelOwned { cancelled.add(it) }
        assertTrue(cancelled.isEmpty())
        assertTrue(gate.ownsExport("new"))
        assertTrue(oldJobs.isEmpty()) // stale Intent can stop an otherwise idle old instance.
        scope.cancel()
    }

    @Test fun latestDuplicateOrProgressStartIdIsUsedOnlyWhenIdle() = runBlocking {
        val jobs = OwnedExportJobs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val drain = CompletableDeferred<Unit>()
        jobs.recordStart(1)
        jobs.launch(scope, "a", cleanup = {}, onCompletion = { _, _ -> }) { drain.await() }
        val cancelled = jobs.snapshot()
        jobs.recordStart(2) // duplicate ACTION_START
        jobs.recordStart(3) // ACTION_UPDATE_PROGRESS
        kotlin.test.assertNull(jobs.idleStopId())
        assertEquals(3, jobs.cancelledStopId(cancelled))
        drain.complete(Unit)
        assertEquals(3, jobs.idleStopId())
        val next = CompletableDeferred<Unit>()
        jobs.recordStart(4)
        jobs.launch(scope, "b", cleanup = {}, onCompletion = { _, _ -> }) { next.await() }
        kotlin.test.assertNull(jobs.idleStopId())
        kotlin.test.assertNull(jobs.cancelledStopId(cancelled))
        next.complete(Unit)
        assertEquals(4, jobs.idleStopId())
        scope.cancel()
    }

    @Test fun duplicateStartDoesNotFinishOrReplaceExistingWorker() = runBlocking {
        val jobs = OwnedExportJobs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val drain = CompletableDeferred<Unit>()
        val completions = AtomicInteger()
        assertTrue(jobs.launch(scope, "a", cleanup = {}, onCompletion = { _, _ -> completions.incrementAndGet() }) { drain.await() })
        assertFalse(jobs.launch(scope, "a", cleanup = { error("duplicate cleanup") }, onCompletion = { _, _ -> error("duplicate finish") }) { error("duplicate body") })
        assertTrue(jobs.contains("a"))
        assertEquals(0, completions.get())
        drain.complete(Unit)
        assertEquals(1, completions.get())
        assertTrue(jobs.isEmpty())
        scope.cancel()
    }
}

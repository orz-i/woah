package art.gaoge.dance.engine.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewExportResourceGateTest {
    @Test fun exportWaitsForActualPreviewAndRejectsQueuedPreview() = runBlocking {
        val gate = PreviewExportResourceGate()
        var released = 0
        val owner = PreviewExportResourceGate.PreviewOwner { released++ }
        val entered = CompletableDeferred<Unit>()
        val drain = CompletableDeferred<Unit>()
        val preview = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withPreview(owner) { entered.complete(Unit); drain.await() }
        }
        entered.await()
        val export = async(start = CoroutineStart.UNDISPATCHED) { gate.reserveExport("a") }
        assertTrue(gate.ownsExport("a"))
        assertFalse(export.isCompleted)
        assertEquals(0, released)
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { gate.withPreview(owner) { error("must not enter") } }
        }
        drain.complete(Unit)
        withTimeout(2000) { preview.join(); export.await() }
        assertEquals(1, released)
        assertTrue(queued.await().exceptionOrNull() is IllegalStateException)
        gate.finishExport("wrong")
        assertTrue(gate.ownsExport("a"))
        gate.finishExport("a")
        assertEquals(7, gate.withPreview(owner) { 7 })
    }

    @Test fun cancelNotificationDoesNotReleaseExportUntilWorkerFinishes() = runBlocking {
        val gate = PreviewExportResourceGate()
        val owner = PreviewExportResourceGate.PreviewOwner {}
        gate.reserveExport("a")
        // A cancellation status is deliberately not a gate operation.
        assertFailsWith<IllegalStateException> { gate.withPreview(owner) { Unit } }
        assertFailsWith<IllegalStateException> { gate.reserveExport("b") }
        assertTrue(gate.ownsExport("a"))
        gate.finishExport("a")
        gate.finishExport("a")
        gate.reserveExport("b")
        gate.finishExport("a")
        assertTrue(gate.ownsExport("b"))
    }

    @Test fun cancelledDrainRollsBackOnlyItsOwnReservation() = runBlocking {
        val gate = PreviewExportResourceGate()
        val owner = PreviewExportResourceGate.PreviewOwner {}
        val drain = CompletableDeferred<Unit>()
        val preview = launch(start = CoroutineStart.UNDISPATCHED) { gate.withPreview(owner) { drain.await() } }
        val export = launch(start = CoroutineStart.UNDISPATCHED) { gate.reserveExport("a") }
        assertTrue(gate.ownsExport("a"))
        export.cancelAndJoin()
        assertFalse(gate.ownsExport("a"))
        drain.complete(Unit)
        preview.join()
        gate.reserveExport("b")
        assertTrue(gate.ownsExport("b"))
    }

    @Test fun exportDrainsAllEngineOwnersAndFailsBeforeStartingOnCleanupError() = runBlocking {
        val gate = PreviewExportResourceGate()
        val releases = mutableListOf<String>()
        val first = PreviewExportResourceGate.PreviewOwner { releases.add("first") }
        var fail = true
        val second = PreviewExportResourceGate.PreviewOwner {
            releases.add("second")
            if (fail) error("cleanup failure")
        }
        gate.withPreview(first) { Unit }
        gate.withPreview(second) { Unit }
        assertFailsWith<IllegalStateException> { gate.reserveExport("a") }
        assertFalse(gate.ownsExport("a"))
        assertEquals(listOf("first", "second"), releases)
        fail = false
        // A failed native close is not evidence that the GPU lane is safe to reuse.
        assertFailsWith<IllegalStateException> { gate.reserveExport("b") }
        val nextEngine = PreviewExportResourceGate.PreviewOwner {}
        assertFailsWith<IllegalStateException> { gate.withPreview(nextEngine) { Unit } }
        assertFalse(gate.ownsExport("b"))
    }

    @Test fun detachDrainsAndPermanentlyRejectsOldOwner() = runBlocking {
        val gate = PreviewExportResourceGate()
        var released = false
        val owner = PreviewExportResourceGate.PreviewOwner { released = true }
        val drain = CompletableDeferred<Unit>()
        val preview = launch(start = CoroutineStart.UNDISPATCHED) { gate.withPreview(owner) { drain.await() } }
        val close = launch(start = CoroutineStart.UNDISPATCHED) { gate.closePreview(owner) }
        assertTrue(owner.closing.get())
        assertFalse(released)
        drain.complete(Unit)
        preview.join(); close.join()
        assertTrue(released)
        assertFailsWith<IllegalStateException> { gate.withPreview(owner) { Unit } }
        val next = PreviewExportResourceGate.PreviewOwner {}
        assertEquals(1, gate.withPreview(next) { 1 })
    }

    @Test fun cacheMutationIsBlockedDuringExportAndWaitsForPreview() = runBlocking {
        val gate = PreviewExportResourceGate()
        gate.reserveExport("a")
        assertFailsWith<IllegalStateException> { gate.withPreviewResources { Unit } }
        gate.finishExport("a")
        assertEquals(1, gate.withPreviewResources { 1 })
    }
}

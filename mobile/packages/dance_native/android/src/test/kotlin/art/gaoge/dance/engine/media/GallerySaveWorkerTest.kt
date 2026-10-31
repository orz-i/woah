package art.gaoge.dance.engine.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GallerySaveWorkerTest {
    private class ManualExecutor : AbstractExecutorService() {
        val queue = ArrayDeque<Runnable>()
        var stopped = false
        var beforeExecute: () -> Unit = {}
        override fun execute(command: Runnable) {
            beforeExecute()
            if (stopped) throw RejectedExecutionException()
            queue.add(command)
        }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> = error("must not discard queued calls")
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private class Backend : GallerySaveBackend {
        val rollbacks = AtomicInteger()
        var publishAction: () -> Unit = {}
        var outputAction: () -> OutputStream = { ByteArrayOutputStream() }
        override fun insert() = "owned:new"
        override fun openOutput(uri: String) = outputAction()
        override fun publish(uri: String) { publishAction() }
        override fun rollback(uri: String) { assertEquals("owned:new", uri); rollbacks.incrementAndGet() }
    }

    @Test fun queuedDetachDrainsCancellationAndRepliesExactlyOnce() {
        val executor = ManualExecutor()
        val replies = mutableListOf<Result<String>>()
        val backend = Backend()
        val worker = GallerySaveWorker(executor, { it() }) { _, control ->
            GallerySaveTransaction(backend).save({ ByteArrayInputStream(byteArrayOf(1)) }, control)
        }
        worker.submit("synthetic") { replies.add(it) }
        worker.close(); worker.close()
        assertTrue(replies.isEmpty())
        executor.runAll()
        assertEquals(1, replies.size)
        assertEquals("SAVE_CANCELLED", (replies.single().exceptionOrNull() as GallerySaveFailure).code)
        assertEquals(0, backend.rollbacks.get()) // cancelled before insert
    }

    @Test fun submitAfterDetachRepliesWithoutSchedulingIo() {
        val executor = ManualExecutor()
        val replies = mutableListOf<Result<String>>()
        val worker = GallerySaveWorker(executor, { it() }) { _, _ -> error("no I/O") }
        worker.close()
        worker.submit("synthetic") { replies.add(it) }
        assertEquals(1, replies.size)
        assertEquals("PLUGIN_DETACHED", (replies.single().exceptionOrNull() as GallerySaveFailure).code)
        assertTrue(executor.queue.isEmpty())
    }

    @Test fun closeRacingSubmissionRejectionStillRepliesOnce() {
        val executor = ManualExecutor()
        val replies = mutableListOf<Result<String>>()
        val worker = GallerySaveWorker(executor, { it() }) { _, _ -> error("no I/O") }
        executor.beforeExecute = { worker.close() }
        worker.submit("synthetic") { replies.add(it) }
        executor.runAll()
        assertEquals(1, replies.size)
        assertTrue(replies.single().isFailure)
    }

    @Test fun slowIoNeverRunsOnCallerAndReplyUsesCapturedDispatcher() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replyQueued = CountDownLatch(1)
        val callbacks = ConcurrentLinkedQueue<() -> Unit>()
        val caller = Thread.currentThread()
        val worker = GallerySaveWorker(executor, { callbacks.add(it); replyQueued.countDown() }) { _, _ ->
            assertNotEquals(caller, Thread.currentThread())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            "owned:new"
        }
        var replies = 0
        try {
            worker.submit("synthetic") { assertEquals(caller, Thread.currentThread()); replies++ }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(0, replies)
            release.countDown()
            assertTrue(replyQueued.await(3, TimeUnit.SECONDS))
            assertEquals(0, replies)
            callbacks.remove().invoke()
            assertEquals(1, replies)
        } finally { release.countDown(); worker.close(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }

    @Test fun detachDuringCopyRollsBackBeforeSingleFailureReply() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val backend = Backend().apply { outputAction = {
            object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    super.write(bytes, offset, length)
                }
            }
        } }
        val calls = AtomicInteger()
        val worker = GallerySaveWorker(executor, { it() }) { _, control ->
            GallerySaveTransaction(backend).save({ ByteArrayInputStream(byteArrayOf(1, 2)) }, control)
        }
        try {
            worker.submit("synthetic") {
                assertEquals("SAVE_CANCELLED", (it.exceptionOrNull() as GallerySaveFailure).code)
                assertEquals(1, backend.rollbacks.get())
                calls.incrementAndGet(); completed.countDown()
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            worker.close() // returns despite blocked provider output
            assertEquals(0, calls.get())
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { release.countDown(); worker.close(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }

    @Test fun detachAfterCommitStartsDoesNotUndoSuccessfulSave() {
        val executor = Executors.newSingleThreadExecutor()
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val backend = Backend().apply { publishAction = {
            publishing.countDown(); check(release.await(5, TimeUnit.SECONDS))
        } }
        val worker = GallerySaveWorker(executor, { it() }) { _, control ->
            GallerySaveTransaction(backend).save({ ByteArrayInputStream(byteArrayOf(1)) }, control)
        }
        try {
            worker.submit("synthetic") { assertTrue(it.isSuccess); completed.countDown() }
            assertTrue(publishing.await(3, TimeUnit.SECONDS))
            worker.close()
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(0, backend.rollbacks.get())
        } finally { release.countDown(); worker.close(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }

    @Test fun replyFailureNeverRollsBackCommittedMedia() {
        val executor = ManualExecutor()
        val backend = Backend()
        val worker = GallerySaveWorker(executor, { it() }) { _, control ->
            GallerySaveTransaction(backend).save({ ByteArrayInputStream(byteArrayOf(1)) }, control)
        }
        worker.submit("synthetic") { error("engine gone") }
        assertFailsWith<IllegalStateException> { executor.runAll() }
        assertEquals(0, backend.rollbacks.get())
        worker.close()
    }

    @Test fun oldAttachmentCloseCannotCancelNewAttachmentWork() {
        val oldExecutor = ManualExecutor()
        val newExecutor = ManualExecutor()
        fun worker(executor: ManualExecutor) = GallerySaveWorker(executor, { it() }) { _, control ->
            control.checkCancelled(); "owned:new"
        }
        val old = worker(oldExecutor)
        val next = worker(newExecutor)
        val results = mutableListOf<Boolean>()
        old.submit("old") { results.add(it.isSuccess) }
        next.submit("new") { results.add(it.isSuccess) }
        old.close()
        oldExecutor.runAll(); newExecutor.runAll()
        assertEquals(listOf(false, true), results)
        next.close()
    }
}

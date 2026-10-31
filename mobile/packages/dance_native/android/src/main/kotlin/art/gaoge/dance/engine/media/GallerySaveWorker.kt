package art.gaoge.dance.engine.media

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** One plugin attachment owns this worker and its stable reply dispatcher. */
class GallerySaveWorker(
    private val executor: ExecutorService,
    private val postReply: (() -> Unit) -> Unit,
    private val save: (String, GallerySaveControl) -> String
) {
    private class Operation {
        val control = GallerySaveControl()
        val replied = AtomicBoolean(false)
    }
    private val closed = AtomicBoolean(false)
    private val operations = ConcurrentHashMap.newKeySet<Operation>()

    fun submit(sourcePath: String, reply: (Result<String>) -> Unit) {
        val operation = Operation()
        fun complete(result: Result<String>) {
            operations.remove(operation)
            if (operation.replied.compareAndSet(false, true)) {
                // Callback errors must never roll back an already published gallery asset.
                postReply { reply(result) }
            }
        }
        if (closed.get()) {
            complete(Result.failure(GallerySaveFailure("PLUGIN_DETACHED")))
            return
        }
        operations.add(operation)
        if (closed.get()) operation.control.cancel()
        try {
            executor.execute {
                val outcome = runCatching { save(sourcePath, operation.control) }
                complete(outcome)
            }
        } catch (error: RejectedExecutionException) {
            operation.control.cancel()
            complete(Result.failure(GallerySaveFailure("PLUGIN_DETACHED", error)))
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        operations.forEach { it.control.cancel() }
        // Queued operations must reach their cancellation path and exactly one reply.
        // shutdownNow would drop queued Runnables and strand their MethodChannel calls.
        executor.shutdown()
    }
}
